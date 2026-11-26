package com.zrezzougui.orbite.assistant

import android.content.Context
import android.content.Intent
import com.zrezzougui.orbite.launcher.AppActions
import com.zrezzougui.orbite.launcher.AppCategorizer
import com.zrezzougui.orbite.launcher.AppEntry
import com.zrezzougui.orbite.launcher.AppRepository
import com.zrezzougui.orbite.launcher.AppStats
import com.zrezzougui.orbite.launcher.AppsActivity
import com.zrezzougui.orbite.normalize

/**
 * Compétence « gérer mes applis » : désinstaller, ranger, infos, mises à jour,
 * applis inutilisées ou lourdes, contenu d'un dossier. Tout se fait sur le téléphone, sans internet.
 */
class AppSkill(private val ctx: Context) {

    private val repo by lazy { AppRepository(ctx) }

    private val uninstall = Regex("^(?:peux[- ]tu |tu peux )?(?:desinstalle[rz]?|supprime[rz]?|enleve[rz]?|efface[rz]?|vire[rz]?) (?:moi )?(?:l'appli(?:cation)? |l'app |l'application )?(.+)$")
    private val move = Regex("^(?:range[rz]?|deplace[rz]?|mets|mettre|classe[rz]?) (?:l'appli(?:cation)? |l'app )?(.+?) (?:dans|en|vers) (?:le dossier |la categorie |les |le |la |l')?(.+)$")
    private val details = Regex("^(?:ouvre |affiche |montre )?(?:les )?(?:infos?|informations|parametres|reglages|autorisations|permissions|stockage|notifications) (?:de |d'|sur |du |pour )(?:l'appli(?:cation)? |l'app |l')?(.+)$")
    private val update = Regex("^(?:mets a jour|mettre a jour|met a jour|actualise|mise a jour (?:de |d'))(?: l'appli(?:cation)?| l'app)? (.+)$")

    fun matches(n: String): Boolean =
        uninstall.matches(n) && !n.contains("rendez") && !n.contains("rdv") ||
            move.matches(n) && !n.contains("agenda") && !n.contains("calendrier") ||
            details.matches(n) || update.matches(n) ||
            isUnused(n) || isHeavy(n) || isList(n) || isManage(n)

    private fun isUnused(n: String) = Regex("n'?utilise (?:jamais|pas|plus|presque jamais)|inutilise|pas utilise|ne sers? (?:a rien|jamais)|ne sert (?:a rien|jamais)|dont je ne me sers").containsMatchIn(n)
    private fun isHeavy(n: String) = Regex("(?:plus lourde|plus grosse|prennent le plus de place|prend le plus de place|plus d'espace|plus volumineuse|liberer de la place|faire de la place)").containsMatchIn(n)
    private fun isList(n: String) = Regex("(?:combien d'app|combien j'ai d'app|quelles? (?:sont )?(?:mes )?app|liste (?:mes |les )?app|montre[- ](?:moi )?(?:mes |les )?app|quelles applis|mes jeux|quels jeux|qu'y a-t-il dans|qu'est-ce qu'il y a dans|contenu du dossier|applis (?:du|de) (?:dossier )?)").containsMatchIn(n)
    private fun isManage(n: String) = Regex("(?:gere[rz]? (?:mes |les )?app|gestion (?:des |de mes )?app|mes applis|mes applications|toutes (?:mes|les) applis)").containsMatchIn(n)

    fun answer(q: String, n: String): Reply {
        uninstall.matchEntire(n)?.let { m -> if (!n.contains("rendez") && !n.contains("rdv")) return doUninstall(m.groupValues[1]) }
        move.matchEntire(n)?.let { m -> if (!n.contains("agenda")) return doMove(q, m) }
        details.matchEntire(n)?.let { return doDetails(it.groupValues[1]) }
        update.matchEntire(n)?.let { return doUpdate(it.groupValues[1]) }
        if (isUnused(n)) return unused()
        if (isHeavy(n)) return heavy()
        if (isList(n)) return list(n)
        return manage()
    }

    // ---------------------------------------------------------------- actions

    private fun doUninstall(target: String): Reply {
        val app = find(target) ?: return notFound(target)
        return Reply(
            "Je lance la désinstallation de ${app.label}. Android vous demande de confirmer.",
            listOf(Action("🗑️ Désinstaller ${app.label}", AppActions.uninstallIntent(app.packageName)), manageAction()).autoStart(),
        )
    }

