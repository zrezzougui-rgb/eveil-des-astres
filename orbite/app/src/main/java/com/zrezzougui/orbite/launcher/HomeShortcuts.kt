package com.zrezzougui.orbite.launcher

import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Icon
import com.zrezzougui.orbite.Palette

/**
 * Dossiers d'Orbite posés sur l'écran d'accueil du fabricant (raccourcis épinglés).
 * Orbite ne peut pas lire ni modifier cet écran, mais peut mettre à jour SES raccourcis :
 * icône (4 premières applis), nom, et dossier visé après un renommage.
 */
object HomeShortcuts {

    private const val PREFIX = "dossier:"

    private fun manager(ctx: Context) = ctx.getSystemService(ShortcutManager::class.java)

    fun canPin(ctx: Context) = manager(ctx)?.isRequestPinShortcutSupported == true

    /** Nom du dossier visé par chaque raccourci épinglé. */
    fun pinnedFolders(ctx: Context): Set<String> =
        runCatching {
            manager(ctx)?.pinnedShortcuts.orEmpty()
                .filter { it.id.startsWith(PREFIX) && it.isEnabled }
                .mapNotNull { folderOf(it) }
                .toSet()
        }.getOrDefault(emptySet())

    private fun folderOf(s: ShortcutInfo): String? =
        s.intent?.getStringExtra(FolderActivity.EXTRA_FOLDER) ?: s.id.removePrefix(PREFIX)

    fun build(ctx: Context, name: String, apps: List<AppEntry>, id: String = PREFIX + name): ShortcutInfo {
        val intent = Intent(ctx, FolderActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .putExtra(FolderActivity.EXTRA_FOLDER, name)
        return ShortcutInfo.Builder(ctx, id)
            .setShortLabel(name.take(24))
            .setLongLabel(name)
            .setIcon(folderIcon(ctx, apps.filter { it.folder == name }))
            .setIntent(intent)
            .build()
    }

    /** Demande l'ajout ; `callback` est déclenché quand l'utilisateur a accepté. */
    fun request(ctx: Context, name: String, apps: List<AppEntry>, callback: IntentSender? = null): Boolean {
        val sm = manager(ctx) ?: return false
        if (!sm.isRequestPinShortcutSupported) return false
        return runCatching { sm.requestPinShortcut(build(ctx, name, apps), callback) }.getOrDefault(false)
    }

    /**
     * Met à jour tous les raccourcis épinglés : nouvelle icône si le contenu a changé,
     * désactivation (avec message) si le dossier n'existe plus. `apps` doit contenir les icônes.
     */
    fun sync(ctx: Context, apps: List<AppEntry>) {
        val sm = manager(ctx) ?: return
        runCatching {
            val folders = apps.map { it.folder }.toSet()
            val pinned = sm.pinnedShortcuts.filter { it.id.startsWith(PREFIX) }
            val update = mutableListOf<ShortcutInfo>()
            val gone = mutableListOf<String>()
            for (s in pinned) {
                val name = folderOf(s) ?: continue
                if (name in folders) update += build(ctx, name, apps, id = s.id) else gone += s.id
            }
            if (update.isNotEmpty()) {
                sm.enableShortcuts(update.map { it.id })
                sm.updateShortcuts(update)
            }
            if (gone.isNotEmpty()) sm.disableShortcuts(gone, "Ce dossier est vide ou a été supprimé dans Orbite.")
        }
    }

    /** Après un renommage, le raccourci déjà posé suit le nouveau nom. */
    fun rename(ctx: Context, old: String, new: String, apps: List<AppEntry>) {
        val sm = manager(ctx) ?: return
        runCatching {
            val s = sm.pinnedShortcuts.firstOrNull { it.id.startsWith(PREFIX) && folderOf(it) == old } ?: return
            sm.updateShortcuts(listOf(build(ctx, new, apps.map { if (it.folder == old) it.copy(folder = new) else it }, id = s.id)))
        }
    }

    /** Icône de dossier : fond Orbite et les 4 premières icônes, au format icône adaptative. */
    fun folderIcon(ctx: Context, list: List<AppEntry>): Icon {
        val px = (108 * ctx.resources.displayMetrics.density).toInt().coerceAtLeast(108)
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Palette.SURFACE_2)
        val safe = px * 0.62f // zone sûre d'une icône adaptative
        val start = (px - safe) / 2
        val gap = safe * 0.08f
        val cell = (safe - gap) / 2
        list.take(4).forEachIndexed { i, e ->
            val x = start + (i % 2) * (cell + gap)
            val y = start + (i / 2) * (cell + gap)
            val d = e.iconCopy()
            d.setBounds(x.toInt(), y.toInt(), (x + cell).toInt(), (y + cell).toInt())
            d.draw(c)
        }
        return Icon.createWithAdaptiveBitmap(bmp)
    }
}
