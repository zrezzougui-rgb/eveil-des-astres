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
    private var downloading = -2

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        edgeToEdge()
        tts = TextToSpeech(this) { if (it == TextToSpeech.SUCCESS) runOnUiThread { tts?.let { t -> Persona.applyVoice(this, t) }; render() } }
        val root = vertical().apply { setBackgroundColor(Palette.BG) }
        root.padForSystemBars()
        val header = horizontal().apply { setPadding(dp(16), dp(12), dp(16), dp(4)) }
        header.addView(label("‹", 28f, Palette.MUTED).apply { setPadding(0, 0, dp(16), 0); setOnClickListener { finish() } })
        header.addView(label("RÉGLAGES J.A.R.V.I.S", 16f, Palette.PRIMARY).apply { typeface = Fonts.hudBold(context); letterSpacing = 0.12f }, weighted())
        root.addView(header, matchWrap())
        body = vertical().apply { setPadding(dp(16), dp(8), dp(16), dp(24)) }
        root.addView(ScrollView(this).apply { addView(body) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    override fun onDestroy() {
        tts?.shutdown()
        super.onDestroy()
    }

    private fun card(title: String, text: String): LinearLayout {
        val c = vertical().apply {
            background = rounded(Palette.SURFACE, dpf(Radius.LG), dp(1), Palette.LINE_STRONG)
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
            "« Jarvis » mains libres",
            "Dites « Jarvis » à tout moment, puis votre demande. Le mot est reconnu sur le téléphone par Vosk (libre, hors ligne) : " +
                "aucun son n'est envoyé ni enregistré. Une notification discrète reste affichée tant que Jarvis écoute. " +
                "L'écoute consomme un peu de batterie.",
        )
        val mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val overlay = Settings.canDrawOverlays(this)
        val notif = Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val ready = WakeModel.isReady(this)

        // 1. Modèle
        c.addView(status(ready, "Reconnaissance du mot « Jarvis » installée", "Reconnaissance à télécharger (${WakeModel.SIZE_MB} Mo, une seule fois)"))
        if (!ready) {
            val txt = when {
                downloading >= 0 -> "Téléchargement… $downloading %"
                downloading == -1 -> "Téléchargement…"
                else -> "Télécharger (${WakeModel.SIZE_MB} Mo)"
            }
            c.addView(pill(txt) { if (downloading == -2) download() }, wrapWrap().margins(t = dp(6), b = dp(10)))
        }
        // 2. Micro
        c.addView(status(mic, "Micro autorisé", "Micro à autoriser"), matchWrap().margins(t = dp(4)))
        if (!mic) c.addView(pill("Autoriser le micro") { requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1) }, wrapWrap().margins(t = dp(6), b = dp(10)))
        // 3. Ouvrir l'assistant par-dessus les autres applis
        c.addView(status(overlay, "Jarvis peut s'ouvrir par-dessus vos applis", "Autoriser « Afficher par-dessus les autres applis »"), matchWrap().margins(t = dp(4)))
        if (!overlay) c.addView(pill("Ouvrir le réglage") {
            runCatching { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }
        }, wrapWrap().margins(t = dp(6), b = dp(10)))
        // 4. Notification d'écoute
        if (!notif) {
            c.addView(status(false, "", "Notifications à autoriser (pour voir que Jarvis écoute)"), matchWrap().margins(t = dp(4)))
            c.addView(pill("Autoriser") { requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2) }, wrapWrap().margins(t = dp(6), b = dp(10)))
        }

        val on = Persona.wakeEnabled(this)
        val row = horizontal().apply { setPadding(0, dp(10), 0, 0) }
        row.addView(chip(if (on) "🎙️ Écoute « Jarvis » activée" else "🎙️ Activer l'écoute « Jarvis »", on) {
            if (!on && (!ready || !mic)) {
                Toast.makeText(this, "Téléchargez la reconnaissance et autorisez le micro d'abord.", Toast.LENGTH_LONG).show()
                return@chip
            }
            Persona.setWakeEnabled(this, !on)
            if (!on) JarvisService.start(this) else JarvisService.stop(this)
            render()
        }, wrapWrap().margins(r = dp(8)))
        c.addView(row, matchWrap())
        val charge = Persona.onlyCharging(this)
        c.addView(chip(if (charge) "🔌 Seulement en charge ✔" else "🔌 Seulement en charge", charge) {
            Persona.setOnlyCharging(this, !charge)
            if (Persona.wakeEnabled(this)) { JarvisService.stop(this); JarvisService.start(this) }
            render()
        }, wrapWrap().margins(t = dp(8)))
        c.addView(label("Astuce : Android ne permet de démarrer l'écoute que lorsqu'Orbite est à l'écran. Elle reprend toute seule à chaque retour sur l'accueil.", 12f, Palette.MUTED), matchWrap().margins(t = dp(10)))
        if (ready) c.addView(label("Supprimer la reconnaissance téléchargée", 13f, Palette.DANGER).apply {
            setPadding(0, dp(10), 0, 0)
            setOnClickListener {
                JarvisService.stop(this@JarvisSettingsActivity)
                Persona.setWakeEnabled(this@JarvisSettingsActivity, false)
                WakeModel.delete(this@JarvisSettingsActivity); render()
            }
        })
    }

    private fun download() {
        downloading = -1; render()
        Thread {
            val result = runCatching {
                WakeModel.download(this) { p -> runOnUiThread { if (downloading != p) { downloading = p; render() } } }
            }
            runOnUiThread {
                downloading = -2
                Toast.makeText(this,
                    if (result.isSuccess) "Reconnaissance installée. Activez l'écoute « Jarvis »."
                    else "Téléchargement impossible : ${result.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                render()
            }
        }.start()
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
        val c = card("Voix", "Voix de synthèse française installée sur le téléphone. D'autres voix s'ajoutent dans les réglages Android « Synthèse vocale ».")
        val t = tts
        val voices = if (t != null) Persona.frenchVoices(t) else emptyList()
        if (voices.isEmpty()) {
            c.addView(label("Chargement des voix…", 13f, Palette.MUTED))
        } else {
            val chosen = Persona.voiceName(this)
            voices.take(8).forEachIndexed { i, v ->
                val name = "Voix ${i + 1}" + (if (v.isNetworkConnectionRequired) " (internet)" else "")
                c.addView(chip(name, v.name == chosen) {
                    Persona.setVoiceName(this, v.name); t?.voice = v
                    t?.speak("Bonjour${Persona.suffix(this)}. Je suis Jarvis.", TextToSpeech.QUEUE_FLUSH, null, "test")
                    render()
                }, wrapWrap().margins(b = dp(6)))
            }
        }
        c.addView(pill("▶ Tester", filled = true) {
            t?.let { Persona.applyVoice(this, it) }
            t?.speak("${Persona.greeting(this)}. Tous les systèmes sont opérationnels.", TextToSpeech.QUEUE_FLUSH, null, "test")
        }, wrapWrap().margins(t = dp(6)))
        c.addView(pill("Réglages de synthèse vocale d'Android") {
            runCatching { startActivity(Intent("com.android.settings.TTS_SETTINGS")) }
        }, wrapWrap().margins(t = dp(8)))
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        render()
    }
}
