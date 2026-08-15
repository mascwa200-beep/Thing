package dev.mascwa.pulse.core.telemetry

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Where a satellite is in *your* sky, and when it will next cross it.
 *
 * [Sgp4] deliberately stops at a TEME state vector, and [Ephemeris] knows where the Sun is. This
 * joins them: it rotates the satellite into an Earth-fixed frame, works out its look angles from a
 * ground site, decides whether sunlight is actually falling on it, and searches for passes.
 *
 * Everything here is deterministic — no clock reads, no I/O — so it is exercised end to end by
 * [SatellitePassesTest] against values generated from Skyfield and the JPL DE421 ephemeris.
 *
 * Two frames meet here on purpose. SGP4 is *defined* against WGS-72 and must keep those constants
 * or it stops being SGP4, while an observer's coordinates arrive from the phone's GNSS receiver in
 * WGS-84. Mixing them is standard practice: the ellipsoids differ by about two metres at the
 * equator, which is orders of magnitude below the error an hours-old element set already carries.
 */
object SatellitePasses {

    // ---- constants -------------------------------------------------------------------------

    private const val DEG = PI / 180.0

    /** WGS-84, the frame consumer GNSS reports in — see the class note on the deliberate mix. */
    private const val WGS84_A_KM = 6378.137
    private const val WGS84_F = 1.0 / 298.257223563
    private const val WGS84_B_KM = WGS84_A_KM * (1.0 - WGS84_F)
    private const val WGS84_E2 = WGS84_F * (2.0 - WGS84_F)
    private const val WGS84_EP2 = WGS84_E2 / (1.0 - WGS84_E2)

    /** Earth's rotation rate, rad/s — needed to turn an inertial velocity into an Earth-fixed one. */
    private const val EARTH_ROTATION_RAD_S = 7.292115e-5

    /** Half-angles of the Sun's shadow cones at Earth (Vallado). The umbra converges, the penumbra
     *  diverges, which is why one is subtracted below and the other added. */
    private const val UMBRA_ANGLE_DEG = 0.264121
    private const val PENUMBRA_ANGLE_DEG = 0.269007

    /** Below this solar altitude the sky is dark enough for a lit satellite to stand out. Matches
     *  the civil-twilight threshold every pass-prediction service uses. */
    const val DARK_ENOUGH_SUN_ALTITUDE_DEG = Ephemeris.Altitudes.CIVIL_TWILIGHT

    /** Default horizon cut for a reported pass — anything lower is usually behind buildings. */
    const val DEFAULT_MIN_ELEVATION_DEG = 10.0

    /** Hard ceiling on how finely one pass is sampled for brightness and closest approach. */
    private const val MAX_PASS_SAMPLES = 240L

    // ---- types -----------------------------------------------------------------------------

    /** A ground observer. Altitude is metres above the ellipsoid. */
    data class Site(
        val latitudeDeg: Double,
        val longitudeDeg: Double,
        val altitudeM: Double = 0.0,
    )

    /** How much sunlight is reaching the satellite. */
    enum class Illumination {
        /** Full sunlight — the only state in which a satellite is naked-eye visible. */
        SUNLIT,

        /** Partial shadow: the Sun is a bisected disc from up there, and the object dims sharply. */
        PENUMBRA,

        /** Full shadow. */
        UMBRA,
        ;

        val isLit: Boolean get() = this == SUNLIT
    }

    /** The satellite as seen from a site at one instant. */
    data class LookAngle(
        val altitudeDeg: Double,
        val azimuthDeg: Double,
        val rangeKm: Double,
        /** Positive is receding — the sign a Doppler shift follows. */
        val rangeRateKmS: Double,
        val illumination: Illumination,
        /** Sun-satellite-observer angle; 0 is fully lit head-on, 180 is fully back-lit. */
        val phaseAngleDeg: Double,
        /** Estimated visual magnitude, or null when this object has no published standard
         *  magnitude. A guessed brightness is worse than an absent one. */
        val magnitude: Double?,
    ) {
        val aboveHorizon: Boolean get() = altitudeDeg > 0.0
        val cardinal: String get() = Geodesy.cardinal(azimuthDeg)
    }

    /** The point on the ground the satellite is directly above. */
    data class SubPoint(
        val latitudeDeg: Double,
        val longitudeDeg: Double,
        val altitudeKm: Double,
    )

