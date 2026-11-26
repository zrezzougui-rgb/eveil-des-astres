package com.zrezzougui.orbite.jarvis

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.zrezzougui.orbite.Fonts
import com.zrezzougui.orbite.Palette
import com.zrezzougui.orbite.dp
import com.zrezzougui.orbite.edgeToEdge
import com.zrezzougui.orbite.horizontal
import com.zrezzougui.orbite.hud.HudBackgroundDrawable
import com.zrezzougui.orbite.hud.HudIcon
import com.zrezzougui.orbite.hud.hudLabel
import com.zrezzougui.orbite.hud.hudPanel
import com.zrezzougui.orbite.hud.iconButton
import com.zrezzougui.orbite.label
import com.zrezzougui.orbite.margins
import com.zrezzougui.orbite.matchWrap
import com.zrezzougui.orbite.padForSystemBars
import com.zrezzougui.orbite.pill
import com.zrezzougui.orbite.vertical
import com.zrezzougui.orbite.weighted
import com.zrezzougui.orbite.wrapWrap

/**
 * Diagnostic : état de Jarvis, test en direct de « Hey Jarvis » (jauge de score et de volume),
 * et journal des plantages / blocages relevés par Android. Tout reste sur le téléphone ;
 * « Copier le rapport » permet de le coller dans une conversation.
 */
class DiagActivity : Activity() {

    private lateinit var body: LinearLayout
    private lateinit var scoreBar: android.view.View
    private lateinit var levelBar: android.view.View
    private lateinit var scoreText: TextView
    private lateinit var testButton: TextView
    private var engine: WakeWordEngine? = null
    private var peak = 0f
    private var detections = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        edgeToEdge()
        Diag.install(this)
        val root = vertical().apply { background = HudBackgroundDrawable(resources.displayMetrics.density, 0.05f) }
        root.padForSystemBars()
        val header = horizontal().apply { setPadding(dp(6), dp(4), dp(16), 0) }
        header.addView(iconButton(HudIcon.Kind.BACK, "Retour") { finish() })
        header.addView(hudLabel("Diagnostic", Palette.PRIMARY, 12f).apply { typeface = Fonts.hudBold(context) }, weighted())
        root.addView(header, matchWrap())
        body = vertical().apply { setPadding(dp(16), dp(8), dp(16), dp(24)) }
        root.addView(ScrollView(this).apply { addView(body) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        render()
    }

    override fun onPause() {
        stopTest()
        super.onPause()
    }

    private fun card(title: String): LinearLayout {
        val c = vertical().apply { background = hudPanel(); setPadding(dp(14), dp(12), dp(14), dp(14)) }
        c.addView(hudLabel(title), matchWrap().margins(b = dp(8)))
        body.addView(c, matchWrap().margins(b = dp(12)))
        return c
    }

    private fun line(ok: Boolean, text: String) =
        label((if (ok) "✔ " else "✖ ") + text, 14f, if (ok) Palette.SUCCESS else Palette.ACCENT)

    private fun status(): List<Pair<Boolean, String>> = listOf(
        Persona.wakeEnabled(this) to "« Hey Jarvis » activé dans les réglages",
        (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) to "Micro autorisé",
        JarvisService.running to "Service d'écoute en marche",
        Settings.canDrawOverlays(this) to "Ouverture par-dessus les autres applis autorisée",
        (Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) to "Notifications autorisées",
        (getSystemService(android.os.PowerManager::class.java)?.isIgnoringBatteryOptimizations(packageName) == true) to "Pas de restriction de batterie",
    )

    private fun render() {
        body.removeAllViews()

        val s = card("État")
        status().forEach { (ok, t) -> s.addView(line(ok, t), matchWrap().margins(b = dp(4))) }
        s.addView(label("Android ${Build.VERSION.RELEASE} · ${Build.MANUFACTURER} ${Build.MODEL}", 12f, Palette.MUTED), matchWrap().margins(t = dp(6)))
        if (getSystemService(android.os.PowerManager::class.java)?.isIgnoringBatteryOptimizations(packageName) != true) {
            s.addView(pill("Retirer la restriction de batterie") {
                runCatching { startActivity(android.content.Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
            }, wrapWrap().margins(t = dp(8)))
        }

        val t = card("Test « Hey Jarvis »")
        t.addView(label("Touchez « Lancer le test », puis dites « Hey Jarvis » plusieurs fois. La barre cyan doit dépasser le trait (seuil ${Persona.threshold(this)}).", 14f, Palette.TEXT))
        t.addView(hudLabel("Score", Palette.MUTED, 9f), matchWrap().margins(t = dp(10), b = dp(4)))
        val (sb, track1) = bar(Palette.PRIMARY, Persona.threshold(this))
        scoreBar = sb; t.addView(track1, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(10)))
        t.addView(hudLabel("Volume du micro", Palette.MUTED, 9f), matchWrap().margins(t = dp(10), b = dp(4)))
        val (lb, track2) = bar(Palette.SECONDARY, -1f)
        levelBar = lb; t.addView(track2, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(6)))
        scoreText = label("Test arrêté.", 13f, Palette.MUTED)
        t.addView(scoreText, matchWrap().margins(t = dp(8)))
        testButton = pill(if (engine != null) "Arrêter le test" else "Lancer le test", filled = engine == null) { if (engine != null) stopTest() else startTest() }
        t.addView(testButton, wrapWrap().margins(t = dp(8)))

        val e = card("Fermetures anormales (Android)")
        e.addView(label(Diag.exits(this), 11f, Palette.TEXT).apply { typeface = android.graphics.Typeface.MONOSPACE; setTextIsSelectable(true) })

        val j = card("Journal de Jarvis")
        j.addView(label(Diag.journal(this).ifEmpty { "Vide." }, 11f, Palette.TEXT).apply { typeface = android.graphics.Typeface.MONOSPACE; setTextIsSelectable(true) })
        val row = horizontal()
        row.addView(pill("Copier le rapport", filled = true) { copy() }, wrapWrap().margins(r = dp(8)))
        row.addView(pill("Effacer") { Diag.clear(this); render() }, wrapWrap())
        j.addView(row, matchWrap().margins(t = dp(10)))
    }

    /** Barre horizontale avec un trait de seuil facultatif. */
    private fun bar(color: Int, threshold: Float): Pair<android.view.View, FrameLayout> {
        val track = FrameLayout(this).apply { setBackgroundColor(Palette.PRIMARY_SOFT) }
        val fill = android.view.View(this).apply { setBackgroundColor(color) }
        track.addView(fill, FrameLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT))
        if (threshold > 0) {
            val mark = android.view.View(this).apply { setBackgroundColor(Palette.TEXT) }
            track.addView(mark, FrameLayout.LayoutParams(dp(2), ViewGroup.LayoutParams.MATCH_PARENT))
            track.post { mark.translationX = track.width * threshold }
        }
        return fill to track
    }

