# Éveil des Astres

Prototype de jeu gacha idle pour Android : invocations avec pity, héros chibi évolutifs jusqu'à ★6, combats automatiques avec éléments et boss, missions quotidiennes et récompenses d'absence.

## Installer le jeu sur Android

1. Ouvrez l'onglet **Releases** du dépôt, puis **Dernière version du jeu**.
2. Téléchargez **eveil-des-astres.apk** depuis votre téléphone.
3. Ouvrez le fichier. Android demande d'autoriser l'installation depuis cette source la première fois.

Chaque modification envoyée sur `main` reconstruit l'APK automatiquement (onglet **Actions**).

## Structure

- `www/index.html` : le jeu (HTML, CSS et JavaScript dans un seul fichier).
- `android/` : projet Android généré par Capacitor.
- `.github/workflows/build-apk.yml` : construction automatique de l'APK.
