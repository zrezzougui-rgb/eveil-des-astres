package com.zrezzougui.orbite.jarvis

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import java.util.Calendar
import java.util.Locale

/** Personnalité de Jarvis : comment il vous appelle, ses formules et sa voix. */
object Persona {

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("jarvis", Context.MODE_PRIVATE)

    /** « monsieur », « madame », un prénom, ou vide. */
    fun address(ctx: Context): String = prefs(ctx).getString("adresse", "").orEmpty()
    fun setAddress(ctx: Context, v: String) = prefs(ctx).edit().putString("adresse", v.trim()).apply()

    fun wakeEnabled(ctx: Context) = prefs(ctx).getBoolean("eveil", false)
    fun setWakeEnabled(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("eveil", on).apply()

    fun onlyCharging(ctx: Context) = prefs(ctx).getBoolean("en_charge", false)
    fun setOnlyCharging(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("en_charge", on).apply()

    /** Sensibilité du mot d'éveil : plus le seuil est bas, plus Jarvis réagit (et se trompe parfois). */
    fun sensitivity(ctx: Context): Int = prefs(ctx).getInt("sensibilite", 1) // 0 faible, 1 normale, 2 forte
    fun setSensitivity(ctx: Context, v: Int) = prefs(ctx).edit().putInt("sensibilite", v).apply()
    // Seuils ajustés après mesure sur téléphone : « Hey Jarvis » prononcé à la française plafonnait vers 0,48.
    // Bruit de fond mesuré sur 10 min : score max 0,10 ; « Hey Jarvis » réel : 0,24 à 0,71.
    fun threshold(ctx: Context): Float = when (sensitivity(ctx)) { 0 -> 0.45f; 2 -> 0.18f; else -> 0.28f }

    fun voiceName(ctx: Context): String? = prefs(ctx).getString("voix", null)
    fun setVoiceName(ctx: Context, v: String?) = prefs(ctx).edit().putString("voix", v).apply()

    /** «, monsieur » ou rien. */
    fun suffix(ctx: Context): String = address(ctx).let { if (it.isEmpty()) "" else ", $it" }

    fun greeting(ctx: Context): String {
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val hello = when (h) { in 5..17 -> "Bonjour"; else -> "Bonsoir" }
        return hello + suffix(ctx)
    }

    private val acks = listOf("Tout de suite", "Bien", "C'est parti", "Entendu", "Avec plaisir")
    fun ack(ctx: Context): String = acks.random() + suffix(ctx) + "."

    // ------------------------------------------------------------------ voix

    const val SHERPA = "org.woheller69.ttsengine"     // SherpaTTS : voix neuronales Piper, libres, hors ligne
    const val GOOGLE = "com.google.android.tts"       // Services vocaux Google

    fun engine(ctx: Context): String? = prefs(ctx).getString("moteur", null)
    fun setEngine(ctx: Context, pkg: String?) = prefs(ctx).edit().putString("moteur", pkg).remove("voix").apply()

    /** 0 lente, 1 normale, 2 rapide */
    fun rate(ctx: Context): Int = prefs(ctx).getInt("debit", 1)
    fun setRate(ctx: Context, v: Int) = prefs(ctx).edit().putInt("debit", v).apply()

    fun isInstalled(ctx: Context, pkg: String) =
        runCatching { ctx.packageManager.getPackageInfo(pkg, 0); true }.getOrDefault(false)

    /**
     * Moteur de voix à utiliser : celui choisi, sinon SherpaTTS (voix Piper naturelles) s'il est installé,
     * sinon les services vocaux Google, sinon le moteur par défaut du téléphone.
     */
    fun preferredEngine(ctx: Context): String? =
        engine(ctx)?.takeIf { isInstalled(ctx, it) }
            ?: SHERPA.takeIf { isInstalled(ctx, it) }
            ?: GOOGLE.takeIf { isInstalled(ctx, it) }

    fun newTts(ctx: Context, onReady: (TextToSpeech, Boolean) -> Unit): TextToSpeech {
        var tts: TextToSpeech? = null
        val listener = TextToSpeech.OnInitListener { status -> tts?.let { onReady(it, status == TextToSpeech.SUCCESS) } }
        tts = preferredEngine(ctx)?.let { TextToSpeech(ctx, listener, it) } ?: TextToSpeech(ctx, listener)
        return tts
    }

    /** Voix française de meilleure qualité (ou celle choisie), débit naturel. */
    fun applyVoice(ctx: Context, tts: TextToSpeech) {
        tts.language = Locale.FRANCE
        val voices = frenchVoices(tts)
        val chosen = voiceName(ctx)?.let { n -> voices.firstOrNull { it.name == n } }
            ?: voices.filter { TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features }
                .maxWithOrNull(compareBy<Voice>({ it.quality }, { !it.isNetworkConnectionRequired }))
        if (chosen != null) tts.voice = chosen
        tts.setSpeechRate(when (rate(ctx)) { 0 -> 0.9f; 2 -> 1.15f; else -> 1.0f })
        tts.setPitch(1.0f)
    }

    fun frenchVoices(tts: TextToSpeech): List<Voice> =
        runCatching { tts.voices }.getOrNull().orEmpty()
            .filter { it.locale.language == "fr" }
            .sortedWith(compareByDescending<Voice> { it.quality }.thenBy { it.isNetworkConnectionRequired }.thenBy { it.name })

    fun describe(v: Voice, index: Int): String {
        val q = when {
            v.quality >= Voice.QUALITY_VERY_HIGH -> "très haute qualité"
            v.quality >= Voice.QUALITY_HIGH -> "haute qualité"
            else -> "standard"
        }
        val region = v.locale.country.takeIf { it.isNotEmpty() && it != "FR" }?.let { " ($it)" } ?: ""
        return "Voix ${index + 1}$region · $q" + if (v.isNetworkConnectionRequired) " · internet" else ""
    }

    /** Rend le texte agréable à l'oreille : heures, unités, abréviations, symboles. */
    fun spoken(text: String): String = text
        .replace("°", " degrés")
        .replace(Regex("[\\p{So}\\p{Cs}\\uFE0F•]"), "")
        .replace(Regex("\\b1 h\\b"), "1 heure")
        .replace(Regex("(\\d+) h\\b")) { "${it.groupValues[1]} heures" }
        .replace(Regex("(\\d+) degrés / (\\d+) degrés")) { "de ${it.groupValues[1]} à ${it.groupValues[2]} degrés" }
        .replace(Regex("(\\d{1,2})h(\\d{2})")) { "${it.groupValues[1]} heures ${it.groupValues[2]}" }
        .replace(Regex("(\\d{1,2})h\\b")) { "${it.groupValues[1]} heures" }
        .replace(Regex("(\\d{1,2}):(\\d{2})")) { "${it.groupValues[1]} heures ${it.groupValues[2]}" }
        .replace("km/h", "kilomètres heure")
        .replace(" %", " pour cent").replace("%", " pour cent")
        .replace(Regex("\\bRDV\\b"), "rendez-vous")
        .replace(" — ", ", ").replace(" · ", ", ")
        .replace("«", "").replace("»", "")
        .replace(Regex("\\s*\\n+\\s*"), ". ")
        .replace(Regex("([.!?:])\\.\\s"), "$1 ")
        .replace(Regex("\\s+"), " ")
        .trim()
}