    private fun doMove(q: String, m: MatchResult): Reply {
        val app = find(m.groupValues[1]) ?: return notFound(m.groupValues[1])
        val wanted = m.groupValues[2].trim()
        val all = repo.load(withIcons = false)
        val known = (repo.orderedFolders(all).map { it.first } + AppCategorizer.defaultFolders).distinct()
        // Dossier existant le plus proche, sinon nouveau dossier au nom tel que dit.
        val target = matchFolder(wanted, known) ?: run {
            val start = normalize(q).lastIndexOf(wanted)
            (if (start >= 0) q.substring(start, start + wanted.length) else wanted).trim().replaceFirstChar { it.uppercase() }
        }
        repo.moveApp(app.packageName, target)
        val isNew = target !in known
        return Reply(
            "C'est fait : ${app.label} est rangée dans « $target »" + (if (isNew) " (nouveau dossier)." else ".") +
                "\nPour l'annuler : « range ${app.label} dans ${app.folder} ».",
            listOf(manageAction(folder = target)),
        )
    }

    private fun doDetails(target: String): Reply {
        val app = find(target) ?: return notFound(target)
        return Reply(
            "J'ouvre la fiche de ${app.label} : autorisations, notifications, stockage, cache.",
            listOf(Action("Infos de ${app.label}", AppActions.detailsIntent(app.packageName))).autoStart(),
        )
    }

    private fun doUpdate(target: String): Reply {
        val app = find(target) ?: return notFound(target)
        return Reply(
            "J'ouvre ${app.label} dans le Play Store : touchez « Mettre à jour » si le bouton apparaît.",
            listOf(Action("Play Store", AppActions.storeIntent(app.packageName))).autoStart(),
        )
    }

    private fun unused(): Reply {
        if (!AppStats.hasUsageAccess(ctx)) {
            return Reply(
                "Pour savoir quelles applis vous n'utilisez pas, autorisez Orbite dans « Accès aux données d'utilisation » (tout reste sur le téléphone), puis reposez la question.",
                listOf(Action("Autoriser", AppStats.usageAccessIntent())),
            )
        }
        val apps = repo.load(withIcons = false)
        val info = AppStats.collect(ctx, apps)
        val limit = System.currentTimeMillis() - AppStats.WINDOW_DAYS * 86_400_000L
        val unused = apps.filter { (info[it.packageName]?.lastUsed ?: 0L) < limit && (info[it.packageName]?.installedAt ?: 0L) < limit }
            .sortedByDescending { info[it.packageName]?.sizeBytes ?: 0 }
        if (unused.isEmpty()) return Reply("Toutes vos applis ont servi ces ${AppStats.WINDOW_DAYS} derniers jours.")
        val total = unused.sumOf { info[it.packageName]?.sizeBytes ?: 0 }
        val lines = unused.take(12).joinToString("\n") { "• ${it.label} — ${AppStats.size(info[it.packageName]?.sizeBytes ?: 0)}" }
        return Reply(
            "${unused.size} applis pas ouvertes depuis ${AppStats.WINDOW_DAYS} jours (${AppStats.size(total)}) :\n$lines" +
                (if (unused.size > 12) "\n…et ${unused.size - 12} autres." else "") +
                "\nDites « désinstalle <nom> » pour en retirer une.",
            listOf(manageAction(sort = AppsActivity.Sort.UNUSED)),
        )
    }

    private fun heavy(): Reply {
        val apps = repo.load(withIcons = false)
        val info = AppStats.collect(ctx, apps)
        val top = apps.sortedByDescending { info[it.packageName]?.sizeBytes ?: 0 }.take(10)
        val lines = top.joinToString("\n") { "• ${it.label} — ${AppStats.size(info[it.packageName]?.sizeBytes ?: 0)}" }
        return Reply(
            "Les applis les plus lourdes (taille installée, sans leurs données) :\n$lines",
            listOf(manageAction(sort = AppsActivity.Sort.SIZE)),
        )
    }

