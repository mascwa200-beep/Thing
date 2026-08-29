plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    // The Gradle module path is :core:sky. Most of it is `dev.mascwa.pulse.sky`, which matches,
    // because none of the renderer existed before the module did.
    //
    // ⚠️ The five catalogue readers under `dev.mascwa.pulse.data.sky` are the exception, and they
    // keep the package they were written in for the reason :core:health and :core:feeds keep theirs:
    // they were CARVED OUT of :app, and an identical package means not one of their call sites had
    // to move. They came here because the assets they read did — a reader and the bytes it opens in
    // one module, so a build that packages one packages the other.
    //
    // The namespace only fixes where R and BuildConfig are generated, so it is unaffected by holding
    // two packages.
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

    // ⚠️ The graphics artifacts, and deliberately NOT the Compose compiler plugin. Nothing here is
    // @Composable — the renderer is a DrawScope extension, which is an ordinary function — so this
    // module needs the TYPES (DrawScope, Brush, Color, Dp) and none of the compiler machinery. A
    // module that takes the plugin also takes its build cost and its stability rules, for nothing.
    //
    // `api` on ui-graphics because the renderer's own signatures name DrawScope and Color: a caller
    // that could invoke it but not name what it takes would have to declare Compose itself, which
    // is a dependency in all but name.
    api(platform(libs.androidx.compose.bom))
    api(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui)
}
