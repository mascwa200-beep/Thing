plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
}

android {
    // The Gradle module path is :core:database; the code keeps its existing
    // dev.mascwa.pulse.data.jarvis package, which this namespace mirrors.
    namespace = "dev.mascwa.pulse.data.jarvis"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        // ⚠️ The WIDE floor, not `:app`'s. This module is shared with the standalone nutrition
        // app, and a library declaring a higher minimum than its consumer fails the manifest
        // merge. Safe to lower: measured, the only platform type this module touches at all is
        // `android.content.Context` — everything else is Room and androidx.sqlite, both of which
        // support far older releases than this. `:app` keeps its own 31 and is unaffected.
        minSdk = libs.versions.minSdkWide.get().toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // Exposed via `api` so consumers (the :app composition root) can reference the
    // entity types returned by JarvisMemory's Flows without their own Room dep.
    api(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.kotlinx.coroutines.core)
}
