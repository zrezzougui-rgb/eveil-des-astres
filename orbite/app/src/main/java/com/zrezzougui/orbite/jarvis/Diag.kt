package com.zrezzougui.orbite.jarvis

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Journal technique local (jamais envoyé) : plantages, blocages (« ne répond pas ») relevés par Android,
 * et événements de l'écoute « Hey Jarvis ». Affiché dans Réglages Jarvis → Diagnostic.
 */
object Diag {

    private fun file(ctx: Context) = File(ctx.filesDir, "journal.txt")
    private val fmt get() = SimpleDateFormat("dd/MM HH:mm:ss", Locale.FRENCH)

    /** Enregistre les plantages de l'appli avant qu'Android ne la ferme. */
    fun install(ctx: Context) {
        val app = ctx.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        if (previous is Handler) return
        Thread.setDefaultUncaughtExceptionHandler(Handler(app, previous))
    }

    private class Handler(val ctx: Context, val previous: Thread.UncaughtExceptionHandler?) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(t: Thread, e: Throwable) {
            runCatching { log(ctx, "PLANTAGE [${t.name}] " + e.stackTraceToString().lines().take(25).joinToString("\n")) }
            previous?.uncaughtException(t, e)
        }
    }

    fun log(ctx: Context, msg: String) {
        runCatching {
            val f = file(ctx)
            if (f.length() > 200_000) f.writeText(f.readText().takeLast(100_000))
            f.appendText("${fmt.format(Date())}  $msg\n")
        }
    }

    fun journal(ctx: Context): String = runCatching { file(ctx).readText().takeLast(6000) }.getOrDefault("")

    fun clear(ctx: Context) { runCatching { file(ctx).delete() } }

    /** Dernières fermetures de l'appli vues par Android (blocages et plantages), avec la pile du fil principal. */
    fun exits(ctx: Context): String {
        if (Build.VERSION.SDK_INT < 30) return "Historique indisponible (Android 11 minimum)."
        val am = ctx.getSystemService(ActivityManager::class.java) ?: return ""
        val list = runCatching { am.getHistoricalProcessExitReasons(ctx.packageName, 0, 6) }.getOrNull().orEmpty()
        if (list.isEmpty()) return "Aucune fermeture anormale enregistrée."
        return list.joinToString("\n\n") { e ->
            val reason = when (e.reason) {
                ApplicationExitInfo.REASON_ANR -> "BLOCAGE (ne répond pas)"
                ApplicationExitInfo.REASON_CRASH -> "PLANTAGE"
                ApplicationExitInfo.REASON_CRASH_NATIVE -> "PLANTAGE NATIF"
                ApplicationExitInfo.REASON_LOW_MEMORY -> "mémoire faible"
                ApplicationExitInfo.REASON_USER_REQUESTED -> "fermée par l'utilisateur"
                ApplicationExitInfo.REASON_USER_STOPPED -> "arrêt forcé"
                ApplicationExitInfo.REASON_EXIT_SELF -> "fermeture normale"
                ApplicationExitInfo.REASON_SIGNALED -> "tuée par le système"
                ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "autorisation modifiée"
                ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "dépendance arrêtée"
                else -> "autre (${e.reason})"
            }
            val head = "${fmt.format(Date(e.timestamp))}  $reason  ${e.description.orEmpty()}".trim()
            if (e.reason == ApplicationExitInfo.REASON_ANR) head + "\n" + anrMainThread(e) else head
        }
    }

    /** Pile du fil principal au moment du blocage : c'est elle qui dit ce qui bloquait. */
    private fun anrMainThread(e: ApplicationExitInfo): String {
        val trace = runCatching { e.traceInputStream?.bufferedReader()?.use { it.readText() } }.getOrNull() ?: return "(pas de trace)"
        val start = trace.indexOf("\"main\"")
        if (start < 0) return trace.take(1500)
        val block = trace.substring(start).substringBefore("\n\n")
        return block.lines().filter { it.contains("at ") || it.startsWith("\"main\"") }
            .take(30).joinToString("\n")
    }
}
