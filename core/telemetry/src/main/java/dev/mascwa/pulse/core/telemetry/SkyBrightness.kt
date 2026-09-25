package dev.mascwa.pulse.core.telemetry

import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin

/**
 * The atmosphere's other two halves: how bright the sky is, and how much of a star's light it lets
 * through.
 *
 * [Refraction] is where the air moves things; this is where it hides them. Both ride the star map's
 * ATMOSPHERE switch, and Stellarium draws both by default — a chart with the atmosphere on at midday
 * is a blue sky with the Sun, the Moon and Venus on it, not a black one with Vega at full brightness.
 *
 * ## Two questions, two inputs
 *
 * **Sky brightness** is a function of the SUN's altitude alone: how far below the horizon it is
 * decides whether the sky is dark, twilit or lit. Everything in that half — [skyFactor],
 * [milkyWayFactor], [glowStrength], [dimmingMag] — is a piecewise-linear ramp over the Sun's altitude
 * through the three twilights everybody uses (astronomical −18°, nautical −12°, civil −6°), and every
 * ramp is monotone, which a test holds.
 *
 * **Extinction** is a function of the STAR's altitude alone: how much air its light crosses. The
 * airmass is Rozenberg's 1966 form, `X = 1 / (cos z + 0.025 e^(−11 cos z))`, which is 1 at the zenith
 * and exactly 40 at the horizon and — unlike the plane-parallel `sec z` — stays finite there; the loss
 * is `k·(X − 1)` magnitudes with `k` = [EXTINCTION_MAG_PER_AIRMASS], the coefficient Stellarium ships
 * with. Measured from the shipped function: 0.60 magnitudes ten degrees up, 1.18 at five, 2.89 at one
 * degree, 4.04 on the true horizon and 5.07 on the drawn one — so a first-magnitude star setting
 * through a clear sky fades to a speck and then to nothing over its last two degrees, which is what
 * the eye sees and what Stellarium draws.
 *
 * ⚠️ **The airmass is taken at the APPARENT altitude.** The light's path through the air is the bent
 * one, and Rozenberg is stated in apparent zenith distance. ⚠️ Stellarium's own drawing path does it
 * the other way round — `Extinction::forward` takes Young's 1994 form on the GEOMETRIC altitude,
 * `X(90°) ≈ 31.7` — and its header says in as many words that refraction first and then Rozenberg
 * "seems better". The two are one airmass stated in two variables: measured with the shipped Bennett
 * inverse they agree to 1% above a degree of altitude and to 4% on the drawn horizon (40 against
 * 38.5). [extinctionMag] takes the TRUE altitude the map holds and goes through
 * [Refraction.apparentOf] first, so one table serves the renderer without a second conversion.
 *
 * ⚠️ **Nothing below the DRAWN horizon is extincted.** The map draws what has set, dimmed, so you can
 * see where a constellation is under your feet; a physical extinction there is undefined (the air
 * does not end at the ground, the ground does) and Rozenberg's form explodes past the horizon.
 * Stellarium with its ground switched off makes the same cut two degrees down (its default
 * `extinction_mode_below_horizon = zero`: airmass 0 below cos z = −0.035). So the step is at
 * [Refraction.TRUE_AT_APPARENT_HORIZON_DEG] — the same line the renderer splits its above and below
 * batches on, where a brightness discontinuity already exists by design.
 *
 * ## ⚠️ The invariants a test holds, and what they buy
 *
 * **With the Sun eighteen degrees down or lower, every brightness term is the identity** — factor
 * exactly 0.0, Milky Way factor exactly 1.0, glow 0.0, dimming exactly 0.0 — and
 * [limitingMagnitude] returns the map's own cut bit for bit. **With the atmosphere off, extinction is
 * zero everywhere** (the frame answers that; here every function is pure). So a wrong constant in
 * this file can only ever change a twilight or daytime chart, or a star within a few degrees of the
 * horizon with the air on: a dark sky with the atmosphere off is byte-for-byte what it was before
 * this file existed. That is deliberate — every number here is a judgement about looking at a phone
 * screen, and the owner tunes them from a screenshot.
 *
 * Pure and platform-free.
 */
