package com.zrezzougui.orbite.launcher

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
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
import android.widget.TextView
import android.widget.Toast
import com.zrezzougui.orbite.Fonts
import com.zrezzougui.orbite.Palette
import com.zrezzougui.orbite.assistant.AssistantActivity
import com.zrezzougui.orbite.assistant.Locator
import com.zrezzougui.orbite.assistant.Place
import com.zrezzougui.orbite.assistant.Weather
import com.zrezzougui.orbite.camera.CameraActivity
import com.zrezzougui.orbite.dp
import com.zrezzougui.orbite.edgeToEdge
import com.zrezzougui.orbite.horizontal
import com.zrezzougui.orbite.hud.ArcReactorView
import com.zrezzougui.orbite.hud.HudIcon
import com.zrezzougui.orbite.hud.hudAction
import com.zrezzougui.orbite.hud.hudBackground
import com.zrezzougui.orbite.hud.hudLabel
import com.zrezzougui.orbite.hud.hudPanel
import com.zrezzougui.orbite.hud.hudSection
import com.zrezzougui.orbite.hud.hudSensor
import com.zrezzougui.orbite.hud.iconButton
import com.zrezzougui.orbite.hud.micButton
import com.zrezzougui.orbite.jarvis.JarvisService
import com.zrezzougui.orbite.jarvis.JarvisSettingsActivity
import com.zrezzougui.orbite.jarvis.Persona
import com.zrezzougui.orbite.label
import com.zrezzougui.orbite.margins
import com.zrezzougui.orbite.matchWrap
import com.zrezzougui.orbite.normalize
import com.zrezzougui.orbite.padForSystemBars
import com.zrezzougui.orbite.pill
import com.zrezzougui.orbite.vertical
import com.zrezzougui.orbite.weighted
import com.zrezzougui.orbite.wrapWrap
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Écran d'accueil Jarvis : réacteur avec l'heure, capteurs, actions rapides, modules (dossiers) et barre de commande. */
class LauncherActivity : Activity() {

    private lateinit var repo: AppRepository
    private lateinit var actions: AppActions
    private lateinit var reactor: ArcReactorView
    private lateinit var status: TextView
    private lateinit var statusDot: View
    private lateinit var hint: TextView
    private lateinit var weatherV: TextView
    private lateinit var weatherS: TextView
    private lateinit var energyV: TextView
    private lateinit var energyBar: View
    private lateinit var agendaV: TextView
    private lateinit var agendaS: TextView
    private lateinit var folderGrid: GridLayout
    private lateinit var results: LinearLayout
    private lateinit var modulesHeader: View
    private lateinit var search: EditText
    private var apps: List<AppEntry> = emptyList()
    private var weatherAt = 0L
    private var loading = false
    private val tick = Handler(Looper.getMainLooper())
    private val clockTask = object : Runnable {
        override fun run() { updateHud(); tick.postDelayed(this, 15_000) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        edgeToEdge()
        com.zrezzougui.orbite.jarvis.Diag.install(this)
        repo = AppRepository(this)
        actions = AppActions(this, repo, { apps }, onChanged = { reload() })
        NewAppsJob.schedule(this)

        val root = FrameLayout(this).apply { background = hudBackground() }
        val column = vertical()
        column.padForSystemBars()
        root.addView(column)

        // Barre du haut
        val top = horizontal().apply { setPadding(dp(16), dp(6), dp(6), 0) }
        top.addView(hudLabel("J.A.R.V.I.S", Palette.PRIMARY, 12f).apply { typeface = Fonts.hudBold(context); letterSpacing = 0.3f }, weighted())
        statusDot = View(this).apply {
            background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(Palette.SUCCESS) }
        }
        top.addView(statusDot, LinearLayout.LayoutParams(dp(6), dp(6)).margins(r = dp(8)))
        status = hudLabel("", Palette.MUTED, 9f)
        top.addView(status)
        top.addView(iconButton(HudIcon.Kind.GEAR, "Menu") { menu() })
        column.addView(top, matchWrap())

        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        val content = vertical().apply { setPadding(dp(16), 0, dp(16), dp(16)) }
        scroll.addView(content)
        column.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // Réacteur
        reactor = ArcReactorView(this).apply {
            setOnClickListener { AssistantActivity.open(this@LauncherActivity, voice = true) }
            setOnLongClickListener { startActivity(Intent(this@LauncherActivity, JarvisSettingsActivity::class.java)); true }
            contentDescription = "Parler à Jarvis"
        }
        content.addView(reactor, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(250)))
        hint = label("", 14f, Palette.MUTED).apply { gravity = Gravity.CENTER }
        content.addView(hint, matchWrap().margins(b = dp(16)))

