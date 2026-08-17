package dev.mascwa.pulse.desktop.update

import java.util.Properties

/**
 * Which build of the desktop app this is.
 *
 * Written into the resources at package time from the CI run number (see `desktop/build.gradle.kts`), so
 * the running app can say what it is and compare itself against what has been published. The Android app
 * gets this from `BuildConfig`; a Kotlin/JVM module has no equivalent, hence the properties file.
 *
 * A build run locally has no run number and reports [build] `0`. That is deliberately distinguishable
 * from "build 1": an update check treats 0 as *unknown provenance* and declines to claim the copy is
 * either current or out of date, rather than nagging on every launch of a developer build.
 */
object BuildInfo {

    private const val RESOURCE = "/build-info.properties"

    /** The CI run that produced this copy, or 0 when it was not produced by CI. */
    val build: Int by lazy { props().getProperty("build")?.trim()?.toIntOrNull() ?: 0 }

    /** The version Windows Installer compares, e.g. `1.0.19`. */
    val version: String by lazy { props().getProperty("version")?.trim().orEmpty().ifBlank { "1.0.0" } }

    /** What to show a person: the version, and plainly said when it is not a released build. */
    val display: String get() = if (build <= 0) "$version (development build)" else version

    private fun props(): Properties {
        val p = Properties()
        // Absent when running from a source build that skipped processResources. Defaulting rather than
        // throwing keeps a missing file a cosmetic problem, not a crash on startup.
        runCatching {
            BuildInfo::class.java.getResourceAsStream(RESOURCE)?.use { p.load(it) }
        }
        return p
    }
}
