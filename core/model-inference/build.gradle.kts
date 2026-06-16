plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    // Gradle path :core:model-inference; code keeps the dev.mascwa.pulse.jarvis.inference package.
    namespace = "dev.mascwa.pulse.jarvis.inference"
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
    // MediaPipe LLM Inference — a standalone on-device runtime (no Google Play Services).
    // Kept internal to this module: nothing in its public API exposes a MediaPipe type.
    implementation(libs.mediapipe.tasks.genai)
    implementation(libs.kotlinx.coroutines.core)
    // Streaming model download.
    implementation(libs.okhttp)
}