    /** Whether a pass can actually be seen, and if not, why not. */
    enum class PassKind {
        /** Sky dark, satellite lit — go outside and look. */
        VISIBLE,

        /** Above the horizon, but the Sun is up: a radio can hear it, an eye cannot see it. */
        DAYLIGHT,

        /** Sky dark, but the satellite spends the whole pass in Earth's shadow. */
        ECLIPSED,
    }

    /** One horizon-to-horizon crossing. Rise and set are always real crossings inside the searched
     *  window — see [passes] for why a partial pass is never reported. */
    data class Pass(
        val noradId: Int,
        val name: String,
        val riseEpochMs: Long,
        val riseAzimuthDeg: Double,
        val culminationEpochMs: Long,
        val culminationAzimuthDeg: Double,
        val maxAltitudeDeg: Double,
        val setEpochMs: Long,
        val setAzimuthDeg: Double,
        val kind: PassKind,
        /** Brightest magnitude reached while sunlit, or null when unknown or never lit. */
        val brightestMagnitude: Double?,
        val minRangeKm: Double,
    ) {
        val durationMs: Long get() = setEpochMs - riseEpochMs
        val isVisible: Boolean get() = kind == PassKind.VISIBLE

        /** "SW to NE" — the two horizons the pass runs between. */
        val trackDescription: String
            get() = "${Geodesy.cardinal(riseAzimuthDeg)} to ${Geodesy.cardinal(setAzimuthDeg)}"
    }

    private data class Vec3(val x: Double, val y: Double, val z: Double) {
        val length: Double get() = sqrt(x * x + y * y + z * z)
        operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
        infix fun dot(o: Vec3) = x * o.x + y * o.y + z * o.z
    }

    /**
     * The observer's fixed geometry, worked out once.
     *
     * This is not micro-optimisation. A pass search evaluates hundreds of thousands of instants,
     * and rebuilding the site vector and its four trig terms at every one of them dominated the
     * cost: scanning two days over the bright-object catalogue took 88 seconds before this existed.
     */
    private class Observer(site: Site) {
        val ecef = siteEcef(site)
        val sinLat = sin(site.latitudeDeg * DEG)
        val cosLat = cos(site.latitudeDeg * DEG)
        val sinLon = sin(site.longitudeDeg * DEG)
        val cosLon = cos(site.longitudeDeg * DEG)
    }

    // ---- standard magnitudes ---------------------------------------------------------------

    /**
     * Published standard magnitudes — brightness at 1000 km, half illuminated.
     *
     * Deliberately tiny. There is no size or albedo anywhere in a TLE, so a magnitude can only come
     * from an observer-derived catalogue, and inventing one produces a *confidently wrong* number
     * on screen. Objects absent from this table report a null magnitude, and the surface says so.
     * Adding an entry is a content decision backed by a published value, not an estimate.
     */
    private val STANDARD_MAGNITUDES = mapOf(
        25544 to -1.8, // ISS (ZARYA)
        48274 to -0.5, // CSS (TIANHE)
    )

    fun standardMagnitude(noradId: Int): Double? = STANDARD_MAGNITUDES[noradId]

    /**
     * The visual-magnitude model pass predictors have used for decades: inverse-square with range,
     * modulated by how much of the lit hemisphere is turned towards you.
     */
    fun visualMagnitude(standardMagnitude: Double, rangeKm: Double, phaseAngleDeg: Double): Double? {
        if (rangeKm <= 0.0 || !rangeKm.isFinite()) return null
        val phase = phaseAngleDeg.coerceIn(0.0, 180.0) * DEG
        val illuminatedFraction = ((PI - phase) * cos(phase) + sin(phase)) / PI
        // Fully back-lit: nothing is reflecting towards the observer, so there is no magnitude.
        if (illuminatedFraction <= 1e-9) return null
        return standardMagnitude - 15.75 + 2.5 * log10(rangeKm * rangeKm / illuminatedFraction)
    }

    // ---- frames ----------------------------------------------------------------------------

    /** Rotate about Z by [angleDeg]; a positive angle rotates the frame, not the vector. */
    private fun rotZ(v: Vec3, angleDeg: Double): Vec3 {
        val a = angleDeg * DEG
        val c = cos(a)
        val s = sin(a)
        return Vec3(v.x * c + v.y * s, -v.x * s + v.y * c, v.z)
    }

