import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Read optional release-signing credentials from gradle.properties (or -P flags).
val releaseStoreFile = (project.findProperty("PULSE_RELEASE_STORE_FILE") as String?).orEmpty()
val releaseStorePassword = (project.findProperty("PULSE_RELEASE_STORE_PASSWORD") as String?).orEmpty()
val releaseKeyAlias = (project.findProperty("PULSE_RELEASE_KEY_ALIAS") as String?).orEmpty()
val releaseKeyPassword = (project.findProperty("PULSE_RELEASE_KEY_PASSWORD") as String?).orEmpty()
val hasReleaseSigning = releaseStoreFile.isNotBlank() && file(releaseStoreFile).exists()

// CI passes -PPULSE_VERSION_CODE=<github run number> so each published build has a higher
// versionCode than the last — Android blocks downgrades, so this keeps in-place updates working.
val pulseBuildNumber = (project.findProperty("PULSE_VERSION_CODE") as String?)?.toIntOrNull() ?: 1

android {
    namespace = "dev.mascwa.pulse"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "dev.mascwa.pulse"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = pulseBuildNumber
        versionName = "1.0.$pulseBuildNumber"

        vectorDrawables { useSupportLibrary = true }
        resourceConfigurations += listOf("en")

        // Device the app is built exclusively for. The runtime gate matches
        // Build.MODEL against this (case-insensitive, substring). Documented
        // override in DeviceGate keeps the sole user from ever being locked out.
        buildConfigField("String", "TARGET_DEVICE_MODEL", "\"Pixel 10 Pro XL\"")
    }

    signingConfigs {
        // Pin the debug key to a committed keystore so every CI build is signed identically.
        // Without this each runner generates a throwaway debug key, which makes Android reject
        // the new APK as a signature change and forces an uninstall (wiping the model + data).
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseStoreFile)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Use the real release key when configured; otherwise fall back to
            // the debug key so a personal sideload release still installs.
            signingConfig = if (hasReleaseSigning) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = false
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-opt-in=kotlin.RequiresOptIn",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.foundation.layout.ExperimentalLayoutApi",
            "-opt-in=androidx.compose.animation.ExperimentalAnimationApi",
            "-opt-in=androidx.compose.ui.text.ExperimentalTextApi",
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi"
        )
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/DEPENDENCIES",
                "/META-INF/INDEX.LIST"
            )
        }
    }
}

dependencies {
    // Core / lifecycle / activity
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.core.splashscreen)

    // Compose (BOM-managed)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material3.window.size)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Coroutines
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)

    // Serialization
    implementation(libs.kotlinx.serialization.json)

    // Networking (thin OkHttp wrapper + kotlinx.serialization handles
    // JSON, RSS/XML and CSV uniformly across many keyless hosts)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)

    // Local feature modules
    implementation(project(":core:database"))
    implementation(project(":core:model-inference"))
    implementation(project(":core:telemetry"))

    // Offline on-device speech-to-text (Vosk). JNA must be the Android @aar variant so its
    // native libraries are packaged; the plain jar lacks them.
    implementation(libs.vosk.android)
    implementation("net.java.dev.jna:jna:${libs.versions.jna.get()}@aar")

    // 3D vector map engine (open-source, no Google); vector tiles from keyless OpenFreeMap.
    implementation(libs.maplibre.android)

    // Storage / background / images / location
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.coil.compose)
    implementation(libs.play.services.location)

    // Keyless OpenStreetMap map view
    implementation(libs.osmdroid.android)
}
