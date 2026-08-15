package dev.mascwa.pulse.core.telemetry

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * SGP4 — the orbital propagator that turns a two-line element set into a satellite's position.
 *
 * `SkyDigest.kt` has carried the line *"No pass-time prediction (that needs SGP4/TLE) — we never
 * fabricate times"* since it was written. This is that missing piece, so the app can finally say
 * when something will actually fly over instead of declining to guess.
 *
 * **Scope, stated honestly.** This is the near-Earth SGP4 model from Spacetrack Report #3 as
 * corrected in Vallado's *Revisiting Spacetrack Report #3*. It deliberately does **not** implement
 * the deep-space SDP4 extension (lunar-solar periodics and resonance); an object whose orbital
 * period is 225 minutes or more returns [Propagation.DeepSpace] rather than a plausible-looking
 * wrong answer. That excludes geostationary and GPS satellites and includes everything you can
 * stand outside and watch go past — the ISS, Starlink, weather and imaging satellites.
 *
 * Units are kilometres and kilometres/second in the TEME frame (true equator, mean equinox), which
 * is the frame SGP4 natively produces; converting to something you can point at is [Teme]'s job.
 */
object Sgp4 {

    // WGS-72 — SGP4 is *defined* against WGS-72, not WGS-84. Using WGS-84 constants here is a
    // classic way to get answers that look almost right and drift by kilometres.
    const val EARTH_RADIUS_KM = 6378.135
    private const val MU = 398600.8            // km^3/s^2
    private const val XKE = 0.07436691613317342 // sqrt(MU) in earth-radii^1.5 / min
    private const val J2 = 1.082616e-3
    private const val J3 = -2.53881e-6
    private const val J4 = -1.65597e-6
    private const val CK2 = 0.5 * J2
    private const val CK4 = -0.375 * J4
    private const val QOMS2T = 1.880279159015270e-9
    private const val S_DENSITY = 1.012229121151446   // s = 78/EARTH_RADIUS + 1
    private const val TWO_PI = 2.0 * Math.PI
    private const val MIN_PER_DAY = 1440.0

    /** The period, in minutes, at or above which an orbit is deep-space and SGP4 does not apply. */
    const val DEEP_SPACE_PERIOD_MIN = 225.0

    /** A position and velocity in the TEME frame, kilometres and km/s. */
    data class State(
        val x: Double, val y: Double, val z: Double,
        val vx: Double, val vy: Double, val vz: Double,
    ) {
        val radiusKm: Double get() = sqrt(x * x + y * y + z * z)
        val speedKmS: Double get() = sqrt(vx * vx + vy * vy + vz * vz)
        val altitudeKm: Double get() = radiusKm - EARTH_RADIUS_KM
    }

    /** Every way a propagation can end. Nothing here ever returns a wrong number silently. */
    sealed interface Propagation {
        data class Ok(val state: State) : Propagation
        /** Period >= 225 min: this needs SDP4, which is not implemented. */
        data object DeepSpace : Propagation
        /** The orbit decayed — the satellite is inside the atmosphere at this time. */
        data object Decayed : Propagation
        /** The element set itself is unusable (negative eccentricity, zero mean motion, …). */
        data class BadElements(val reason: String) : Propagation
    }

    /**
     * A TLE reduced to the values SGP4 works in: radians, and mean motion in radians/minute.
     * Build one with [Tle.parse] rather than by hand.
     */
    data class Elements(
        val noradId: Int,
        val name: String,
        val epochJulian: Double,
        /** Mean motion, radians/minute (the "Kozai" mean motion straight off the TLE). */
        val noKozai: Double,
        val eccentricity: Double,
        val inclinationRad: Double,
        val raanRad: Double,
        val argPerigeeRad: Double,
        val meanAnomalyRad: Double,
        val bstar: Double,
    ) {
        /** Orbital period in minutes. */
        val periodMinutes: Double get() = if (noKozai <= 0.0) Double.MAX_VALUE else TWO_PI / noKozai
        val isDeepSpace: Boolean get() = periodMinutes >= DEEP_SPACE_PERIOD_MIN
    }

