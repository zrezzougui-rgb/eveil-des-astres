package com.zrezzougui.orbite.launcher

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Process
import android.provider.Settings
import java.io.File

data class AppInfo(
    val installedAt: Long,
    val updatedAt: Long,
    /** Taille du paquet installé (hors données et cache, qui demandent une autorisation système). */
    val sizeBytes: Long,
    /** Dernière utilisation sur 30 jours ; null = statistiques non autorisées ; 0 = pas utilisée. */
    val lastUsed: Long?,
    val foregroundMs: Long?,
)

/** Infos sur les applis : installation et taille sans autorisation, usage avec « Accès aux données d'utilisation ». */
object AppStats {

    const val WINDOW_DAYS = 30

    fun hasUsageAccess(ctx: Context): Boolean {
        val ops = ctx.getSystemService(AppOpsManager::class.java) ?: return false
        return ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName) ==
            AppOpsManager.MODE_ALLOWED
    }

    fun usageAccessIntent() = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)

    /** À appeler hors du fil principal. */
    fun collect(ctx: Context, apps: List<AppEntry>): Map<String, AppInfo> {
        val pm = ctx.packageManager
        val usage = if (hasUsageAccess(ctx)) {
            val usm = ctx.getSystemService(UsageStatsManager::class.java)
            val end = System.currentTimeMillis()
            runCatching { usm?.queryAndAggregateUsageStats(end - WINDOW_DAYS * 86_400_000L, end) }.getOrNull() ?: emptyMap()
        } else null
        return apps.map { it.packageName }.distinct().associateWith { pkg ->
            val pi = runCatching { pm.getPackageInfo(pkg, 0) }.getOrNull()
            val ai = pi?.applicationInfo
            var size = 0L
            if (ai != null) {
                size += File(ai.sourceDir).length()
                ai.splitSourceDirs?.forEach { size += File(it).length() }
            }
            val u = usage?.get(pkg)
            AppInfo(
                installedAt = pi?.firstInstallTime ?: 0L,
                updatedAt = pi?.lastUpdateTime ?: 0L,
                sizeBytes = size,
                lastUsed = if (usage == null) null else (u?.lastTimeUsed ?: 0L),
                foregroundMs = if (usage == null) null else (u?.totalTimeInForeground ?: 0L),
            )
        }
    }

    fun size(bytes: Long): String = when {
        bytes <= 0 -> "?"
        bytes < 1_000_000 -> "${bytes / 1000} Ko"
        bytes < 1_000_000_000 -> "${bytes / 1_000_000} Mo"
        else -> "%.1f Go".format(java.util.Locale.FRENCH, bytes / 1e9)
    }

    fun ago(t: Long): String {
        if (t <= 0) return "jamais (30 j)"
        val d = (System.currentTimeMillis() - t) / 86_400_000L
        return when {
            d < 1 -> "aujourd'hui"
            d < 2 -> "hier"
            else -> "il y a $d j"
        }
    }
}