    /** Geodetic coordinates to an Earth-fixed vector, kilometres. */
    private fun siteEcef(site: Site): Vec3 {
        val lat = site.latitudeDeg * DEG
        val lon = site.longitudeDeg * DEG
        val h = site.altitudeM / 1000.0
        val sinLat = sin(lat)
        val n = WGS84_A_KM / sqrt(1.0 - WGS84_E2 * sinLat * sinLat)
        return Vec3(
            (n + h) * cos(lat) * cos(lon),
            (n + h) * cos(lat) * sin(lon),
            (n * (1.0 - WGS84_E2) + h) * sinLat,
        )
    }

    /** Earth-fixed vector back to geodetic coordinates, via Bowring's method. */
    private fun ecefToGeodetic(v: Vec3): SubPoint {
        val p = sqrt(v.x * v.x + v.y * v.y)
        // Directly over a pole: the general formula divides by cos(lat), so take the axis case.
        if (p < 1e-9) {
            val lat = if (v.z >= 0.0) 90.0 else -90.0
            return SubPoint(lat, 0.0, abs(v.z) - WGS84_B_KM)
        }
        val theta = atan2(v.z * WGS84_A_KM, p * WGS84_B_KM)
        val sinT = sin(theta)
        val cosT = cos(theta)
        val lat = atan2(
            v.z + WGS84_EP2 * WGS84_B_KM * sinT * sinT * sinT,
            p - WGS84_E2 * WGS84_A_KM * cosT * cosT * cosT,
        )
        val sinLat = sin(lat)
        val n = WGS84_A_KM / sqrt(1.0 - WGS84_E2 * sinLat * sinLat)
        return SubPoint(
            latitudeDeg = lat / DEG,
            longitudeDeg = Geodesy.normalizeLongitude(atan2(v.y, v.x) / DEG),
            altitudeKm = p / cos(lat) - n,
        )
    }

    // ---- primitives ------------------------------------------------------------------------

    /** The point on the ground directly beneath the satellite, or null if it cannot be propagated. */
    fun subPoint(propagator: Sgp4.Propagator, epochMs: Long): SubPoint? {
        val state = (propagator.propagateAt(epochMs) as? Sgp4.Propagation.Ok)?.state ?: return null
        val gmst = Ephemeris.gmstDeg(Ephemeris.julianDate(epochMs))
        return ecefToGeodetic(rotZ(Vec3(state.x, state.y, state.z), gmst))
    }

    /**
     * Where the satellite sits in the site's sky, how fast it is closing, and whether the Sun is on
     * it. Null when the element set cannot be propagated to this instant — a caller gets an honest
     * absence rather than a plausible wrong sky position.
     */
    fun look(propagator: Sgp4.Propagator, site: Site, epochMs: Long): LookAngle? =
        look(propagator, Observer(site), epochMs)

    private fun look(propagator: Sgp4.Propagator, obs: Observer, epochMs: Long): LookAngle? {
        val state = (propagator.propagateAt(epochMs) as? Sgp4.Propagation.Ok)?.state ?: return null
        val gmst = Ephemeris.gmstDeg(Ephemeris.julianDate(epochMs))

        val satEcef = rotZ(Vec3(state.x, state.y, state.z), gmst)
        // An Earth-fixed velocity is the rotated inertial one minus the frame's own rotation.
        val vRot = rotZ(Vec3(state.vx, state.vy, state.vz), gmst)
        val satVelEcef = Vec3(
            vRot.x + EARTH_ROTATION_RAD_S * satEcef.y,
            vRot.y - EARTH_ROTATION_RAD_S * satEcef.x,
            vRot.z,
        )

        val rel = satEcef - obs.ecef
        val range = rel.length
        if (range <= 0.0 || !range.isFinite()) return null

        // South-east-zenith: the local frame azimuth and elevation are defined in.
        val south = obs.sinLat * obs.cosLon * rel.x + obs.sinLat * obs.sinLon * rel.y - obs.cosLat * rel.z
        val east = -obs.sinLon * rel.x + obs.cosLon * rel.y
        val zenith = obs.cosLat * obs.cosLon * rel.x + obs.cosLat * obs.sinLon * rel.y + obs.sinLat * rel.z

        val altitude = asin((zenith / range).coerceIn(-1.0, 1.0)) / DEG
        val azimuth = Geodesy.normalizeBearing(atan2(east, -south) / DEG)
        val rangeRate = (rel dot satVelEcef) / range

        val sunEcef = sunEcef(epochMs, gmst)
        val illumination = illumination(satEcef, sunEcef)
        val phase = phaseAngleDeg(satEcef, sunEcef, obs.ecef)
        val magnitude = standardMagnitude(propagator.elements.noradId)
            ?.takeIf { illumination.isLit }
            ?.let { visualMagnitude(it, range, phase) }

        return LookAngle(altitude, azimuth, range, rangeRate, illumination, phase, magnitude)
    }

