package dev.mascwa.pulse.data.space

import dev.mascwa.pulse.core.telemetry.SolarActivity
import kotlinx.serialization.Serializable

@Serializable
data class SpaceAlert(
    val title: String,
    val issued: String,
    val message: String,
)

/** One sample on a time series. A bare `List<Double>` cannot be plotted against a real clock. */
@Serializable
data class TimePoint(val t: Long, val v: Double)

/** A NOAA R/S/G scale reading for one day — current conditions or a forecast day. */
@Serializable
data class ScaleForecast(
    val label: String = "",   // "Today", "Day 2", …
    val r: Int = 0,
    val s: Int = 0,
    val g: Int = 0,
    /** Probability (%) of an R1-R2 blackout, when NOAA publishes one for this day. */
    val rMinorProbPct: Int? = null,
    /** Probability (%) of an R3+ blackout. */
    val rMajorProbPct: Int? = null,
    /** Probability (%) of an S1+ radiation storm. */
    val sProbPct: Int? = null,
)

/** A sunspot group currently on the visible disc. */
@Serializable
data class SolarRegion(
    val number: Int,
    val location: String = "",
    val spotClass: String = "",
    val magClass: String = "",
    val area: Int = 0,
    val spotCount: Int = 0,
    val cFlareProbPct: Int = 0,
    val mFlareProbPct: Int = 0,
    val xFlareProbPct: Int = 0,
    /** The date NOAA actually observed this — the feed can lag by weeks, so never imply "now". */
    val observedDate: String = "",
)

@Serializable
data class SpaceWeather(
    val kp: Double?,                       // latest planetary K-index
    val kpSeries: List<Double> = emptyList(),
    val solarWindSpeed: Double? = null,    // km/s
    val bz: Double? = null,                // nT (negative = geoeffective)
    val stormLevel: String = "None",       // NOAA G-scale label
    val auroraChance: String = "Low",
    val auroraProbabilityPct: Int? = null, // NOAA OVATION probability at your location
    val alerts: List<SpaceAlert> = emptyList(),
    val updatedEpochMs: Long = System.currentTimeMillis(),

    // ---- everything below is new; all defaulted so cached blobs still deserialize ----

    /** Kp with timestamps, for a chart with a real time axis. */
    val kpPoints: List<TimePoint> = emptyList(),
    /** Solar wind speed history (km/s). */
    val windPoints: List<TimePoint> = emptyList(),
    /** IMF Bz history (nT). */
    val bzPoints: List<TimePoint> = emptyList(),

    /** GOES long-channel (0.1-0.8 nm) X-ray flux, W/m² — the flare-class channel. */
    val xrayFlux: Double? = null,
    val xrayPoints: List<TimePoint> = emptyList(),
    /** GOES short-channel (0.05-0.4 nm) X-ray flux, W/m². */
    val xrayShortFlux: Double? = null,
    /** Integral proton flux at >=10 MeV, pfu — what the S scale is defined on. */
    val protonFlux: Double? = null,
    val protonPoints: List<TimePoint> = emptyList(),
    /** F10.7 cm solar radio flux, sfu — the standard proxy for solar activity. */
    val f107: Double? = null,
    /** The 90-day mean F10.7 NOAA publishes alongside it, when present. */
    val f107Mean: Double? = null,

    /** Current NOAA scales, straight from `noaa-scales.json` rather than derived. */
    val scalesNow: ScaleForecast? = null,
    /** The worst scales reached in the past 24 hours. */
    val scales24h: ScaleForecast? = null,
    /** The three forecast days NOAA publishes. */
    val scaleForecast: List<ScaleForecast> = emptyList(),

    /** Sunspot groups on the disc, each stamped with its own observation date. */
    val regions: List<SolarRegion> = emptyList(),
) {
    /** The current flare class from the long channel, e.g. `B5.8` — null on a quiet Sun. */
    val flareLabel: String? get() = SolarActivity.flareClass(xrayFlux)?.label

    /** Radio-blackout level now: NOAA's own number when it published one, else from the flux. */
    val rLevel: Int get() = scalesNow?.r ?: SolarActivity.radioBlackout(xrayFlux)

    /** Radiation-storm level now. */
    val sLevel: Int get() = scalesNow?.s ?: SolarActivity.radiationStorm(protonFlux)

    /** Geomagnetic-storm level now. */
    val gLevel: Int get() = scalesNow?.g ?: SolarActivity.geomagneticStorm(kp)

    /** One line naming whichever of the three scales is worst right now. */
    val headline: String get() = SolarActivity.headline(rLevel, sLevel, gLevel)

    companion object {
        /** Delegates to the tested core, but keeps "—" for *no reading* — the core's "None" would
         *  claim we know there is no storm when in fact we know nothing. */
        fun stormLevelForKp(kp: Double?): String =
            if (kp == null) "—" else SolarActivity.scaleLabel('G', SolarActivity.geomagneticStorm(kp))

        fun auroraForKp(kp: Double?): String = when {
            kp == null -> "—"
            kp >= 7 -> "Very high — visible at mid-latitudes"
            kp >= 5 -> "High — possible at higher latitudes"
            kp >= 4 -> "Moderate — high latitudes"
            else -> "Low"
        }
    }
}
