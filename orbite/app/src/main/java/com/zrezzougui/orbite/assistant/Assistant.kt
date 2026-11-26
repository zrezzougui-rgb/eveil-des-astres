package com.zrezzougui.orbite.assistant

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.Settings
import com.zrezzougui.orbite.camera.CameraActivity
import com.zrezzougui.orbite.normalize
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

data class Action(val label: String, val intent: Intent)

data class Reply(
    val text: String,
    val actions: List<Action> = emptyList(),
    /** Permission Android à demander avant de relancer la même question. */
    val needPermission: String? = null,
)

/**
 * Assistant à règles : chaque « compétence » reconnaît des mots-clés et répond.
 * Aucun modèle de langage, aucun jeton consommé : seules la météo et la recherche
 * interrogent des services web gratuits et sans clé (Open-Meteo, Wikipédia, DuckDuckGo).
 * Principe inspiré de l'assistant libre Dicio (compétences + correspondance de phrases).
 */
class Assistant(private val ctx: Context) {

    private val prefs = ctx.getSharedPreferences("assistant", Context.MODE_PRIVATE)
    private val apps = AppSkill(ctx)

    fun answer(raw: String): Reply {
        val q = raw.trim().trimEnd('?', '!', '.', ' ')
        val n = normalize(q)
        if (n.isEmpty() || n.matches(Regex("(aide|help|que sais[- ]tu faire|que peux[- ]tu faire|bonjour|salut|coucou|hello)( .*)?"))) {
            return help(n.startsWith("bonjour") || n.startsWith("salut") || n.startsWith("coucou"))
        }
        return try {
            when {
                n.contains("ma ville est") || n.contains("j'habite a") || n.contains("j habite a") -> saveCity(q, n)
                has(n, "quelle heure", "l'heure", "heure est-il", "heure est il") -> time()
                has(n, "quel jour", "quelle date", "on est le", "date d'aujourd") -> date()
                has(n, "minuteur", "chrono", "timer", "compte a rebours") -> timer(n)
                has(n, "reveille", "alarme", "reveil ") || n.endsWith("reveil") -> alarm(q)
                isAgendaList(n) -> listEvents(q)
                isAppointment(n) -> appointment(q, n)
                apps.matches(n) -> apps.answer(q, n)
                isWeather(n) -> weather(q)
                has(n, "camera", "appareil photo") || n.matches(Regex(".*(prends|prendre|fais|faire) une photo.*")) -> camera()
                isCalc(n) -> calc(n)
                n.matches(Regex("(ouvre|ouvrir|lance|lancer|demarre|demarrer|affiche) .+")) -> openApp(q)
                has(n, "itineraire", "comment aller", "emmene-moi", "emmene moi", "guide-moi", "guide moi", "trajet vers", "aller a ") -> route(q, n)
                n.matches(Regex("(appelle|appeler|compose|telephone a).*\\d.*")) -> dial(q)
                else -> search(q)
            }
        } catch (e: Exception) {
            Reply("Je n'ai pas pu obtenir la réponse (connexion internet ?).\n(${e.message ?: e.javaClass.simpleName})")
        }
    }

    // ---------------------------------------------------------------- aide

    private fun help(greet: Boolean) = Reply(
        (if (greet) "Bonjour ! " else "") +
            "Je fonctionne sans IA ni jeton. Essayez :\n" +
            "• « Météo à Lyon demain » ou « Va-t-il pleuvoir ce week-end ? »\n" +
            "• « Prends rendez-vous chez le dentiste mardi à 14h30 »\n" +
            "• « Qu'est-ce que j'ai demain ? »\n" +
            "• « Réveille-moi à 7h », « Minuteur 10 minutes »\n" +
            "• « Qui est Marie Curie ? », « 15 % de 80 »\n" +
            "• « Ouvre Spotify », « Itinéraire vers la gare »\n" +
            "• « Désinstalle Duck Survival », « Range Waze dans Voyage »\n" +
            "• « Quelles applis je n'utilise jamais ? », « Applis les plus lourdes »\n" +
            "• « Ouvre la caméra », « Ma ville est Marseille »"
    )

    private fun has(n: String, vararg words: String) = words.any { n.contains(it) }

    // ---------------------------------------------------------------- heure, date

    private fun time(): Reply {
        val c = Calendar.getInstance()
        return Reply("Il est ${c.get(Calendar.HOUR_OF_DAY)}h${"%02d".format(c.get(Calendar.MINUTE))}.")
    }

