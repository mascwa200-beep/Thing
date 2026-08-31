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

    buildFeatures {
        compose = true
        // ⚠️ **Off by default since AGP 8, and the updater is what needs it.** `BuildConfig.VERSION_CODE`
        // is how this app knows which build it is running — the whole basis of "is there a newer one"
        // and of the marker that stops a failed install being retried for ever. Without this the
        // class is simply not generated, which reads as an unresolved reference rather than as a
        // missing build feature.
        buildConfig = true
    }

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

    // ⚠️ **What makes `src/main/baseline-prof.txt` do anything at all.** AGP packages the profile
    // into the APK either way; on Play the store then delivers it to ART, and this APK is sideloaded
    // from a GitHub release, so that path does not exist. This library's startup initializer is the
    // only thing that writes the profile for ART to read. The file and this line are inert
    // separately and were added on one commit for that reason.
    //
    // ⚠️ It also has to be a real dependency rather than an assumption: `:app` has carried both for
    // months while this module — the one built for a cheap phone — had neither.
    implementation(libs.androidx.profileinstaller)

    // ⚠️ Declared here as well as in `:core:health`, and it has to be. The shared library keeps
    // DataStore as `implementation`, which is right — none of its public types mention it — so it
    // reaches this module's RUNTIME classpath and not its COMPILE classpath, and `HealthSettingsStore`
    // is this app's own file building a DataStore directly. The version is the one `:app` and
    // `:core:health` already use, so there is still one copy of it in the APK.
    implementation(libs.androidx.datastore.preferences)

    // The barcode scanner — the camera, both decoders and the rotation, all in `:core:scan`.
    //
    // ⚠️ **CameraX is the one dependency here that could have undone the module's whole point, and
    // it was checked before a line was written**: `camera-core` packages
    // `libimage_processing_util_jni.so` and `libsurface_util_jni.so`, and it packages both for
    // arm64-v8a, armeabi-v7a, x86 AND x86_64 — read out of the 1.4.1 AAR, not assumed. So one
    // universal APK still covers every architecture, which is exactly what the CI check asserts.
    // The same is true of ML Kit's bundled decoder: `libbarhopper_v3.so` ships for all four.
    //
    // ⚠️ **The note that used to be here said ZXing rather than ML Kit "because the bundled one adds
    // two or three megabytes". That figure was wrong by an order of magnitude and the conclusion it
    // supported no longer holds.** Measured from the published artifact: the bundled AAR is 9.9 MB
    // and its native decoder is 20.2 MB across the four architectures a universal APK ships. What
    // changed the answer is not the number but what it is being weighed against — the database is
    // leaving the APK, so twenty megabytes is no longer a rounding error inside four hundred, and the
    // scanner it buys reads curved, crinkled, angled and dim barcodes that a scanline reader cannot.
    // ZXing is still in the graph, behind it, because ML Kit's bundled model working without Play
    // Services is strong evidence rather than proof and cannot be established from a build machine.
    implementation(project(":core:scan"))
    // ⚠️ **Guava is no longer declared here, and the hard-won note about why it had to be moved
    // with the scanner rather than being deleted — it is `ProcessCameraProvider.getInstance()` that
    // returns a `ListenableFuture`, and that call now lives in `:core:scan`. The full account of why
    // the 3 kB `listenablefuture` artifact cannot stand in for full Guava is in that module's build
    // script; it cost a CI round to establish and is exactly the shape of thing somebody re-derives.

    // ⚠️ Coil for the progress-photograph thumbnails, and it earns its place rather than being a
    // habit: a 96dp thumbnail of a full-resolution camera capture is a bitmap decode that has to be
    // downsampled and taken off the main thread, and hand-rolling `BitmapFactory` with an
    // `inSampleSize` inside a composable is how a scrolling row of them drops frames. The LCARS
    // application already uses this version, so there is one copy of it in the repository.
    implementation(libs.coil.compose)

    // The bundled barcode database and the nutrient declarations behind it. Nothing of the LCARS
    // application is reachable from here; these two are shared because a second copy of either
    // would be a second chance to disagree about what a stored figure means.
    implementation(project(":core:database"))
    implementation(project(":core:telemetry"))
    implementation(project(":core:feeds"))
    implementation(project(":core:health"))

    // Keeping itself current: the GitHub release check with its green gate, and the PackageInstaller
    // ladder. Shared with the LCARS application so the two cannot come to disagree about when a
    // build is safe to install.
    implementation(project(":core:update"))
}