    /**
     * Pre-computed constants for one element set. Deriving these is most of SGP4's work, and they
     * do not depend on time — so build this once per satellite and propagate it many times.
     */
    class Propagator internal constructor(
        val elements: Elements,
        private val init: Init?,
        private val failure: String?,
    ) {
        val isDeepSpace: Boolean get() = elements.isDeepSpace

        /** Propagate to [minutesSinceEpoch] — negative is allowed and runs the model backwards. */
        fun propagate(minutesSinceEpoch: Double): Propagation {
            if (failure != null) return Propagation.BadElements(failure)
            if (elements.isDeepSpace) return Propagation.DeepSpace
            val i = init ?: return Propagation.BadElements("uninitialised")
            return run(i, elements, minutesSinceEpoch)
        }

        /** Propagate to a wall-clock instant. */
        fun propagateAt(epochMillis: Long): Propagation =
            propagate(minutesBetween(elements.epochJulian, epochMillis))
    }

    /** Build a propagator. Never throws — bad elements surface at [Propagator.propagate]. */
    fun propagator(elements: Elements): Propagator {
        val why = validate(elements)
        if (why != null) return Propagator(elements, null, why)
        if (elements.isDeepSpace) return Propagator(elements, null, null)
        return Propagator(elements, initialise(elements), null)
    }

    private fun validate(e: Elements): String? = when {
        e.noKozai <= 0.0 -> "mean motion must be positive"
        e.eccentricity < 0.0 || e.eccentricity >= 1.0 -> "eccentricity out of range"
        !e.inclinationRad.isFinite() || !e.raanRad.isFinite() -> "non-finite angles"
        else -> null
    }

    /** Minutes from a TLE epoch (Julian date) to a wall-clock instant. */
    fun minutesBetween(epochJulian: Double, epochMillis: Long): Double {
        val jdNow = epochMillis / 86_400_000.0 + 2440587.5
        return (jdNow - epochJulian) * MIN_PER_DAY
    }

    // ---- the model ------------------------------------------------------------------------

    /** Time-independent constants derived from one element set. */
    class Init internal constructor(
        val cosio: Double, val sinio: Double, val eta: Double, val coef1: Double,
        val c1: Double, val c2: Double, val c3: Double, val c4: Double, val c5: Double,
        val aodp: Double, val xnodp: Double, val x3thm1: Double, val x1mth2: Double,
        val x7thm1: Double, val xmdot: Double, val omgdot: Double, val xnodot: Double,
        val xnodcf: Double, val t2cof: Double, val xlcof: Double, val aycof: Double,
        val delmo: Double, val sinmo: Double, val omgcof: Double, val xmcof: Double,
        val d2: Double, val d3: Double, val d4: Double,
        val t3cof: Double, val t4cof: Double, val t5cof: Double,
        val isimp: Boolean,
    )

