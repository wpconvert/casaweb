plugins {
    id("com.android.application")
}

android {
    namespace = "com.wpconvert.phoneagent"
    compileSdk = 36

    val ciVersionCode =
        System.getenv("VERSION_CODE")?.toIntOrNull() ?: 101

    val ciVersionName =
        System.getenv("VERSION_NAME") ?: "1.0.1"

    defaultConfig {
        applicationId = "com.wpconvert.phoneagent"
        minSdk = 26
        targetSdk = 36

        versionCode = ciVersionCode
        versionName = ciVersionName
    }

    signingConfigs {
        create("release") {
            val keystoreFile = rootProject.file("release.keystore")

            storeFile = keystoreFile
            storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("ANDROID_KEY_ALIAS")
            keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false

            signingConfig = signingConfigs.getByName("release")

            proguardFiles(
                getDefaultProguardFile(
                    "proguard-android-optimize.txt"
                ),
                "proguard-rules.pro"
            )
        }

        debug {
            // Debug tetap tersedia untuk testing lokal.
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.2.10")

    implementation("io.github.webrtc-sdk:android:150.7871.01")

    implementation("org.java-websocket:Java-WebSocket:1.5.6")
}