object SkyBrightness {

    // ---- Sky brightness: everything below is a ramp over the SUN's altitude ---------------------

    /** Astronomical twilight ends: the sky is as dark as it gets. Below this nothing here changes. */
    const val NIGHT_SUN_ALT_DEG = -18.0

    /** Nautical twilight ends. The Milky Way is gone by here; the stars are still all drawn. */
    const val NAUTICAL_SUN_ALT_DEG = -12.0

    /** Civil twilight ends: the bright stars and the planets, and little else. */
    const val CIVIL_SUN_ALT_DEG = -6.0

    /** Sunrise and sunset, at the geometric horizon. */
    const val HORIZON_SUN_ALT_DEG = 0.0

    /** Above this the sky is at full daytime brightness; the ramps stop here. */
    const val DAY_SUN_ALT_DEG = 10.0

    /**
     * How much of the daytime sky colour to blend into the background, 0 at night to 1 by day.
     *
     * The knots are perceptual rather than photometric — the real sky brightens by five orders of
     * magnitude between the end of astronomical twilight and noon, and a linear blend of that would
     * be black until the last few degrees. What the eye reports is closer to this: barely a tint
     * through astronomical twilight, a deep blue by the end of civil twilight, most of the way to day
     * at sunrise.
     */
    fun skyFactor(sunAltDeg: Double): Double = ramp(sunAltDeg, SKY_FACTOR_KNOTS)

    /**
     * What to multiply the Milky Way's opacity by: 1 at night, 0 by the end of nautical twilight.
     *
     * The diffuse glow is the first thing twilight takes and the last it gives back — it needs a
     * genuinely dark sky, and by the time the Sun is twelve degrees down it is gone to the eye while
     * the bright stars are all still there.
     */
    fun milkyWayFactor(sunAltDeg: Double): Double = ramp(sunAltDeg, MILKY_WAY_KNOTS)

    /**
     * How strongly the warm glow around the Sun is drawn, 0..1.
     *
     * A bump rather than a ramp: nothing while the Sun is well down, strongest just after it has set
     * (the orange band is a sunset thing), fading again as the whole sky brightens around it and the
     * glow stops standing out.
     */
    fun glowStrength(sunAltDeg: Double): Double = ramp(sunAltDeg, GLOW_KNOTS)

    /** How far from the Sun the twilight glow reaches, in degrees of sky. */
    const val GLOW_RADIUS_DEG = 40.0

    /**
     * How many magnitudes the brightening sky steals from the map's cut, 0 at night.
     *
     * At the widest field the map's own cut is [SkyProjection.WIDEST_LIMIT], 6.0: so the end of civil
     * twilight leaves 3.5 (Vega, Arcturus, Sirius, Capella), sunrise leaves −0.5 (Sirius, Canopus),
     * and full day leaves −4.0, which is Venus and nothing else — a chart that agrees with the naked
     * eye at each. The same loss applies at every field, so a narrow field in daylight still shows
     * what a telescope would: its cut is deeper by [SkyProjection.MAGNITUDES_PER_DECADE] per decade
     * of zoom, and the loss does not grow with it.
     */
    fun dimmingMag(sunAltDeg: Double): Double = ramp(sunAltDeg, DIMMING_KNOTS)

    /**
     * The map's magnitude cut under this sky: [mapLimit] less [dimmingMag].
     *
     * ⚠️ With the Sun at or below [NAUTICAL_SUN_ALT_DEG] the dimming is exactly zero and this returns
     * [mapLimit] bit for bit — a test holds it — so a dark sky's cut cannot drift by a rounding
     * error through this function.
     */
    fun limitingMagnitude(mapLimit: Double, sunAltDeg: Double): Double = mapLimit - dimmingMag(sunAltDeg)

