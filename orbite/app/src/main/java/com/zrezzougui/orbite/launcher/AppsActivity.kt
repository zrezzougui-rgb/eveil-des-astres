package com.zrezzougui.orbite.launcher

import android.app.Activity
import android.os.Bundle
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
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
import com.zrezzougui.orbite.normalize
import com.zrezzougui.orbite.padForSystemBars
import com.zrezzougui.orbite.pill
import com.zrezzougui.orbite.rounded
import com.zrezzougui.orbite.vertical
import com.zrezzougui.orbite.weighted
import com.zrezzougui.orbite.wrapWrap
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** « Mes applis » : toutes les applis, triées et filtrées, avec taille, installation et utilisation. */
class AppsActivity : Activity() {

    enum class Sort(val label: String) { NAME("Nom"), SIZE("Les plus lourdes"), RECENT("Installées récemment"), UNUSED("Les moins utilisées") }

    private lateinit var repo: AppRepository
    private lateinit var actions: AppActions
    private var apps: List<AppEntry> = emptyList()
    private var info: Map<String, AppInfo> = emptyMap()
    private var sort = Sort.NAME
    private var folder: String? = null
    private var query = ""

    private lateinit var summary: TextView
    private lateinit var banner: LinearLayout
    private lateinit var sortRow: LinearLayout
    private lateinit var folderRow: LinearLayout
    private lateinit var list: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        edgeToEdge()
        repo = AppRepository(this)
        actions = AppActions(this, repo, { apps }, onChanged = { reload() })
        intent.getStringExtra(EXTRA_SORT)?.let { s -> Sort.entries.firstOrNull { it.name == s }?.let { sort = it } }
        folder = intent.getStringExtra(EXTRA_FOLDER)

        val root = vertical().apply { background = com.zrezzougui.orbite.hud.HudBackgroundDrawable(resources.displayMetrics.density, 0.05f) }
        root.padForSystemBars()

        val header = horizontal().apply { setPadding(dp(16), dp(12), dp(16), dp(4)) }
        header.addView(label("‹", 28f, Palette.MUTED).apply { setPadding(0, 0, dp(16), 0); setOnClickListener { finish() } })
        header.addView(vertical().apply {
            addView(heading("Mes applis", 22f))
            summary = label("Chargement…", 13f, Palette.MUTED)
            addView(summary)
        }, weighted())
        root.addView(header, matchWrap())