    /**
     * Altitude alone, which is all the pass search ever asks for.
     *
     * [look] additionally works out an Earth-fixed velocity, the Sun's position, the shadow cone,
     * the phase angle and a magnitude. None of that changes whether the satellite is above the
     * horizon, and computing it at every scanned instant made a two-day search over the bright
     * catalogue take a minute and a half instead of a second.
     */
    private fun altitudeDegAt(propagator: Sgp4.Propagator, obs: Observer, epochMs: Long): Double? {
        val state = (propagator.propagateAt(epochMs) as? Sgp4.Propagation.Ok)?.state ?: return null
        val gmst = Ephemeris.gmstDeg(Ephemeris.julianDate(epochMs))
        val satEcef = rotZ(Vec3(state.x, state.y, state.z), gmst)
        val rel = satEcef - obs.ecef
        val range = rel.length
        if (range <= 0.0 || !range.isFinite()) return null
        val zenith = obs.cosLat * obs.cosLon * rel.x + obs.cosLat * obs.sinLon * rel.y + obs.sinLat * rel.z
        return asin((zenith / range).coerceIn(-1.0, 1.0)) / DEG
    }

    /** The Sun as an Earth-fixed vector, kilometres. */
    private fun sunEcef(epochMs: Long, gmstDeg: Double): Vec3 {
        val sun = Ephemeris.sunEquatorial(epochMs)
        val ra = sun.rightAscensionDeg * DEG
        val dec = sun.declinationDeg * DEG
        val r = sun.distanceKm
        // Equatorial-of-date and TEME share an equator and differ only by the equation of the
        // equinoxes — about an arcsecond, far below anything a shadow boundary cares about.
        return rotZ(Vec3(r * cos(dec) * cos(ra), r * cos(dec) * sin(ra), r * sin(dec)), gmstDeg)
    }

    /**
     * Vallado's conical shadow test. The cheap alternative — a cylinder the width of the Earth —
     * is wrong precisely at the shadow boundary, which is where the interesting passes are: a
     * satellite emerging into sunlight halfway across the sky is the classic bright pass.
     */
    private fun illumination(satEcef: Vec3, sunEcef: Vec3): Illumination {
        // On the sunward side of the Earth nothing can be shadowed by it.
        if ((satEcef dot sunEcef) > 0.0) return Illumination.SUNLIT

        val satRange = satEcef.length
        val sunRange = sunEcef.length
        if (satRange <= 0.0 || sunRange <= 0.0) return Illumination.SUNLIT

        // Angle between the anti-sun axis (the shadow's centreline) and the satellite.
        val cosAngle = ((satEcef dot sunEcef) / (satRange * sunRange)).coerceIn(-1.0, 1.0)
        val angle = PI - acos(cosAngle)
        val alongAxis = satRange * cos(angle)
        val offAxis = satRange * sin(angle)

        val penumbraRadius = WGS84_A_KM + tan(PENUMBRA_ANGLE_DEG * DEG) * alongAxis
        if (offAxis > penumbraRadius) return Illumination.SUNLIT
        val umbraRadius = WGS84_A_KM - tan(UMBRA_ANGLE_DEG * DEG) * alongAxis
        return if (offAxis <= umbraRadius) Illumination.UMBRA else Illumination.PENUMBRA
    }