    /** Knots for [skyFactor]: Sun altitude → factor. Monotone, and asserted so. */
    private val SKY_FACTOR_KNOTS = doubleArrayOf(
        NIGHT_SUN_ALT_DEG, 0.0,
        NAUTICAL_SUN_ALT_DEG, 0.03,
        CIVIL_SUN_ALT_DEG, 0.20,
        HORIZON_SUN_ALT_DEG, 0.60,
        DAY_SUN_ALT_DEG, 1.0,
    )

    private val MILKY_WAY_KNOTS = doubleArrayOf(
        NIGHT_SUN_ALT_DEG, 1.0,
        NAUTICAL_SUN_ALT_DEG, 0.0,
    )

    /** Where the glow peaks: just after sunset, when the band above the Sun is at its most orange. */
    const val GLOW_PEAK_SUN_ALT_DEG = -2.0

    private val GLOW_KNOTS = doubleArrayOf(
        NAUTICAL_SUN_ALT_DEG, 0.0,
        GLOW_PEAK_SUN_ALT_DEG, 1.0,
        8.0, 0.0,
    )

    /** What full daylight costs at the widest field: 6.0 down to −4.0, Venus alone. */
    const val DAY_DIMMING_MAG = 10.0

    private val DIMMING_KNOTS = doubleArrayOf(
        NAUTICAL_SUN_ALT_DEG, 0.0,
        CIVIL_SUN_ALT_DEG, 2.5,
        HORIZON_SUN_ALT_DEG, 6.5,
        DAY_SUN_ALT_DEG, DAY_DIMMING_MAG,
    )

    /**
     * Piecewise-linear interpolation through `(x, y)` pairs sorted by `x`, clamped at both ends.
     *
     * ⚠️ Clamping is what makes the night identity exact: below the first knot the answer IS the first
     * knot's value, not an extrapolation that happens to round to it.
     */
    private fun ramp(x: Double, knots: DoubleArray): Double {
        if (x.isNaN()) return knots[1]
        if (x <= knots[0]) return knots[1]
        val last = knots.size - 2
        if (x >= knots[last]) return knots[last + 1]
        var i = 0
        while (x > knots[i + 2]) i += 2
        val x0 = knots[i]
        val y0 = knots[i + 1]
        val x1 = knots[i + 2]
        val y1 = knots[i + 3]
        return y0 + (y1 - y0) * (x - x0) / (x1 - x0)
    }

    // ---- Extinction: everything below is a function of the STAR's altitude ---------------------

    /**
     * Magnitudes lost per airmass at the zenith — Stellarium's shipped default.
     *
     * ⚠️ Read out of Stellarium's source rather than recalled, because the first figure written here
     * was 0.2 and wrong: `StelSkyDrawer.cpp` reads `landscape/atmospheric_extinction_coefficient`
     * with a default of 0.13, while the `Extinction` header beside it still carries a stale
     * "(default 0.20)". That same header's site table — 0.1 for the highest mountains, 0.2 for a
     * very good lowland site, 0.35 for typical lowland, 0.5 in a humid climate — says a lakeshore
     * would honestly sit nearer 0.35; the directive was to draw what Stellarium draws, so its default
     * it is. It scales everything below linearly and is the one number here somebody might
     * reasonably want as a setting.
     */
    const val EXTINCTION_MAG_PER_AIRMASS = 0.13

    /**
     * Rozenberg's airmass for an APPARENT altitude: 1 at the zenith, 40 on the horizon, finite
     * everywhere.
     *
     * ⚠️ Clamped at the horizon rather than evaluated below it: the form's denominator shrinks past
     * zero altitude and the airmass runs away, which is the same nonsense `sec z` produces at the
     * horizon moved two degrees down. No caller reaches there — [extinctionMag] stops at the drawn
     * horizon — but a formula that answers 574 for an input somebody might one day pass it is worse
     * than one that answers 40.
     */
    fun airmass(apparentAltDeg: Double): Double {
        val cosZ = sin(Math.toRadians(apparentAltDeg.coerceIn(0.0, 90.0)))
        return 1.0 / (cosZ + 0.025 * exp(-11.0 * cosZ))
    }

