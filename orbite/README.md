# Orbite

Écran d'accueil Android qui **range vos applis par genre dans des dossiers nommés**, avec un
**assistant sans IA** (aucun jeton consommé) et une **caméra à réglages manuels calibrable**.

## Installer sur Android

1. Ouvrez l'onglet **Releases** du dépôt, puis **Dernière version d'Orbite**.
2. Téléchargez **orbite.apk** depuis votre téléphone et ouvrez-le (autorisez l'installation depuis cette source la première fois).
3. Touchez le bouton accueil : Android propose de choisir Orbite comme écran d'accueil
   (sinon : menu **⋮ → Définir Orbite comme écran d'accueil**).

Chaque modification envoyée sur `main` reconstruit l'APK (onglet **Actions**). L'APK est signé
avec une clé de test fixe (`app/debug.keystore`), donc une nouvelle version s'installe par-dessus
l'ancienne sans perdre vos dossiers. Pour une publication sur le Play Store, il faudra une vraie clé privée.

## Fonctions

### Dossiers automatiques
- Les applis sont rangées dans **Messagerie, Réseaux sociaux, Jeux, Musique & Audio, Vidéo & TV, Photo,
  Navigation & Voyage, Achats, Banque & Finance, Santé & Sport, Éducation, Actualités & Lecture,
  Productivité, Outils & Système, Autres**.
- Classement 100 % hors ligne : mots-clés sur le nom de l'appli et du paquet, puis catégorie déclarée
  par le développeur (Android 8+), puis appli système.
- **Appui long sur une appli** → déplacer vers un autre dossier ou un nouveau dossier, infos, désinstaller.
- **Appui long sur un dossier** (ou toucher son titre une fois ouvert) → renommer. Les nouvelles applis du même genre suivent.
- La barre du bas cherche une appli en tapant ; Entrée envoie la phrase à l'assistant.

### Gérer ses applis
- **Mes applis** (bouton sur l'accueil ou menu ⋮) : toutes les applis avec leur dossier, leur taille, leur date
  d'installation et leur dernière utilisation. Tri par nom, poids, installation récente ou usage ; filtre par dossier.
  Chaque appli : ouvrir, ranger, infos/autorisations/stockage, mise à jour Play Store, désinstaller.
- La dernière utilisation demande l'autorisation « Accès aux données d'utilisation » (facultative, rien ne quitte le téléphone).
- **Ranger mon écran d'accueil** (bouton 🧹, menu ⋮ ou « range mon écran » à l'assistant), sans remplacer l'écran du fabricant :
  1. pose tous les dossiers d'Orbite d'affilée (Android confirme chacun), icônes mises à jour automatiquement ;
  2. liste à cocher des icônes devenues doublons à retirer de l'écran (l'appli reste installée) ;
  3. range les nouvelles applis dans le bon dossier (vérification environ toutes les heures) et peut prévenir par notification.
- **Dossiers sur l'écran d'accueil du téléphone** : sans remplacer l'écran du fabricant, Orbite peut y ajouter ses dossiers
  (appui long sur un dossier → « Mettre sur l'écran d'accueil du téléphone »). Android demande une confirmation par dossier.
  Une appli ne peut pas déplacer elle-même les icônes ou dossiers de l'écran du fabricant : c'est une limite d'Android.

### Jarvis
- **Interface** inspirée de la démo J.A.R.V.I.S de TechEnClair : fond noir, sphère de particules 3D animée (réagit à la voix,
  pulse quand Jarvis parle), panneau horloge (batterie · HH:MM:SS · mémoire, SYS_CLOCK, date, ONLINE),
  trois boutons ronds (parler, modules, « Hey Jarvis » on/off) et état « veille... ». Glisser vers le haut : panneau Modules
  (capteurs, actions rapides, dossiers, recherche). Tout est dessiné dans l'appli (aucune image).
- **« Hey Jarvis » mains libres** (Réglages Jarvis) : détection sur le téléphone avec
  [openWakeWord](https://github.com/dscripka/openWakeWord) (le moteur libre utilisé par Home Assistant) et TensorFlow Lite.
  Pipeline vérifié contre l'implémentation de référence (scores identiques). Sensibilité réglable, option « seulement en charge ».
  Rien n'est enregistré ni envoyé.
- **Réponses parlées** : toujours lues à voix haute, formule d'accueil, forme d'adresse (rien, monsieur, madame, prénom).
  Choix du moteur et de la voix : Orbite utilise en priorité [SherpaTTS](https://f-droid.org/fr/packages/org.woheller69.ttsengine/)
  (libre, voix neuronales Piper, hors ligne) s'il est installé, sinon les voix Google. Texte adapté à l'oral
  (« 14h30 » → « 14 heures 30 », « 8° » → « 8 degrés »…), débit posé / naturel / rapide. « Bonjour Jarvis » ou « briefing » : heure, météo, agenda, batterie.
- Licences : code openWakeWord Apache 2.0 ; **modèles pré-entraînés CC BY-NC-SA 4.0 (usage non commercial)**,
  voir `app/src/main/assets/wake/NOTICE.txt`.

### Assistant (sans IA, sans jeton)
Fonctionne par règles en français. Seules la météo et la recherche utilisent internet, via des services gratuits sans clé ni compte.

| Exemple | Ce qui se passe |
|---|---|
| « Météo à Lyon demain », « Va-t-il pleuvoir ce week-end ? » | Prévisions [Open-Meteo](https://open-meteo.com) (position du téléphone ou ville donnée) |
| « Prends rendez-vous chez le dentiste mardi à 14h30 » | Prépare l'événement dans votre agenda + bouton Doctolib pour un créneau en ligne |
| « Qu'est-ce que j'ai demain ? », « Mes rendez-vous de la semaine » | Lit votre agenda |
| « Réveille-moi à 7h », « Minuteur 10 minutes » | Règle l'horloge |
| « Qui est Marie Curie ? », « C'est quoi un trou noir » | Résumé DuckDuckGo / Wikipédia |
| « 15 % de 80 », « combien font 12 x 7 » | Calcul hors ligne |
| « Ouvre Spotify », « Itinéraire vers la gare », « Ouvre la caméra » | Ouvre l'appli / la carte / la caméra |
| « Désinstalle Duck Survival », « Range Waze dans Voyage » | Gère l'appli (Android confirme la désinstallation) |
| « Quelles applis je n'utilise jamais ? », « Applis les plus lourdes », « Montre mes jeux » | Listes calculées sur le téléphone |
| « Ma ville est Marseille » | Ville par défaut si la position est refusée |

Le micro utilise la reconnaissance vocale du téléphone, les réponses sont lues à voix haute.

### Caméra calibrée
- Mode **Auto** (compensation d'exposition) ou **Manuel** (ISO et vitesse, si le capteur le permet).
- **Mise au point** manuelle (∞ → distance minimale) ou automatique.
- **Balance des blancs** : préréglages + **Calibrer le blanc** (visez une feuille blanche ou grise :
  les gains de couleur sont mémorisés et réappliqués).
- **Grille des tiers** et **niveau à bulle**, avec **Calibrer le niveau** (posez le téléphone contre une surface droite).
- Tous les réglages sont enregistrés par objectif et restaurés à l'ouverture. Photos dans `Images/Orbite`.

## Projets libres dont Orbite s'inspire

Orbite est écrit de zéro (aucun code copié), en reprenant les approches de ces projets libres :

- [KISS Launcher](https://github.com/Neamar/KISS) et [Olauncher](https://github.com/tanujnotes/Olauncher) : lanceur léger, recherche d'applis au clavier.
- [Dicio](https://github.com/Stypox/dicio-android) : assistant libre par « compétences » et phrases-types, sans service cloud.
- [Open Camera](https://opencamera.org.uk) : réglages manuels Camera2, grille, niveau à bulle.
- [openWakeWord](https://github.com/dscripka/openWakeWord) (modèle « hey jarvis ») et les assistants libres « Jarvis » pour le principe mot d'éveil + réponses parlées + interface tête haute.
- Données : [Open-Meteo](https://open-meteo.com) (CC BY 4.0), Wikipédia, API instantanée DuckDuckGo.

## Structure

- `app/src/main/java/com/zrezzougui/orbite/launcher/` : écran d'accueil et classement (`AppCatalog.kt`).
- `app/src/main/java/com/zrezzougui/orbite/assistant/` : assistant, dates en français, météo.
- `app/src/main/java/com/zrezzougui/orbite/camera/` : caméra manuelle et calibration.
- Une seule bibliothèque externe : TensorFlow Lite (détection de « Hey Jarvis »). Le reste : SDK Android et Kotlin.

## Design

L'interface suit le design system Orbite : thème Nuit, couleur primaire « Orbite » `#7C9CFF`,
secondaire « Soleil » `#FFD27A`, accent « Aurore » `#5EE0C2`, grille de 4 dp, boutons en pilule de 48 dp.
Les jetons sont dans `Ui.kt` (`Palette`, `Space`, `Radius`, `Fonts`).

Polices embarquées dans `app/src/main/assets/fonts/` : **Sora** (titres), **Inter** (texte) et **Orbitron** (HUD),
toutes sous licence libre SIL Open Font License 1.1 (Google Fonts).
