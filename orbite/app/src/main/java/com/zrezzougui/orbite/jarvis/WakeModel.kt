package com.zrezzougui.orbite.jarvis

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * Modèle de reconnaissance vocale Vosk (libre, Apache 2.0) utilisé uniquement pour entendre « Jarvis ».
 * Téléchargé une fois (~40 Mo), puis tout fonctionne hors ligne : aucun son ne quitte le téléphone.
 */
object WakeModel {

    const val URL_ZIP = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"
    const val SIZE_MB = 40

    fun dir(ctx: Context) = File(ctx.filesDir, "vosk-wake")
    fun isReady(ctx: Context) = File(dir(ctx), "ready").exists()

    /**
     * Télécharge et décompresse le modèle (hors du fil principal).
     * `progress` reçoit un pourcentage, -1 si la taille est inconnue.
     */
    fun download(ctx: Context, progress: (Int) -> Unit) {
        val target = dir(ctx)
        val tmp = File(ctx.cacheDir, "vosk.zip")
        val c = URL(URL_ZIP).openConnection() as HttpURLConnection
        c.connectTimeout = 15000; c.readTimeout = 30000
        try {
            if (c.responseCode !in 200..299) throw IllegalStateException("Téléchargement impossible (HTTP ${c.responseCode})")
            val total = c.contentLengthLong
            c.inputStream.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L; var last = -2
                    while (true) {
                        val n = input.read(buf); if (n < 0) break
                        out.write(buf, 0, n); done += n
                        val pct = if (total > 0) (done * 100 / total).toInt() else -1
                        if (pct != last) { last = pct; progress(pct) }
                    }
                }
            }
        } finally {
            c.disconnect()
        }
        target.deleteRecursively()
        target.mkdirs()
        val root = target.canonicalPath + File.separator
        ZipInputStream(tmp.inputStream().buffered()).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                // L'archive contient un dossier racine : on l'enlève.
                val rel = e.name.substringAfter('/', "")
                if (rel.isEmpty()) continue
                val out = File(target, rel)
                if (!out.canonicalPath.startsWith(root)) continue // protection contre les chemins « ../ »
                if (e.isDirectory) out.mkdirs() else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { zip.copyTo(it) }
                }
            }
        }
        tmp.delete()
        File(target, "ready").writeText("ok")
    }

    fun delete(ctx: Context) {
        dir(ctx).deleteRecursively()
    }
}
