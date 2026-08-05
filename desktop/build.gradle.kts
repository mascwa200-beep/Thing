import org.jetbrains.compose.desktop.application.dsl.TargetFormat

// Windows desktop companion — Kotlin/JVM + Compose Multiplatform, structurally separate from :app/:core:*
// (no project dependency on either) so this module never touches the Android build's AGP/SDK requirements.
// Reusable files are copy-adapted in from the Android app on this first pass, not shared via true
// multiplatform source sets — see the plan doc for why (a real source-set migration is a later, deliberate
// step once this shell is proven, not rushed alongside standing the module up).
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    // 21, not 17 (the Android modules' target) — this module shares no JVM process with the Android app,
    // so there's no technical reason to match it; 21 is what's actually available to build/verify this
    // module with locally.
    jvmToolchain(21)
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
}

compose.desktop {
    application {
        mainClass = "dev.mascwa.pulse.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            packageName = "Pulse"
            packageVersion = "1.0.0"
            description = "Pulse — on-device-first companion, desktop edition"
        }
    }
}
