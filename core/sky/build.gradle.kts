plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)

    // ⚠️ **Taken the day the chart itself moved in, and the comment below the dependencies used to
    // argue against it.** That reasoning held while this module was only a `DrawScope` extension —
    // an ordinary function drawing into somebody else's canvas. The chart is the engine, so two
    // applications drawing the sky means either one `@Composable` here or two copies of it there,
    // and a second copy of a star renderer is the drift this module exists to prevent.
    alias(libs.plugins.kotlin.compose)
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

    // ⚠️ `api`, because SkyMapViewModel IS a ViewModel and both applications name it when they ask
    // a factory to build one. A consumer that could construct it but not name its supertype would
    // have to declare lifecycle itself, which is a dependency in all but name.
    //
    api(libs.androidx.lifecycle.viewmodel.ktx)

    // ⚠️ **`lifecycle-runtime-compose`, which an earlier note here said would never be needed** on
    // the grounds that collecting state is the screen's business. It became this module's business
    // the day the chart moved in: SkyChart reads eleven flows off the view model, and collecting
    // them WITHOUT lifecycle awareness would leave a backgrounded star map recomposing on every
    // sensor sample.
    implementation(libs.androidx.lifecycle.runtime.compose)

    // ⚠️ The graphics artifacts. `api` on both ui and ui-graphics because this module's public
    // signatures name their types — DrawScope and Color on the renderer, Modifier on SkyChart — and
    // a caller that could invoke them but not name what they take would have to declare Compose
    // itself, which is a dependency in all but name. (`ui` was `implementation` while nothing here
    // was @Composable; the chart moving in is what put Modifier in a public signature.)
    //
    // ⚠️ `foundation` is here and nowhere else in this build, and it stays `implementation` because
    // nothing it holds reaches a signature. Canvas, the two gesture detectors and the layout
    // modifiers all live there, and every other module reaches it transitively through material3 —
    // which this one deliberately does not take, because a chart has no buttons on it and each
    // application supplies its own chrome.
    api(platform(libs.androidx.compose.bom))
    api(libs.androidx.compose.ui.graphics)
    api(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
}
