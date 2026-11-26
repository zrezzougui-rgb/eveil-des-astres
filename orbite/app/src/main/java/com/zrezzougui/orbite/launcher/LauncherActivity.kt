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
    private lateinit var batteryPod: TextView
    private lateinit var agendaPod: TextView
    private lateinit var reactor: com.zrezzougui.orbite.hud.ArcReactorView
    private lateinit var wakeHint: View
    private val tick = android.os.Handler(android.os.Looper.getMainLooper())
    private val clockTask = object : Runnable {
        override fun run() { updateHud(); tick.postDelayed(this, 15_000) }
    }
    private lateinit var search: EditText
    private var apps: List<AppEntry> = emptyList()
    private var weatherAt = 0L
    private var loading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        edgeToEdge()
        repo = AppRepository(this)
        actions = AppActions(this, repo, { apps }, onChanged = { reload() })
        NewAppsJob.schedule(this)

        val root = vertical().apply { setBackgroundColor(Palette.BG) }
        root.padForSystemBars()

        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        val content = vertical().apply { setPadding(dp(16), dp(20), dp(16), dp(16)) }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // En-tête HUD : titre, menu, réacteur avec l'heure, capteurs
        val top = horizontal()
        top.addView(label("J.A.R.V.I.S", 14f, Palette.PRIMARY).apply {
            typeface = com.zrezzougui.orbite.Fonts.hudBold(context); letterSpacing = 0.25f
        }, weighted())
        top.addView(label("⋮", 26f, Palette.MUTED).apply {
            setPadding(dp(12), dp(4), dp(4), dp(4)); setOnClickListener { menu() }
        })
        content.addView(top, matchWrap())

        reactor = com.zrezzougui.orbite.hud.ArcReactorView(this).apply {
            setOnClickListener { AssistantActivity.open(this@LauncherActivity, voice = true) }
            setOnLongClickListener { startActivity(Intent(this@LauncherActivity, com.zrezzougui.orbite.jarvis.JarvisSettingsActivity::class.java)); true }
            contentDescription = "Parler à Jarvis"
        }
        content.addView(reactor, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(230)))

        val pods = horizontal().apply { setPadding(0, dp(4), 0, dp(12)) }
        weatherLine = pod("MÉTÉO", "Touchez").apply { setOnClickListener { AssistantActivity.open(this@LauncherActivity, "Météo") } }
        batteryPod = pod("ÉNERGIE", "—")
        agendaPod = pod("AGENDA", "Touchez").apply { setOnClickListener { AssistantActivity.open(this@LauncherActivity, "Qu'est-ce que j'ai aujourd'hui") } }
        listOf(weatherLine, batteryPod, agendaPod).forEachIndexed { i, v ->
            pods.addView(v.parent as View, weighted().margins(l = if (i == 0) 0 else dp(8)))
        }
        content.addView(pods, matchWrap())

        wakeHint = pill("🎙️ Activer « Jarvis » mains libres") {
            startActivity(Intent(this, com.zrezzougui.orbite.jarvis.JarvisSettingsActivity::class.java))
        }
        content.addView(wakeHint, wrapWrap().margins(b = dp(12)))

        // Raccourcis
        val quick = horizontal()
        quick.addView(pill("✨ Jarvis", filled = true) { AssistantActivity.open(this) }, wrapWrap().margins(r = dp(8)))
        quick.addView(pill("📷 Caméra pro") { startActivity(Intent(this, CameraActivity::class.java)) }, wrapWrap().margins(r = dp(8)))
        quick.addView(pill("🧹 Ranger mon écran") { startActivity(Intent(this, TidyActivity::class.java)) }, wrapWrap().margins(r = dp(8)))
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
        tick.post(clockTask)
        wakeHint.visibility = if (com.zrezzougui.orbite.jarvis.Persona.wakeEnabled(this)) View.GONE else View.VISIBLE
        // L'écoute « Jarvis » ne peut démarrer que quand Orbite est à l'écran.
        com.zrezzougui.orbite.jarvis.JarvisService.start(this)
    }

    override fun onPause() {
        tick.removeCallbacks(clockTask)
        super.onPause()
    }

    /** Petit capteur du HUD : étiquette cyan + valeur. Renvoie la valeur ; la carte est son parent. */
    private fun pod(title: String, value: String): TextView {
        val box = vertical().apply {
            background = rounded(Palette.SURFACE, dpf(com.zrezzougui.orbite.Radius.MD), dp(1), Palette.LINE_STRONG)
            setPadding(dp(10), dp(8), dp(10), dp(10))
        }
        box.addView(label(title, 10f, Palette.PRIMARY).apply { typeface = com.zrezzougui.orbite.Fonts.hud(context); letterSpacing = 0.15f })
        val v = label(value, 13f, Palette.TEXT).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END; setPadding(0, dp(4), 0, 0) }
        box.addView(v, matchWrap())
        box.setOnClickListener { v.performClick() }
        return v
    }

    private fun updateHud() {
        val now = java.util.Calendar.getInstance()
        reactor.centerText = "%02d:%02d".format(now.get(java.util.Calendar.HOUR_OF_DAY), now.get(java.util.Calendar.MINUTE))
        reactor.subText = java.text.SimpleDateFormat("EEE d MMM", java.util.Locale.FRENCH).format(now.time)
        val bm = getSystemService(android.os.BatteryManager::class.java)
        val pct = bm?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        val charging = bm?.isCharging == true
        batteryPod.text = if (pct in 0..100) "$pct %" + (if (charging) " ⚡" else "") else "—"
        batteryPod.setTextColor(if (pct in 0..19 && !charging) Palette.ACCENT else Palette.TEXT)
        Thread {
            val next = nextEvent()
            runOnUiThread { agendaPod.text = next }
        }.start()
    }

    /** Prochain rendez-vous dans les 24 h (si l'agenda est autorisé). */
    private fun nextEvent(): String {
        if (checkSelfPermission(android.Manifest.permission.READ_CALENDAR) != android.content.pm.PackageManager.PERMISSION_GRANTED) return "Touchez"
        val now = System.currentTimeMillis()
        val uri = android.provider.CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            android.content.ContentUris.appendId(it, now)
            android.content.ContentUris.appendId(it, now + 24 * 3600_000L)
        }.build()
        return runCatching {
            contentResolver.query(uri, arrayOf(android.provider.CalendarContract.Instances.TITLE, android.provider.CalendarContract.Instances.BEGIN),
                null, null, android.provider.CalendarContract.Instances.BEGIN + " ASC")?.use { c ->
                if (c.moveToFirst()) java.text.SimpleDateFormat("HH'h'mm", java.util.Locale.FRENCH).format(java.util.Date(c.getLong(1))) + " " + (c.getString(0) ?: "")
                else "Rien de prévu"
            }
        }.getOrNull() ?: "Rien de prévu"
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
            HomeShortcuts.sync(this, list)
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
                "${Weather.emoji(f.code)} ${f.temp.toInt()}° ${place.name.substringBefore(',')}" +
                    (d?.let { " · ${it.tMin.toInt()}/${it.tMax.toInt()}°" } ?: "")
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
            "Réglages Jarvis (voix, « Jarvis » mains libres)",
            "Gérer mes applis",
            "Ranger l'écran d'accueil du téléphone",
            "Définir Orbite comme écran d'accueil",
            "Réinitialiser le rangement automatique",
            "Autoriser la position et l'agenda (capteurs du HUD)",
            "Réglages Android",
        )
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Orbite")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> startActivity(Intent(this, com.zrezzougui.orbite.jarvis.JarvisSettingsActivity::class.java))
                    1 -> startActivity(Intent(this, AppsActivity::class.java))
                    2 -> startActivity(Intent(this, TidyActivity::class.java))
                    3 -> safeStart(Intent(Settings.ACTION_HOME_SETTINGS))
                    4 -> AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                        .setMessage("Remettre toutes les applis dans leurs dossiers automatiques et annuler les noms personnalisés ?")
                        .setPositiveButton("Réinitialiser") { _, _ -> repo.resetAll(); reload() }
                        .setNegativeButton("Annuler", null).show()
                    5 -> requestPermissions(arrayOf(android.Manifest.permission.ACCESS_COARSE_LOCATION, android.Manifest.permission.READ_CALENDAR), 7)
                    6 -> safeStart(Intent(Settings.ACTION_SETTINGS))
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
