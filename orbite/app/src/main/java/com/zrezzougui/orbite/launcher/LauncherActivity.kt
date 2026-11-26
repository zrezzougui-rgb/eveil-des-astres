package com.zrezzougui.orbite.launcher

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextClock
import android.widget.TextView
import android.widget.Toast
import com.zrezzougui.orbite.Palette
import com.zrezzougui.orbite.assistant.AssistantActivity
import com.zrezzougui.orbite.assistant.Locator
import com.zrezzougui.orbite.assistant.Weather
import com.zrezzougui.orbite.camera.CameraActivity
import com.zrezzougui.orbite.dp
import com.zrezzougui.orbite.dpf
import com.zrezzougui.orbite.edgeToEdge
import com.zrezzougui.orbite.horizontal
import com.zrezzougui.orbite.heading
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

/** Écran d'accueil : horloge, météo, barre de recherche/assistant et applis rangées en dossiers. */
class LauncherActivity : Activity() {

    private lateinit var repo: AppRepository
    private lateinit var actions: AppActions
    private lateinit var folderGrid: GridLayout
    private lateinit var results: LinearLayout
    private lateinit var weatherLine: TextView
    private lateinit var search: EditText
    private var apps: List<AppEntry> = emptyList()
    private var weatherAt = 0L
    private var loading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        edgeToEdge()
        repo = AppRepository(this)
        actions = AppActions(this, repo, { apps }, onChanged = { reload() })

        val root = vertical().apply { setBackgroundColor(Palette.BG) }
        root.padForSystemBars()

        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        val content = vertical().apply { setPadding(dp(16), dp(20), dp(16), dp(16)) }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // En-tête : horloge + menu
        val top = horizontal()
        val clocks = vertical()
        clocks.addView(TextClock(this).apply {
            format24Hour = "HH:mm"; format12Hour = "HH:mm"
            textSize = 56f; setTextColor(Palette.TEXT)
            typeface = com.zrezzougui.orbite.Fonts.displayLight(context)
            setOnClickListener { safeStart(Intent(android.provider.AlarmClock.ACTION_SHOW_ALARMS)) }
        })
        clocks.addView(TextClock(this).apply {
            format24Hour = "EEEE d MMMM"; format12Hour = "EEEE d MMMM"
            textSize = 16f; setTextColor(Palette.MUTED)
        })
        top.addView(clocks, weighted())
        top.addView(label("⋮", 26f, Palette.MUTED).apply {
            setPadding(dp(12), dp(4), dp(4), dp(4)); setOnClickListener { menu() }
        })
        content.addView(top, matchWrap())

        weatherLine = label("☁️  Touchez pour la météo", 15f, Palette.MUTED).apply {
            setPadding(0, dp(6), 0, dp(14))
            setOnClickListener { AssistantActivity.open(this@LauncherActivity, "Météo") }
        }
        content.addView(weatherLine, matchWrap())