    private fun list(n: String): Reply {
        val apps = repo.load(withIcons = false)
        val folders = repo.orderedFolders(apps)
        val f = matchFolder(n, folders.map { it.first })
        if (f != null) {
            val inside = folders.first { it.first == f }.second
            return Reply("Dossier « $f » (${inside.size}) :\n" + inside.joinToString(", ") { it.label }, listOf(manageAction(folder = f)))
        }
        return Reply(
            "Vous avez ${apps.size} applis :\n" + folders.joinToString("\n") { "• ${it.first} : ${it.second.size}" },
            listOf(manageAction()),
        )
    }

    private fun manage() = Reply("J'ouvre « Mes applis » : tri par taille, date ou usage, et gestion de chaque appli.", listOf(manageAction()).autoStart())

    // ---------------------------------------------------------------- recherche

    private fun find(target: String): AppEntry? {
        val t = normalize(target).trim().trimEnd('.', '!', '?')
            .removePrefix("le ").removePrefix("la ").removePrefix("les ").removePrefix("l'").trim()
        if (t.isEmpty()) return null
        return repo.load(withIcons = false)
            .map { it to normalize(it.label) }
            .mapNotNull { (a, label) ->
                val score = when {
                    label == t -> 4
                    label.startsWith(t) -> 3
                    label.split(' ', '-', '.').any { it == t } -> 2
                    label.contains(t) || (t.contains(label) && label.length >= 3) -> 1
                    // Nom affiché dans une autre écriture (ex. 淘宝) : on cherche aussi dans le nom du paquet.
                    t.length >= 4 && normalize(a.packageName).contains(t.replace(" ", "")) -> 1
                    else -> 0
                }
                if (score > 0) a to score else null
            }
            .maxWithOrNull(compareBy({ it.second }, { -it.first.label.length }))?.first
    }

    private val synonyms = mapOf(
        "jeu" to "Jeux", "jeux" to "Jeux", "reseau" to "Réseaux sociaux", "reseaux" to "Réseaux sociaux",
        "social" to "Réseaux sociaux", "message" to "Messagerie", "messagerie" to "Messagerie", "shopping" to "Achats",
        "achat" to "Achats", "achats" to "Achats", "courses" to "Achats", "banque" to "Banque & Finance",
        "finance" to "Banque & Finance", "argent" to "Banque & Finance", "musique" to "Musique & Audio",
        "video" to "Vidéo & TV", "videos" to "Vidéo & TV", "tv" to "Vidéo & TV", "film" to "Vidéo & TV",
        "photo" to "Photo", "photos" to "Photo", "voyage" to "Navigation & Voyage", "transport" to "Navigation & Voyage",
        "gps" to "Navigation & Voyage", "sante" to "Santé & Sport", "medecine" to "Santé & Sport", "sport" to "Santé & Sport",
        "ecole" to "Éducation", "education" to "Éducation", "actu" to "Actualités & Lecture", "actualites" to "Actualités & Lecture",
        "lecture" to "Actualités & Lecture", "travail" to "Productivité", "productivite" to "Productivité",
        "outils" to "Outils & Système", "outil" to "Outils & Système", "systeme" to "Outils & Système", "autres" to "Autres",
    )

    /** Dossier dont le nom (ou un synonyme courant) apparaît dans la phrase. */
    private fun matchFolder(text: String, folders: List<String>): String? {
        val t = normalize(text)
        folders.firstOrNull { normalize(it) == t.trim() }?.let { return it }
        folders.firstOrNull { f -> normalize(f).split(Regex("[^a-z]+")).any { w -> w.length >= 4 && Regex("\\b$w\\b").containsMatchIn(t) } }
            ?.let { return it }
        for ((word, folder) in synonyms) {
            if (Regex("\\b$word\\b").containsMatchIn(t) && folder in folders) return folder
        }
        return null
    }

    private fun notFound(target: String) = Reply(
        "Je ne trouve pas d'appli « ${target.trim()} » sur ce téléphone.",
        listOf(manageAction()),
    )

    private fun manageAction(folder: String? = null, sort: AppsActivity.Sort? = null) = Action(
        "🗂️ Mes applis",
        Intent(ctx, AppsActivity::class.java).apply {
            folder?.let { putExtra(AppsActivity.EXTRA_FOLDER, it) }
            sort?.let { putExtra(AppsActivity.EXTRA_SORT, it.name) }
        },
    )

    private fun List<Action>.autoStart(): List<Action> = mapIndexed { i, a ->
        if (i == 0) a.copy(intent = a.intent.putExtra(Assistant.AUTO_START, true)) else a
    }
}
