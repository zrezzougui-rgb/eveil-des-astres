package com.zrezzougui.orbite.assistant

import com.zrezzougui.orbite.normalize
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

data class ParsedWhen(
    val start: Calendar,
    val hasDate: Boolean,
    val hasTime: Boolean,
    val durationMin: Int?,
    /** Jours couverts (1 par défaut, 7 pour « cette semaine »). */
    val spanDays: Int,
    /** Texte d'origine sans les morceaux de date/heure reconnus. */
    val rest: String,
) {
    val found get() = hasDate || hasTime

    fun describe(): String {
        val day = SimpleDateFormat("EEEE d MMMM", Locale.FRENCH).format(start.time)
        if (!hasTime) return day
        val m = start.get(Calendar.MINUTE)
        return day + " à " + start.get(Calendar.HOUR_OF_DAY) + "h" + (if (m == 0) "" else "%02d".format(m))
    }
}

/**
 * Lecture des dates et heures en français courant, par règles (aucun modèle d'IA).
 * Exemples : « demain à 14h30 », « mardi prochain 9h », « le 12 décembre à midi »,
 * « dans 3 jours », « ce soir », « pendant 2 heures ».
 */
object FrenchDate {

    private const val NUM =
        "(\\d{1,2}|une|un|deux|trois|quatre|cinq|six|sept|huit|neuf|dix|onze|douze|treize|quatorze|" +
            "quinze|seize|vingt|trente|quarante|quarante-cinq|cinquante)"

    private val words = mapOf(
        "un" to 1, "une" to 1, "deux" to 2, "trois" to 3, "quatre" to 4, "cinq" to 5, "six" to 6,
        "sept" to 7, "huit" to 8, "neuf" to 9, "dix" to 10, "onze" to 11, "douze" to 12, "treize" to 13,
        "quatorze" to 14, "quinze" to 15, "seize" to 16, "vingt" to 20, "trente" to 30, "quarante" to 40,
        "quarante-cinq" to 45, "cinquante" to 50,
    )

    private val months = listOf(
        "janvier", "fevrier", "mars", "avril", "mai", "juin",
        "juillet", "aout", "septembre", "octobre", "novembre", "decembre",
    )
    private val weekdays = listOf("dimanche", "lundi", "mardi", "mercredi", "jeudi", "vendredi", "samedi")

    fun num(s: String): Int = s.toIntOrNull() ?: words[s] ?: 0

