import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.chaquopy)
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

    // ⚠️ Pinned, and pinned to the SAME string the CI workflow feeds sdkmanager. AGP otherwise picks
    // a default that varies with the AGP version, and a runner that has some other NDK installed
    // fails with a message about the missing one rather than about anything real. The two must be
    // edited together; there is no gate that notices if they drift.
    ndkVersion = "27.0.12077973"

    // The acoustic interrogator's native layer. See src/main/cpp/CMakeLists.txt for why this starts
    // as a single trivial file: nothing in this repository has ever compiled native code, and the
    // development container can neither cross compile nor reach the upstreams, so the first build
    // of anything here happens on CI with no local gate.
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    defaultConfig {
        applicationId = "dev.mascwa.pulse"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = pulseBuildNumber
        versionName = "1.0.$pulseBuildNumber"

        vectorDrawables { useSupportLibrary = true }
        resourceConfigurations += listOf("en")

        // The app ships exclusively to one arm64 device (Pixel 10 Pro XL), so package only its native
        // libraries (Vosk / JNA / MediaPipe / MapLibre / ExoPlayer) for arm64-v8a. Dropping the unused
        // armeabi-v7a / x86 / x86_64 variants removes the bulk of the APK's native footprint with zero
        // behaviour change on the target device.
        ndk { abiFilters += "arm64-v8a" }

        // ⚠️ **NAME THE ONE TARGET, because the AGP default is "build every executable and shared
        // library the CMake project defines" — and `EXCLUDE_FROM_ALL` on `add_subdirectory` does not
        // save you, since AGP enumerates targets from CMake's file API rather than building `all`.**
        //
        // This did not matter while the tree held whisper.cpp and llama.cpp: both have
        // BUILD_TESTS/EXAMPLES/SERVER options that are set OFF here, so their tools are never
        // *defined*. quickjs-ng is the first upstream to define command-line targets
        // unconditionally — at the pinned v0.16.0 that is `qjsc`, `qjs_exe`, `run-test262`,
        // `api-test`, `lre-test` and `function_source`, none of which upstream ever compiles for
        // Android and none of which this app has any use for.
        //
        // ⚠️ **MEASURED AFTERWARDS: this is hygiene, not a repair, and the record should say so.**
        // The worry was that one of those tools would fail to cross-compile and take the APK with
        // it — upstream's own Android CI job builds only `qjs` and sets `QJS_BUILD_LIBC=ON` so the
        // standalone `qjs-libc` those tools link never exists, so it looked live. It is not: CI run
        // 1903 built and packaged a green APK from this tree WITHOUT this line, with quickjs
        // compiled in and the JS symbol verified in the shipped library. Whatever AGP named, it
        // cross-compiled. So what this buys is a smaller, faster, explicitly-stated build graph —
        // the same thing `ndkVersion` and the ABI filter above buy — and not a fixed break.
        externalNativeBuild { cmake { targets += "lcarsnative" } }

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
            // This is the SHIPPED sideload build. It keeps the same package id + signing key as the
            // previously-installed debug build (applicationIdSuffix ".debug" + the debug keystore
            // fallback), so it updates IN PLACE — the on-device model and settings are preserved — while
            // being non-debuggable so ART honours the baseline profile (the PGO-equivalent AOT win).
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            // R8 code shrinking ON (owner-approved). It tree-shakes unused code — most notably the large
            // material-icons-extended library — for a real APK/DEX win. R8 fullMode is disabled
            // (gradle.properties) for a conservative enablement.
            //
            // ⚠️ THE KEEP RULES ARE LOAD-BEARING AND THIS LIST HAS ALREADY BEEN INCOMPLETE ONCE. It used
            // to read "cover everything reflection/JNI/serialization-driven (MediaPipe, Vosk/JNA, MapLibre,
            // Spotify/Gson, luaj, Room, kotlinx.serialization)" — third-party libraries, every one of them,
            // and it omitted the direction that actually broke: OUR OWN classes reached by name from the
            // bundled Python. R8 renamed `data.media.JsRuntime` away, so YouTube extraction had no
            // JavaScript engine and every media URL came back 403, on every release build, for months.
            //
            // The gap is closed twice over: the keeps in proguard-rules.pro, and the CI step "Verify
            // Python's Java lookups survived R8", which derives the class names from the Python sources and
            // asserts each survived into the shipped DEX. A missing keep now fails the build instead of the
            // device. Resource shrinking stays off until the owner confirms this build on the Pixel.
            isMinifyEnabled = true
            isShrinkResources = false
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
            // Inert build/metadata artifacts with no runtime behaviour — dropped to trim the APK.
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/DEPENDENCIES",
                "/META-INF/INDEX.LIST",
                "/META-INF/*.version",
                "/META-INF/androidx.*.version",
                "/META-INF/com/android/build/gradle/app-metadata.properties",
                "DebugProbesKt.bin",
                "kotlin-tooling-metadata.json"
            )
        }
    }
}

