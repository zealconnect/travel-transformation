import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.voiceagent"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.voiceagent"
        minSdk = 30          // Android 11+, needed for screenshots and the Enter key
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        ndk { abiFilters += "arm64-v8a" }   // Pixel 7 and every modern phone
    }

    // A fixed key, kept in the repo, so every new build installs over the old one
    // instead of asking you to uninstall first. Fine for a personal, sideloaded app.
    signingConfigs {
        create("personal") {
            storeFile = file("voiceagent.keystore")
            storePassword = "voiceagent"
            keyAlias = "voiceagent"
            keyPassword = "voiceagent"
        }
    }

    buildTypes {
        debug { signingConfig = signingConfigs.getByName("personal") }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("personal")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging { jniLibs { useLegacyPackaging = false } }
}

kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    // Free, offline AI that runs on the phone (optional; only used if you add a model file).
    implementation("com.google.mediapipe:tasks-genai:0.10.35")

    testImplementation("junit:junit:4.13.2")
}
