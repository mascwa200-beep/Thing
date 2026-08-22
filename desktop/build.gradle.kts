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
    // The typefaces, borrowed rather than copied. One set of `.ttf` files in the repository means
    // the phone and the companion cannot end up on different versions of the same face, and the
    // OFL notice that ships in the survival assets already covers them.
    from(rootProject.file("app/src/main/res/font")) { into("font") }
    from(writeBuildInfo)
}

// JavaFX, for live television. Declared as plain coordinates rather than through the version catalog
// because the artifact that matters is the CLASSIFIED one — `javafx-media-21.0.5-win.jar` carries the
// four native libraries (jfxmedia, gstreamer-lite, glib-lite, fxplugins) that actually decode the
// stream, and the catalog has no clean way to express a classifier that varies by host.
//
// Windows is the target; the linux classifier exists so the module still resolves and compiles on the
// ubuntu CI runner and here. Confirmed from the shipped Windows jar rather than the documentation:
// it contains `com/sun/media/jfxmedia/locator/HLSConnectionHolder` with its playlist and
// variant-playlist parsers, so HLS is real support and not an aspiration.
val javafxVersion = "21.0.5"
val javafxPlatform: String = System.getProperty("os.name").lowercase().let {
    when {
        it.contains("win") -> "win"
        it.contains("mac") -> "mac"
        else -> "linux"
    }
}

dependencies {
    // ⚠️ The whole point of Part B. `:core:telemetry` is a plain Kotlin/JVM module (no AGP, no Android
    // SDK), so this module depends on the SHARED cores directly instead of holding 53 copies of them
    // generated by a script and policed by a drift test. One project line replaced all of that.
    //
    // This is still not a dependency on `:app` — that is an Android application module and always will
    // be. What stays copied here is only what genuinely reaches into `:app`'s own plumbing.
    implementation(project(":core:telemetry"))
    // The world's data, fetched by the same sixteen repositories the phone uses — space weather,
    // satellites, launches, aircraft, earthquakes, official alerts, places, routing, discussion feeds —
    // plus the HTTP client and disk cache beneath them. Every one of those files imported `android.*`
    // exactly zero times; the only thing standing in the way was `DiskCache` taking a `Context`.
    //
    // It brings okhttp, coroutines and serialization with it as `api` dependencies, which is why those
    // three are no longer declared below.
    implementation(project(":core:feeds"))
    implementation(compose.desktop.currentOs)
    implementation("org.openjfx:javafx-base:$javafxVersion:$javafxPlatform")
    implementation("org.openjfx:javafx-graphics:$javafxVersion:$javafxPlatform")
    implementation("org.openjfx:javafx-media:$javafxVersion:$javafxPlatform")
    implementation("org.openjfx:javafx-swing:$javafxVersion:$javafxPlatform")
    implementation(compose.material3)
    // Swing only — the rest of coroutines, plus serialization and okhttp, arrive through `:core:feeds`,
    // and jsoup through `:core:telemetry`. Both are `api` dependencies there, so re-declaring them here
    // would be two statements of one version.
    implementation(libs.kotlinx.coroutines.swing)

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
            //
            // ⚠️ `jdk.unsupported` and `jdk.unsupported.desktop` are here for JavaFX, and they were
            // NOT guessed — `javap -v` on the `module-info.class` inside the shipped
            // javafx-graphics / javafx-swing jars declares exactly these:
            //
            //   javafx.graphics  requires java.desktop, java.xml, jdk.unsupported
            //   javafx.swing     requires java.datatransfer, java.desktop, jdk.unsupported.desktop
            //
            // `java.desktop` and `java.xml` are already accounted for, and `java.datatransfer` comes
            // free (java.desktop requires it transitively — checked with `java --describe-module`).
            // The two `jdk.unsupported*` ones do not, and a missing module here is invisible until
            // the MSI runs on a real Windows machine, where it is a crash rather than a build error.
            modules(
                "java.naming", "java.security.jgss", "java.xml", "jdk.crypto.ec", "java.instrument",
                "jdk.unsupported", "jdk.unsupported.desktop",
                // ⚠️ The standby display's machine vitals. `com.sun.management.OperatingSystemMXBean`
                // is exported by `jdk.management` (read out of the JDK with `--describe-module`, not
                // recalled) and reached through a ServiceLoader `provides` that only exists if that
                // module is in the image. `java.management` comes with it transitively and is listed
                // anyway, because an entry that is merely implied is one an image trim can drop.
                //
                // The whole reason this list is load-bearing: jlink strips anything unlisted, and a
                // missing module surfaces ONLY as a failure on real Windows — never as a build error.
                // Here it would be silent rather than loud, because the vitals read is wrapped: the
                // panel would simply say it could not measure this machine, forever, on every install.
                "java.management", "jdk.management",
            )

            windows {
                // A stable UUID is what lets an installer UPGRADE an existing install instead of sitting
                // alongside it as a second copy. jpackage invents a fresh one per build without this, so
                // omitting it quietly breaks every future update.
                upgradeUuid = "6f3a1c48-9b2e-4d77-a1f0-2c5b8e94d310"
                menuGroup = "LCARS"
                shortcut = true
                // ⚠️ **A per-user install is what makes an unattended upgrade possible at all.** A
                // per-machine MSI lands under Program Files and therefore needs elevation, and
                // `msiexec /qn` cannot suppress a UAC prompt — it only fails behind one. Installing
                // under %LOCALAPPDATA% needs no elevation, so `DesktopUpdater.launchInstaller` can
                // run the upgrade with no window and no click.
                //
                // ⚠️ ONE-TIME COST: this is a different install context, so the first per-user MSI
                // will NOT upgrade an existing per-machine copy — it installs beside it. The owner
                // uninstalls LCARS once, exactly as the phone needed one uninstall after the signing
                // change. After that every upgrade is silent.
                perUserInstall = true
                // Dropped deliberately: an unattended upgrade must not be able to ask where to go,
                // and a fixed location is what lets it land on top of what is already installed.
                dirChooser = false
            }
        }
    }
}