// ---- Python (Chaquopy) ---------------------------------------------------------------------
//
// ⚠️ TOOLCHAIN PROOF ONLY. Nothing in the app depends on Python yet, and that is deliberate: this
// lands alone so that when the extractor is added on top, a build failure is unambiguously in the
// extractor rather than in the toolchain. It is the same reason the CMake/NDK proof shipped by
// itself before whisper.cpp and llama.cpp went in — and that is the round that made those land
// cleanly.
//
// ⚠️ `ndk.abiFilters` above is load-bearing here, not incidental: the plugin refuses to configure
// without it ("Chaquopy requires ndk.abiFilters"), and arm64-v8a alone keeps the interpreter to one
// native ABI, which is the single biggest control on what this costs in the APK.
chaquopy {
    defaultConfig {
        // ⚠️ THE TARGET INTERPRETER, AND IT MUST BE SET EXPLICITLY. Chaquopy 16.1.0's
        // `DEFAULT_PYTHON_VERSION` is **3.8** — read straight off the plugin jar by reflection, not
        // inferred — while yt-dlp declares `requires_python >= 3.10`. So the first build with the pip
        // requirement failed in pip's resolver, and the Gradle stack trace was long enough that the
        // "what went wrong" line could not be read back through the log API at all. Asking the
        // plugin what its default was answered it in one step.
        //
        // 3.12 rather than 3.13 deliberately: the plugin offers 3.8 through 3.13, and 3.13.0 is a
        // .0 release with the least-exercised support of the set, where 3.12.7 is a mature point
        // release. Nothing here needs 3.13.
        // ⚠️ ASSIGNMENT, not a call. `PythonExtension` exposes `getVersion`/`setVersion` and no
        // `version(String)` method — unlike `buildPython(String...)` on the very next line, which IS
        // a method. Writing `version("3.12")` by analogy with its neighbour is a build-script compile
        // error, and it cost a round: the javap output naming both spellings was already open at the
        // time. Derive the call from the real declaration, never from the shape of the one beside it.
        version = "3.12"

        // The interpreter that runs on the BUILD machine, not the phone. CI's ubuntu runner has
        // python3 preinstalled.
        buildPython("python3")

        // Ship .py rather than compiling to .pyc at build time. Compilation wants a build interpreter
        // whose version matches the target's, and this proof has no reason to take that coupling on.
        pyc { src = false }

        pip {
            // ⚠️ PINNED, and that is not fussiness: yt-dlp ships a release most weeks, so a floating
            // version means the extractor silently differs between two builds of the same commit —
            // and when extraction breaks, "which version was in that APK" is the first question.
            //
            // Checked on PyPI rather than assumed, because a pip dependency is exactly the kind of
            // thing that fails in an unfamiliar way inside a build system: 2026.7.4 publishes as
            // `py3-none-any`, a PURE-PYTHON wheel with **no native code to cross-compile**, and its
            // `requires_dist` is EMPTY — every extra is optional. So this adds one 3.2 MB wheel and
            // nothing else, on a build that has no Android SDK story for native Python packages.
            install("yt-dlp==2026.7.4")

            // The JavaScript the YouTube challenge solver actually runs. Without it the engine
            // shipped in liblcarsnative.so has nothing to execute.
            //
            // ⚠️ **NOT OPTIONAL, AND THAT WAS MEASURED RATHER THAN ASSUMED.** yt-dlp vendors its own
            // copy of the solver's *core* script but NOT the *lib* script — its `_builtin/vendor`
            // directory holds `yt.solver.core.js` alongside two 240-byte NPM import shims, and
            // nothing else. So the builtin source can never supply the lib half; the remaining
            // routes are this package, a warm cache, or a GitHub download gated behind an opt-in
            // `remote_components` flag. On a phone with neither, this is the only one.
            //
            // ⚠️ **THE VERSION MUST TRACK yt-dlp's OWN `vendor.VERSION`.** The scripts are checked
            // against a hash table baked into yt-dlp, and a mismatch is not an error — the script is
            // rejected with a warning and the provider quietly becomes unavailable. Verified for
            // this pair: yt-dlp 2026.7.4 declares 0.8.0, and 0.8.0's lib and core hash exactly to
            // its `yt.solver.lib.min.js` and `yt.solver.core.min.js` entries.
            //
            // Pure-Python `py3-none-any`, like yt-dlp itself, so there is nothing to cross-compile.
            install("yt-dlp-ejs==0.8.0")
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

    // Internet-radio playback — ExoPlayer handles ICY/SHOUTcast, HLS, and cross-protocol
    // redirects that bare MediaPlayer fails on (StreamTheWorld/Triton commercial streams).
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.extractor)

    // Local feature modules
    implementation(project(":core:database"))
    implementation(project(":core:model-inference"))
    implementation(project(":core:telemetry"))
    // The HTTP client, the disk cache and the sixteen world-data repositories, shared with the
    // desktop companion. Moved out of this module in the same pass that made `:core:telemetry`
    // a plain JVM module: not one of those files imported `android.*`.
    implementation(project(":core:feeds"))
    implementation(project(":core:health"))
    implementation(project(":core:update"))

    // Offline on-device speech-to-text (Vosk). JNA must be the Android @aar variant so its
    // native libraries are packaged; the plain jar lacks them.
    implementation(libs.vosk.android)
    implementation("net.java.dev.jna:jna:${libs.versions.jna.get()}@aar")

    // Sensorium's on-device classifiers: YAMNet soundscape (tasks-audio) + EfficientNet scene
    // (tasks-vision), frames sampled via CameraX on a headless lifecycle (no -view: no preview
    // exists anywhere). Models are fetched once at runtime, never bundled — see AmbientSamplers.
    implementation(libs.mediapipe.tasks.audio)
    implementation(libs.mediapipe.tasks.vision)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    // The viewfinder for the barcode scanner. A scanner that cannot show what it is aimed at
    // is not usable, and PreviewView owns the surface lifecycle and transform that doing this
    // by hand over a raw SurfaceView gets wrong. 111 kB.
    implementation(libs.androidx.camera.view)
    // ⚠️ ZXing core, NOT ML Kit. The unbundled ML Kit variant needs Play Services, which is the
    // wrong bet on GrapheneOS, and the bundled one adds 2-3 MB to an APK the auto-updater
    // re-downloads in full on every build. This is pure JVM and 608 kB.
    implementation(libs.zxing.core)

    // 3D vector map engine (open-source, no Google); vector tiles from keyless OpenFreeMap.
    implementation(libs.maplibre.android)

    // Pure-JVM Lua interpreter for sandboxed, user-approved tool authoring (no native / DEX loading).
    implementation(libs.luaj.jse)

    // Storage / background / images / location
    implementation(libs.androidx.datastore.preferences)
    // Reads weight/steps from a scale or watch, and writes weight back. Behind a capability
    // check: without a provider installed the whole integration degrades to manual entry.
    implementation(libs.androidx.health.connect)
    implementation(libs.androidx.work.runtime.ktx)
    // Baseline Profiles: installs the app's + AndroidX libraries' profiles so ART AOT-compiles hot paths
    // (faster cold start / less jank) on the non-debuggable shipped build.
    implementation(libs.androidx.profileinstaller)
    implementation(libs.coil.compose)
    implementation(libs.coil.svg) // renders bundled offline .svg survival diagrams (crisp at any size)
    implementation(libs.play.services.location)

    // Spotify App Remote: the "real" player — connects to the installed Spotify app and drives its
    // playback (audio plays through the Spotify app, controlled from PIP-BOY). Shipped only as a
    // manually-vendored AAR (not on Maven); it needs Gson at runtime.
    implementation(files("libs/spotify-app-remote-release-0.8.0.aar"))
    implementation("com.google.code.gson:gson:2.11.0")

    testImplementation(libs.junit)
}
