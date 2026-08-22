// The world's data, fetched once for both applications.
//
// ⚠️ This module exists for the reason `:core:telemetry` stopped being an Android library. Sixteen
// repository files — space weather, satellites, launches, aircraft, earthquakes, official alerts, places,
// routing, discussion feeds — import `android.*` a total of ZERO times. Every one is HTTP, JSON and a call
// into a pure core. They were about to be copied into `:desktop` one by one, which is precisely the mistake
// the 53 mirrored files were.
//
// The plumbing beneath them had exactly ONE Android dependency in it: `DiskCache` took a `Context` purely
// to resolve `filesDir`. It takes a directory now, and each application says where its own is.
//
// Kept SEPARATE from `:core:telemetry` deliberately. That module's value is that it performs no I/O at all,
// so it is deterministic and CI runs the whole of it; putting an HTTP client in it would end that. This
// depends on it, never the other way round.
//
// Same jvmTarget reasoning as `:core:telemetry`: `:app` reads 17, `:desktop` reads 21, so the bytecode has
// to be the lower of the two.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    // `api`, not `implementation`: a repository here returns `Fetched<SpaceWeather>` and `SpaceWeather`
    // is built out of `core:telemetry` types, so every consumer needs them on its own compile classpath.
    api(project(":core:telemetry"))
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)
    // `api` as well — `HttpClient` is constructed from an `OkHttpClient` by both applications.
    api(libs.okhttp)
    testImplementation(libs.junit)
}
