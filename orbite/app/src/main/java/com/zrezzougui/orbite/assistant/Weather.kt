package com.zrezzougui.orbite.assistant

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Requêtes HTTP simples : aucun compte, aucune clé, aucun jeton d'IA. */
object Http {
    fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 8000
            c.readTimeout = 10000
            c.setRequestProperty("User-Agent", "Orbite/0.1 (Android; assistant hors IA)")
            c.setRequestProperty("Accept", "application/json")
            val code = c.responseCode
            if (code !in 200..299) throw IllegalStateException("HTTP $code")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
}

data class Place(val name: String, val lat: Double, val lon: Double)

data class DayForecast(val date: String, val code: Int, val tMax: Double, val tMin: Double, val rain: Int)

data class Forecast(
    val place: Place,
    val temp: Double,
    val feels: Double,
    val code: Int,
    val wind: Double,
    val days: List<DayForecast>,
)

/** Météo via Open-Meteo (service libre et gratuit, sans clé). */
object Weather {

    fun geocode(city: String): Place? {
        val json = JSONObject(
            Http.get(
                "https://geocoding-api.open-meteo.com/v1/search?count=1&language=fr&format=json&name=" +
                    Http.enc(city)
            )
        )
        val results = json.optJSONArray("results") ?: return null
        if (results.length() == 0) return null
        val r = results.getJSONObject(0)
        val region = r.optString("admin1").takeIf { it.isNotEmpty() && it != r.optString("name") }
        val name = listOfNotNull(r.optString("name"), region).joinToString(", ")
        return Place(name, r.getDouble("latitude"), r.getDouble("longitude"))
    }

    fun forecast(place: Place): Forecast {
        val url = "https://api.open-meteo.com/v1/forecast?latitude=${place.lat}&longitude=${place.lon}" +
            "&current=temperature_2m,apparent_temperature,weather_code,wind_speed_10m" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
            "&timezone=auto&forecast_days=7"
        val json = JSONObject(Http.get(url))
        val cur = json.getJSONObject("current")
        val d = json.getJSONObject("daily")
        val dates = d.getJSONArray("time")
        val codes = d.getJSONArray("weather_code")
        val max = d.getJSONArray("temperature_2m_max")
        val min = d.getJSONArray("temperature_2m_min")
        val rain = d.optJSONArray("precipitation_probability_max")
        val days = (0 until dates.length()).map { i ->
            DayForecast(
                dates.getString(i), codes.optInt(i), max.optDouble(i), min.optDouble(i),
                rain?.optInt(i, -1) ?: -1,
            )
        }
        return Forecast(
            place,
            cur.optDouble("temperature_2m"),
            cur.optDouble("apparent_temperature"),
            cur.optInt("weather_code"),
            cur.optDouble("wind_speed_10m"),
            days,
        )
    }

    /** Codes météo de l'OMM (WMO) utilisés par Open-Meteo. */
    fun describe(code: Int): String = when (code) {
        0 -> "ciel dégagé"
        1 -> "plutôt dégagé"
        2 -> "partiellement nuageux"
        3 -> "couvert"
        45, 48 -> "brouillard"
        51, 53, 55 -> "bruine"
        56, 57 -> "bruine verglaçante"
        61 -> "pluie faible"
        63 -> "pluie"
        65 -> "forte pluie"
        66, 67 -> "pluie verglaçante"
        71 -> "neige faible"
        73 -> "neige"
        75 -> "forte neige"
        77 -> "grains de neige"
        80 -> "averses faibles"
        81 -> "averses"
        82 -> "violentes averses"
        85, 86 -> "averses de neige"
        95 -> "orages"
        96, 99 -> "orages avec grêle"
        else -> "temps inconnu"
    }

    fun emoji(code: Int): String = when (code) {
        0 -> "☀️"
        1, 2 -> "⛅"
        3 -> "☁️"
        45, 48 -> "🌫️"
        in 51..67, in 80..82 -> "🌧️"
        in 71..77, 85, 86 -> "❄️"
        in 95..99 -> "⛈️"
        else -> "🌡️"
    }
}

/** Position approximative de l'appareil (réseau / GPS), sans service Google obligatoire. */
object Locator {

    fun hasPermission(ctx: Context): Boolean =
        ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** À appeler hors du fil principal. */
    @SuppressLint("MissingPermission")
    fun current(ctx: Context): Place? {
        if (!hasPermission(ctx)) return null
        val lm = ctx.getSystemService(LocationManager::class.java) ?: return null
        var best: Location? = null
        for (p in lm.getProviders(true)) {
            val l = try { lm.getLastKnownLocation(p) } catch (_: Exception) { null } ?: continue
            if (best == null || l.time > best.time) best = l
        }
        val fresh = best != null && System.currentTimeMillis() - best.time < 3 * 3600_000L
        if (!fresh && Build.VERSION.SDK_INT >= 30) {
            val provider = when {
                lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
                lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
                else -> null
            }
            if (provider != null) {
                val latch = CountDownLatch(1)
                var got: Location? = null
                try {
                    lm.getCurrentLocation(provider, null, ctx.mainExecutor) { l -> got = l; latch.countDown() }
                    latch.await(6, TimeUnit.SECONDS)
                } catch (_: Exception) {
                }
                if (got != null) best = got
            }
        }
        val loc = best ?: return null
        return Place(cityName(ctx, loc.latitude, loc.longitude) ?: "votre position", loc.latitude, loc.longitude)
    }

    private fun cityName(ctx: Context, lat: Double, lon: Double): String? = try {
        if (!Geocoder.isPresent()) null else {
            @Suppress("DEPRECATION")
            Geocoder(ctx, Locale.FRANCE).getFromLocation(lat, lon, 1)?.firstOrNull()?.locality
        }
    } catch (_: Exception) {
        null
    }
}
