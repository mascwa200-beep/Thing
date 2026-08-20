plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "dev.mascwa.pulse.core.telemetry"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
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
    implementation(libs.kotlinx.coroutines.core)
    // ⚠️ The one third-party parser this module takes, and it is here rather than in :app on purpose.
    // Readability is pure logic over a parsed document — no Android types, no I/O — which is exactly
    // what this module is for, and putting it here means CI gates it and it can be run locally. jsoup
    // is plain JVM, so a desktop mirror needs only the same dependency line.
    api(libs.jsoup)
    testImplementation(libs.junit)
}
