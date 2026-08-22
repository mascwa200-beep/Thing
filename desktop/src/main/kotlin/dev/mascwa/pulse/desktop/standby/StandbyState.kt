package dev.mascwa.pulse.desktop.standby

import dev.mascwa.pulse.core.telemetry.Insight
import dev.mascwa.pulse.core.telemetry.MarketMood

/**
 * Everything the standby display draws, already reduced to what goes on screen.
 *
 * ⚠️ **The display is a pure function of this and nothing else — no effects, no flows, no
 * suspending reads inside the composable.** That is not a style preference. The lock-screen image
 * is produced by rendering the composable exactly once, off-screen, with no window; a `LaunchedEffect`
 * would never get a frame to run in, so anything the display fetched for itself would be blank
 * there and present in the HUD. One state in, three identical surfaces out.
 *
 * Every field is nullable or empty-able and every consumer must cope, because a source that could
 * not answer is the ordinary case on a machine that has been asleep, not an exception.
 */
data class StandbyState(
    /** "STARDATE 26621.5" — the console introducing itself. */
    val stardate: String = "",
    val clock: String = "",
    val dateLine: String = "",
    val placeName: String = "",

    /** The Oracle's read. First is the headline; the rest are the standing stream. */
    val insights: List<Insight> = emptyList(),
    val briefing: String = "",

    /** Weather, already formatted in the units the settings ask for. */
    val temperature: String = "",
    val condition: String = "",
    val feelsLike: String = "",
    val weatherDetail: String = "",
    /** Next twenty-four hourly temperatures, for the strip chart. Celsius, chart-relative. */
    val hourlyTemps: List<Double> = emptyList(),

    val mood: MarketMood.Mood? = null,
    /** Top movers as (label, percent), biggest absolute move first. */
    val movers: List<Pair<String, Double>> = emptyList(),

    val headlines: List<String> = emptyList(),

    val reviewsDue: Int = 0,
    val studyStreakDays: Int = 0,

    /** Space weather, in the words the shared explainer chooses. */
    val spaceWeather: String = "",
    /** Set only when the station is genuinely worth walking outside for. */
    val issLine: String = "",

    val nowPlaying: String = "",

    val machine: MachineVitals = MachineVitals(),

    /** How old the oldest feed on the display is, in the words `Freshness` chooses. */
    val freshness: String = "",

    /** What could not answer, and the three rungs' own states. */
    val report: StandbyDiagnostics.Report? = null,
) {
    val lead: Insight? get() = insights.firstOrNull()
    val rest: List<Insight> get() = insights.drop(1)
    val degraded: String get() = StandbyDiagnostics.degradedLine(report?.outcomes.orEmpty())
}

/**
 * What the machine itself is doing.
 *
 * ⚠️ Read defensively and reported as text rather than numbers where the number might be absent.
 * `com.sun.management.OperatingSystemMXBean` is the only way to see real machine memory and CPU
 * load from the JVM, it lives in the `jdk.management` module, and **jpackage's jlink step strips
 * anything not listed in `nativeDistributions.modules`** — a miss there surfaces as a crash on a
 * real Windows box and never as a build failure. The fallbacks below are what make that survivable
 * rather than fatal.
 */
data class MachineVitals(
    val cpuLoadPct: Int = -1,
    val memoryUsedPct: Int = -1,
    val diskUsedPct: Int = -1,
    val uptime: String = "",
    val build: String = "",
) {
    val known: Boolean get() = cpuLoadPct >= 0 || memoryUsedPct >= 0 || diskUsedPct >= 0
}
