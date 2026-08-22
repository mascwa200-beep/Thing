// A PLAIN Kotlin/JVM module, deliberately — not an Android library.
//
// ⚠️ This is the single most load-bearing line in the desktop companion's architecture. While this was an
// `android.library`, `:desktop` could not depend on it (that would drag AGP and the Android SDK into a
// module that has neither), so 53 files were COPIED into the desktop by a generator script and a drift
// test policed them. Exactly ONE of this module's 110 files imported `android.*` —
// `DeviceContextProvider`, which reads battery and connectivity — and it now lives in `:app`, where the
// platform calls belong. Everything left here is arithmetic, parsing and text.
//
// So: one file moved, and 53 copies, a code generator and a drift guard all stopped being necessary.
//
// `:app` (Android, JVM 17) and `:desktop` (JVM 21) both depend on this directly. It therefore has to emit
// bytecode BOTH can read, which means the lower of the two — see jvmTarget below. A 21-target class file
// is simply unreadable to the Android toolchain, and the failure comes late and reads as unrelated.
plugins {
    alias(libs.plugins.kotlin.jvm)
    // For the `@Serializable` guide/index models, which are shared content shapes rather than anything
    // platform-specific. ⚠️ Note for the wire protocol: `RemoteWire`'s hand-rolled length-prefixed framing
    // was chosen when this module had no serialization dependency at all. That reason is now gone, but the
    // framing is NOT to be replaced with JSON — it doubles as the canonical byte encoding the handshake
    // HMAC is computed over, and changing it would change what both ends sign.
    alias(libs.plugins.kotlin.serialization)
}

// ⚠️ Set as a target rather than a `jvmToolchain(17)`, on purpose: a toolchain makes Gradle go and find (or
// download) a JDK 17, which needs the network and a provisioning setup this repo does not have. Targeting
// 17 from whichever JDK is running compiles here, on CI, and on a developer machine alike.
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
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    // ⚠️ The one third-party parser this module takes, and it is here rather than in :app on purpose.
    // Readability is pure logic over a parsed document — no Android types, no I/O — which is exactly
    // what this module is for, and putting it here means CI gates it and it can be run locally. jsoup
    // is plain JVM, so both consumers get it from the same place.
    api(libs.jsoup)
    testImplementation(libs.junit)
}
