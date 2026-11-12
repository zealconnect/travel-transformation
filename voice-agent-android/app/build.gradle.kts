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
        versionCode = 3
        versionName = "3.0"
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

    buildFeatures { buildConfig = true }

    // "lite" (default): installs normally; Play Protect doesn't block it.
    // "full": adds screen control via Accessibility; install it from a computer (adb).
    flavorDimensions += "edition"
    productFlavors {
        create("lite") {
            dimension = "edition"
            isDefault = true
            buildConfigField("boolean", "FULL", "false")
        }
        // Same app id as "lite", so the phone-only upgrade installs over it and keeps
        // settings and the downloaded brain.
        create("full") {
            dimension = "edition"
            versionNameSuffix = "-full"
            buildConfigField("boolean", "FULL", "true")
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
    testOptions { unitTests { isIncludeAndroidResources = true } }
}

kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.13.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    // Free AI that runs entirely on the phone (Google LiteRT-LM + Gemma 4). No account, no API key.
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.19.1")
    // Phone-only one-time upgrade to the full version (see Upgrade.kt).
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation("androidx.test:core:1.7.0")
    testImplementation("org.json:json:20250517")
}

// The "lite" app carries the "full" app inside it (as an asset), so it can upgrade itself
// on the phone via Shizuku (Upgrade.kt). Only the lite flavor bundles it.
val bundleFullApk by tasks.registering(Copy::class) {
    dependsOn("assembleFullDebug")
    from(layout.buildDirectory.dir("outputs/apk/full/debug")) {
        include("*.apk")
        rename { "full.apk" }
    }
    into(layout.buildDirectory.dir("generated/fullApk/upgrade"))
}
android.sourceSets.getByName("lite").assets.srcDir(layout.buildDirectory.dir("generated/fullApk"))
tasks.configureEach {
    if (name.contains("Lite") && (name.contains("Assets") || name.contains("Lint"))) dependsOn(bundleFullApk)
}
