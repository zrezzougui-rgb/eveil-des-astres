package com.zrezzougui.orbite.launcher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.drawable.Drawable
import com.zrezzougui.orbite.normalize

data class AppEntry(
    val label: String,
    val packageName: String,
    val activityName: String,
    val icon: Drawable,
    val folder: String,
) {
    /** Copie de l'icône : un même Drawable ne doit pas être partagé entre plusieurs vues. */
    fun iconCopy(): Drawable = icon.constantState?.newDrawable()?.mutate() ?: icon

    fun launchIntent(): Intent = Intent(Intent.ACTION_MAIN)
        .addCategory(Intent.CATEGORY_LAUNCHER)
        .setComponent(ComponentName(packageName, activityName))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
}

/**
 * Range chaque appli dans un dossier nommé.
 *
 * Ordre de décision (même principe que les lanceurs libres KISS / Olauncher / Kvaesitso,
 * qui s'appuient sur les métadonnées Android plutôt que sur un service en ligne) :
 *  1. choix manuel de l'utilisateur (appui long → Déplacer vers…) ;
 *  2. mots-clés sur le nom du paquet et le nom affiché ;
 *  3. catégorie déclarée par le développeur (ApplicationInfo.category, Android 8+) ;
 *  4. appli système → Outils & Système ; sinon Autres.
 */
object AppCategorizer {

    const val OTHER = "Autres"
    const val TOOLS = "Outils & Système"

    /** Ordre important : la première règle qui correspond l'emporte. */
    private val rules: List<Pair<String, List<String>>> = listOf(
        "Messagerie" to listOf(
            "whatsapp", "telegram", "signal", "messenger", "messaging", "mms", "sms", "thoughtcrime",
            "element", "viber", "wechat", "skype", "olvid", "threema", "messages",
        ),
        "Réseaux sociaux" to listOf(
            "facebook", "instagram", "twitter", "tiktok", "musically", "snapchat", "linkedin", "reddit",
            "pinterest", "mastodon", "bluesky", "discord", "bereal", "tumblr", "threads", "social",
        ),
        "Jeux" to listOf(
            "game", "games", "jeu", "supercell", "rovio", "gameloft", "ubisoft", "epicgames", "mojang",
            "minecraft", "roblox", "chess", "echecs", "puzzle", "candy", "clash", "pokemon", "nintendo",
            "playrix", "zynga", "miniclip", "solitaire", "sudoku", "voodoo", "ketchapp",
        ),
        "Musique & Audio" to listOf(
            "spotify", "deezer", "music", "musique", "podcast", "soundcloud", "audio", "radio", "shazam",
            "tidal", "qobuz", "audible", "equalizer",
        ),
        "Vidéo & TV" to listOf(
            "youtube", "netflix", "avod", "primevideo", "disney", "twitch", "canal", "molotov",
            "crunchyroll", "vlc", "video", "player", "stream", "francetv", "tf1", "tv.arte", "plexapp",
            "capcut", "kodi",
        ),
        "Photo" to listOf(
            "camera", "gallery", "galerie", "photo", "snapseed", "lightroom", "picsart", "vsco",
            "instax", "lens", "selfie",
        ),
        "Navigation & Voyage" to listOf(
            "maps", "waze", "uber", "bolt", "sncf", "ratp", "citymapper", "airbnb", "booking", "blablacar",
            "transit", "trainline", "navigation", "gps", "flight", "tripadvisor", "transport", "osmand",
            "organicmaps", "taxi", "heetch", "parking", "airfrance", "ryanair", "easyjet",
        ),
        "Achats" to listOf(
            "amazon.mshop", "shopping", "leboncoin", "vinted", "ebay", "aliexpress", "shein", "temu",
            "cdiscount", "fnac", "ikea", "lidl", "carrefour", "auchan", "leclerc", "decathlon", "zalando",
            "wish", "shop", "boutique", "intermarche", "deliveroo", "ubereats", "eats", "justeat",
            "mcdonalds", "dominos",
        ),
        "Banque & Finance" to listOf(
            "bank", "banque", "paypal", "lydia", "revolut", "boursorama", "credit", "caisse",
            "societegenerale", "bnp", "lcl", "n26", "wallet", "crypto", "binance", "sumeria",
            "impots", "finance", "bourse", "labanquepostale", "fortuneo", "hellobank", "qonto",
            "coinbase", "trade",
        ),
        "Santé & Sport" to listOf(
            "fit", "health", "sante", "ameli", "doctolib", "strava", "sport", "workout", "running",
            "yoga", "sleep", "meditat", "headspace", "garmin", "fitbit", "pedometer", "podometre",
            "nike", "adidas", "pharma", "mutuelle",
        ),
        "Éducation" to listOf(
            "duolingo", "learn", "school", "education", "ecole", "pronote", "babbel", "khan", "quizlet",
            "dictionnaire", "dictionary", "dico", "cours", "anki", "brilliant", "pronote",
        ),
        "Actualités & Lecture" to listOf(
            "news", "actu", "lemonde", "figaro", "liberation", "20minutes", "ouestfrance", "franceinfo",
            "kindle", "book", "livre", "reader", "lecture", "wikipedia", "rss", "comics", "manga",
            "webtoon", "medium", "presse", "journal", "magazine", "feedly",
        ),
        "Productivité" to listOf(
            "office", "docs", "sheets", "slides", "drive", "gmail", "mail", "outlook", "calendar",
            "agenda", "notes", "keep", "notion", "todo", "tasks", "trello", "slack", "teams", "zoom",
            "dropbox", "pdf", "word", "excel", "powerpoint", "evernote", "onenote", "scanner",
            "email", "meet", "webex", "onedrive", "authenticator",
        ),
        TOOLS to listOf(
            "settings", "parametres", "launcher", "calculator", "calculatrice", "calc", "clock", "horloge",
            "files", "fichiers", "filemanager", "contacts", "dialer", "phone", "telephone", "browser",
            "chrome", "firefox", "brave", "opera", "duckduckgo", "vpn", "keyboard", "clavier",
            "translate", "traduc", "weather", "meteo", "flashlight", "torch", "lampe", "qr", "barcode",
            "security", "antivirus", "cleaner", "backup", "vending", "fdroid", "store", "recorder",
            "dictaphone", "compass", "boussole", "assistant", "google.android.googlequicksearchbox",
        ),
    )

