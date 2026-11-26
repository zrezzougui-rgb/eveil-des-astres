plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.zrezzougui.orbite"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.zrezzougui.orbite"
        minSdk = 29
        targetSdk = 35
        versionCode = 3
        versionName = "0.3.0"
        // Téléphones actuels uniquement : garde l'APK léger (la détection de « Hey Jarvis » embarque du code natif).
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    // Clé de test fixe : chaque nouvel APK peut remplacer le précédent sans désinstaller.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // TensorFlow Lite (Apache 2.0) : exécute les modèles openWakeWord pour entendre « Hey Jarvis ».
    implementation("org.tensorflow:tensorflow-lite:2.16.1")
}