        // Raccourcis
        val quick = horizontal()
        quick.addView(pill("✨ Assistant", filled = true) { AssistantActivity.open(this) }, wrapWrap().margins(r = dp(8)))
        quick.addView(pill("📷 Caméra pro") { startActivity(Intent(this, CameraActivity::class.java)) }, wrapWrap().margins(r = dp(8)))
        quick.addView(pill("🗂️ Mes applis") { startActivity(Intent(this, AppsActivity::class.java)) }, wrapWrap().margins(r = dp(8)))
        quick.addView(pill("📅 Agenda") { AssistantActivity.open(this, "Qu'est-ce que j'ai aujourd'hui") }, wrapWrap())
        content.addView(android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false; addView(quick)
        }, matchWrap().margins(b = dp(16)))

        results = vertical().apply { visibility = View.GONE }
        content.addView(results, matchWrap().margins(b = dp(12)))

        folderGrid = GridLayout(this).apply { columnCount = 2; useDefaultMargins = false }
        content.addView(folderGrid, matchWrap())

        // Barre du bas : recherche d'appli + assistant
        val bar = horizontal().apply { setPadding(dp(12), dp(8), dp(12), dp(12)) }
        search = EditText(this).apply {
            hint = "Rechercher une appli ou demander…"
            setHintTextColor(Palette.MUTED); setTextColor(Palette.TEXT); textSize = 16f
            background = rounded(Palette.SURFACE, dpf(24f))
            setPadding(dp(18), dp(12), dp(18), dp(12))
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEND
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) = filter(s?.toString().orEmpty())
            })
            setOnEditorActionListener { _, _, _ ->
                val q = text.toString().trim()
                if (q.isNotEmpty()) {
                    val match = matches(q)
                    if (match.size == 1) open(match[0]) else AssistantActivity.open(this@LauncherActivity, q)
                    setText("")
                }
                true
            }
        }
        bar.addView(search, weighted())
        bar.addView(label("🎤", 20f).apply {
            gravity = Gravity.CENTER
            background = rounded(Palette.PRIMARY, dpf(24f))
            setOnClickListener { AssistantActivity.open(this@LauncherActivity, voice = true) }
        }, LinearLayout.LayoutParams(dp(48), dp(48)).margins(l = dp(8)))
        root.addView(bar, matchWrap())

        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        reload()
        refreshWeather()
    }

    @Deprecated("Écran d'accueil : le bouton retour ne quitte pas")
    override fun onBackPressed() {
        if (search.text.isNotEmpty()) search.setText("")
    }

    // ------------------------------------------------------------------ données

    private fun reload() {
        if (loading) return
        loading = true
        Thread {
            val list = runCatching { repo.load() }.getOrDefault(emptyList())
            runOnUiThread {
                loading = false
                apps = list
                renderFolders()
                filter(search.text.toString())
            }
        }.start()
    }

    private fun refreshWeather() {
        if (System.currentTimeMillis() - weatherAt < 30 * 60_000L) return
        val prefs = getSharedPreferences("assistant", MODE_PRIVATE)
        if (!Locator.hasPermission(this) && prefs.getString("ville", null) == null) return
        weatherAt = System.currentTimeMillis()
        Thread {
            val text = runCatching {
                val place = Locator.current(this) ?: prefs.getString("ville", null)?.let { name ->
                    com.zrezzougui.orbite.assistant.Place(name, prefs.getFloat("lat", 0f).toDouble(), prefs.getFloat("lon", 0f).toDouble())
                } ?: return@runCatching null
                val f = Weather.forecast(place)
                val d = f.days.firstOrNull()
                "${Weather.emoji(f.code)}  ${f.temp.toInt()}° · ${Weather.describe(f.code)} · ${place.name}" +
                    (d?.let { "  (${it.tMin.toInt()}° / ${it.tMax.toInt()}°)" } ?: "")
            }.getOrNull()
            if (text != null) runOnUiThread { weatherLine.text = text; weatherLine.setTextColor(Palette.TEXT) }
            else weatherAt = 0L
        }.start()
    }

    // ------------------------------------------------------------------ dossiers

    private fun renderFolders() {
        folderGrid.removeAllViews()
        val folders = repo.orderedFolders(apps)
        if (folders.isEmpty()) {
            folderGrid.addView(label("Chargement des applis…", 14f, Palette.MUTED))
            return
        }
        for ((name, list) in folders) {
            val tile = vertical().apply {
                background = rounded(Palette.SURFACE, dpf(com.zrezzougui.orbite.Radius.LG))
                setPadding(dp(14), dp(12), dp(14), dp(12))
                setOnClickListener { openFolder(name) }
                setOnLongClickListener { folderMenu(name); true }
            }
            val icons = GridLayout(this).apply { columnCount = 4 }
            list.take(8).forEach { a ->
                icons.addView(ImageView(this).apply {
                    setImageDrawable(a.iconCopy())
                    setPadding(dp(2), dp(2), dp(2), dp(2))
                }, GridLayout.LayoutParams().apply { width = dp(34); height = dp(34) })
            }
            tile.addView(icons, wrapWrap())
            tile.addView(heading(name, 17f).apply {
                maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setPadding(0, dp(8), 0, 0)
            }, matchWrap())
            tile.addView(label("${list.size} appli${if (list.size > 1) "s" else ""}", 12f, Palette.MUTED), matchWrap())

            val lp = GridLayout.LayoutParams(
                GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f),
            ).apply { width = 0; setMargins(dp(5), dp(5), dp(5), dp(5)) }
            folderGrid.addView(tile, lp)
        }
    }

    private fun open(a: AppEntry) {
        search.setText("")
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(search.windowToken, 0)
        actions.launch(a)
    }

    private fun safeStart(i: Intent) = actions.safeStart(i)
    private fun openFolder(name: String) = actions.openFolder(name)
    private fun renameFolder(name: String) = actions.renameFolder(name)
    private fun appTile(a: AppEntry) = actions.appTile(a)
    private fun cell() = actions.cell()


    // ------------------------------------------------------------------ recherche

    private fun matches(q: String): List<AppEntry> {
        val n = normalize(q.trim())
        if (n.isEmpty()) return emptyList()
        return apps.filter { normalize(it.label).contains(n) }
            .sortedBy { if (normalize(it.label).startsWith(n)) 0 else 1 }
    }

    private fun filter(q: String) {
        results.removeAllViews()
        val found = matches(q).take(8)
        if (q.isBlank()) {
            results.visibility = View.GONE
            folderGrid.visibility = View.VISIBLE
            return
        }
        results.visibility = View.VISIBLE
        folderGrid.visibility = View.GONE
        if (found.isNotEmpty()) {
            val grid = GridLayout(this).apply { columnCount = 4 }
            found.forEach { grid.addView(appTile(it), cell()) }
            results.addView(grid, matchWrap())
        }
        results.addView(pill("✨ Demander à l'assistant : « ${q.trim()} »") {
            AssistantActivity.open(this, q.trim()); search.setText("")
        }, wrapWrap().margins(t = dp(8)))
        if (found.isEmpty()) {
            results.addView(label("Aucune appli ne correspond. Entrée pour demander à l'assistant.", 13f, Palette.MUTED),
                matchWrap().margins(t = dp(10)))
        }
    }

    // ------------------------------------------------------------------ menus

    private fun menu() {
        val items = arrayOf(
            "Gérer mes applis",
            "Mettre des dossiers sur l'écran d'accueil du téléphone",
            "Définir Orbite comme écran d'accueil",
            "Réinitialiser le rangement automatique",
            "Autoriser la position (météo)",
            "Réglages Android",
        )
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Orbite")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> startActivity(Intent(this, AppsActivity::class.java))
                    1 -> pinPicker()
                    2 -> safeStart(Intent(Settings.ACTION_HOME_SETTINGS))
                    3 -> AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                        .setMessage("Remettre toutes les applis dans leurs dossiers automatiques et annuler les noms personnalisés ?")
                        .setPositiveButton("Réinitialiser") { _, _ -> repo.resetAll(); reload() }
                        .setNegativeButton("Annuler", null).show()
                    4 -> requestPermissions(arrayOf(android.Manifest.permission.ACCESS_COARSE_LOCATION), 7)
                    5 -> safeStart(Intent(Settings.ACTION_SETTINGS))
                }
            }
            .show()
    }

    private fun folderMenu(name: String) {
        val items = arrayOf("Ouvrir", "Renommer", "Mettre sur l'écran d'accueil du téléphone")
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(name)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> openFolder(name)
                    1 -> renameFolder(name)
                    2 -> actions.pinFolder(name)
                }
            }
            .show()
    }

    /** Un dossier à la fois : Android demande une confirmation pour chaque raccourci. */
    private fun pinPicker() {
        if (!actions.canPin()) {
            Toast.makeText(this, "Votre écran d'accueil n'accepte pas les raccourcis d'applis.", Toast.LENGTH_LONG).show()
            return
        }
        val names = repo.orderedFolders(apps).map { it.first }.toTypedArray()
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Quel dossier ajouter à l'écran d'accueil du téléphone ?")
            .setItems(names) { _, which -> actions.pinFolder(names[which]) }
            .show()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        weatherAt = 0L
        refreshWeather()
    }
}