    private fun initialise(e: Elements): Init {
        val eo = e.eccentricity
        val xincl = e.inclinationRad
        val xno = e.noKozai
        val bstar = e.bstar

        // Un-Kozai the mean motion: recover the Brouwer semi-major axis the model actually needs.
        val a1 = (XKE / xno).pow(2.0 / 3.0)
        val cosio = cos(xincl)
        val theta2 = cosio * cosio
        val x3thm1 = 3.0 * theta2 - 1.0
        val eosq = eo * eo
        val betao2 = 1.0 - eosq
        val betao = sqrt(betao2)
        val del1 = 1.5 * CK2 * x3thm1 / (a1 * a1 * betao * betao2)
        val ao = a1 * (1.0 - del1 * (0.5 * (2.0 / 3.0) + del1 * (1.0 + 134.0 / 81.0 * del1)))
        val delo = 1.5 * CK2 * x3thm1 / (ao * ao * betao * betao2)
        val xnodp = xno / (1.0 + delo)
        val aodp = ao / (1.0 - delo)

        // Drag model: below 156 km perigee the atmospheric fit switches to a simplified form.
        val perigee = (aodp * (1.0 - eo) - 1.0) * EARTH_RADIUS_KM
        val isimp = (aodp * (1.0 - eo) / 1.0) < (220.0 / EARTH_RADIUS_KM + 1.0)

        var s4 = S_DENSITY
        var qoms24 = QOMS2T
        if (perigee < 156.0) {
            s4 = perigee - 78.0
            if (perigee <= 98.0) s4 = 20.0
            qoms24 = ((120.0 - s4) / EARTH_RADIUS_KM).pow(4.0)
            s4 = s4 / EARTH_RADIUS_KM + 1.0
        }

        val pinvsq = 1.0 / (aodp * aodp * betao2 * betao2)
        val tsi = 1.0 / (aodp - s4)
        val eta = aodp * eo * tsi
        val etasq = eta * eta
        val eeta = eo * eta
        val psisq = abs(1.0 - etasq)
        val coef = qoms24 * tsi.pow(4.0)
        val coef1 = coef / psisq.pow(3.5)
        val sinio = sin(xincl)

        val c2 = coef1 * xnodp * (
            aodp * (1.0 + 1.5 * etasq + eeta * (4.0 + etasq)) +
                0.75 * CK2 * tsi / psisq * x3thm1 * (8.0 + 3.0 * etasq * (8.0 + etasq))
            )
        val c1 = bstar * c2
        val a3ovk2 = -J3 / CK2
        val c3 = if (eo > 1.0e-4) coef * tsi * a3ovk2 * xnodp * sinio / eo else 0.0
        val x1mth2 = 1.0 - theta2
        val c4 = 2.0 * xnodp * coef1 * aodp * betao2 * (
            eta * (2.0 + 0.5 * etasq) + eo * (0.5 + 2.0 * etasq) -
                2.0 * CK2 * tsi / (aodp * psisq) * (
                    -3.0 * x3thm1 * (1.0 - 2.0 * eeta + etasq * (1.5 - 0.5 * eeta)) +
                        0.75 * x1mth2 * (2.0 * etasq - eeta * (1.0 + etasq)) * cos(2.0 * e.argPerigeeRad)
                    )
            )
        val c5 = 2.0 * coef1 * aodp * betao2 * (1.0 + 2.75 * (etasq + eeta) + eeta * etasq)

        val theta4 = theta2 * theta2
        val temp1 = 3.0 * CK2 * pinvsq * xnodp
        val temp2 = temp1 * CK2 * pinvsq
        val temp3 = 1.25 * CK4 * pinvsq * pinvsq * xnodp
        val xmdot = xnodp + 0.5 * temp1 * betao * x3thm1 +
            0.0625 * temp2 * betao * (13.0 - 78.0 * theta2 + 137.0 * theta4)
        val x1m5th = 1.0 - 5.0 * theta2
        val omgdot = -0.5 * temp1 * x1m5th +
            0.0625 * temp2 * (7.0 - 114.0 * theta2 + 395.0 * theta4) +
            temp3 * (3.0 - 36.0 * theta2 + 49.0 * theta4)
        val xhdot1 = -temp1 * cosio
        val xnodot = xhdot1 + (0.5 * temp2 * (4.0 - 19.0 * theta2) + 2.0 * temp3 * (3.0 - 7.0 * theta2)) * cosio

        val omgcof = bstar * c3 * cos(e.argPerigeeRad)
        val xmcof = if (eo > 1.0e-4) -(2.0 / 3.0) * coef * bstar / eeta else 0.0
        val xnodcf = 3.5 * betao2 * xhdot1 * c1
        val t2cof = 1.5 * c1
        val xlcof = 0.125 * a3ovk2 * sinio * (3.0 + 5.0 * cosio) / (1.0 + cosio)
        val aycof = 0.25 * a3ovk2 * sinio
        val delmo = (1.0 + eta * cos(e.meanAnomalyRad)).pow(3.0)
        val sinmo = sin(e.meanAnomalyRad)
        val x7thm1 = 7.0 * theta2 - 1.0

        var d2 = 0.0; var d3 = 0.0; var d4 = 0.0
        var t3cof = 0.0; var t4cof = 0.0; var t5cof = 0.0
        if (!isimp) {
            val c1sq = c1 * c1
            d2 = 4.0 * aodp * tsi * c1sq
            val temp = d2 * tsi * c1 / 3.0
            d3 = (17.0 * aodp + s4) * temp
            d4 = 0.5 * temp * aodp * tsi * (221.0 * aodp + 31.0 * s4) * c1
            t3cof = d2 + 2.0 * c1sq
            t4cof = 0.25 * (3.0 * d3 + c1 * (12.0 * d2 + 10.0 * c1sq))
            t5cof = 0.2 * (3.0 * d4 + 12.0 * c1 * d3 + 6.0 * d2 * d2 + 15.0 * c1sq * (2.0 * d2 + c1sq))
        }

        return Init(
            cosio, sinio, eta, coef1, c1, c2, c3, c4, c5, aodp, xnodp,
            x3thm1, x1mth2, x7thm1, xmdot, omgdot, xnodot, xnodcf, t2cof, xlcof, aycof,
            delmo, sinmo, omgcof, xmcof, d2, d3, d4, t3cof, t4cof, t5cof, isimp,
        )
    }

