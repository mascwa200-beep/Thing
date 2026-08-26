plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// CI passes -PNUTRITION_VERSION_CODE=<run number> so each published build out-versions the last.
val nutritionBuildNumber = (project.findProperty("NUTRITION_VERSION_CODE") as String?)?.toIntOrNull() ?: 1

android {
    namespace = "dev.mascwa.nutrition"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        // ⚠️ **A DIFFERENT applicationId from `dev.mascwa.pulse`, which is what lets both live on one
        // phone at once.** They share the data FORMAT and none of the storage: each has its own
        // sandbox, so a log kept in one is invisible to the other. Export and import is the bridge
        // between them, and that is deliberate rather than a gap — two apps writing one store would
        // be a synchronisation problem nobody asked for.
        applicationId = "dev.mascwa.nutrition"

        // ⚠️ **26, not the 31 the rest of this repository uses, and not lower.** The shared code
        // reaches for `java.time` in a dozen places — HealthDays, the food log, the export and
        // import — and that is an API 26 library. Going below it means core library desugaring,
        // which is a real dependency and a build feature, to reach phones from 2014 to 2017 that
        // could not hold this application anyway: it bundles the whole barcode database.
        //
        // ⚠️ And 26 is NOT what makes this run on "every type of phone" — that was never the
        // version. `:app` is arm64-only and refuses to start except on one Pixel model running
        // GrapheneOS. This module compiles no native code of its own and narrows nothing, so ONE
        // universal APK covers arm64, arm32, x86 and x86_64, and there is no device gate to pass.
        //
        // ⚠️ It is not literally free of native libraries, and the first version of this comment
        // said it was. Measured from the shipped artifact: Compose UI depends transitively on
        // `androidx.graphics:graphics-path`, whose ~10 kB `.so` is packaged — for all four
        // architectures, which is why the property still holds. The CI check enforces exactly that:
        // every native library present for every architecture, never the absence of all of them.
        minSdk = libs.versions.minSdkWide.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = nutritionBuildNumber
        versionName = "1.0.$nutritionBuildNumber"

        vectorDrawables { useSupportLibrary = true }
        resourceConfigurations += listOf("en")

        // ⚠️ **No `ndk { abiFilters }` and no `externalNativeBuild`, deliberately.** Naming an ABI
        // here is exactly what would undo the point of the module.
    }

    signingConfigs {
        // ⚠️ **The same committed key `:app` uses, and pinning it is not cosmetic.** Without this a
        // CI runner generates a throwaway debug key per run, Android reads the next build as a
        // signature change, and every update becomes "App not installed" followed by an uninstall
        // that takes the user's whole food log with it. The two applications have different
        // package names, so sharing one personal sideload key costs nothing.
        getByName("debug") {
            storeFile = rootProject.file("app/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            // ⚠️ **R8 is OFF here and that is a decision rather than an oversight.** In `:app` it
            // earns its place by tree-shaking a very large icon library, and it also cost that
            // application months of broken video because a class the bundled Python resolved BY
            // NAME was renamed away — a failure no build could see. This module has no reflection
            // and no name-resolved anything; it also has no icon library to shake. The size here is
            // the bundled database, which shrinking cannot touch.
            isMinifyEnabled = false
            isShrinkResources = false
            // The committed debug key, so a personal sideload installs and updates in place. There
            // is nothing to sign for a store here.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions { jvmTarget = "17" }

    buildFeatures { compose = true }

    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    // Collecting the shared view model's flows, and holding it across a rotation. Both aliases
    // already existed for the LCARS application; neither pulls anything this app does not use.
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.material3)
    implementation(libs.kotlinx.coroutines.android)

    // ⚠️ Declared here as well as in `:core:health`, and it has to be. The shared library keeps
    // DataStore as `implementation`, which is right — none of its public types mention it — so it
    // reaches this module's RUNTIME classpath and not its COMPILE classpath, and `HealthSettingsStore`
    // is this app's own file building a DataStore directly. The version is the one `:app` and
    // `:core:health` already use, so there is still one copy of it in the APK.
    implementation(libs.androidx.datastore.preferences)

    // The bundled barcode database and the nutrient declarations behind it. Nothing of the LCARS
    // application is reachable from here; these two are shared because a second copy of either
    // would be a second chance to disagree about what a stored figure means.
    implementation(project(":core:database"))
    implementation(project(":core:telemetry"))
    implementation(project(":core:feeds"))
    implementation(project(":core:health"))
}
