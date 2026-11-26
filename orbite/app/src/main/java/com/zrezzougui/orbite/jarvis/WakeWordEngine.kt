package com.zrezzougui.orbite.jarvis

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random

/**
 * Détection de « Hey Jarvis » sur le téléphone, d'après openWakeWord (libre, Apache 2.0).
 *
 * Toutes les 80 ms (1280 échantillons à 16 kHz) :
 *  1. spectrogramme mel des 1760 derniers échantillons → 8 trames × 32 ;
 *  2. empreinte vocale (Google speech embedding) des 76 dernières trames → 96 valeurs ;
 *  3. modèle « hey jarvis » sur les 16 dernières empreintes (1,28 s) → score entre 0 et 1.
 * Ce découpage a été vérifié : mêmes scores que l'implémentation de référence en Python.
 * Aucun son n'est enregistré ni envoyé.
 */
class WakeWordEngine(ctx: Context) : AutoCloseable {

    private val mel = interpreter(ctx, "wake/melspectrogram.tflite").apply { resizeInput(0, intArrayOf(1, RAW)); allocateTensors() }
    private val emb = interpreter(ctx, "wake/embedding_model.tflite").apply { resizeInput(0, intArrayOf(1, WINDOW, 32, 1)); allocateTensors() }
    private val ww = interpreter(ctx, "wake/hey_jarvis_v0.1.tflite")

    private val prev = FloatArray(CONTEXT)
    private val raw = FloatArray(RAW)
    private val melBuf = Array(WINDOW) { FloatArray(32) { 1f } }
    private val feat = Array(FEATURES) { FloatArray(96) }

    private val melIn = arrayOf(raw)
    private val melOut = arrayOf(arrayOf(Array(8) { FloatArray(32) }))
    private val embIn = arrayOf(Array(WINDOW) { Array(32) { FloatArray(1) } })
    private val embOut = arrayOf(arrayOf(arrayOf(FloatArray(96))))
    private val wwIn = arrayOf(feat)
    private val wwOut = arrayOf(FloatArray(1))

    @Volatile private var running = false
    private var thread: Thread? = null

    init {
        // Comme la référence : on amorce les tampons avec du bruit pour éviter un faux score au démarrage.
        val noise = FloatArray(CHUNK)
        repeat(25) {
            for (i in noise.indices) noise[i] = (Random.nextFloat() * 2 - 1) * 32767f
            step(noise)
        }
    }

    /** Traite 1280 échantillons (valeurs int16 en flottants) et renvoie le score « Hey Jarvis ». */
    fun step(chunk: FloatArray): Float {
        System.arraycopy(prev, 0, raw, 0, CONTEXT)
        System.arraycopy(chunk, 0, raw, CONTEXT, CHUNK)
        System.arraycopy(raw, RAW - CONTEXT, prev, 0, CONTEXT)

        mel.run(melIn, melOut)
        val frames = melOut[0][0]
        // Décale le tampon mel de 8 trames et ajoute les nouvelles (transformation x/10 + 2).
        for (i in 0 until WINDOW - frames.size) System.arraycopy(melBuf[i + frames.size], 0, melBuf[i], 0, 32)
        for (f in frames.indices) for (k in 0 until 32) melBuf[WINDOW - frames.size + f][k] = frames[f][k] / 10f + 2f

        for (i in 0 until WINDOW) for (k in 0 until 32) embIn[0][i][k][0] = melBuf[i][k]
        emb.run(embIn, embOut)

        for (i in 0 until FEATURES - 1) System.arraycopy(feat[i + 1], 0, feat[i], 0, 96)
        System.arraycopy(embOut[0][0][0], 0, feat[FEATURES - 1], 0, 96)

        ww.run(wwIn, wwOut)
        return wwOut[0][0]
    }

    /**
     * Écoute le micro sur un fil dédié. `onWake` est appelé
     * quand le score dépasse `threshold` (au plus une fois toutes les 2 s). `onError` si le micro est indisponible.
     */
    @SuppressLint("MissingPermission")
    fun start(threshold: Float, onWake: (Float) -> Unit, onError: (Exception) -> Unit) {
        if (running) return
        running = true
        thread = Thread({
            val minBuf = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val rec = try {
                AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, CHUNK * 4)).also {
                    if (it.state != AudioRecord.STATE_INITIALIZED) throw IllegalStateException("micro indisponible")
                    it.startRecording()
                }
            } catch (e: Exception) {
                running = false; onError(e); return@Thread
            }
            val pcm = ShortArray(CHUNK)
            val chunk = FloatArray(CHUNK)
            var lastWake = 0L
            try {
                while (running) {
                    var read = 0
                    while (read < CHUNK && running) {
                        val n = rec.read(pcm, read, CHUNK - read)
                        if (n < 0) throw IllegalStateException("lecture micro impossible ($n)")
                        read += n
                    }
                    if (!running) break
                    for (i in 0 until CHUNK) chunk[i] = pcm[i].toFloat()
                    val score = step(chunk)
                    val now = System.currentTimeMillis()
                    if (score >= threshold && now - lastWake > 2000) {
                        lastWake = now
                        onWake(score)
                    }
                }
            } catch (e: Exception) {
                if (running) onError(e)
            } finally {
                running = false
                runCatching { rec.stop() }
                rec.release()
            }
        }, "hey-jarvis").apply { priority = Thread.NORM_PRIORITY - 1; start() }
    }

    fun stop() {
        running = false
        thread?.join(500)
        thread = null
    }

    override fun close() {
        stop()
        mel.close(); emb.close(); ww.close()
    }

    companion object {
        private const val CHUNK = 1280
        private const val CONTEXT = 480
        private const val RAW = CHUNK + CONTEXT
        private const val WINDOW = 76
        private const val FEATURES = 16

        private fun interpreter(ctx: Context, asset: String): Interpreter {
            val bytes = ctx.assets.open(asset).use { it.readBytes() }
            val buf = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).put(bytes)
            buf.rewind()
            return Interpreter(buf, Interpreter.Options().setNumThreads(1))
        }
    }
}
