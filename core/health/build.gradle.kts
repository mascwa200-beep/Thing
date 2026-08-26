plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

android {
    // The Gradle module path is :core:health; the code keeps its existing
    // dev.mascwa.pulse.data.health package, which this namespace mirrors.
    //
    // ⚠️ **Keeping the package identical to where these files came from is the whole reason this
    // move cost no import churn.** `:app` has ~40 references to these types and not one of them
    // changed. `:core:feeds` was carved out of `:app` the same way and for the same reason; the
    // alternative — renaming the package to match the module path — would have meant editing every
    // call site to gain nothing a build file cannot already express.
    namespace = "dev.mascwa.pulse.data.health"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        // ⚠️ The WIDE floor, not `:app`'s, for the reason `:core:database` states: a library
        // declaring a higher minimum than its consumer fails the manifest merge, and the standalone
        // nutrition app deliberately reaches much further back than the Pixel-gated one.
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
    // ⚠️ `api`, not `implementation`, for the three modules whose types appear in this one's public
    // signatures — `NutritionDay`, `Food`, `FoodRow` and the rest are what these stores return. A
    // consumer that can call `FoodRepository.lookup` but cannot name the `Food` it hands back would
    // have to declare those modules itself to use this one, which is a dependency in all but name.
    api(project(":core:telemetry"))
    api(project(":core:feeds"))
    api(project(":core:database"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    // Optional at runtime and gated behind its own capability check — see HealthConnectBridge,
    // which never assumes the provider is installed.
    implementation(libs.androidx.health.connect)

    // ⚠️ FoodLogSchemaTest exercises the store's `internal` serialization DTOs, which is why it lives
    // here rather than in `:app` -- `internal` is module-scoped, so the same test one module over
    // cannot see them. That is not a detail: those DTOs ARE the on-disk contract, and a test that
    // could only reach the public API would not be testing the thing that breaks when it changes.
    testImplementation(libs.junit)
}
