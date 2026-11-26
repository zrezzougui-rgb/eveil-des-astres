package com.zrezzougui.orbite.launcher

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build

/**
 * Tâche de fond (environ toutes les heures, quand Android le permet) :
 * repère les applis nouvellement installées, les range dans leur dossier,
 * met à jour les dossiers posés sur l'écran d'accueil et prévient l'utilisateur.
 * Rien n'est envoyé sur internet.
 */
class NewAppsJob : JobService() {

    @Volatile private var worker: Thread? = null

    override fun onStartJob(params: JobParameters): Boolean {
        worker = Thread {
            runCatching { run(applicationContext) }
            jobFinished(params, false)
        }.also { it.start() }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        worker?.interrupt()
        return true
    }

    companion object {
        private const val JOB_ID = 4242
        private const val CHANNEL = "nouvelles_applis"
        private const val PREFS = "surveillance"

        /** Planifie la surveillance (sans effet si elle l'est déjà). */
        fun schedule(ctx: Context) {
            val js = ctx.getSystemService(JobScheduler::class.java) ?: return
            if (js.getPendingJob(JOB_ID) != null) return
            val job = JobInfo.Builder(JOB_ID, ComponentName(ctx, NewAppsJob::class.java))
                .setPeriodic(60 * 60_000L, 30 * 60_000L)
                .setPersisted(true)
                .build()
            runCatching { js.schedule(job) }
        }

        fun notifyEnabled(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("prevenir", true)
        fun setNotifyEnabled(ctx: Context, on: Boolean) =
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("prevenir", on).apply()

        /**
         * Compare avec la dernière liste connue. Appelée par la tâche de fond et à chaque ouverture d'Orbite.
         * La première fois, elle mémorise simplement les applis présentes.
         */
        fun run(ctx: Context) {
            val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val repo = AppRepository(ctx)
            val apps = repo.load()
            val now = apps.map { it.packageName }.toSet()
            val known = prefs.getStringSet("connues", null)
            prefs.edit().putStringSet("connues", now).apply()
            HomeShortcuts.sync(ctx, apps)
            if (known == null) return
            val fresh = apps.filter { it.packageName !in known }.distinctBy { it.packageName }
            if (fresh.isEmpty() || !notifyEnabled(ctx)) return
            notify(ctx, fresh, HomeShortcuts.pinnedFolders(ctx))
        }

        private fun notify(ctx: Context, fresh: List<AppEntry>, pinned: Set<String>) {
            if (Build.VERSION.SDK_INT >= 33 &&
                ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) return
            val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Nouvelles applis rangées", NotificationManager.IMPORTANCE_LOW)
            )
            fresh.take(5).forEachIndexed { i, e ->
                val open = Intent(ctx, FolderActivity::class.java)
                    .setAction(Intent.ACTION_VIEW)
                    .putExtra(FolderActivity.EXTRA_FOLDER, e.folder)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                val manage = Intent(ctx, AppsActivity::class.java)
                    .putExtra(AppsActivity.EXTRA_FOLDER, e.folder)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                val onHome = e.folder in pinned
                val text = if (onHome) "Elle est dans ce dossier sur votre écran d'accueil. Vous pouvez retirer son icône seule."
                else "Touchez pour ouvrir le dossier, ou changez-la de dossier dans « Mes applis »."
                val n = Notification.Builder(ctx, CHANNEL)
                    .setSmallIcon(android.R.drawable.ic_menu_sort_by_size)
                    .setContentTitle("${e.label} rangée dans « ${e.folder} »")
                    .setContentText(text)
                    .setStyle(Notification.BigTextStyle().bigText(text))
                    .setContentIntent(PendingIntent.getActivity(ctx, 100 + i, open, flags))
                    .addAction(Notification.Action.Builder(null, "Changer de dossier", PendingIntent.getActivity(ctx, 200 + i, manage, flags)).build())
                    .setAutoCancel(true)
                    .build()
                nm.notify(e.packageName.hashCode(), n)
            }
        }
    }
}
