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

// The standalone nutrition app: the health half of :app with none of its novelty, its device gate
// or its native code, so one universal APK runs on any phone. Shares :core:telemetry and
// :core:database; deliberately reaches nothing else of the LCARS application.
include(":nutrition")

// Windows desktop companion (Kotlin/JVM + Compose Multiplatform) — a structurally separate module, no
// dependency on :app/:core:* on this first pass (see desktop/build.gradle.kts), so it never touches the
// Android build's plugin/SDK requirements.
include(":desktop")
