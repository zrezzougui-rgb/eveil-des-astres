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

    /** Voix française la plus soignée disponible (ou celle choisie dans les réglages). */
    fun applyVoice(ctx: Context, tts: TextToSpeech) {
        tts.language = Locale.FRANCE
        val voices = runCatching { tts.voices }.getOrNull().orEmpty().filter { it.locale.language == "fr" }
        val chosen = voiceName(ctx)?.let { n -> voices.firstOrNull { it.name == n } }
            ?: voices.filter { !it.isNetworkConnectionRequired && TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features }
                .maxByOrNull { it.quality }
        if (chosen != null) tts.voice = chosen
        tts.setSpeechRate(1.05f)
        tts.setPitch(0.92f)
    }

    fun frenchVoices(tts: TextToSpeech): List<Voice> =
        runCatching { tts.voices }.getOrNull().orEmpty().filter { it.locale.language == "fr" }.sortedBy { it.name }
}
