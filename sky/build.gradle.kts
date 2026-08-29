plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// CI passes -PSKY_VERSION_CODE=<run number> so each published build out-versions the last.
val skyBuildNumber = (project.findProperty("SKY_VERSION_CODE") as String?)?.toIntOrNull() ?: 1

android {
    namespace = "dev.mascwa.sky"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        // ⚠️ A DIFFERENT applicationId from `dev.mascwa.pulse`, which is what lets both live on one
        // phone at once. They share the renderer and the catalogues and none of the storage.
        applicationId = "dev.mascwa.sky"

        // ⚠️ **What makes this run on any phone is that no architecture is narrowed, not the API
        // floor.** `:app` is arm64-only — it compiles whisper.cpp, llama.cpp and QuickJS — and
        // refuses to start except on one Pixel model. This module compiles no native code of its
        // own and names no ABI, so ONE universal APK covers arm64, arm32, x86 and x86_64, and
        // there is no device gate to pass.
        //
        // ⚠️ It is not free of native libraries and saying so would be wrong: Compose UI depends
        // transitively on `androidx.graphics:graphics-path`, whose small `.so` is packaged — for
        // all four architectures, which is exactly why the property holds. The CI check asserts
        // that shape, never the absence of `lib/`.
        //
        // ⚠️ **No `ndk { abiFilters }` and no `externalNativeBuild`, deliberately.** Naming an ABI
        // here is the one edit that would undo the point of the module.
        minSdk = libs.versions.minSdkWide.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = skyBuildNumber
        versionName = "1.0.$skyBuildNumber"

        vectorDrawables { useSupportLibrary = true }
        resourceConfigurations += listOf("en")
    }

    signingConfigs {
        // ⚠️ The same committed key the other two applications use. Without this a CI runner
        // generates a throwaway debug key per run, Android reads the next build as a signature
        // change, and every update becomes "App not installed" followed by an uninstall. The three
        // package names differ, so sharing one personal sideload key costs nothing.
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
            // ⚠️ R8 is OFF, and that is a decision. In `:app` it earns its place tree-shaking a
            // very large icon library, and it also cost that application months of broken video
            // because a class the bundled Python resolved BY NAME was renamed away — a failure no
            // build could see. There is no reflection here and no icon library to shake; the size
            // is the star catalogue, which shrinking cannot touch.
            isMinifyEnabled = false
            isShrinkResources = false
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
        // `BuildConfig.VERSION_CODE` is how the app knows which build it is running, which is the
        // whole basis of "is there a newer one". Off by default since AGP 8.
        buildConfig = true
    }

    // ⚠️ **The star catalogue must be stored UNCOMPRESSED or it cannot be memory-mapped**, and the
    // point of its tile index is that a view reads a few kilobytes rather than all twenty-five
    // megabytes. Deflating it forces the whole file onto the heap — on the phone least able to
    // hold it, since this is the module built to run anywhere.
    //
    // ⚠️ This CANNOT live in `:core:sky` even though the asset does: packaging belongs to whichever
    // module builds the APK, so every application bundling the catalogue declares it separately.
    // `:app` has the same three lines and `SkyCatalogSource` reports the mistake at runtime rather
    // than silently working slowly.
    androidResources {
        noCompress += "skycat"
    }

    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.material3)
    implementation(libs.kotlinx.coroutines.android)

    // ⚠️ What makes `src/main/baseline-prof.txt` do anything at all — AGP packages the profile
    // either way, but on a sideloaded APK there is no store to hand it to ART, and this library's
    // startup initializer is the only thing that writes it. Inert without a profile file, which is
    // why it can be declared before one exists.
    implementation(libs.androidx.profileinstaller)

    // The whole star map: catalogues, renderer, view model. Everything this application draws.
    implementation(project(":core:sky"))
    implementation(project(":core:telemetry"))

    // Keeping itself current, and saying what went wrong: the GitHub release check with its green
    // gate, the `PackageInstaller` ladder, and the crash reporter. Shared with the other two
    // applications so the three cannot come to disagree about when a build is safe to install.
    //
    // ⚠️ **This is the only dependency here that costs the APK anything worth naming**, and the
    // honest accounting is: it declares `api(project(":core:feeds"))`, which declares okhttp, okio
    // and kotlinx-serialization as `api` in turn, and it declares `api(libs.coil.base)` for an
    // image-decode interceptor this application will never call. With R8 off (see the release block
    // above) none of that is shaken out. Measured jars: okhttp 771 kB, okio 351 kB, serialization
    // 646 kB, plus coil-base and `:core:feeds`' own twenty-two repositories.
    //
    // ⚠️ It is accepted rather than worked around, and the alternative was worse: a self-contained
    // updater here would be a third copy of the green gate and the installer ladder, in code that
    // installs software. `UpdateRepository`'s own KDoc records that it was parameterised for
    // exactly this — this is the fourth reader of these releases. Splitting `:core:update` so the
    // updater does not drag coil is a real follow-up and a much bigger change than this slice.
    implementation(project(":core:update"))
}
