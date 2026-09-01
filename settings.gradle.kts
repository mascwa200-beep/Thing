pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Pulse"
include(":app")
include(":core:database")
include(":core:model-inference")
include(":core:telemetry")
include(":core:feeds")
include(":core:health")

// The self-update path, shared by both Android applications: the GitHub release check with its
// green gate, and the PackageInstaller ladder. Two copies of either would be two chances to
// disagree about when a build is safe to install.
include(":core:update")

// The camera and the two barcode decoders, shared by both Android applications. It exists because
// the two had a near-verbatim copy each — each with a header saying in writing that they must be
// changed together, and each carrying the same rotation defect that made the scanner unable to read
// a barcode held upright. It is deliberately NOT depended on by `:core:health`, which every consumer
// of the health data layer pulls in and none of which wants CameraX.
include(":core:scan")

include(":core:sky")

// The standalone nutrition app: the health half of :app with none of its novelty, its device gate
// or its native code, so one universal APK runs on any phone. Shares :core:telemetry and
// :core:database; deliberately reaches nothing else of the LCARS application.
include(":nutrition")

// The standalone star map, on the same terms as :nutrition — plain Material 3, no device gate, no
// native code of its own, so one universal APK. Everything it draws comes from :core:sky and
// :core:telemetry; it reaches no other part of the LCARS application and, uniquely among the three,
// makes no network request at all.
include(":sky")

// Windows desktop companion (Kotlin/JVM + Compose Multiplatform) — a structurally separate module, no
// dependency on :app/:core:* on this first pass (see desktop/build.gradle.kts), so it never touches the
// Android build's plugin/SDK requirements.
include(":desktop")