        val searchBox = EditText(this).apply {
            hint = "Filtrer par nom…"
            setHintTextColor(Palette.MUTED); setTextColor(Palette.TEXT); textSize = 16f
            background = com.zrezzougui.orbite.hud.HudPanelDrawable(resources.displayMetrics.density)
            setPadding(dp(18), dp(10), dp(18), dp(10)); isSingleLine = true
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { query = s?.toString().orEmpty(); render() }
            })
        }
        root.addView(searchBox, matchWrap().margins(l = dp(16), r = dp(16), t = dp(8), b = dp(4)))

        banner = vertical()
        root.addView(banner, matchWrap())

        sortRow = horizontal().apply { setPadding(dp(16), dp(6), dp(16), dp(2)) }
        root.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(sortRow) }, matchWrap())
        folderRow = horizontal().apply { setPadding(dp(16), dp(4), dp(16), dp(6)) }
        root.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(folderRow) }, matchWrap())

        list = vertical().apply { setPadding(dp(12), dp(4), dp(12), dp(16)) }
        root.addView(ScrollView(this).apply { addView(list) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun reload() {
        Thread {
            val l = runCatching { repo.load() }.getOrDefault(emptyList())
            val i = runCatching { AppStats.collect(this, l) }.getOrDefault(emptyMap())
            runOnUiThread { apps = l; info = i; render() }
        }.start()
    }

    private fun render() {
        val usageOk = AppStats.hasUsageAccess(this)
        banner.removeAllViews()
        if (!usageOk) {
            val b = vertical().apply {
                background = com.zrezzougui.orbite.hud.HudPanelDrawable(resources.displayMetrics.density)
                setPadding(dp(16), dp(12), dp(16), dp(12))
                addView(label("Pour voir quelles applis vous utilisez, autorisez Orbite dans « Accès aux données d'utilisation ». Rien ne quitte le téléphone.", 14f, Palette.TEXT))
                addView(pill("Autoriser") { actions.safeStart(AppStats.usageAccessIntent()) }, wrapWrap().margins(t = dp(8)))
            }
            banner.addView(b, matchWrap().margins(l = dp(16), r = dp(16), t = dp(4), b = dp(4)))
        }

        sortRow.removeAllViews()
        Sort.entries.forEach { s ->
            sortRow.addView(chip(s.label, s == sort) { sort = s; render() }, wrapWrap().margins(r = dp(8)))
        }
        folderRow.removeAllViews()
        folderRow.addView(chip("Toutes (${apps.size})", folder == null) { folder = null; render() }, wrapWrap().margins(r = dp(8)))
        repo.orderedFolders(apps).forEach { (name, l) ->
            folderRow.addView(chip("$name (${l.size})", folder == name) { folder = name; render() }, wrapWrap().margins(r = dp(8)))
        }

        val q = normalize(query.trim())
        var shown = apps.filter { (folder == null || it.folder == folder) && (q.isEmpty() || normalize(it.label).contains(q)) }
        shown = when (sort) {
            Sort.NAME -> shown
            Sort.SIZE -> shown.sortedByDescending { info[it.packageName]?.sizeBytes ?: 0 }
            Sort.RECENT -> shown.sortedByDescending { info[it.packageName]?.installedAt ?: 0 }
            Sort.UNUSED -> shown.sortedBy { info[it.packageName]?.lastUsed ?: 0 }
        }
        val total = shown.sumOf { info[it.packageName]?.sizeBytes ?: 0 }
        summary.text = "${shown.size} applis · ${AppStats.size(total)} installés" +
            if (sort == Sort.UNUSED && !usageOk) " · usage non autorisé" else ""

        list.removeAllViews()
        if (shown.isEmpty()) list.addView(label("Aucune appli ne correspond.", 14f, Palette.MUTED), matchWrap().margins(t = dp(16)))
        shown.forEach { list.addView(row(it, usageOk), matchWrap().margins(b = dp(6))) }
    }

    private fun row(e: AppEntry, usageOk: Boolean): LinearLayout {
        val i = info[e.packageName]
        val date = SimpleDateFormat("d MMM yyyy", Locale.FRENCH)
        val details = buildList {
            add(e.folder)
            if (i != null) {
                add(AppStats.size(i.sizeBytes))
                if (i.installedAt > 0) add("installée le " + date.format(Date(i.installedAt)))
                if (usageOk && i.lastUsed != null) add("utilisée " + AppStats.ago(i.lastUsed))
            }
        }.joinToString(" · ")
        return horizontal().apply {
            background = com.zrezzougui.orbite.hud.HudPanelDrawable(resources.displayMetrics.density)
            setPadding(dp(12), dp(10), dp(8), dp(10))
            addView(ImageView(context).apply { setImageDrawable(e.iconCopy()) }, LinearLayout.LayoutParams(dp(44), dp(44)))
            addView(vertical().apply {
                setPadding(dp(12), 0, dp(8), 0)
                addView(label(e.label, 16f, bold = true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
                addView(label(details, 12f, Palette.MUTED).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
            }, weighted())
            addView(label("⋮", 22f, Palette.MUTED).apply {
                gravity = Gravity.CENTER
                setOnClickListener { actions.appMenu(e) }
                contentDescription = "Gérer ${e.label}"
            }, LinearLayout.LayoutParams(dp(40), dp(48)))
            setOnClickListener { actions.launch(e) }
            setOnLongClickListener { actions.appMenu(e); true }
        }
    }

    companion object {
        const val EXTRA_SORT = "tri"
        const val EXTRA_FOLDER = "dossier"
    }
}