        // Capteurs
        val sensors = horizontal()
        val w = hudSensor("Météo"); weatherV = w.second; weatherS = w.third
        w.first.setOnClickListener { AssistantActivity.open(this, "Météo") }
        val e = hudSensor("Énergie"); energyV = e.second
        energyBar = View(this).apply { setBackgroundColor(Palette.PRIMARY) }
        val track = FrameLayout(this).apply { setBackgroundColor(Palette.PRIMARY_SOFT); addView(energyBar, FrameLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT)) }
        e.first.removeView(e.third)
        e.first.addView(track, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)).margins(t = dp(8), b = dp(3)))
        val a = hudSensor("Agenda"); agendaV = a.second; agendaS = a.third
        a.first.setOnClickListener { AssistantActivity.open(this, "Qu'est-ce que j'ai aujourd'hui") }
        listOf(w.first, e.first, a.first).forEachIndexed { i, v -> sensors.addView(v, weighted().margins(l = if (i == 0) 0 else dp(8))) }
        content.addView(sensors, matchWrap())

        // Actions rapides
        val quick = horizontal().apply { setPadding(0, dp(18), 0, 0) }
        quick.addView(hudAction(HudIcon.Kind.MIC, "Parler", lit = true) { AssistantActivity.open(this, voice = true) }, weighted())
        quick.addView(hudAction(HudIcon.Kind.GRID, "Applis") { startActivity(Intent(this, AppsActivity::class.java)) }, weighted())
        quick.addView(hudAction(HudIcon.Kind.LAYERS, "Ranger") { startActivity(Intent(this, TidyActivity::class.java)) }, weighted())
        quick.addView(hudAction(HudIcon.Kind.CAMERA, "Caméra") { startActivity(Intent(this, CameraActivity::class.java)) }, weighted())
        content.addView(quick, matchWrap())

        // Résultats de recherche puis modules
        results = vertical().apply { visibility = View.GONE }
        content.addView(results, matchWrap().margins(t = dp(18)))
        modulesHeader = hudSection("Modules")
        content.addView(modulesHeader, matchWrap().margins(t = dp(22), b = dp(10)))
        folderGrid = GridLayout(this).apply { columnCount = 3 }
        content.addView(folderGrid, matchWrap())

        // Barre de commande
        val bar = horizontal().apply { setPadding(dp(16), dp(8), dp(16), dp(12)) }
        search = EditText(this).apply {
            hint = "Commande ou appli…"
            setHintTextColor(Palette.MUTED); setTextColor(Palette.TEXT); textSize = 15f
            typeface = com.zrezzougui.orbite.Fonts.body(context)
            background = hudPanel()
            setPadding(dp(16), dp(12), dp(16), dp(12))
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
        bar.addView(micButton { AssistantActivity.open(this, voice = true) }, LinearLayout.LayoutParams(dp(48), dp(48)).margins(l = dp(10)))
        column.addView(bar, matchWrap())

        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        reload()
        refreshWeather()
        tick.post(clockTask)
        // L'écoute « Hey Jarvis » ne peut démarrer que quand Orbite est à l'écran.
        JarvisService.start(this)
        val wake = Persona.wakeEnabled(this)
        status.text = if (wake) "À L'ÉCOUTE" else "EN VEILLE"
        statusDot.background.setTint(if (wake) Palette.SUCCESS else Palette.MUTED)
        hint.text = Persona.greeting(this) + ". " +
            if (wake) "Dites « Hey Jarvis » ou touchez le réacteur." else "Touchez le réacteur pour parler."
    }

    override fun onPause() {
        tick.removeCallbacks(clockTask)
        super.onPause()
    }

    @Deprecated("Écran d'accueil : le bouton retour ne quitte pas")
    override fun onBackPressed() {
        if (search.text.isNotEmpty()) search.setText("")
    }

    // ------------------------------------------------------------------ capteurs

    private fun updateHud() {
        val now = Calendar.getInstance()
        reactor.centerText = "%02d:%02d".format(now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE))
        reactor.subText = SimpleDateFormat("EEE d MMM", Locale.FRENCH).format(now.time).replace(".", "")
        val bm = getSystemService(BatteryManager::class.java)
        val pct = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        val charging = bm?.isCharging == true
        energyV.text = if (pct in 0..100) "$pct%" else "—"
        val low = pct in 0..19 && !charging
        energyV.setTextColor(if (low) Palette.ACCENT else Palette.TEXT)
        energyBar.setBackgroundColor(if (low) Palette.ACCENT else if (charging) Palette.SUCCESS else Palette.PRIMARY)
        (energyBar.parent as View).post {
            val full = (energyBar.parent as View).width
            energyBar.layoutParams = energyBar.layoutParams.apply { width = full * pct.coerceIn(0, 100) / 100 }
        }
        Thread {
            val next = nextEvent()
            runOnUiThread { agendaV.text = next.first; agendaS.text = next.second }
        }.start()
    }

    /** Prochain rendez-vous dans les 24 h : (heure, titre). */
    private fun nextEvent(): Pair<String, String> {
        if (checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) return "—" to "Touchez"
        val now = System.currentTimeMillis()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, now); ContentUris.appendId(it, now + 24 * 3600_000L)
        }.build()
        return runCatching {
            contentResolver.query(uri, arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN),
                null, null, CalendarContract.Instances.BEGIN + " ASC")?.use { c ->
                if (c.moveToFirst()) SimpleDateFormat("HH:mm", Locale.FRENCH).format(Date(c.getLong(1))) to (c.getString(0) ?: "")
                else null
            }
        }.getOrNull() ?: ("—" to "Rien de prévu")
    }

    private fun refreshWeather() {
        if (System.currentTimeMillis() - weatherAt < 30 * 60_000L) return
        val prefs = getSharedPreferences("assistant", MODE_PRIVATE)
        if (!Locator.hasPermission(this) && prefs.getString("ville", null) == null) {
            weatherV.text = "—"; weatherS.text = "Touchez"
            return
        }
        weatherAt = System.currentTimeMillis()
        Thread {
            val res = runCatching {
                val place = Locator.current(this) ?: prefs.getString("ville", null)?.let { name ->
                    Place(name, prefs.getFloat("lat", 0f).toDouble(), prefs.getFloat("lon", 0f).toDouble())
                } ?: return@runCatching null
                val f = Weather.forecast(place)
                "${f.temp.toInt()}°" to "${place.name.substringBefore(',')} · ${Weather.describe(f.code)}"
            }.getOrNull()
            if (res != null) runOnUiThread { weatherV.text = res.first; weatherS.text = res.second }
            else weatherAt = 0L
        }.start()
    }

    // ------------------------------------------------------------------ modules (dossiers)

    private fun reload() {
        if (loading) return
        loading = true
        Thread {
            val list = runCatching { repo.load() }.getOrDefault(emptyList())
            HomeShortcuts.sync(this, list)
            runOnUiThread {
                loading = false
                apps = list
                renderFolders()
                filter(search.text.toString())
            }
        }.start()
    }

    private fun renderFolders() {
        folderGrid.removeAllViews()
        val folders = repo.orderedFolders(apps)
        if (folders.isEmpty()) {
            folderGrid.addView(label("Analyse des applis…", 14f, Palette.MUTED))
            return
        }
        for ((name, list) in folders) {
            val tile = vertical().apply {
                background = hudPanel()
                setPadding(dp(10), dp(10), dp(10), dp(10))
                setOnClickListener { actions.openFolder(name) }
                setOnLongClickListener { folderMenu(name); true }
                contentDescription = "Dossier $name, ${list.size} applis"
            }
            val icons = GridLayout(this).apply { columnCount = 2 }
            list.take(4).forEach { app ->
                icons.addView(ImageView(this).apply { setImageDrawable(app.iconCopy()) },
                    GridLayout.LayoutParams().apply { width = dp(24); height = dp(24); setMargins(0, 0, dp(4), dp(4)) })
            }
            tile.addView(icons, wrapWrap())
            tile.addView(label(name, 12f, Palette.TEXT, bold = true).apply {
                maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setPadding(0, dp(6), 0, 0)
            }, matchWrap())
            tile.addView(hudLabel("${list.size} applis", Palette.MUTED, 9f).apply { setPadding(0, dp(2), 0, 0) }, matchWrap())
            folderGrid.addView(tile, GridLayout.LayoutParams(
                GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f),
            ).apply { width = 0; setMargins(dp(4), dp(4), dp(4), dp(4)) })
        }
    }

    private fun open(a: AppEntry) {
        search.setText("")
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(search.windowToken, 0)
        actions.launch(a)
    }

    // ------------------------------------------------------------------ recherche

    private fun matches(q: String): List<AppEntry> {
        val n = normalize(q.trim())
        if (n.isEmpty()) return emptyList()
        return apps.filter { normalize(it.label).contains(n) }
            .sortedBy { if (normalize(it.label).startsWith(n)) 0 else 1 }
    }

    private fun filter(q: String) {
        results.removeAllViews()
        if (q.isBlank()) {
            results.visibility = View.GONE
            folderGrid.visibility = View.VISIBLE
            modulesHeader.visibility = View.VISIBLE
            return
        }
        results.visibility = View.VISIBLE
        folderGrid.visibility = View.GONE
        modulesHeader.visibility = View.GONE
        val found = matches(q).take(8)
        results.addView(hudSection("Résultats"), matchWrap().margins(b = dp(8)))
        if (found.isNotEmpty()) {
            val grid = GridLayout(this).apply { columnCount = 4 }
            found.forEach { grid.addView(actions.appTile(it), actions.cell()) }
            results.addView(grid, matchWrap())
        }
        results.addView(pill("Demander à Jarvis : « ${q.trim()} »") {
            AssistantActivity.open(this, q.trim()); search.setText("")
        }, wrapWrap().margins(t = dp(8)))
    }

    // ------------------------------------------------------------------ menus

    private fun menu() {
        val items = arrayOf(
            "Réglages Jarvis (voix, « Hey Jarvis »)",
            "Gérer mes applis",
            "Ranger l'écran d'accueil du téléphone",
            "Choisir l'écran d'accueil",
            "Réinitialiser le rangement automatique",
            "Autoriser la position et l'agenda (capteurs)",
            "Réglages Android",
            "Diagnostic (si Jarvis ne répond pas)",
        )
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("J.A.R.V.I.S")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> startActivity(Intent(this, JarvisSettingsActivity::class.java))
                    1 -> startActivity(Intent(this, AppsActivity::class.java))
                    2 -> startActivity(Intent(this, TidyActivity::class.java))
                    3 -> actions.safeStart(Intent(Settings.ACTION_HOME_SETTINGS))
                    4 -> AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                        .setMessage("Remettre toutes les applis dans leurs dossiers automatiques et annuler les noms personnalisés ?")
                        .setPositiveButton("Réinitialiser") { _, _ -> repo.resetAll(); reload() }
                        .setNegativeButton("Annuler", null).show()
                    5 -> requestPermissions(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.READ_CALENDAR), 7)
                    6 -> actions.safeStart(Intent(Settings.ACTION_SETTINGS))
                    7 -> startActivity(Intent(this, com.zrezzougui.orbite.jarvis.DiagActivity::class.java))
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
                    0 -> actions.openFolder(name)
                    1 -> actions.renameFolder(name)
                    2 -> actions.pinFolder(name)
                }
            }
            .show()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        weatherAt = 0L
        refreshWeather()
        updateHud()
        if (grantResults.isEmpty() || grantResults.all { it != PackageManager.PERMISSION_GRANTED }) {
            Toast.makeText(this, "Autorisation refusée : les capteurs resteront vides.", Toast.LENGTH_LONG).show()
        }
    }
}
