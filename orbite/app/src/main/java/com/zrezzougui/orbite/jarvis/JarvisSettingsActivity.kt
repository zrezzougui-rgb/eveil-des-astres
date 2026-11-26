package com.zrezzougui.orbite.jarvis

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.zrezzougui.orbite.Fonts
import com.zrezzougui.orbite.Palette
import com.zrezzougui.orbite.Radius
import com.zrezzougui.orbite.chip
import com.zrezzougui.orbite.dp
import com.zrezzougui.orbite.dpf
import com.zrezzougui.orbite.edgeToEdge
import com.zrezzougui.orbite.heading
import com.zrezzougui.orbite.hud.hudLabel
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

/** Réglages de Jarvis : mot d'éveil mains libres, autorisations, façon de vous appeler, voix. */
class JarvisSettingsActivity : Activity() {

    private lateinit var body: LinearLayout
    private var tts: TextToSpeech? = null
    private var resumedOnce = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        edgeToEdge()
        tts = Persona.newTts(this) { t, ok -> if (ok) runOnUiThread { Persona.applyVoice(this, t); render() } }
        val root = vertical().apply { background = com.zrezzougui.orbite.hud.HudBackgroundDrawable(resources.displayMetrics.density, 0.05f) }
        root.padForSystemBars()
        val header = horizontal().apply { setPadding(dp(16), dp(12), dp(16), dp(4)) }
        header.addView(label("‹", 28f, Palette.MUTED).apply { setPadding(0, 0, dp(16), 0); setOnClickListener { finish() } })
        header.addView(hudLabel("Réglages J.A.R.V.I.S", Palette.PRIMARY, 12f).apply { typeface = Fonts.hudBold(context) }, weighted())
        root.addView(header, matchWrap())
        body = vertical().apply { setPadding(dp(16), dp(8), dp(16), dp(24)) }
        root.addView(ScrollView(this).apply { addView(body) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    override fun onDestroy() {
        tts?.shutdown()
        super.onDestroy()
    }

    private fun card(title: String, text: String): LinearLayout {
        val c = vertical().apply {
            background = com.zrezzougui.orbite.hud.HudPanelDrawable(resources.displayMetrics.density)
            setPadding(dp(16), dp(14), dp(16), dp(16))
        }
        c.addView(heading(title, 18f))
        c.addView(label(text, 14f, Palette.MUTED), matchWrap().margins(t = dp(4), b = dp(10)))
        body.addView(c, matchWrap().margins(b = dp(12)))
        return c
    }

    private fun status(ok: Boolean, yes: String, no: String): TextView =
        label(if (ok) "✔ $yes" else "• $no", 14f, if (ok) Palette.SUCCESS else Palette.SECONDARY)

    private fun render() {
        body.removeAllViews()
        wakeCard()
        addressCard()
        voiceCard()
    }

    // ------------------------------------------------------------------ mains libres

    private fun wakeCard() {
        val c = card(
            "« Hey Jarvis » mains libres",
            "Dites « Hey Jarvis » à tout moment, puis votre demande. La phrase est reconnue sur le téléphone " +
                "par openWakeWord (projet libre, utilisé par Home Assistant) : aucun son n'est enregistré ni envoyé. " +
                "Une notification discrète reste affichée tant que Jarvis écoute.",
        )
        val mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val overlay = Settings.canDrawOverlays(this)
        val notif = Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

        c.addView(status(mic, "Micro autorisé", "Micro à autoriser"))
        if (!mic) c.addView(pill("Autoriser le micro") { requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1) }, wrapWrap().margins(t = dp(6), b = dp(10)))
        c.addView(status(overlay, "Jarvis peut s'ouvrir par-dessus vos applis", "Autoriser « Afficher par-dessus les autres applis »"), matchWrap().margins(t = dp(6)))
        if (!overlay) c.addView(pill("Ouvrir le réglage") {
            runCatching { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }
        }, wrapWrap().margins(t = dp(6), b = dp(10)))
        if (!notif) {
            c.addView(status(false, "", "Notifications à autoriser (pour voir que Jarvis écoute)"), matchWrap().margins(t = dp(6)))
            c.addView(pill("Autoriser") { requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2) }, wrapWrap().margins(t = dp(6), b = dp(10)))
        }

        val on = Persona.wakeEnabled(this)
        c.addView(pill(if (on) "Désactiver « Hey Jarvis »" else "Activer « Hey Jarvis »", filled = !on) {
            if (!on && !mic) {
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
                return@pill
            }
            Persona.setWakeEnabled(this, !on)
            if (!on) JarvisService.start(this) else JarvisService.stop(this)
            render()
        }, wrapWrap().margins(t = dp(14)))

        c.addView(hudLabel("Sensibilité", Palette.MUTED), matchWrap().margins(t = dp(16), b = dp(6)))
        val row = horizontal()
        val sens = Persona.sensitivity(this)
        listOf("Faible", "Normale", "Forte").forEachIndexed { i, l ->
            row.addView(chip(l, sens == i) {
                Persona.setSensitivity(this, i)
                if (Persona.wakeEnabled(this)) { JarvisService.stop(this); JarvisService.start(this) }
                render()
            }, wrapWrap().margins(r = dp(8)))
        }
        c.addView(row, matchWrap())
        c.addView(label("Forte : réagit même de loin, mais peut se déclencher par erreur. Faible : il faut parler clairement.", 12f, Palette.MUTED), matchWrap().margins(t = dp(6)))

        val charge = Persona.onlyCharging(this)
        c.addView(chip(if (charge) "Seulement en charge ✔" else "Seulement en charge", charge) {
            Persona.setOnlyCharging(this, !charge)
            if (Persona.wakeEnabled(this)) { JarvisService.stop(this); JarvisService.start(this) }
            render()
        }, wrapWrap().margins(t = dp(14)))
        c.addView(label("Android ne permet de démarrer l'écoute que lorsqu'Orbite est à l'écran : elle reprend toute seule à chaque retour sur l'accueil.", 12f, Palette.MUTED), matchWrap().margins(t = dp(10)))
    }

