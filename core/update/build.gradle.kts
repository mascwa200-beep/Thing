plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

android {
    // The Gradle module path is :core:update; the code keeps the `dev.mascwa.pulse.data.update`
    // package it was carved out of, which this namespace mirrors.
    //
    // ⚠️ **Keeping the package identical is the whole reason this move cost no import churn**, the
    // same reasoning `:core:health` and `:core:feeds` record. `:app`'s manifest names the result
    // receiver by that fully-qualified name and its one call site imports it; neither changed.
    namespace = "dev.mascwa.pulse.data.update"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        // ⚠️ The WIDE floor. A library declaring a higher minimum than its consumer fails the
        // manifest merge, and the standalone nutrition app reaches much further back than the
        // Pixel-gated one.
        minSdk = libs.versions.minSdkWide.get().toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    // ⚠️ `api`, because `UpdateCheck`/`UpdateInfo` are what this module's public functions return and
    // `HttpClient` is what its constructor takes — a consumer that could call `check()` but not name
    // its result would have to declare these itself, which is a dependency in all but name.
    api(project(":core:feeds"))
    api(project(":core:telemetry"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)

    // ⚠️ This module had NO test dependency and no tests, so its update policy and its whole
    // crash-report path were gated by nothing at all. `LogcatFilter` is deliberately free of Android
    // imports for exactly this reason: the decision about what to keep out of a dump is testable on
    // the JVM, and the real dumps off the real phones can be handed straight to it.
    testImplementation(libs.junit)
}
