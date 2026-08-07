// Top-level build file. Plugin versions are declared here with `apply false`
// and applied per-module. All versions live in gradle/libs.versions.toml.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    // Declared apply-false here too (not just in desktop/build.gradle.kts) so it resolves through the same
    // classpath pass as kotlin-android — kotlin.jvm/kotlin.android are different plugin IDs from the SAME
    // kotlin-gradle-plugin jar, and requesting one from a subproject without the other already registered
    // at the root causes a "plugin already on the classpath with an unknown version" resolution failure.
    alias(libs.plugins.compose.multiplatform) apply false
}
