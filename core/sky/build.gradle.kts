plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    // The Gradle module path is :core:sky and the package is dev.mascwa.pulse.sky. Unlike
    // :core:health and :core:feeds — which were CARVED OUT of :app and kept their old packages so
    // forty call sites did not have to move — nothing here existed before, so the package can simply
    // match the module.
    namespace = "dev.mascwa.pulse.sky"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        // ⚠️ The WIDE floor, for the reason :core:database states: a library declaring a higher
        // minimum than its consumer fails the manifest merge, and the standalone sky app this module
        // exists to serve will reach much further back than the Pixel-gated one.
        minSdk = libs.versions.minSdkWide.get().toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    // ⚠️ `api`, because this module's public functions return SkyGrid tiles, StarCatalogReader sinks
    // and SkyFieldPlan decisions — a consumer that could call into the star field but not name what
    // it hands back would have to declare the core itself, which is a dependency in all but name.
    api(project(":core:telemetry"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.core)
}