    val defaultFolders: List<String> = rules.map { it.first } + OTHER

    fun categorize(label: String, packageName: String, info: ApplicationInfo?): String {
        val hay = normalize("$packageName $label")
        for ((folder, words) in rules) {
            if (words.any { hay.contains(it) }) return folder
        }
        if (info != null) {
            when (info.category) {
                ApplicationInfo.CATEGORY_GAME -> return "Jeux"
                ApplicationInfo.CATEGORY_AUDIO -> return "Musique & Audio"
                ApplicationInfo.CATEGORY_VIDEO -> return "Vidéo & TV"
                ApplicationInfo.CATEGORY_IMAGE -> return "Photo"
                ApplicationInfo.CATEGORY_SOCIAL -> return "Réseaux sociaux"
                ApplicationInfo.CATEGORY_NEWS -> return "Actualités & Lecture"
                ApplicationInfo.CATEGORY_MAPS -> return "Navigation & Voyage"
                ApplicationInfo.CATEGORY_PRODUCTIVITY -> return "Productivité"
                ApplicationInfo.CATEGORY_ACCESSIBILITY -> return TOOLS
            }
            @Suppress("DEPRECATION")
            if (info.flags and ApplicationInfo.FLAG_IS_GAME != 0) return "Jeux"
            if (info.flags and ApplicationInfo.FLAG_SYSTEM != 0) return TOOLS
        }
        return OTHER
    }
}

/** Charge les applis installées et mémorise les choix de l'utilisateur. */
class AppRepository(private val context: Context) {

    private val prefs = context.getSharedPreferences("dossiers", Context.MODE_PRIVATE)

    fun load(): List<AppEntry> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        val activities = pm.queryIntentActivities(intent, 0)
        return activities
            .filter { it.activityInfo.packageName != context.packageName }
            .map { ri ->
                val ai = ri.activityInfo
                val label = ri.loadLabel(pm)?.toString()?.trim().orEmpty().ifEmpty { ai.packageName }
                AppEntry(
                    label = label,
                    packageName = ai.packageName,
                    activityName = ai.name,
                    icon = ri.loadIcon(pm),
                    folder = folderFor(label, ai.packageName, ai.applicationInfo),
                )
            }
            .sortedBy { normalize(it.label) }
    }

    private fun folderFor(label: String, pkg: String, info: ApplicationInfo?): String {
        prefs.getString("app:$pkg", null)?.let { return it }
        val auto = AppCategorizer.categorize(label, pkg, info)
        return prefs.getString("renomme:$auto", null) ?: auto
    }

    fun moveApp(pkg: String, folder: String) {
        prefs.edit().putString("app:$pkg", folder.trim()).apply()
    }

    /** Renomme un dossier : les applis déjà rangées et les futures applis de cette catégorie suivent. */
    fun renameFolder(old: String, new: String, apps: List<AppEntry>) {
        val name = new.trim()
        if (name.isEmpty() || name == old) return
        val e = prefs.edit()
        apps.filter { it.folder == old }.forEach { e.putString("app:${it.packageName}", name) }
        AppCategorizer.defaultFolders.forEach { auto ->
            val current = prefs.getString("renomme:$auto", null) ?: auto
            if (current == old) e.putString("renomme:$auto", name)
        }
        e.apply()
    }

    fun resetAll() {
        prefs.edit().clear().apply()
    }

    /** Ordre d'affichage : dossiers par défaut d'abord, puis dossiers créés, « Autres » en dernier. */
    fun orderedFolders(apps: List<AppEntry>): List<Pair<String, List<AppEntry>>> {
        val grouped = apps.groupBy { it.folder }
        val known = AppCategorizer.defaultFolders.map { prefs.getString("renomme:$it", null) ?: it }
        return grouped.entries
            .sortedWith(compareBy({ it.key == AppCategorizer.OTHER }, {
                val i = known.indexOf(it.key); if (i < 0) known.size else i
            }, { it.key }))
            .map { it.key to it.value }
    }
}
