plugins {
    id("com.android.application")
}

android {
    namespace = "com.wpconvert.phoneagent"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.wpconvert.phoneagent"
        minSdk = 26
        targetSdk = 36
        versionCode = 100
        versionName = "1.0.0-CONTROL-TEST"
    }

    buildTypes {
        release {
            isMinifyEnabled = false

            proguardFiles(
                getDefaultProguardFile(
                    "proguard-android-optimize.txt"
                ),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}


dependencies {

    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.2.10")

    // WebRTC yang sudah ada
    implementation("io.github.webrtc-sdk:android:150.7871.01")

    // WebSocket untuk koneksi APK ke server.py
    implementation("org.java-websocket:Java-WebSocket:1.5.6")

}
