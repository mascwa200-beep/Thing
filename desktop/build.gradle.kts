import org.jetbrains.compose.desktop.application.dsl.TargetFormat

// Windows desktop companion — Kotlin/JVM + Compose Multiplatform, structurally separate from :app/:core:*
// (no project dependency on either) so this module never touches the Android build's AGP/SDK requirements.
// Reusable files are copy-adapted in from the Android app on this first pass, not shared via true
// multiplatform source sets — see the plan doc for why (a real source-set migration is a later, deliberate
// step once this shell is proven, not rushed alongside standing the module up).
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    // 21, not 17 (the Android modules' target) — this module shares no JVM process with the Android app,
    // so there's no technical reason to match it; 21 is what's actually available to build/verify this
    // module with locally.
    jvmToolchain(21)
}

// The bundled Knowledge Base — 581 guides, their per-category shards and their diagrams — copied into
// this module's resources at build time from the Android app's asset directory.
//
// A file copy, deliberately, NOT a project dependency: `:desktop` still declares no dependency on `:app`
// or `:core:*`, so it never pulls in AGP or the Android SDK, and the module stays independently
// buildable. And a copy rather than a second checked-in tree, so the corpus has exactly ONE home in the
// repo — a duplicated 95 MB of content would drift the first time a KB wave landed.
//
// ⚠️ `.github/workflows/desktop-build.yml` lists this path in its trigger filter for the same reason: a
// content wave changes what the desktop ships, so it has to re-verify the desktop too.
// The build number this copy was produced by, and the version Windows Installer compares.
//
// ⚠️ This was hardcoded "1.0.0". Every build therefore carried the same ProductVersion, which means
// Windows Installer would have seen a newly-downloaded MSI as the SAME product already installed and
// declined to upgrade it — so an updater on top of that would have downloaded, run, and changed nothing.
// The version has to move for an update to be an update.
//
// CI passes `-PdesktopBuild=<run_number>` of the DESKTOP workflow (its own counter, unrelated to the
// Android one). 0 locally, which the app reports as a development build rather than pretending to a
// provenance it does not have.
val desktopBuild: Int = (findProperty("desktopBuild") as String?)?.toIntOrNull() ?: 0

// major.minor.build, and MSI caps `build` at 65535 — far above any run number this will reach, but the
// coercion is here so a bad property can never produce an installer jpackage rejects late in CI.
val desktopVersion: String = "1.0.${desktopBuild.coerceIn(0, 65535)}"

// How the running app learns its own build. A generated properties file rather than generated Kotlin:
// nothing to order against compilation, and it reads back in three lines.
//
// ⚠️ Registered here, at the top level — registering a task from inside another task's configuration
// block is not allowed and fails the build outright ("register on task set cannot be executed in the
// current context").
val writeBuildInfo = tasks.register("writeBuildInfo") {
    // ⚠️ Copied into locals of THIS block before the action closure uses them. Referencing the
    // script-level properties directly from inside `doLast` makes the closure capture the build script
    // object, which the configuration cache refuses to serialise ("cannot serialize Gradle script object
    // references"). Plain locals capture as plain values.
    val out = layout.buildDirectory.file("generated/build-info/build-info.properties").get().asFile
    val version = desktopVersion
    val buildNumber = desktopBuild
    // Declared as inputs so the task re-runs when the version changes, rather than being considered up to
    // date with a stale number baked in — which is exactly the failure that would have the updater
    // misreport which build is installed.
    inputs.property("version", version)
    inputs.property("build", buildNumber)
    outputs.file(out)
    doLast {
        out.parentFile.mkdirs()
        out.writeText("version=$version\nbuild=$buildNumber\n")
    }
}

tasks.processResources {
    from(rootProject.file("app/src/main/assets/survival")) { into("survival") }
    from(writeBuildInfo)
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)

    testImplementation(libs.junit)
}

compose.desktop {
    application {
        mainClass = "dev.mascwa.pulse.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            packageName = "LCARS"
            packageVersion = desktopVersion
            description = "LCARS — on-device-first companion, desktop edition"
            vendor = "LCARS"

            // The desktop talks to the phone over a TCP socket and parses XML feeds, so the runtime image
            // needs the networking, crypto and XML modules. jpackage's jlink step strips anything not
            // listed, and a missing module surfaces only at runtime on a real Windows box — where it would
            // be a crash, not a build failure. Listing them explicitly is what keeps that from happening.
            modules("java.naming", "java.security.jgss", "java.xml", "jdk.crypto.ec", "java.instrument")

            windows {
                // A stable UUID is what lets an installer UPGRADE an existing install instead of sitting
                // alongside it as a second copy. jpackage invents a fresh one per build without this, so
                // omitting it quietly breaks every future update.
                upgradeUuid = "6f3a1c48-9b2e-4d77-a1f0-2c5b8e94d310"
                menuGroup = "LCARS"
                shortcut = true
                dirChooser = true
            }
        }
    }
}
