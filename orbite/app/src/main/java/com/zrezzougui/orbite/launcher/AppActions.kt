package com.zrezzougui.orbite.launcher

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import com.zrezzougui.orbite.Palette
import com.zrezzougui.orbite.dp
import com.zrezzougui.orbite.heading
import com.zrezzougui.orbite.horizontal
import com.zrezzougui.orbite.label
import com.zrezzougui.orbite.margins
import com.zrezzougui.orbite.matchWrap
import com.zrezzougui.orbite.pill
import com.zrezzougui.orbite.vertical
import com.zrezzougui.orbite.weighted
import com.zrezzougui.orbite.wrapWrap

/**
 * Actions sur les applis et les dossiers, partagées par l'accueil d'Orbite, l'écran « Mes applis »
 * et les dossiers épinglés sur l'écran d'accueil du téléphone.
 */
class AppActions(
    private val activity: Activity,
    val repo: AppRepository,
    private val apps: () -> List<AppEntry>,
    private val onChanged: () -> Unit,
) {

    // ------------------------------------------------------------------ dossier ouvert

    fun openFolder(name: String, onClose: () -> Unit = {}): Dialog? {
        val list = apps().filter { it.folder == name }
        if (list.isEmpty()) {
            toast("Le dossier « $name » est vide ou n'existe plus.")
            onClose()
            return null
        }
        val a = activity
        val d = Dialog(a, android.R.style.Theme_Material_NoActionBar)
        d.window?.setBackgroundDrawable(ColorDrawable(0xEB050B14.toInt()))
        d.setOnDismissListener { onClose() }
        val box = a.vertical().apply { setPadding(a.dp(20), a.dp(48), a.dp(20), a.dp(24)); isClickable = true }
        val head = a.horizontal()
        head.addView(a.heading(name, 22f).apply {
            setOnClickListener { renameFolder(name) { d.dismiss() } }
        }, weighted())
        head.addView(a.label("✕", 22f, Palette.MUTED).apply {
            setPadding(a.dp(16), a.dp(4), 0, a.dp(4)); setOnClickListener { d.dismiss() }
        })
        box.addView(head, matchWrap())
        box.addView(a.label("${list.size} applis · appui long sur une appli pour la gérer · touchez le titre pour renommer", 12f, Palette.MUTED),
            matchWrap().margins(t = a.dp(4), b = a.dp(12)))
        if (canPin()) {
            box.addView(a.pill("📌 Mettre ce dossier sur l'écran d'accueil du téléphone") { pinFolder(name) },
                wrapWrap().margins(b = a.dp(12)))
        }

        val grid = GridLayout(a).apply { columnCount = 4 }
        list.forEach { e -> grid.addView(appTile(e, onOpen = { d.dismiss() }), cell()) }
        box.addView(ScrollView(a).apply { addView(grid) }, matchWrap())
        d.setContentView(FrameLayout(a).apply {
            addView(box)
            setOnClickListener { d.dismiss() }
        })
        d.show()
        return d
    }

    fun cell() = GridLayout.LayoutParams(
        GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f),
    ).apply { width = 0 }

    fun appTile(e: AppEntry, onOpen: () -> Unit = {}): View = activity.vertical().apply {
        val a = activity
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(a.dp(4), a.dp(8), a.dp(4), a.dp(8))
        addView(ImageView(a).apply { setImageDrawable(e.iconCopy()) }, LinearLayout.LayoutParams(a.dp(52), a.dp(52)))
        addView(a.label(e.label, 12f).apply {
            gravity = Gravity.CENTER; maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }, matchWrap().margins(t = a.dp(4)))
        setOnClickListener { onOpen(); launch(e) }
        setOnLongClickListener { appMenu(e, onOpen); true }
    }

    fun launch(e: AppEntry) = safeStart(e.launchIntent(), "Impossible d'ouvrir ${e.label}.")

    // ------------------------------------------------------------------ menus

    fun appMenu(e: AppEntry, onDone: () -> Unit = {}) {
        val items = arrayOf(
            "Ouvrir",
            "Déplacer vers un dossier… (actuel : ${e.folder})",
            "Infos, autorisations et stockage",
            "Mettre à jour (Play Store)",
            "Désinstaller",
        )
        AlertDialog.Builder(activity, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(e.label)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> { onDone(); launch(e) }
                    1 -> moveDialog(e, onDone)
                    2 -> safeStart(detailsIntent(e.packageName))
                    3 -> safeStart(storeIntent(e.packageName))
                    4 -> { onDone(); safeStart(uninstallIntent(e.packageName)) }
                }
            }
            .show()
    }

    fun moveDialog(e: AppEntry, onDone: () -> Unit = {}) {
        val names = (repo.orderedFolders(apps()).map { it.first } + AppCategorizer.defaultFolders)
            .distinct().filter { it != e.folder }
        val items = (listOf("➕ Nouveau dossier…", "↺ Rangement automatique") + names).toTypedArray()
        AlertDialog.Builder(activity, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Ranger « ${e.label} » dans…")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> askName("Nom du nouveau dossier", "") { name -> repo.moveApp(e.packageName, name); onDone(); onChanged() }
                    1 -> { repo.resetApp(e.packageName); onDone(); onChanged() }
                    else -> { repo.moveApp(e.packageName, items[which]); onDone(); onChanged() }
                }
            }
            .show()
    }

    fun renameFolder(old: String, onDone: () -> Unit = {}) {
        askName("Renommer le dossier", old) { name ->
            repo.renameFolder(old, name, apps())
            HomeShortcuts.rename(activity, old, name, apps())
            onDone(); onChanged()
        }
    }

    fun askName(title: String, initial: String, onOk: (String) -> Unit) {
        val a = activity
        val field = EditText(a).apply {
            setText(initial); setSelection(initial.length); isSingleLine = true
            setTextColor(Color.WHITE)
        }
        val frame = FrameLayout(a).apply { setPadding(a.dp(20), a.dp(8), a.dp(20), 0); addView(field) }
        AlertDialog.Builder(a, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(title)
            .setView(frame)
            .setPositiveButton("OK") { _, _ ->
                val v = field.text.toString().trim()
                if (v.isNotEmpty()) onOk(v)
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    // ------------------------------------------------------------------ dossiers sur l'écran du téléphone

    fun canPin(): Boolean = HomeShortcuts.canPin(activity)

    /**
     * Ajoute le dossier sur l'écran d'accueil actuel du téléphone (celui du fabricant).
     * Android affiche toujours une confirmation : une appli ne peut pas modifier cet écran toute seule.
     */
    fun pinFolder(name: String) {
        if (!canPin()) {
            toast("Votre écran d'accueil n'accepte pas les raccourcis d'applis.")
            return
        }
        val ok = HomeShortcuts.request(activity, name, apps())
        toast(if (ok) "Confirmez l'ajout de « $name » sur l'écran d'accueil." else "Ajout refusé par l'écran d'accueil.")
    }

    // ------------------------------------------------------------------ utilitaires

    fun safeStart(i: Intent, error: String = "Action impossible sur ce téléphone.") {
        try {
            activity.startActivity(i)
        } catch (_: Exception) {
            toast(error)
        }
    }

    private fun toast(s: String) = Toast.makeText(activity, s, Toast.LENGTH_LONG).show()

    companion object {
        fun detailsIntent(pkg: String) = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))
        fun uninstallIntent(pkg: String) = Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg"))
        fun storeIntent(pkg: String) = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg"))
    }
}
