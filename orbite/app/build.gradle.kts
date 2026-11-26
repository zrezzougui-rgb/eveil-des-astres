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
        versionCode = 2
        versionName = "0.2.0"
        // Téléphones actuels uniquement : garde l'APK léger (la reconnaissance vocale embarque du code natif).
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
    // Vosk (Apache 2.0) : reconnaissance vocale hors ligne pour le mot « Jarvis ».
    implementation("net.java.dev.jna:jna:5.13.0@aar")
    implementation("com.alphacephei:vosk-android:0.3.47@aar")
}