    private fun setBar(v: android.view.View, value: Float) {
        val w = (v.parent as android.view.View).width
        v.layoutParams = v.layoutParams.apply { width = (w * value.coerceIn(0f, 1f)).toInt() }
    }

    private fun startTest() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1); return
        }
        // Le service libère le micro pendant le test.
        JarvisService.send(this, JarvisService.ACTION_PAUSE)
        peak = 0f; detections = 0
        scoreText.text = "Chargement du détecteur…"
        Thread {
            val r = runCatching { WakeWordEngine(this) }
            runOnUiThread {
                val eng = r.getOrElse {
                    scoreText.text = "Détecteur impossible à charger : ${it.javaClass.simpleName} ${it.message}"
                    Diag.log(this, "Test : chargement impossible ${it.message}")
                    return@runOnUiThread
                }
                engine = eng
                testButton.text = "Arrêter le test"
                eng.start(Persona.threshold(this),
                    onWake = { sc -> runOnUiThread { detections++; Diag.log(this, "Test : détecté (%.2f)".format(sc)) } },
                    onError = { err -> runOnUiThread { scoreText.text = "Erreur micro : ${err.message}"; Diag.log(this, "Test : micro ${err.message}") } },
                    onScore = { sc, lv -> runOnUiThread {
                        if (engine == null) return@runOnUiThread
                        peak = maxOf(peak, sc)
                        setBar(scoreBar, sc); setBar(levelBar, lv)
                        scoreText.text = "Score %.2f · meilleur %.2f · détections : %d".format(sc, peak, detections)
                    } })
            }
        }.start()
    }

    private fun stopTest() {
        val e = engine ?: return
        engine = null
        Thread { runCatching { e.close() } }.start()
        if (::testButton.isInitialized) { testButton.text = "Lancer le test"; scoreText.text = "Test arrêté. Meilleur score : %.2f, détections : %d".format(peak, detections) }
        JarvisService.send(this, JarvisService.ACTION_RESUME)
    }

    private fun copy() {
        val report = buildString {
            appendLine("ORBITE / JARVIS — rapport")
            appendLine("Android ${Build.VERSION.RELEASE} (${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}")
            status().forEach { (ok, t) -> appendLine((if (ok) "[OK] " else "[NON] ") + t) }
            appendLine("Test : meilleur score %.2f, détections %d".format(peak, detections))
            appendLine("\n-- Fermetures --\n" + Diag.exits(this@DiagActivity))
            appendLine("\n-- Journal --\n" + Diag.journal(this@DiagActivity))
        }
        runCatching {
            getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Rapport Jarvis", report))
            Toast.makeText(this, "Rapport copié : collez-le dans la conversation.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startTest()
    }
}