    /** Sun-satellite-observer angle, degrees. */
    private fun phaseAngleDeg(satEcef: Vec3, sunEcef: Vec3, obsEcef: Vec3): Double {
        val toSun = sunEcef - satEcef
        val toObserver = obsEcef - satEcef
        val denominator = toSun.length * toObserver.length
        if (denominator <= 0.0) return 0.0
        return acos(((toSun dot toObserver) / denominator).coerceIn(-1.0, 1.0)) / DEG
    }

    // ---- pass search -----------------------------------------------------------------------

    /**
     * Every complete pass between [fromEpochMs] and [toEpochMs].
     *
     * Only whole passes are reported. A satellite already up when the search begins has no
     * observable rise, and one still up when it ends has no set — reporting either would mean
     * inventing a boundary time, so both are skipped. A caller wanting "what is overhead right
     * now" asks [look], which answers that question directly.
     *
     * The search is a coarse scan for horizon crossings, then bisection onto each crossing and a
     * ternary search for the culmination, so the returned times are accurate to well under a
     * second — against the element set given. TLE age, not this arithmetic, is the real error term.
     */
    fun passes(
        elements: Sgp4.Elements,
        site: Site,
        fromEpochMs: Long,
        toEpochMs: Long,
        minElevationDeg: Double = DEFAULT_MIN_ELEVATION_DEG,
        coarseStepMs: Long = 30_000L,
        limit: Int = 40,
    ): List<Pass> {
        if (toEpochMs <= fromEpochMs || limit <= 0) return emptyList()
        val step = coarseStepMs.coerceAtLeast(1_000L)
        val propagator = Sgp4.propagator(elements)
        // Deep-space and malformed element sets fail here rather than returning wrong times.
        if (propagator.propagateAt(fromEpochMs) !is Sgp4.Propagation.Ok) return emptyList()

        val obs = Observer(site)
        fun altitudeAt(ms: Long): Double? = altitudeDegAt(propagator, obs, ms)

        val out = mutableListOf<Pass>()
        var previousMs = fromEpochMs
        var previousAlt = altitudeAt(previousMs) ?: return emptyList()
        // Null means "no rise seen yet". A satellite already up when the window opens has no
        // observable rise, so its descent must NOT be treated as the end of a pass -- there is no
        // start time to pair it with, and a sentinel like 0 would be read as 1970.
        var riseMs: Long? = null

        var t = fromEpochMs + step
        while (t <= toEpochMs && out.size < limit) {
            val alt = altitudeAt(t)
            if (alt == null) break

            val crossedUp = previousAlt < minElevationDeg && alt >= minElevationDeg
            val crossedDown = previousAlt >= minElevationDeg && alt < minElevationDeg
            if (crossedUp && riseMs == null) {
                riseMs = bisectCrossing(::altitudeAt, previousMs, t, minElevationDeg, rising = true)
            } else if (crossedDown) {
                val start = riseMs
                if (start != null) {
                    val setMs = bisectCrossing(::altitudeAt, previousMs, t, minElevationDeg, rising = false)
                    buildPass(propagator, site, obs, start, setMs, step)?.let { out += it }
                }
                riseMs = null
            }

            previousMs = t
            previousAlt = alt
            t += step
        }
        return out
    }

    /** Bisect a bracketed horizon crossing down to millisecond resolution. */
    private fun bisectCrossing(
        altitudeAt: (Long) -> Double?,
        lowMs: Long,
        highMs: Long,
        thresholdDeg: Double,
        rising: Boolean,
    ): Long {
        var lo = lowMs
        var hi = highMs
        repeat(32) {
            if (hi - lo <= 1L) return@repeat
            val mid = lo + (hi - lo) / 2
            val alt = altitudeAt(mid) ?: return mid
            val above = alt >= thresholdDeg
            // Keep the bracket straddling the crossing, whichever way it runs.
            if (above == rising) hi = mid else lo = mid
        }
        return if (rising) hi else lo
    }