    fun parse(text: String, now: Calendar = Calendar.getInstance()): ParsedWhen {
        val n = normalize(text)
        val mask = BooleanArray(n.length)

        fun take(pattern: String): MatchResult? {
            val re = Regex("(?<![\\p{L}\\d])(?:$pattern)(?![\\p{L}\\d])")
            var m = re.find(n)
            while (m != null) {
                if (m.range.none { mask[it] }) {
                    m.range.forEach { mask[it] = true }
                    return m
                }
                m = re.find(n, m.range.first + 1)
            }
            return null
        }

        val cal = now.clone() as Calendar
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        var hasDate = false
        var hasTime = false
        var duration: Int? = null
        var span = 1
        var hintHour = -1

        // Durée : « pendant 2 heures », « pour 30 minutes », « durée 1h30 »
        take("(?:pendant|durant|duree(?: de)?|pour une duree de) $NUM ?(heures?|h|minutes?|min)(?: ?(\\d{2}))?( et demie?)?")
            ?.let { m ->
                val v = num(m.groupValues[1])
                val unit = m.groupValues[2]
                duration = if (unit.startsWith("h")) v * 60 + (m.groupValues[3].toIntOrNull() ?: 0) +
                    (if (m.groupValues[4].isNotEmpty()) 30 else 0) else v
            }

        // Relatif : « dans 3 jours », « dans 2 heures », « dans 20 minutes »
        take("dans $NUM ?(minutes?|min|heures?|h|jours?|semaines?|mois)")?.let { m ->
            val v = num(m.groupValues[1])
            when (m.groupValues[2].first()) {
                'm' -> if (m.groupValues[2] == "mois") cal.add(Calendar.MONTH, v)
                else { cal.add(Calendar.MINUTE, v); hasTime = true }
                'h' -> { cal.add(Calendar.HOUR_OF_DAY, v); hasTime = true }
                'j' -> cal.add(Calendar.DAY_OF_YEAR, v)
                's' -> cal.add(Calendar.DAY_OF_YEAR, 7 * v)
            }
            hasDate = true
        }

        // Semaine : « cette semaine », « la semaine prochaine »
        take("(?:pour |de )?(?:cette semaine|la semaine prochaine|semaine prochaine)")?.let { m ->
            if (m.value.contains("prochaine")) {
                val dow = cal.get(Calendar.DAY_OF_WEEK)
                val toMonday = ((Calendar.MONDAY - dow) + 7) % 7
                cal.add(Calendar.DAY_OF_YEAR, if (toMonday == 0) 7 else toMonday)
            }
            span = 7
            hasDate = true
        }

        // Jours proches et moments de la journée
        take("(?:pour |d')?(?:apres[- ]demain)")?.let { cal.add(Calendar.DAY_OF_YEAR, 2); hasDate = true }
        take("(?:pour |de )?demain(?: (matin|midi|soir|apres[- ]midi))?")?.let { m ->
            cal.add(Calendar.DAY_OF_YEAR, 1); hasDate = true
            hintHour = partOfDay(m.groupValues[1])
        }
        take("(?:pour |d')?aujourd'?hui")?.let { hasDate = true }
        take("(?:pour |de )?(?:ce|cet) (matin|midi|soir|apres[- ]midi)")?.let { m ->
            hasDate = true
            hintHour = partOfDay(m.groupValues[1])
        }

        // Jour de la semaine : « mardi », « lundi prochain »
        take("(?:pour |le |ce )?(dimanche|lundi|mardi|mercredi|jeudi|vendredi|samedi)(?: prochain)?(?: (matin|midi|soir|apres[- ]midi))?")
            ?.let { m ->
                val target = weekdays.indexOf(m.groupValues[1]) + 1
                var diff = (target - cal.get(Calendar.DAY_OF_WEEK) + 7) % 7
                if (diff == 0) diff = 7
                cal.add(Calendar.DAY_OF_YEAR, diff)
                hasDate = true
                if (m.groupValues[2].isNotEmpty()) hintHour = partOfDay(m.groupValues[2])
            }

        // Date explicite : « 12/12 », « 12/12/2026 »
        take("(?:le )?(\\d{1,2})/(\\d{1,2})(?:/(\\d{2,4}))?")?.let { m ->
            setDate(cal, now, m.groupValues[1].toInt(), m.groupValues[2].toInt() - 1, m.groupValues[3])
            hasDate = true
        }
        // « le 12 décembre 2026 », « 3 janvier », « le 1er »
        take("(?:le |pour le )?(\\d{1,2}|1er|premier) (${months.joinToString("|")})(?: (\\d{4}))?")?.let { m ->
            setDate(cal, now, dayNum(m.groupValues[1]), months.indexOf(m.groupValues[2]), m.groupValues[3])
            hasDate = true
        }
        take("(?:le |pour le )(\\d{1,2}|1er|premier)(?! ?(?:h|heures?|:|min))")?.let { m ->
            val d = dayNum(m.groupValues[1])
            if (d in 1..31) {
                val month = if (d < now.get(Calendar.DAY_OF_MONTH)) now.get(Calendar.MONTH) + 1 else now.get(Calendar.MONTH)
                setDate(cal, now, d, month, "")
                hasDate = true
            }
        }

        // Heure : « 14h30 », « 9 heures et demie », « 14:30 », « 3h de l'après-midi », « midi »
        val prefix = "(?:(?:a|vers|pour|des|des|de) )?"
        val timeMatch = take(
            "$prefix$NUM ?(?:h|heures?|:) ?(\\d{2})?(?: (et demie?|et quart|moins le quart))?" +
                "(?: (du matin|de l'apres[- ]midi|de l apres[- ]midi|du soir|de la nuit))?"
        )
        if (timeMatch != null) {
            var h = num(timeMatch.groupValues[1])
            var min = timeMatch.groupValues[2].toIntOrNull() ?: 0
            when (timeMatch.groupValues[3]) {
                "et demi", "et demie" -> min = 30
                "et quart" -> min = 15
                "moins le quart" -> { h -= 1; min = 45 }
            }
            val part = timeMatch.groupValues[4]
            val pm = part.contains("apres") || part.contains("soir") || hintHour >= 15
            if (pm && h in 1..11) h += 12
            else if (part.isEmpty() && hintHour < 0 && h in 1..7) h += 12 // « à 3h » : plutôt l'après-midi
            if (h in 0..23 && min in 0..59) {
                cal.set(Calendar.HOUR_OF_DAY, h)
                cal.set(Calendar.MINUTE, min)
                hasTime = true
            }
        } else {
            take("${prefix}midi(?: et demie?)?")?.let { m ->
                cal.set(Calendar.HOUR_OF_DAY, 12); cal.set(Calendar.MINUTE, if (m.value.contains("demi")) 30 else 0)
                hasTime = true
            } ?: take("${prefix}minuit")?.let {
                cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0)
                if (!hasDate) cal.add(Calendar.DAY_OF_YEAR, 1)
                hasTime = true
            }
        }

        if (!hasTime && hintHour >= 0) {
            cal.set(Calendar.HOUR_OF_DAY, hintHour)
            cal.set(Calendar.MINUTE, 0)
            hasTime = true
        }
        // Heure seule déjà passée : on prend le lendemain.
        if (hasTime && !hasDate && cal.before(now)) cal.add(Calendar.DAY_OF_YEAR, 1)

        val rest = StringBuilder()
        for (i in text.indices) if (!mask[i]) rest.append(text[i])
        val cleaned = rest.toString().replace(Regex("\\s+"), " ").trim()
        return ParsedWhen(cal, hasDate, hasTime, duration, span, cleaned)
    }

    private fun partOfDay(s: String): Int = when {
        s.isEmpty() -> -1
        s == "matin" -> 9
        s == "midi" -> 12
        s.startsWith("apres") -> 15
        s == "soir" -> 19
        else -> -1
    }

    private fun dayNum(s: String): Int = if (s == "1er" || s == "premier") 1 else s.toIntOrNull() ?: 1

    private fun setDate(cal: Calendar, now: Calendar, day: Int, month: Int, year: String) {
        cal.set(Calendar.DAY_OF_MONTH, 1)
        if (year.isNotEmpty()) {
            val y = year.toInt()
            cal.set(Calendar.YEAR, if (y < 100) 2000 + y else y)
        } else {
            cal.set(Calendar.YEAR, now.get(Calendar.YEAR))
        }
        cal.set(Calendar.MONTH, month)
        cal.set(Calendar.DAY_OF_MONTH, day.coerceIn(1, cal.getActualMaximum(Calendar.DAY_OF_MONTH)))
        if (year.isEmpty()) {
            val endOfDay = (cal.clone() as Calendar).apply { set(Calendar.HOUR_OF_DAY, 23) }
            if (endOfDay.before(now)) cal.add(Calendar.YEAR, 1)
        }
    }
}