    /**
     * How many magnitudes the air takes from a star at a TRUE altitude, through the apparent one.
     *
     * Zero at the zenith (asserted exactly — Rozenberg's form is a shade under 1 there and the
     * difference is clamped away rather than allowed to brighten a star), zero below the drawn
     * horizon, and `k · 39` — about 5.1 — on it.
     */
    fun extinctionMag(trueAltDeg: Double): Double {
        if (trueAltDeg.isNaN() || trueAltDeg < Refraction.TRUE_AT_APPARENT_HORIZON_DEG) return 0.0
        val x = airmass(Refraction.apparentOf(trueAltDeg))
        return (EXTINCTION_MAG_PER_AIRMASS * (x - 1.0)).coerceAtLeast(0.0)
    }

    /** The fraction of a star's light that arrives after [extinctionMag], `10^(−0.4 m)`. */
    fun transmission(trueAltDeg: Double): Double = 10.0.pow(-0.4 * extinctionMag(trueAltDeg))

    /**
     * How many entries the per-star tables hold over the sine of altitude from the drawn horizon to
     * the zenith.
     *
     * ⚠️ Indexed by the SINE rather than the angle, because that is what the renderer already holds
     * for every star — one dot product, no `asin`. The sine is coarse in degrees near the zenith and
     * fine near the horizon, which is exactly where the extinction changes fastest (about two
     * magnitudes over the last degree): at this size a step is a hundredth of a degree there, and
     * the interpolation error is measured at a thousandth of a magnitude or better everywhere.
     */
    const val TABLE_ENTRIES = 4096

    /** The sine of the drawn horizon's TRUE altitude — where the tables start. */
    val SIN_DRAWN_HORIZON: Double = sin(Math.toRadians(Refraction.TRUE_AT_APPARENT_HORIZON_DEG))

    private val TABLE_SCALE: Double = (TABLE_ENTRIES - 1) / (1.0 - SIN_DRAWN_HORIZON)

    /** [extinctionMag] sampled over the sine of altitude, built on first use. Thirty-two kilobytes. */
    private val EXTINCTION_TABLE: DoubleArray by lazy {
        DoubleArray(TABLE_ENTRIES) { i ->
            val s = SIN_DRAWN_HORIZON + i / TABLE_SCALE
            extinctionMag(Math.toDegrees(kotlin.math.asin(s.coerceIn(-1.0, 1.0))))
        }
    }

    /** [transmission] sampled the same way, so the Milky Way pass needs no `pow` per pixel. */
    private val TRANSMISSION_TABLE: DoubleArray by lazy {
        val e = EXTINCTION_TABLE
        DoubleArray(TABLE_ENTRIES) { i -> 10.0.pow(-0.4 * e[i]) }
    }

    /**
     * [extinctionMag] by table lookup and linear interpolation, from the SINE of the true altitude
     * — the per-star path.
     */
    fun fastExtinctionMag(sinTrueAlt: Double): Double = lookup(EXTINCTION_TABLE, sinTrueAlt, 0.0)

    /** [transmission] by the same lookup — the per-pixel path. */
    fun fastTransmission(sinTrueAlt: Double): Double = lookup(TRANSMISSION_TABLE, sinTrueAlt, 1.0)

    private fun lookup(table: DoubleArray, sinTrueAlt: Double, below: Double): Double {
        if (sinTrueAlt.isNaN() || sinTrueAlt < SIN_DRAWN_HORIZON) return below
        val x = (sinTrueAlt - SIN_DRAWN_HORIZON) * TABLE_SCALE
        val i = x.toInt()
        if (i >= TABLE_ENTRIES - 1) return table[TABLE_ENTRIES - 1]
        val f = x - i
        val a = table[i]
        return a + (table[i + 1] - a) * f
    }
}
