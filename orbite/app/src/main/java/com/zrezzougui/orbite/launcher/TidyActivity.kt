package com.zrezzougui.orbite.launcher

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.zrezzougui.orbite.Palette
import com.zrezzougui.orbite.Radius
import com.zrezzougui.orbite.chip
import com.zrezzougui.orbite.dp
import com.zrezzougui.orbite.dpf
import com.zrezzougui.orbite.edgeToEdge
import com.zrezzougui.orbite.heading
import com.zrezzougui.orbite.horizontal
import com.zrezzougui.orbite.label
import com.zrezzougui.orbite.margins
import com.zrezzougui.orbite.matchWrap
import com.zrezzougui.orbite.padForSystemBars
import com.zrezzougui.orbite.pill
import com.zrezzougui.orbite.rounded
import com.zrezzougui.orbite.vertical
import com.zrezzougui.orbite.weighted
import com.zrezzougui.orbite.wrapWrap

/**
 * « Ranger mon écran d'accueil » : Orbite organise l'écran du fabricant sans le remplacer.
 * 1. poser les dossiers d'Orbite ; 2. retirer les icônes et dossiers devenus inutiles ;
 * 3. ranger automatiquement les nouvelles applis.
 */
class TidyActivity : Activity() {

    private lateinit var repo: AppRepository
    private var apps: List<AppEntry> = emptyList()
    private var pinned: Set<String> = emptySet()
    /** File des dossiers à poser un par un (Android confirme chaque ajout). */
    private val queue = ArrayDeque<String>()
    private lateinit var body: LinearLayout
    private val checks by lazy { getSharedPreferences("menage", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        edgeToEdge()
        repo = AppRepository(this)
        NewAppsJob.schedule(this)

        val root = vertical().apply { setBackgroundColor(Palette.BG) }
        root.padForSystemBars()
        val header = horizontal().apply { setPadding(dp(16), dp(12), dp(16), dp(4)) }
        header.addView(label("‹", 28f, Palette.MUTED).apply { setPadding(0, 0, dp(16), 0); setOnClickListener { finish() } })
        header.addView(vertical().apply {
            addView(heading("Ranger mon écran d'accueil", 22f))
            addView(label("Votre écran reste le vôtre : Orbite y range vos applis par dossiers.", 13f, Palette.MUTED))
        }, weighted())
        root.addView(header, matchWrap())

        body = vertical().apply { setPadding(dp(16), dp(8), dp(16), dp(24)) }
        root.addView(ScrollView(this).apply { addView(body) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun reload() {
        Thread {
            val l = runCatching { repo.load() }.getOrDefault(emptyList())
            HomeShortcuts.sync(this, l)
            val p = HomeShortcuts.pinnedFolders(this)
            runOnUiThread { apps = l; pinned = p; render() }
        }.start()
    }

    // ------------------------------------------------------------------ affichage

    private fun render() {
        body.removeAllViews()
        val folders = repo.orderedFolders(apps)
        stepFolders(folders)
        stepCleanup(folders)
        stepNewApps()
    }

    private fun card(step: String, title: String, text: String): LinearLayout {
        val c = vertical().apply {
            background = rounded(Palette.SURFACE, dpf(Radius.LG))
            setPadding(dp(16), dp(14), dp(16), dp(16))
        }
        c.addView(label(step, 12f, Palette.PRIMARY, bold = true).apply { letterSpacing = 0.06f })
        c.addView(heading(title, 18f), matchWrap().margins(t = dp(2)))
        c.addView(label(text, 14f, Palette.MUTED), matchWrap().margins(t = dp(4), b = dp(10)))
        body.addView(c, matchWrap().margins(b = dp(12)))
        return c
    }

    private fun stepFolders(folders: List<Pair<String, List<AppEntry>>>) {
        val missing = folders.map { it.first }.filter { it !in pinned }
        val c = card(
            "ÉTAPE 1", "Poser les dossiers d'Orbite",
            if (!HomeShortcuts.canPin(this)) "Votre écran d'accueil n'accepte pas les dossiers ajoutés par une appli."
            else "Chaque dossier apparaît sur votre écran. Android demande de confirmer chaque ajout. " +
                "Leurs icônes se mettent à jour toutes seules.",
        )
        if (!HomeShortcuts.canPin(this)) return
        if (missing.isNotEmpty()) {
            c.addView(pill("Tout ajouter (${missing.size} dossiers)", filled = true) {
                queue.clear(); queue.addAll(missing); pinNext()
            }, wrapWrap().margins(b = dp(10)))
        } else {
            c.addView(label("✔ Tous les dossiers sont sur votre écran.", 14f, Palette.SUCCESS), matchWrap().margins(b = dp(8)))
        }
        folders.forEach { (name, list) ->
            val row = horizontal().apply { setPadding(0, dp(4), 0, dp(4)) }
            row.addView(label("$name · ${list.size}", 15f), weighted())
            if (name in pinned) row.addView(label("✔ sur l'écran", 13f, Palette.SUCCESS))
            else row.addView(chip("Ajouter", false) { queue.clear(); queue.add(name); pinNext() })
            c.addView(row, matchWrap())
        }
    }

    private fun stepCleanup(folders: List<Pair<String, List<AppEntry>>>) {
        val c = card(
            "ÉTAPE 2", "Retirer ce qui est en double",
            "Ces applis sont maintenant dans un dossier d'Orbite sur votre écran. Retirez leur icône seule : " +
                "appui long sur l'icône → « Supprimer » ou « Retirer ». L'appli reste installée. " +
                "Faites pareil avec vos anciens dossiers (appui long → supprimer le dossier). Cochez au fur et à mesure.",
        )
        val onScreen = folders.filter { it.first in pinned }
        if (onScreen.isEmpty()) {
            c.addView(label("Faites d'abord l'étape 1.", 14f, Palette.MUTED))
            return
        }
        var done = 0; var total = 0
        onScreen.forEach { (name, list) ->
            c.addView(label(name, 13f, Palette.TEXT, bold = true), matchWrap().margins(t = dp(8)))
            list.forEach { e ->
                total++
                val key = "fait:${e.packageName}"
                val isDone = checks.getBoolean(key, false)
                if (isDone) done++
                c.addView(CheckBox(this).apply {
                    text = e.label
                    setTextColor(if (isDone) Palette.MUTED else Palette.TEXT)
                    buttonTintList = ColorStateList.valueOf(Palette.PRIMARY)
                    isChecked = isDone
                    setOnCheckedChangeListener { v, on ->
                        checks.edit().putBoolean(key, on).apply()
                        (v as TextView).setTextColor(if (on) Palette.MUTED else Palette.TEXT)
                    }
                }, matchWrap())
            }
        }
        c.addView(label("$done / $total icônes retirées", 13f, Palette.MUTED), matchWrap().margins(t = dp(8)))
    }

    private fun stepNewApps() {
        val c = card(
            "ÉTAPE 3", "Ranger les nouvelles applis",
            "Une appli installée entre toute seule dans le bon dossier d'Orbite (vérification environ toutes les heures). " +
                "Pour éviter que votre écran ajoute aussi son icône : appui long sur un espace vide de l'écran → " +
                "Paramètres de l'écran d'accueil → désactivez « Ajouter les nouvelles applis à l'écran d'accueil ».",
        )
        val on = NewAppsJob.notifyEnabled(this)
        c.addView(chip(if (on) "🔔 Me prévenir quand une appli est rangée ✔" else "🔕 Me prévenir quand une appli est rangée", on) {
            val next = !NewAppsJob.notifyEnabled(this)
            NewAppsJob.setNotifyEnabled(this, next)
            if (next && Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
            render()
        }, wrapWrap())
        if (on && Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            c.addView(pill("Autoriser les notifications") {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
            }, wrapWrap().margins(t = dp(8)))
        }
    }

    // ------------------------------------------------------------------ ajout un par un

    private fun pinNext() {
        val name = queue.removeFirstOrNull() ?: run { reload(); return }
        // Android rappelle l'appli quand l'ajout est accepté : on enchaîne alors sur le dossier suivant.
        val callback = createPendingResult(REQ_PIN, Intent(), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT)
        val ok = HomeShortcuts.request(this, name, apps, callback.intentSender)
        if (!ok) {
            Toast.makeText(this, "Ajout de « $name » refusé par l'écran d'accueil.", Toast.LENGTH_LONG).show()
            queue.clear()
        } else if (queue.isNotEmpty()) {
            Toast.makeText(this, "« $name » : confirmez, puis le dossier suivant s'affichera.", Toast.LENGTH_SHORT).show()
        }
    }

    @Deprecated("API Activity classique")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PIN) {
            if (queue.isNotEmpty()) body.postDelayed({ pinNext() }, 400) else reload()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        render()
    }

    companion object {
        private const val REQ_PIN = 51
        private const val REQ_NOTIF = 52
    }
}
