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

/**
 * Écoute en continu « Hey Jarvis », entièrement sur le téléphone (openWakeWord + TensorFlow Lite).
 * Seul un score de détection est calculé : rien n'est enregistré ni envoyé.
 * Quand la phrase est entendue, l'assistant s'ouvre et écoute la demande.
 */
class JarvisService : Service() {

    private val main = Handler(Looper.getMainLooper())
    private var engine: WakeWordEngine? = null
    private var listening = false
    private var paused = false
    private var lastWake = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopListening(); stopSelf(); return START_NOT_STICKY }
            ACTION_PAUSE -> { paused = true; stopListening(); return START_STICKY }
            ACTION_RESUME -> { paused = false }
        }
        // Android exige startForeground() rapidement après startForegroundService(), même si on s'arrête aussitôt.
        val fg = runCatching { goForeground() }
        if (fg.isFailure) {
            Diag.log(this, "Service : impossible de passer au premier plan : ${fg.exceptionOrNull()?.message}")
            stopSelf(); return START_NOT_STICKY
        }
        if (!canRun(this)) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return START_NOT_STICKY }
        running = true
        if (!paused) startListening()
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        stopListening()
        runCatching { engine?.close() }
        engine = null
        super.onDestroy()
    }

    // ------------------------------------------------------------------ écoute

    private fun startListening() {
        if (listening) return
        listening = true
        val threshold = Persona.threshold(this)
        // Chargement des modèles (quelques centaines de ms) hors du fil principal.
        Thread {
            try {
                val e = engine ?: WakeWordEngine(this).also { engine = it }
                main.post {
                    if (!listening || paused) return@post
                    Diag.log(this, "Écoute « Hey Jarvis » démarrée (seuil $threshold)")
                    e.resetStats()
                    scheduleReport(e, 1)
                    e.start(threshold,
                        onWake = { score -> main.post { Diag.log(this, "« Hey Jarvis » entendu (score %.2f)".format(score)); heard() } },
                        onError = { err -> main.post { Diag.log(this, "Micro : ${err.message}"); retryLater() } })
                }
            } catch (t: Throwable) {
                Diag.log(this, "Chargement du détecteur impossible : ${t.javaClass.simpleName} ${t.message}")
                main.post { retryLater() }
            }
        }.start()
    }

    private fun stopListening() {
        main.removeCallbacksAndMessages(null)
        listening = false
        runCatching { engine?.stop() }
    }

    /** Bilan de l'écoute en arrière-plan dans le journal : à 1, 2, 5 et 10 minutes. */
    private fun scheduleReport(e: WakeWordEngine, n: Int) {
        val delays = longArrayOf(60_000, 60_000, 180_000, 300_000)
        if (n > delays.size) return
        main.postDelayed({
            if (!listening) return@postDelayed
            Diag.log(this, "Écoute de fond : " + e.statsText())
            scheduleReport(e, n + 1)
        }, delays[n - 1])
    }

    /** Micro occupé (appel, autre appli) : on réessaie un peu plus tard. */
    private fun retryLater() {
        stopListening()
        main.postDelayed({ if (!paused) startListening() }, 15_000)
    }

    private fun heard() {
        val now = System.currentTimeMillis()
        if (now - lastWake < 2500) return
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
        // Android peut bloquer l'ouverture depuis l'arrière-plan : notification de secours à toucher.
        main.postDelayed({
            if (!AssistantActivity.visible) {
                Diag.log(this, "Ouverture de l'assistant bloquée par Android : notification de secours")
                wakeNotification()
            }
        }, 1500)
        // Filet de sécurité : si l'assistant n'a pas repris la main, on réécoute.
        main.postDelayed({ if (paused && !AssistantActivity.listeningNow) { paused = false; startListening() } }, 8000)
    }

    // ------------------------------------------------------------------ notification

    private fun wakeNotification() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(CHANNEL_WAKE, "Jarvis réveillé", NotificationManager.IMPORTANCE_HIGH))
        val open = PendingIntent.getActivity(this, 3,
            Intent(this, AssistantActivity::class.java).putExtra(AssistantActivity.EXTRA_VOICE, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = Notification.Builder(this, CHANNEL_WAKE)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Oui ?")
            .setContentText("Touchez pour parler à Jarvis. Autorisez « Afficher par-dessus les autres applis » pour qu'il s'ouvre seul.")
            .setContentIntent(open)
            .setAutoCancel(true)
            .setTimeoutAfter(15_000)
            .build()
        nm.notify(NOTIF_WAKE, n)
    }

    private fun goForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm?.createNotificationChannel(NotificationChannel(CHANNEL, "Écoute « Hey Jarvis »", NotificationManager.IMPORTANCE_MIN))
        val stop = PendingIntent.getService(this, 1, Intent(this, JarvisService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 2, Intent(this, AssistantActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Jarvis vous écoute")
            .setContentText("Dites « Hey Jarvis » puis votre demande. Rien ne quitte le téléphone.")
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
        private const val CHANNEL_WAKE = "jarvis_reveil"
        private const val NOTIF_WAKE = 78
        const val ACTION_START = "orbite.jarvis.START"
        const val ACTION_STOP = "orbite.jarvis.STOP"
        const val ACTION_PAUSE = "orbite.jarvis.PAUSE"
        const val ACTION_RESUME = "orbite.jarvis.RESUME"

        fun canRun(ctx: Context): Boolean {
            if (!Persona.wakeEnabled(ctx)) return false
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
            Diag.install(ctx)
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
