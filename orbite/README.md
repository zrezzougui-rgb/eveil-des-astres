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
- Données : [Open-Meteo](https://open-meteo.com) (CC BY 4.0), Wikipédia, API instantanée DuckDuckGo.

## Structure

- `app/src/main/java/com/zrezzougui/orbite/launcher/` : écran d'accueil et classement (`AppCatalog.kt`).
- `app/src/main/java/com/zrezzougui/orbite/assistant/` : assistant, dates en français, météo.
- `app/src/main/java/com/zrezzougui/orbite/camera/` : caméra manuelle et calibration.
- Aucune bibliothèque externe : uniquement le SDK Android et Kotlin.

## Design

L'interface suit le design system Orbite : thème Nuit, couleur primaire « Orbite » `#7C9CFF`,
secondaire « Soleil » `#FFD27A`, accent « Aurore » `#5EE0C2`, grille de 4 dp, boutons en pilule de 48 dp.
Les jetons sont dans `Ui.kt` (`Palette`, `Space`, `Radius`, `Fonts`).

Polices embarquées dans `app/src/main/assets/fonts/` : **Sora** (titres) et **Inter** (texte),
toutes deux sous licence libre SIL Open Font License 1.1 (Google Fonts).
