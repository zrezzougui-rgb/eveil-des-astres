package com.zrezzougui.orbite.jarvis

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.zrezzougui.orbite.assistant.AssistantActivity
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService

/**
 * Écoute en continu le mot « Jarvis », entièrement sur le téléphone (Vosk, hors ligne).
 * Seul le mot-clé est reconnu : la grammaire est limitée à quelques mots, rien n'est enregistré ni envoyé.
 * Quand il est entendu, l'assistant s'ouvre et écoute la demande.
 */
class JarvisService : Service(), RecognitionListener {

    private val main = Handler(Looper.getMainLooper())
    private var model: Model? = null
    private var speech: SpeechService? = null
    private var paused = false
    private var lastWake = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopListening(); stopSelf(); return START_NOT_STICKY }
            ACTION_PAUSE -> { paused = true; stopListening(); return START_STICKY }
            ACTION_RESUME -> { paused = false }
        }
        if (!canRun(this)) { stopSelf(); return START_NOT_STICKY }
        goForeground()
        running = true
        if (!paused) startListening()
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        stopListening()
        runCatching { model?.close() }
        model = null
        super.onDestroy()
    }

    // ------------------------------------------------------------------ écoute

    private fun startListening() {
        if (speech != null) return
        Thread {
            try {
                LibVosk.setLogLevel(LogLevel.WARNINGS)
                val m = model ?: Model(WakeModel.dir(this).absolutePath).also { model = it }
                // Mots absents du vocabulaire : ignorés par Vosk. [unk] absorbe tout le reste.
                val rec = Recognizer(m, 16000f, "[\"jarvis\", \"jervis\", \"travis\", \"[unk]\"]")
                main.post {
                    if (paused || speech != null) { rec.close(); return@post }
                    try {
                        speech = SpeechService(rec, 16000f).also { it.startListening(this) }
                    } catch (_: Exception) {
                        retryLater()
                    }
                }
            } catch (_: Exception) {
                main.post { retryLater() }
            }
        }.start()
    }

    private fun stopListening() {
        main.removeCallbacksAndMessages(null)
        runCatching { speech?.stop() }
        runCatching { speech?.shutdown() }
        speech = null
    }

    /** Micro occupé (appel, autre appli) : on réessaie un peu plus tard. */
    private fun retryLater() {
        stopListening()
        main.postDelayed({ if (!paused) startListening() }, 20_000)
    }

    private fun heard(json: String?, key: String) {
        val words = runCatching { JSONObject(json ?: return).optString(key) }.getOrNull() ?: return
        if (!Regex("\\b(jarvis|jervis|travis)\\b").containsMatchIn(words)) return
        val now = System.currentTimeMillis()
        if (now - lastWake < 3000) return
        lastWake = now
        paused = true
        stopListening()
        runCatching {
            val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 60)
            tone.startTone(ToneGenerator.TONE_PROP_ACK, 150)
            main.postDelayed({ tone.release() }, 400)
        }
        runCatching {
            startActivity(
                Intent(this, AssistantActivity::class.java)
                    .putExtra(AssistantActivity.EXTRA_VOICE, true)
                    .putExtra(AssistantActivity.EXTRA_FROM_WAKE, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
        }
        // Si l'assistant ne s'est pas ouvert (autorisation manquante), on reprend l'écoute.
        main.postDelayed({ if (paused && !AssistantActivity.visible) { paused = false; startListening() } }, 4000)
    }

    override fun onPartialResult(hypothesis: String?) = heard(hypothesis, "partial")
    override fun onResult(hypothesis: String?) = heard(hypothesis, "text")
    override fun onFinalResult(hypothesis: String?) = heard(hypothesis, "text")
    override fun onError(exception: Exception?) = retryLater()
    override fun onTimeout() = retryLater()

    // ------------------------------------------------------------------ notification

    private fun goForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm?.createNotificationChannel(NotificationChannel(CHANNEL, "Écoute « Jarvis »", NotificationManager.IMPORTANCE_MIN))
        val stop = PendingIntent.getService(this, 1, Intent(this, JarvisService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 2, Intent(this, AssistantActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Jarvis vous écoute")
            .setContentText("Dites « Jarvis » puis votre demande. Rien ne quitte le téléphone.")
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Arrêter", stop).build())
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 30) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        else startForeground(NOTIF_ID, n)
    }

    companion object {
        private const val CHANNEL = "jarvis_ecoute"
        private const val NOTIF_ID = 77
        const val ACTION_START = "orbite.jarvis.START"
        const val ACTION_STOP = "orbite.jarvis.STOP"
        const val ACTION_PAUSE = "orbite.jarvis.PAUSE"
        const val ACTION_RESUME = "orbite.jarvis.RESUME"

        fun canRun(ctx: Context): Boolean {
            if (!Persona.wakeEnabled(ctx) || !WakeModel.isReady(ctx)) return false
            if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return false
            if (Persona.onlyCharging(ctx) && !isCharging(ctx)) return false
            return true
        }

        private fun isCharging(ctx: Context): Boolean {
            val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return false
            val s = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            return s == BatteryManager.BATTERY_STATUS_CHARGING || s == BatteryManager.BATTERY_STATUS_FULL
        }

        /** À appeler quand Orbite est à l'écran (Android interdit de lancer le micro depuis l'arrière-plan). */
        fun start(ctx: Context) {
            if (!canRun(ctx)) return
            runCatching { ctx.startForegroundService(Intent(ctx, JarvisService::class.java).setAction(ACTION_START)) }
        }

        /** Vrai quand le service tourne au premier plan. */
        @Volatile var running = false
            private set

        /** Pause / reprise pendant que l'assistant utilise lui-même le micro. */
        fun send(ctx: Context, action: String) {
            if (!canRun(ctx)) return
            if (!running) { if (action == ACTION_RESUME) start(ctx); return }
            runCatching { ctx.startService(Intent(ctx, JarvisService::class.java).setAction(action)) }
        }

        fun stop(ctx: Context) {
            if (!running) return
            runCatching { ctx.startService(Intent(ctx, JarvisService::class.java).setAction(ACTION_STOP)) }
        }
    }
}