    // ------------------------------------------------------------------ personnalité

    private fun addressCard() {
        val c = card("Comment Jarvis vous appelle", "Exemple : « Bien, monsieur. Il fait 8 degrés à Lyon. »")
        val current = Persona.address(this)
        val row = horizontal()
        listOf("" to "Rien", "monsieur" to "Monsieur", "madame" to "Madame").forEach { (v, l) ->
            row.addView(chip(l, current == v) { Persona.setAddress(this, v); render() }, wrapWrap().margins(r = dp(8)))
        }
        val custom = current.isNotEmpty() && current != "monsieur" && current != "madame"
        row.addView(chip(if (custom) current else "Prénom…", custom) { askName() }, wrapWrap())
        c.addView(row, matchWrap())
    }

    private fun askName() {
        val field = android.widget.EditText(this).apply { setText(Persona.address(this@JarvisSettingsActivity)); isSingleLine = true; setTextColor(Palette.TEXT) }
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Votre prénom")
            .setView(android.widget.FrameLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(field) })
            .setPositiveButton("OK") { _, _ -> Persona.setAddress(this, field.text.toString()); render() }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun voiceCard() {
        val c = card(
            "Voix",
            "Pour une voix fluide et naturelle, installez SherpaTTS : une appli libre qui fait parler le téléphone " +
                "avec les voix neuronales Piper, sans internet et sans compte. Choisissez-y une voix française " +
                "(par exemple « fr_FR-tom » ou « fr_FR-siwis »), puis sélectionnez-la ici.",
        )
        val t = tts
        val engines = t?.engines.orEmpty()
        val current = Persona.preferredEngine(this) ?: t?.defaultEngine

        c.addView(hudLabel("Moteur de voix", Palette.MUTED), matchWrap().margins(b = dp(6)))
        val er = vertical()
        engines.forEach { e ->
            val tag = when (e.name) { Persona.SHERPA -> " · voix neuronales"; Persona.GOOGLE -> " · Google"; else -> "" }
            er.addView(chip(e.label + tag, e.name == current) {
                Persona.setEngine(this, e.name)
                restartTts()
            }, wrapWrap().margins(b = dp(6)))
        }
        c.addView(er, matchWrap())
        if (!Persona.isInstalled(this, Persona.SHERPA)) {
            c.addView(pill("Installer SherpaTTS (F-Droid)", filled = true) {
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://f-droid.org/fr/packages/org.woheller69.ttsengine/"))) }
            }, wrapWrap().margins(t = dp(6)))
        }
        if (!Persona.isInstalled(this, Persona.GOOGLE)) {
            c.addView(pill("Installer les voix Google") {
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${Persona.GOOGLE}"))) }
            }, wrapWrap().margins(t = dp(8)))
        }

        c.addView(hudLabel("Voix française", Palette.MUTED), matchWrap().margins(t = dp(16), b = dp(6)))
        val voices = if (t != null) Persona.frenchVoices(t) else emptyList()
        if (voices.isEmpty()) {
            c.addView(label(if (t == null) "Chargement…" else "Aucune voix française dans ce moteur. Ouvrez ses réglages pour en télécharger une.", 13f, Palette.MUTED))
        } else {
            val chosen = Persona.voiceName(this) ?: t?.voice?.name
            voices.take(10).forEachIndexed { i, v ->
                c.addView(chip(Persona.describe(v, i), v.name == chosen) {
                    Persona.setVoiceName(this, v.name); t?.voice = v
                    speakTest("Bonjour${Persona.suffix(this)}. Je suis Jarvis. Il fait 8 degrés à Lyon.")
                    render()
                }, wrapWrap().margins(b = dp(6)))
            }
        }

        c.addView(hudLabel("Débit", Palette.MUTED), matchWrap().margins(t = dp(16), b = dp(6)))
        val rr = horizontal()
        listOf("Posé", "Naturel", "Rapide").forEachIndexed { i, l ->
            rr.addView(chip(l, Persona.rate(this) == i) {
                Persona.setRate(this, i); t?.let { Persona.applyVoice(this, it) }; speakTest("Tous les systèmes sont opérationnels.")
                render()
            }, wrapWrap().margins(r = dp(8)))
        }
        c.addView(rr, matchWrap())

        c.addView(pill("Tester la voix", filled = true) {
            speakTest("${Persona.greeting(this)}. Il est ${java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)} heures. Tous les systèmes sont opérationnels.")
        }, wrapWrap().margins(t = dp(14)))
        c.addView(pill("Réglages de la synthèse vocale d'Android") {
            runCatching { startActivity(Intent("com.android.settings.TTS_SETTINGS")) }
        }, wrapWrap().margins(t = dp(8)))
    }

    private fun speakTest(text: String) {
        val t = tts ?: return
        Persona.applyVoice(this, t)
        t.speak(Persona.spoken(text), TextToSpeech.QUEUE_FLUSH, null, "test")
    }

    private fun restartTts() {
        tts?.shutdown()
        tts = null
        render()
        tts = Persona.newTts(this) { t, ok -> if (ok) runOnUiThread { Persona.applyVoice(this, t); render() } }
    }

    override fun onResume() {
        super.onResume()
        // Retour des réglages d'un moteur (voix téléchargée) : on recharge la liste.
        if (resumedOnce && tts != null) restartTts() else render()
        resumedOnce = true
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        render()
    }
}
