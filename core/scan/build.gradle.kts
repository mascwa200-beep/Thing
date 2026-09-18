plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "dev.mascwa.pulse.scan"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        // ⚠️ The WIDE floor, because a library declaring a HIGHER minimum than its consumer fails
        // the manifest merge and the standalone nutrition app reaches further back than the
        // Pixel-gated one. Both decoders sit comfortably under it — ML Kit's bundled barcode model
        // declares 21 and CameraX 1.4.1 declares 21, read from their own manifests rather than
        // recalled.
        minSdk = libs.versions.minSdkWide.get().toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions { jvmTarget = "17" }

    // ⚠️ The floor gate, for the reason `:core:update` states at length: `lintVitalRelease` passes
    // `--fatalOnly`, so an unguarded newer API would compile, ship and throw on a device at the
    // minimum with nothing red anywhere. It has to be declared HERE because lint analyses each
    // module at its OWN minimum, and this one declares a lower minimum than `:app` does.
    lint {
        fatal += "NewApi"
    }
}

dependencies {
    // ⚠️ `api`, because `BarcodeScan.Progress` and `BarcodeScan.Symbology` are what this module's
    // public surface returns and takes. A consumer that could call the scanner but not name its
    // result would have to declare this itself, which is a dependency in all but name.
    api(project(":core:telemetry"))

    // ⚠️ `api` on camera-view: `ScannerHost` hands the caller a `PreviewView` to put in its own
    // `AndroidView`, so the type is part of the signature. The others are implementation detail.
    api(libs.androidx.camera.view)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)

    // The two decoders. See `FrameDecoders.kt` for why there are two and which one leads.
    implementation(libs.mlkit.barcode.scanning)
    implementation(libs.zxing.core)

    // ⚠️ **`ProcessCameraProvider.getInstance()` returns a Guava `ListenableFuture`, and FULL Guava
    // is what has to be declared — the 3 kB `com.google.guava:listenablefuture` artifact cannot work,
    // and trying it first cost a CI round.** Measured from the published metadata rather than
    // reasoned about, when this lived in `:nutrition`:
    //
    //   - `camera-core` declares `listenablefuture:1.0` in its **api** variant, so it does reach a
    //     consumer's compile classpath on its own.
    //   - Health Connect declares `guava:31.1-android` in its **runtime** variant, and full Guava's
    //     own POM declares `listenablefuture:9999.0-empty-to-avoid-conflict-with-guava`. That version
    //     sorts higher, so it wins the conflict and the artifact that wins is **empty** — the
    //     mechanism exists precisely to stop two copies of the class being packaged.
    //   - Guava carries `ListenableFuture` itself, so nothing is missing at runtime. But it arrives
    //     runtime-only, so the compile classpath ends up with the empty jar and no class: "Cannot
    //     access class ... check your module classpath for missing or conflicting dependencies".
    //
    // ⚠️ Do **not** "fix" a future recurrence by forcing `listenablefuture` to 1.0. With Guava in the
    // graph that packages the class twice, which is the outcome the 9999.0 artifact prevents.
    implementation(libs.guava)

    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.core)

    // ⚠️ The rotation is the whole of the historical bug and it is arithmetic over a byte array, so
    // it is testable on the JVM without a camera, a device or an emulator. That is the only reason
    // this module has a test dependency at all.
    testImplementation(libs.junit)
}
