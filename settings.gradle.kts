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

// Windows desktop companion (Kotlin/JVM + Compose Multiplatform) — a structurally separate module, no
// dependency on :app/:core:* on this first pass (see desktop/build.gradle.kts), so it never touches the
// Android build's plugin/SDK requirements.
include(":desktop")