    private fun date(): Reply =
        Reply("Nous sommes le " + SimpleDateFormat("EEEE d MMMM yyyy", Locale.FRENCH).format(Calendar.getInstance().time) + ".")

    // ---------------------------------------------------------------- réveil, minuteur

    private fun timer(n: String): Reply {
        val m = Regex("(\\d+|une|un|deux|trois|quatre|cinq|six|sept|huit|neuf|dix|quinze|vingt|trente) ?(secondes?|s|minutes?|min|mn|heures?|h)\\b").find(n)
            ?: return Reply("Pour combien de temps ? Par exemple « minuteur 10 minutes ».")
        val v = FrenchDate.num(m.groupValues[1])
        val secs = when (m.groupValues[2].first()) {
            's' -> v
            'h' -> v * 3600
            else -> v * 60
        }
        val i = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, secs)
            .putExtra(AlarmClock.EXTRA_MESSAGE, "Minuteur Orbite")
            .putExtra(AlarmClock.EXTRA_SKIP_UI, false)
        return Reply("Minuteur de ${m.value} lancé.", listOf(Action("Ouvrir l'horloge", i)).autoStart())
    }

    private fun alarm(q: String): Reply {
        val w = FrenchDate.parse(q)
        if (!w.hasTime) return Reply("À quelle heure ? Par exemple « réveille-moi à 7h30 ».")
        val h = w.start.get(Calendar.HOUR_OF_DAY)
        val m = w.start.get(Calendar.MINUTE)
        val i = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, h)
            .putExtra(AlarmClock.EXTRA_MINUTES, m)
            .putExtra(AlarmClock.EXTRA_MESSAGE, "Réveil Orbite")
        return Reply("Réveil réglé à ${h}h${"%02d".format(m)}.", listOf(Action("Ouvrir l'horloge", i)).autoStart())
    }

    // ---------------------------------------------------------------- rendez-vous

    private val createVerbs = listOf(
        "prends", "prendre", "ajoute", "ajouter", "cree", "creer", "planifie", "planifier", "fixe", "fixer",
        "note", "noter", "programme un", "programme une", "mets", "mettre", "reserve", "reserver", "rappelle", "booke",
    )

    private fun isAppointment(n: String) =
        has(n, "rendez-vous", "rendez vous", "rdv", "reunion", "rappelle-moi", "rappelle moi", "evenement",
            "dans l'agenda", "dans mon agenda", "au calendrier", "dans le calendrier") ||
            (createVerbs.any { n.startsWith(it) } && has(n, "dentiste", "medecin", "docteur", "coiffeur", "kine"))

    private fun isAgendaList(n: String) =
        createVerbs.none { n.contains(it) } && has(
            n, "mes rendez-vous", "mes rendez vous", "mes rdv", "qu'ai-je", "qu ai je", "qu'est-ce que j'ai",
            "qu'est ce que j'ai", "quoi de prevu", "qu'y a-t-il", "mon agenda", "mon planning", "mon programme",
            "ai-je des", "ai je des", "suis-je libre", "suis je libre",
        )

    private val doctors = linkedMapOf(
        "dentiste" to "dentiste", "orthodont" to "orthodontiste", "ophtalmo" to "ophtalmologue",
        "dermato" to "dermatologue", "kine" to "masseur-kinesitherapeute", "osteo" to "osteopathe",
        "pediatre" to "pediatre", "gyneco" to "gynecologue-medical", "psychologue" to "psychologue",
        "orthophon" to "orthophoniste", "sage-femme" to "sage-femme", "cardio" to "cardiologue",
        "orl" to "oto-rhino-laryngologue-orl-chirurgien-de-la-face-et-du-cou",
        "generaliste" to "medecin-generaliste", "medecin" to "medecin-generaliste", "docteur" to "medecin-generaliste",
    )

    private fun appointment(q: String, n: String): Reply {
        val w = FrenchDate.parse(q)
        val (title, place) = appointmentTitle(w.rest)
        val start = w.start.clone() as Calendar
        if (!w.found) {
            start.add(Calendar.HOUR_OF_DAY, 1)
            start.set(Calendar.MINUTE, 0)
        }
        val minutes = w.durationMin ?: if (n.contains("rappel")) 15 else 60
        val end = (start.clone() as Calendar).apply { add(Calendar.MINUTE, minutes) }

        val insert = Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI)
            .putExtra(CalendarContract.Events.TITLE, title)
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, start.timeInMillis)
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, end.timeInMillis)
            .putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, w.hasDate && !w.hasTime)
            .putExtra(CalendarContract.Reminders.MINUTES, 30)
        if (place != null) insert.putExtra(CalendarContract.Events.EVENT_LOCATION, place)

        val actions = mutableListOf(Action("📅 Ajouter à l'agenda", insert))
        val doctor = doctors.entries.firstOrNull { n.contains(it.key) }?.value
        if (doctor != null) {
            val city = place ?: prefs.getString("ville", null)
            val url = "https://www.doctolib.fr/$doctor" + (city?.let { "/" + slug(it) } ?: "")
            actions += Action("🩺 Trouver un créneau sur Doctolib", web(url))
        } else if (has(n, "coiffeur", "restaurant", "resto", "garage", "esthetic", "barbier", "massage", "spa")) {
            actions += Action("🔎 Réserver en ligne", web("https://duckduckgo.com/?q=" + Http.enc("prendre rendez-vous en ligne $title ${place ?: ""}")))
        }

        val whenText = when {
            !w.found -> "sans date précise (à l'heure suivante, modifiable)"
            else -> w.describe()
        }
        val dur = if (minutes % 60 == 0) "${minutes / 60} h" else "$minutes min"
        return Reply(
            "Rendez-vous « $title » $whenText" + (if (w.hasTime) ", durée $dur" else "") +
                (place?.let { ", à $it" } ?: "") + ".\nTouchez « Ajouter à l'agenda » pour confirmer.",
            actions,
        )
    }

    /** « prends rendez-vous chez le dentiste à Lyon » → ("Dentiste", "Lyon"). */
    private fun appointmentTitle(rest: String): Pair<String, String?> {
        var place: String? = null
        var t = rest
        Regex("(?:^|\\s)(?:à|a|au|sur|en) ([A-ZÉÈÂÎ][\\p{L}'-]+(?: [A-ZÉÈÂÎ][\\p{L}'-]+)*)\\s*$").find(t)?.let {
            place = it.groupValues[1]
            t = t.removeRange(it.range)
        }
        val drop = Regex(
            "(?i)^(?:(?:peux-tu|peux tu|tu peux|merci de|stp|s'il te plait)\\s+)?" +
                "(?:(?:prends|prendre|ajoute|ajouter|crée|cree|créer|creer|planifie|planifier|fixe|fixer|note|noter|" +
                "programme|programmer|mets|mettre|réserve|reserve|réserver|reserver|booke|rappelle[- ]moi|rappelle moi)\\s+)?" +
                "(?:(?:moi|un|une|le|la|mon|ma)\\s+)?" +
                "(?:(?:rendez[- ]vous|rdv|réunion|reunion|évènement|événement|evenement|rappel)\\s*)?" +
                "(?:(?:dans|à|a|au)\\s+(?:mon\\s+|l'|le\\s+)?(?:agenda|calendrier)\\s*)?"
        )
        t = t.replace(drop, "").trim()
        t = t.replace(Regex("(?i)\\s*(?:dans|à|a|au)\\s+(?:mon\\s+|l'|le\\s+)?(?:agenda|calendrier)\\s*"), " ").trim()
        t = t.replace(Regex("(?i)^(?:chez|pour|de|d'|du|avec le|avec la)\\s*(?:le |la |les |l')?"), "").trim()
        t = t.replace(Regex("(?i)^(?:avec)\\s+"), "avec ").trim()
        val title = when {
            t.isEmpty() && normalize(rest).contains("reunion") -> "Réunion"
            t.isEmpty() && normalize(rest).contains("rappel") -> "Rappel"
            t.isEmpty() -> "Rendez-vous"
            t.startsWith("avec ") -> "RDV $t"
            else -> t.replaceFirstChar { it.uppercase() }
        }
        return title to place
    }

    private fun listEvents(q: String): Reply {
        if (ctx.checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            return Reply("Pour lire votre agenda, j'ai besoin de l'autorisation Calendrier.",
                needPermission = Manifest.permission.READ_CALENDAR)
        }
        val w = FrenchDate.parse(q)
        val begin = (w.start.clone() as Calendar).apply {
            if (w.hasDate || !w.hasTime) { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0) }
        }
        val end = (begin.clone() as Calendar).apply {
            if (w.hasDate || !w.hasTime) { add(Calendar.DAY_OF_YEAR, w.spanDays) } else add(Calendar.HOUR_OF_DAY, 1)
        }
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, begin.timeInMillis)
            ContentUris.appendId(it, end.timeInMillis)
        }.build()
        val lines = mutableListOf<String>()
        val dayFmt = SimpleDateFormat("EEE d", Locale.FRENCH)
        val hourFmt = SimpleDateFormat("HH'h'mm", Locale.FRENCH)
        ctx.contentResolver.query(
            uri,
            arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.ALL_DAY),
            null, null, CalendarContract.Instances.BEGIN + " ASC",
        )?.use { c ->
            while (c.moveToNext() && lines.size < 15) {
                val title = c.getString(0) ?: "(sans titre)"
                val t = java.util.Date(c.getLong(1))
                val allDay = c.getInt(2) == 1
                val prefix = (if (w.spanDays > 1) dayFmt.format(t) + " " else "") + if (allDay) "journée" else hourFmt.format(t)
                lines += "• $prefix — $title"
            }
        }
        val period = if (w.spanDays > 1) "cette période" else if (w.found) w.describe().substringBefore(" à") else "aujourd'hui"
        val open = Intent(Intent.ACTION_VIEW, CalendarContract.CONTENT_URI.buildUpon().appendPath("time")
            .also { ContentUris.appendId(it, begin.timeInMillis) }.build())
        return if (lines.isEmpty()) Reply("Rien de prévu pour $period.", listOf(Action("Ouvrir l'agenda", open)))
        else Reply("Au programme pour $period :\n" + lines.joinToString("\n"), listOf(Action("Ouvrir l'agenda", open)))
    }

    // ---------------------------------------------------------------- météo

    private fun isWeather(n: String) = has(
        n, "meteo", "quel temps", "temps fait", "temps va", "temps fera", "pleuvoir", "pleut", "pluie", "neige",
        "temperature", "fait-il chaud", "fait il chaud", "fait-il froid", "fait il froid", "parapluie",
        "previsions", "soleil", "orage", "vent ",
    )

    private val weatherStop = setOf(
        "quelle", "quel", "quels", "est", "la", "le", "les", "l'", "il", "elle", "fait", "fait-il", "fera", "fera-t-il",
        "va", "va-t-il", "t-il", "y", "aura", "aura-t-il", "pleuvoir", "pleut", "pleut-il", "pluie", "neige", "neiger",
        "temps", "meteo", "temperature", "previsions", "prevision", "ce", "cette", "matin", "soir", "dehors",
        "chaud", "froid", "faire", "besoin", "parapluie", "d'un", "de", "du", "des", "un", "une", "quoi", "comment",
        "donne", "donne-moi", "moi", "dis", "dis-moi", "ici", "a", "au", "aux", "sur", "pour", "en", "et", "il-y", "y-a-t-il",
        "soleil", "orage", "vent", "week-end", "weekend", "prendre", "dois-je", "faut-il", "il", "apres-midi", "s'il", "te",
        "plait", "stp", "donner", "savoir", "connaitre", "actuelle", "actuellement", "maintenant", "aujourd'hui",
        "semaine", "jours", "prochains", "va-t-il", "pleuvra", "pleuvra-t-il", "vais-je", "avoir", "j'ai", "quelles",
    )

    private fun weather(q: String): Reply {
        val w = FrenchDate.parse(q)
        val n = normalize(q)
        val weekend = n.contains("week-end") || n.contains("weekend")
        val cityTokens = w.rest.split(Regex("[\\s,?!.]+")).filter { it.isNotEmpty() && normalize(it) !in weatherStop }
        val city = cityTokens.joinToString(" ").trim()

        val place: Place = when {
            city.isNotEmpty() -> Weather.geocode(city) ?: return Reply("Je ne trouve pas la ville « $city ».")
            Locator.hasPermission(ctx) -> Locator.current(ctx) ?: savedPlace()
                ?: return Reply("Position introuvable. Dites « météo à Lyon » ou « ma ville est Lyon ».")
            else -> savedPlace() ?: return Reply(
                "Pour la météo locale, autorisez la position, ou dites « météo à Lyon ».",
                needPermission = Manifest.permission.ACCESS_COARSE_LOCATION,
            )
        }
        val f = Weather.forecast(place)
        val today = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
        val target = (w.start.clone() as Calendar).apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
        val offset = ((target.timeInMillis - today.timeInMillis + 3600_000) / 86_400_000L).toInt()
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val dayName = SimpleDateFormat("EEEE d", Locale.FRENCH)
        fun line(d: DayForecast): String {
            val label = runCatching { dayName.format(fmt.parse(d.date)!!) }.getOrDefault(d.date)
            val rain = if (d.rain >= 0) ", pluie ${d.rain} %" else ""
            return "${Weather.emoji(d.code)} $label : ${Weather.describe(d.code)}, ${d.tMin.toInt()}° / ${d.tMax.toInt()}°$rain"
        }
        val text = when {
            weekend -> {
                val days = f.days.filter {
                    val c = Calendar.getInstance().apply { time = fmt.parse(it.date)!! }
                    val dow = c.get(Calendar.DAY_OF_WEEK)
                    dow == Calendar.SATURDAY || dow == Calendar.SUNDAY
                }.take(2)
                "Météo du week-end à ${place.name} :\n" + days.joinToString("\n") { line(it) }
            }
            w.spanDays > 1 -> "Prévisions à ${place.name} :\n" + f.days.joinToString("\n") { line(it) }
            w.hasDate && offset in 1..6 -> {
                val d = f.days[offset]
                "${place.name}, ${line(d).substringAfter(' ')}" + umbrella(d)
            }
            w.hasDate && offset > 6 -> "Je n'ai les prévisions que sur 7 jours.\n" + f.days.joinToString("\n") { line(it) }
            else -> {
                val d = f.days.firstOrNull()
                "${Weather.emoji(f.code)} ${place.name} : ${f.temp.toInt()}° (ressenti ${f.feels.toInt()}°), " +
                    "${Weather.describe(f.code)}, vent ${f.wind.toInt()} km/h." +
                    (d?.let { "\nAujourd'hui : ${it.tMin.toInt()}° / ${it.tMax.toInt()}°" + (if (it.rain >= 0) ", pluie ${it.rain} %" else "") + umbrella(it) } ?: "")
            }
        }
        return Reply(text, listOf(Action("Prévisions détaillées", web("https://open-meteo.com/en/docs#latitude=${place.lat}&longitude=${place.lon}"))))
    }

    private fun umbrella(d: DayForecast) = when {
        d.rain >= 60 || d.code in 61..67 || d.code in 80..82 -> "\n☂️ Prenez un parapluie."
        d.rain in 30..59 -> "\n🌂 Un parapluie peut servir."
        else -> ""
    }

    private fun savedPlace(): Place? {
        val name = prefs.getString("ville", null) ?: return null
        val lat = prefs.getFloat("lat", Float.NaN)
        val lon = prefs.getFloat("lon", Float.NaN)
        return if (lat.isNaN()) Weather.geocode(name) else Place(name, lat.toDouble(), lon.toDouble())
    }

    private fun saveCity(q: String, n: String): Reply {
        val i = maxOf(n.indexOf("ma ville est").let { if (it < 0) -1 else it + 12 }, n.indexOf("habite a").let { if (it < 0) -1 else it + 8 })
        val city = q.substring(i).trim().trim('.', '!')
        val p = Weather.geocode(city) ?: return Reply("Je ne trouve pas « $city ».")
        prefs.edit().putString("ville", p.name).putFloat("lat", p.lat.toFloat()).putFloat("lon", p.lon.toFloat()).apply()
        return Reply("C'est noté : votre ville est ${p.name}. Je l'utiliserai pour la météo et les rendez-vous.")
    }

    // ---------------------------------------------------------------- caméra

    private fun camera() = Reply(
        "J'ouvre la caméra avec réglages manuels.",
        listOf(Action("Ouvrir la caméra", Intent(ctx, CameraActivity::class.java))).autoStart(),
    )

    // ---------------------------------------------------------------- calcul

    private fun isCalc(n: String) =
        has(n, "combien font", "combien fait", "calcule", "combien egal", "racine carree") ||
            n.matches(Regex("[\\d\\s.,()+\\-*/x×÷^%]+(=)?")) && n.any { it.isDigit() } && n.any { it in "+-*/x×÷^%" } ||
            n.matches(Regex(".*\\d+(?:[.,]\\d+)? ?% de \\d+.*"))

    private fun calc(n: String): Reply {
        var e = n.replace(Regex("combien (font|fait|egal|egale)|calcule[rz]?|ca fait|=|\\?"), " ")
        Regex("(\\d+(?:[.,]\\d+)?) ?% de (\\d+(?:[.,]\\d+)?)").find(e)?.let {
            val r = it.groupValues[1].replace(',', '.').toDouble() * it.groupValues[2].replace(',', '.').toDouble() / 100
            return Reply("${it.value.trim()} = ${fmtNum(r)}")
        }
        Regex("racine carree (?:de )?(\\d+(?:[.,]\\d+)?)").find(e)?.let {
            return Reply("√${it.groupValues[1]} = ${fmtNum(Math.sqrt(it.groupValues[1].replace(',', '.').toDouble()))}")
        }
        e = e.replace("multiplie par", "*").replace("fois", "*").replace("divise par", "/").replace("plus", "+")
            .replace("moins", "-").replace("puissance", "^").replace("au carre", "^2").replace('×', '*')
            .replace('÷', '/').replace(Regex("(?<=\\d)\\s*x\\s*(?=\\d)"), "*").replace(',', '.')
        val expr = e.filter { it.isDigit() || it in ".+-*/^()" }
        val v = Calc(expr).parse()
        return Reply("${expr.replace("*", " × ").replace("/", " ÷ ").replace("+", " + ").replace("-", " − ")} = ${fmtNum(v)}")
    }

    private fun fmtNum(v: Double): String =
        if (v == Math.floor(v) && Math.abs(v) < 1e15) v.toLong().toString()
        else "%.4f".format(Locale.FRENCH, v).trimEnd('0').trimEnd(',')

    private class Calc(val s: String) {
        var i = 0
        fun parse(): Double = expr().also { if (i < s.length) throw IllegalArgumentException("calcul non compris") }
        fun expr(): Double {
            var v = term()
            while (i < s.length && s[i] in "+-") { val op = s[i++]; val r = term(); v = if (op == '+') v + r else v - r }
            return v
        }
        fun term(): Double {
            var v = factor()
            while (i < s.length && s[i] in "*/") { val op = s[i++]; val r = factor(); v = if (op == '*') v * r else v / r }
            return v
        }
        fun factor(): Double {
            val b = unary()
            if (i < s.length && s[i] == '^') { i++; return Math.pow(b, factor()) }
            return b
        }
        fun unary(): Double {
            if (i < s.length && s[i] == '-') { i++; return -unary() }
            if (i < s.length && s[i] == '(') { i++; val v = expr(); if (i < s.length && s[i] == ')') i++; return v }
            val st = i
            while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
            if (st == i) throw IllegalArgumentException("calcul non compris")
            return s.substring(st, i).toDouble()
        }
    }

    // ---------------------------------------------------------------- applis

    private fun openApp(q: String): Reply {
        val target = normalize(q).replace(Regex("^(ouvre|ouvrir|lance|lancer|demarre|demarrer|affiche)\\s+(moi\\s+)?(l'appli(cation)?\\s+|l'app\\s+|le\\s+|la\\s+|l')?"), "").trim()
        if (target.isEmpty()) return Reply("Quelle appli dois-je ouvrir ?")
        val pm = ctx.packageManager
        @Suppress("DEPRECATION")
        val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        val best = apps.map { it to normalize(it.loadLabel(pm).toString()) }
            .mapNotNull { (ri, label) ->
                val score = when {
                    label == target -> 3
                    label.startsWith(target) -> 2
                    label.contains(target) || target.contains(label) && label.length >= 3 -> 1
                    else -> 0
                }
                if (score > 0) Triple(ri, label, score) else null
            }
            .maxByOrNull { it.third }
        if (target in listOf("parametres", "reglages")) {
            return Reply("J'ouvre les réglages.", listOf(Action("Réglages", Intent(Settings.ACTION_SETTINGS))).autoStart())
        }
        best ?: return Reply("Je ne trouve pas d'appli « $target ».",
            listOf(Action("Chercher sur le Play Store", web("https://play.google.com/store/search?c=apps&q=" + Http.enc(target)))))
        val ai = best.first.activityInfo
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            .setClassName(ai.packageName, ai.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return Reply("J'ouvre ${best.first.loadLabel(pm)}.", listOf(Action("Ouvrir", i)).autoStart())
    }

    private fun route(q: String, n: String): Reply {
        val m = Regex("(?:vers|jusqu'a|pour aller a|aller a|aller au|aller chez|pour|a) (.+)$").find(n)
        val dest = m?.let { q.substring(it.groups[1]!!.range.first) }?.trim() ?: return Reply("Où voulez-vous aller ?")
        val i = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(dest)))
        return Reply("Itinéraire vers « $dest ».", listOf(Action("🗺️ Ouvrir la carte", i)).autoStart())
    }

    private fun dial(q: String): Reply {
        val num = q.filter { it.isDigit() || it == '+' }
        return Reply("J'ouvre le téléphone avec le $num.", listOf(Action("Appeler", Intent(Intent.ACTION_DIAL, Uri.parse("tel:$num")))).autoStart())
    }

    // ---------------------------------------------------------------- recherche web

    private fun search(q: String): Reply {
        val n = normalize(q)
        val prefix = Regex(
            "^(?:(?:dis[- ]moi|peux[- ]tu me dire|sais[- ]tu|explique[- ]moi|parle[- ]moi de|cherche|recherche|definition de|definis|c'est quoi|c est quoi|qu'est[- ]ce que|qu'est[- ]ce qu'|qu est ce que|qui est|qui etait|qui sont|qu'est-ce qu'un|quelle est|quel est|ou se trouve|ou est)\\s*)+" +
                "(?:(?:c'est|c est|un|une|le|la|les|l'|des|du|de|d')\\s*)*"
        ).find(n)
        val subject = (if (prefix != null) q.substring(prefix.range.last + 1) else q).trim().ifEmpty { q }
        val webAction = Action("🔎 Rechercher sur le web", web("https://duckduckgo.com/?q=" + Http.enc(q)))

        // 1) Réponse instantanée DuckDuckGo
        runCatching {
            val j = JSONObject(Http.get("https://api.duckduckgo.com/?format=json&no_html=1&skip_disambig=1&kl=fr-fr&q=" + Http.enc(subject)))
            val answer = j.optString("Answer").ifEmpty { j.optString("AbstractText") }
            if (answer.isNotEmpty() && normalize(answer).let { a -> !a.contains("the ") || a.contains(" le ") }) {
                val src = j.optString("AbstractURL")
                return Reply(short(answer), if (src.isNotEmpty()) listOf(Action("Source", web(src)), webAction) else listOf(webAction))
            }
        }
        // 2) Résumé Wikipédia en français
        runCatching {
            val s = JSONObject(Http.get("https://fr.wikipedia.org/w/api.php?action=query&list=search&format=json&srlimit=1&srsearch=" + Http.enc(subject)))
            val hits = s.getJSONObject("query").getJSONArray("search")
            if (hits.length() > 0) {
                val title = hits.getJSONObject(0).getString("title")
                val sum = JSONObject(Http.get("https://fr.wikipedia.org/api/rest_v1/page/summary/" + Http.enc(title.replace(' ', '_')).replace("+", "%20")))
                val extract = sum.optString("extract")
                if (extract.isNotEmpty()) {
                    val page = "https://fr.wikipedia.org/wiki/" + Uri.encode(title.replace(' ', '_'))
                    return Reply("$title — ${short(extract)}", listOf(Action("Lire sur Wikipédia", web(page)), webAction))
                }
            }
        }
        return Reply("Je n'ai pas de réponse directe pour « $subject ».", listOf(webAction))
    }

    private fun short(s: String): String {
        val sentences = s.split(Regex("(?<=[.!?])\\s+"))
        var out = ""
        for (x in sentences) { if (out.length + x.length > 420 && out.isNotEmpty()) break; out += (if (out.isEmpty()) "" else " ") + x }
        return out
    }

    // ---------------------------------------------------------------- utilitaires

    private fun web(url: String) = Intent(Intent.ACTION_VIEW, Uri.parse(url))

    private fun slug(s: String) = normalize(s).substringBefore(',').trim().replace(Regex("[^a-z0-9]+"), "-").trim('-')

    /** Marque la première action pour qu'elle se lance tout de suite. */
    private fun List<Action>.autoStart(): List<Action> = mapIndexed { i, a ->
        if (i == 0) a.copy(intent = a.intent.putExtra(AUTO_START, true)) else a
    }

    companion object {
        const val AUTO_START = "orbite.auto_start"
    }
}