    private fun buildPass(
        propagator: Sgp4.Propagator,
        site: Site,
        obs: Observer,
        riseMs: Long,
        setMs: Long,
        stepMs: Long,
    ): Pass? {
        val rise = look(propagator, obs, riseMs) ?: return null
        val set = look(propagator, obs, setMs) ?: return null

        // Altitude has a single maximum across a pass, so a ternary search converges on it.
        var lo = riseMs
        var hi = setMs
        repeat(60) {
            if (hi - lo <= 1L) return@repeat
            val third = (hi - lo) / 3
            val a = lo + third
            val b = hi - third
            val altA = altitudeDegAt(propagator, obs, a) ?: return@repeat
            val altB = altitudeDegAt(propagator, obs, b) ?: return@repeat
            if (altA < altB) lo = a else hi = b
        }
        val culminationMs = lo + (hi - lo) / 2
        val culmination = look(propagator, obs, culminationMs) ?: return null

        // Walk the pass to find the brightest lit moment and the closest approach. The sample count
        // is bounded rather than derived purely from the interval: a bad rise/set pair once turned
        // this into a fifty-nine-million-iteration loop, and a bound costs nothing to keep.
        var brightest: Double? = null
        var minRange = minOf(rise.rangeKm, culmination.rangeKm, set.rangeKm)
        var anySunlit = false
        val span = (setMs - riseMs).coerceAtLeast(1L)
        val sampleStep = maxOf(
            stepMs.coerceAtMost((span / 8).coerceAtLeast(1_000L)),
            span / MAX_PASS_SAMPLES,
        ).coerceAtLeast(1L)
        var t = riseMs
        while (t <= setMs) {
            val at = look(propagator, obs, t)
            if (at != null) {
                if (at.illumination.isLit) anySunlit = true
                if (at.rangeKm < minRange) minRange = at.rangeKm
                // The brightest moment is NOT the closest one: the phase angle sweeps right across
                // a pass, so peak brightness usually falls short of culmination. It has to be
                // searched for.
                val mag = at.magnitude
                val best = brightest
                if (mag != null && (best == null || mag < best)) brightest = mag
            }
            t += sampleStep
        }
        if (culmination.illumination.isLit) anySunlit = true

        val sunAltitude = Ephemeris.sunPosition(site.latitudeDeg, site.longitudeDeg, culminationMs)
            .altitudeDeg
        val kind = when {
            sunAltitude > DARK_ENOUGH_SUN_ALTITUDE_DEG -> PassKind.DAYLIGHT
            anySunlit -> PassKind.VISIBLE
            else -> PassKind.ECLIPSED
        }

        return Pass(
            noradId = propagator.elements.noradId,
            name = propagator.elements.name,
            riseEpochMs = riseMs,
            riseAzimuthDeg = rise.azimuthDeg,
            culminationEpochMs = culminationMs,
            culminationAzimuthDeg = culmination.azimuthDeg,
            maxAltitudeDeg = culmination.altitudeDeg,
            setEpochMs = setMs,
            setAzimuthDeg = set.azimuthDeg,
            kind = kind,
            brightestMagnitude = if (kind == PassKind.VISIBLE) brightest else null,
            minRangeKm = minRange,
        )
    }

    // ---- ground track ----------------------------------------------------------------------

    /** [count] sub-points at [stepMs] intervals, for drawing the path over a map. */
    fun groundTrack(
        elements: Sgp4.Elements,
        fromEpochMs: Long,
        stepMs: Long,
        count: Int,
    ): List<SubPoint> {
        if (count <= 0 || stepMs == 0L) return emptyList()
        val propagator = Sgp4.propagator(elements)
        val out = ArrayList<SubPoint>(count)
        for (i in 0 until count) {
            out += subPoint(propagator, fromEpochMs + i * stepMs) ?: break
        }
        return out
    }

    /**
     * The same track split wherever it crosses the antimeridian.
     *
     * A single line string through a +179 to -179 step draws a stripe straight back across the
     * whole map. Splitting is the only way to render an orbit honestly on a flat projection.
     */
    fun groundTrackSegments(
        elements: Sgp4.Elements,
        fromEpochMs: Long,
        stepMs: Long,
        count: Int,
    ): List<List<SubPoint>> {
        val points = groundTrack(elements, fromEpochMs, stepMs, count)
        if (points.isEmpty()) return emptyList()
        val segments = mutableListOf<List<SubPoint>>()
        var current = mutableListOf(points.first())
        for (i in 1 until points.size) {
            val previous = points[i - 1]
            val point = points[i]
            if (abs(point.longitudeDeg - previous.longitudeDeg) > 180.0) {
                segments += current
                current = mutableListOf()
            }
            current += point
        }
        if (current.isNotEmpty()) segments += current
        return segments
    }
}
