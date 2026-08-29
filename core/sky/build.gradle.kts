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
        // ⚠️ The SKY floor, which is lower than [minSdkWide] — a library declaring a higher minimum
        // than its consumer fails the manifest merge, and the standalone star map now reaches back to
        // 23. Declaring it here is not a formality: it is what makes this module's own lint check its
        // sources at 23, so an unguarded newer API becomes a build failure rather than a crash on the
        // one phone that would ever hit it. The apps that consume this at a HIGHER floor are
        // unaffected; lint analyses each module at its own minimum.
        minSdk = libs.versions.minSdkSky.get().toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions { jvmTarget = "17" }

    // ⚠️ **The floor gate, and it is not decoration.** `NewApi` has default severity *error*, and
    // `lintVitalRelease` — the only lint any build here runs — passes `--fatalOnly`, which ignores
    // every issue that is not FATAL. So an unguarded newer API in this module would compile, ship
    // and throw on a device at the new minimum, with nothing red anywhere. Promoting the one issue
    // makes the analysis that already runs actually check it. The full reasoning, with the two
    // measurements it rests on, is in `sky/build.gradle.kts`.
    //
    // ⚠️ It has to be declared HERE and not only in the application: lint analyses each module at
    // its OWN minimum with its OWN configuration, and this library declares a lower one than either
    // application that consumes it.
    lint {
        fatal += "NewApi"
    }
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

    // ⚠️ **The first test dependency this module has ever declared.** It shipped twenty-six files
    // with no `src/test` at all, so the nine pure ones — about thirteen hundred lines of layer,
    // field and batching arithmetic — had no gate of any kind. `android-build.yml`'s test line
    // gains `:core:sky:testDebugUnitTest` in the same commit, because a test source set CI does not
    // run is worth exactly nothing.
    testImplementation(libs.junit)
}