    private fun run(i: Init, el: Elements, tsince: Double): Propagation {
        val eo = el.eccentricity
        val bstar = el.bstar

        // Secular effects of drag and gravity.
        val xmdf = el.meanAnomalyRad + i.xmdot * tsince
        val omgadf = el.argPerigeeRad + i.omgdot * tsince
        val xnoddf = el.raanRad + i.xnodot * tsince
        var omega = omgadf
        var xmp = xmdf
        val tsq = tsince * tsince
        val xnode = xnoddf + i.xnodcf * tsq
        var tempa = 1.0 - i.c1 * tsince
        var tempe = bstar * i.c4 * tsince
        var templ = i.t2cof * tsq

        if (!i.isimp) {
            val delomg = i.omgcof * tsince
            val delm = i.xmcof * ((1.0 + i.eta * cos(xmdf)).pow(3.0) - i.delmo)
            val temp = delomg + delm
            xmp = xmdf + temp
            omega = omgadf - temp
            val tcube = tsq * tsince
            val tfour = tsince * tcube
            tempa = tempa - i.d2 * tsq - i.d3 * tcube - i.d4 * tfour
            tempe += bstar * i.c5 * (sin(xmp) - i.sinmo)
            templ += i.t3cof * tcube + tfour * (i.t4cof + tsince * i.t5cof)
        }

        val a = i.aodp * tempa * tempa
        if (a < 1.0) return Propagation.Decayed
        val e2 = eo - tempe
        if (e2 >= 1.0 || e2 < -0.001) return Propagation.Decayed
        val ecc = if (e2 < 1.0e-6) 1.0e-6 else e2
        val xl = xmp + omega + xnode + i.xnodp * templ
        val beta = sqrt(1.0 - ecc * ecc)
        val xn = XKE / a.pow(1.5)

        // Long-period periodics.
        val axn = ecc * cos(omega)
        val temp0 = 1.0 / (a * beta * beta)
        val xll = temp0 * i.xlcof * axn
        val aynl = temp0 * i.aycof
        val xlt = xl + xll
        val ayn = ecc * sin(omega) + aynl

        // Solve Kepler's equation for (E + omega) by Newton-Raphson, exactly as the model spells
        // it: capped at ten iterations, with the step limited to +/-0.95 to stop it diverging on
        // a high-eccentricity orbit.
        val capu = ((xlt - xnode) % TWO_PI + TWO_PI) % TWO_PI
        var epw = capu
        var sinepw = 0.0
        var cosepw = 0.0
        var ecose = 0.0
        var esine = 0.0
        for (iter in 0 until 10) {
            sinepw = sin(epw)
            cosepw = cos(epw)
            ecose = axn * cosepw + ayn * sinepw
            esine = axn * sinepw - ayn * cosepw
            val f = capu - epw + esine
            if (abs(f) < 1.0e-12) break
            val df = 1.0 - ecose
            var delta = f / df
            if (iter == 0) delta = delta.coerceIn(-0.95, 0.95)
            epw += delta
        }

        // Short-period preliminary quantities.
        val elsq = axn * axn + ayn * ayn
        val tempA = 1.0 - elsq
        val pl = a * tempA
        if (pl < 0.0) return Propagation.Decayed
        val r = a * (1.0 - ecose)
        val temp1 = 1.0 / r
        val rdot = XKE * sqrt(a) * esine * temp1
        val rfdot = XKE * sqrt(pl) * temp1
        val temp2 = a * temp1
        val betal = sqrt(tempA)
        val temp3 = 1.0 / (1.0 + betal)
        val cosu = temp2 * (cosepw - axn + ayn * esine * temp3)
        val sinu = temp2 * (sinepw - ayn - axn * esine * temp3)
        val u = atan2(sinu, cosu)
        val sin2u = 2.0 * sinu * cosu
        val cos2u = 1.0 - 2.0 * sinu * sinu
        val temp4 = 1.0 / pl
        val temp5 = CK2 * temp4
        val temp6 = temp5 * temp4

        // Short-period periodics applied to the osculating elements.
        val rk = r * (1.0 - 1.5 * temp6 * betal * i.x3thm1) + 0.5 * temp5 * i.x1mth2 * cos2u
        val uk = u - 0.25 * temp6 * i.x7thm1 * sin2u
        val xnodek = xnode + 1.5 * temp6 * i.cosio * sin2u
        val xinck = el.inclinationRad + 1.5 * temp6 * i.cosio * i.sinio * cos2u
        val rdotk = rdot - xn * temp5 * i.x1mth2 * sin2u
        val rfdotk = rfdot + xn * temp5 * (i.x1mth2 * cos2u + 1.5 * i.x3thm1)

        // Orientation vectors, then position and velocity in TEME.
        val sinuk = sin(uk); val cosuk = cos(uk)
        val sinik = sin(xinck); val cosik = cos(xinck)
        val sinnok = sin(xnodek); val cosnok = cos(xnodek)
        val xmx = -sinnok * cosik
        val xmy = cosnok * cosik
        // Position unit vector (u) and the in-track unit vector (w) — named apart from the output
        // velocity components so the final expressions read unambiguously.
        val ux = xmx * sinuk + cosnok * cosuk
        val uy = xmy * sinuk + sinnok * cosuk
        val uz = sinik * sinuk
        val wx = xmx * cosuk - cosnok * sinuk
        val wy = xmy * cosuk - sinnok * sinuk
        val wz = sinik * cosuk

        return Propagation.Ok(
            State(
                x = rk * ux * EARTH_RADIUS_KM,
                y = rk * uy * EARTH_RADIUS_KM,
                z = rk * uz * EARTH_RADIUS_KM,
                // Earth radii/minute -> km/s.
                vx = (rdotk * ux + rfdotk * wx) * EARTH_RADIUS_KM / 60.0,
                vy = (rdotk * uy + rfdotk * wy) * EARTH_RADIUS_KM / 60.0,
                vz = (rdotk * uz + rfdotk * wz) * EARTH_RADIUS_KM / 60.0,
            ),
        )
    }
}
