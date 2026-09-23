package dev.mascwa.pulse.sky

import dev.mascwa.pulse.core.telemetry.Ephemeris
import dev.mascwa.pulse.core.telemetry.Refraction
import dev.mascwa.pulse.core.telemetry.SkyPointing
import dev.mascwa.pulse.core.telemetry.SkyProjection
import kotlin.math.asin

/**
 * Everything a frame needs to draw stars held in equatorial coordinates.
 *
 * ⚠️ **This is where the Earth's rotation lives, and putting it here rather than in the stars is the
 * whole design.** Held in horizon coordinates a star's position changes continuously as the planet
 * turns, so a loaded set goes stale in seconds — and at a narrow field, where a screen pixel is a
 * fraction of an arcsecond, inside a single frame. The obvious remedies both fail: reconverting tens
 * of thousands of stars every frame is far too slow, and quantising the clock makes the sky visibly
 * jump. Holding equatorial positions and rebuilding **two vectors** per frame costs nothing and is
 * exact.
 *
 * So [StarField] never needs reloading because time passed, only because the view moved somewhere it
 * had not loaded, or zoomed deep enough to want fainter stars.
 *
 * ⚠️ **The frame is built in J2000, not in the equinox of date, and that is what makes it agree
 * with what it draws.** Everything this class is handed to — the star catalogue, the constellation
 * lines and IAU boundaries, the deep-sky catalogue, the Milky Way's galactic raster — is stated in
 * J2000, and everything the observer supplies (a look direction, the zenith) arrives in the equinox
 * of date, because that is the frame [Ephemeris.toEquatorial] speaks. Treating one as the other
 * rotates the entire star field against the horizon and against the planets, which are drawn
 * through the horizon path and never touch this basis. **Measured over the whole sky at as much as
 * twenty-two arcminutes in 2026** (least near the precession axis, at about six) — three pixels at
 * the widest field, twenty at twenty degrees, and more than a whole field at the quarter-degree
 * floor — reaching thirty-seven arcminutes by 2044.
 *
 * ⚠️ It is a rigid rotation, so it changes nothing else: [sinAltitude] still answers the true
 * altitude because the zenith is carried into the same frame as the stars, and an angle between
 * two directions is what it always was.
 *
 * ## ⚠️ Refraction is applied here, per direction, and the argument against it was wrong
 *
 * An earlier version of this note listed refraction as "deliberately NOT applied", on the grounds
 * that it depends on each star's own altitude and so "breaks the two-vectors-per-frame arrangement
 * this whole class exists for". The arrangement is intact: the two vectors are still all a frame
 * costs to BUILD. What refraction adds is a per-star term at DRAW time — and the drawn count is
 * bounded at about nine thousand by the zoom-adaptive magnitude cut (`SkyRenderer` measured it), so
 * the term is a table lookup and a dozen multiplications each, about a millisecond for the busiest
 * sky. Against that stood a **34.5-arcminute** error at the horizon, larger than the Moon, on every
 * object, at every field. [project] applies it: [sinAltitude] is already paid for every drawn
 * direction, [Refraction.fastBendingDeg] turns it into a bend, and [Refraction.lift] tilts the
 * direction toward the zenith before it is projected. Everything held in this frame — the stars, the
 * constellation figures and borders, the deep-sky objects, the two reference circles — goes through
 * it, so nothing can detach from the stars. ⚠️ The Milky Way does not, and says why in its own file.
 *
 * With [refracting] false the geometry is exactly what it was, which is what the ATMOSPHERE switch
 * turns off.
 *
 * ## Aberration and nutation are applied here too, and what remains is measured
 *
 * Precession is here because it is a rotation of the whole sky, and proper motion is applied per
 * star where each layer is filled — see `ProperMotion` — because it changes only when a layer
 * reloads. Since S4 the two whole-sky terms that were listed here as missing are in:
 *
 * * **Annual aberration, up to 20.5 arcseconds** — the Earth's own orbital velocity tilting the
 *   incoming light. [project] adds the frame's β (one J2000 equatorial vector per frame, from
 *   [Ephemeris.aberrationJ2000Equatorial]) to every direction BEFORE anything else, through
 *   [Ephemeris.aberrateEquatorial], the one implementation; `SkyMapViewModel.identify` takes it back
 *   off a tap. ⚠️ Not behind the ATMOSPHERE switch: it is where the light actually comes from, and
 *   Stellarium applies it whatever the atmosphere is set to.
 * * **Nutation, up to about 17 arcseconds of ecliptic longitude** — arrives as the pair it always
 *   had to. An earlier note here argued that adding nutation while [Ephemeris.toEquatorial] ran on
 *   MEAN sidereal time "would make the answer worse rather than better", and that was right: the
 *   equation of the equinoxes carries the same term. Both landed together — [Ephemeris.toEquatorial]
 *   is on [Ephemeris.gastDeg] now, so every observer direction is in the TRUE equinox of date and is
 *   carried to J2000 by [Ephemeris.trueOfDateToJ2000] ([catalogueOf], [Ephemeris.ofDateVectorToJ2000]).
 *   Measured (`ApparentPlaceTest`, fifty catalogue stars over 2000–2045 against Skyfield/DE421):
 *   RA/Dec of date worst **0.13″**, altitude/azimuth worst **0.31″** with UT1 taken as UTC. Before,
 *   over five hundred directions: median 15.8″, worst 25.9″.
 *
 * What is still not applied, with the measured size of each:
 *
 * * **DUT1 (UT1 − UTC), up to 0.9 s of time = 13″ of rotation** — ignored, as Stellarium ignores
 *   it. The IERS publishes it after the fact and nobody can predict it far ahead; against a
 *   real-UT1 reference it is the whole of the residual (3.2″ median over 2000–2026 in those same
 *   fifty rows, rising to 20″ by 2045 where Skyfield extrapolates it).
 * * **Stellar parallax, under an arcsecond for the nearest star there is** — 0.77 arcseconds for
 *   Proxima, which is the extreme. About one pixel at the quarter-degree floor and imperceptible
 *   at every wider field, on a handful of stars out of three million. Diurnal parallax, from the
 *   Earth's own radius rather than its orbit, is smaller again by five orders of magnitude.
 *   Genuinely not worth a per-star term.
 * * **Gravitational light deflection**, 4 mas at right angles to the Sun and only larger at its
 *   limb, where nothing is drawn.
 *
 * ⚠️ The solar-system bodies never touch this basis — they are drawn through the horizon path,
 * where `PlanetCalc` and `Ephemeris` apply their own light-time, aberration, nutation and
 * parallax (an earlier note here recorded parallax — 33″ on Venus — as the planets' largest
 * remaining gap, and it was, until the VSOP87 rewrite).
 *
 * For scale throughout: at the quarter-degree field one pixel is about 0.8 arcseconds, so the
 * residuals above are invisible at every field, while refraction is visible near the horizon at
 * every field.
 */
class SkyFrame private constructor(
    /** The projection basis, in the stars' own equatorial frame. */
    val basis: SkyProjection.Basis,
    /** The observer's zenith as an equatorial unit vector. */
    val zenithX: Double,
    val zenithY: Double,
    val zenithZ: Double,
    /**
     * Where the middle of the screen points, as an equatorial unit vector.
     *
     * ⚠️ Held here because [SkyProjection.Basis] keeps its own copy `internal`, and `internal` is
     * module-scoped — this module cannot read it. Culling whole objects against the view needs the
     * look direction, so it is published once rather than recomputed per object.
     */
    val forwardX: Double,
    val forwardY: Double,
    val forwardZ: Double,
    /**
     * Whether [project] bends every direction the way the air does. False draws the geometric sky.
     *
     * ⚠️ Required at both constructors rather than defaulted, so a caller cannot build a frame that
     * silently ignores the user's ATMOSPHERE switch — the "default that means do not do the thing"
     * this repository has shipped twice.
     */
    val refracting: Boolean,
    /**
     * Annual aberration, `β = v/c`, as a J2000 equatorial vector for the frame's instant — the
     * Earth's motion over the speed of light, about 1e-4, from [Ephemeris.aberrationJ2000Equatorial].
     * [project] adds it to every direction. Held as an array because [Ephemeris.aberrateEquatorial]
     * takes one, and there is exactly one implementation of that addition.
     */
    private val beta: DoubleArray,
) {

    /**
     * The sine of a star's altitude — positive above the horizon, negative below.
     *
     * ⚠️ One dot product, no trigonometry, and no horizon coordinates anywhere. A star's altitude is
     * by definition the angle between it and the observer's horizon plane, so its sine is exactly
     * the projection onto the zenith. The map dims what is below the horizon rather than dropping it
     * — you can look at where a constellation is in daylight — so this is asked of every drawn star.
     *
     * ⚠️ This is the TRUE altitude, before refraction, whatever [refracting] says. Above or below the
     * horizon is a fact about where the star is, and that is what the dimming reports; where it is
     * DRAWN is [project]'s business.
     */
    fun sinAltitude(vx: Double, vy: Double, vz: Double): Double =
        vx * zenithX + vy * zenithY + vz * zenithZ

    /**
     * Where a catalogue-frame unit direction lands on the screen, refraction included.
     *
     * ⚠️ **The one projection every catalogue-frame caller must use** — stars, glow, labels, lines,
     * deep-sky objects — so that everything drawn over the stars is bent by exactly what the stars
     * are. A caller reaching for [SkyProjection.projectUnit] with [basis] directly draws the
     * geometric position, and a constellation line that misses its own stars by half a degree at
     * the horizon is precisely the defect that would produce.
     *
     * Cost when refracting: the dot product every caller was already paying, an `asin`, one table
     * lookup, a square root and about fifteen multiplications, then the projection. Two fast exits
     * bracket it — below the taper the bend is zero, and within a tenth of a degree of the zenith it
     * is under a tenth of an arcsecond and the rotation plane is about to vanish.
     */
    fun project(vx: Double, vy: Double, vz: Double): SkyProjection.Screen {
        // ⚠️ Aberration first, and always: the light arrives from v + β, and everything after this
        // line — the altitude the air bends, the projection — is about the ARRIVING direction.
        // Refraction is about the atmosphere and can be switched off; this is about the observer
        // moving and cannot.
        val a = aberrated
        a[0] = vx
        a[1] = vy
        a[2] = vz
        Ephemeris.aberrateEquatorial(a, beta)
        if (!refracting) return SkyProjection.projectUnit(a[0], a[1], a[2], basis)
        val s = sinAltitude(a[0], a[1], a[2])
        if (s <= Refraction.SIN_TAPER_BOTTOM || s >= Refraction.SIN_NO_BEND) {
            return SkyProjection.projectUnit(a[0], a[1], a[2], basis)
        }
        val bend = Refraction.fastBendingDeg(Math.toDegrees(asin(s)))
        if (bend <= 0.0) return SkyProjection.projectUnit(a[0], a[1], a[2], basis)
        Refraction.lift(a[0], a[1], a[2], zenithX, zenithY, zenithZ, s, bend, lifted)
        return SkyProjection.projectUnit(lifted[0], lifted[1], lifted[2], basis)
    }

    /**
     * Whether a direction is drawn ABOVE the horizon line, given its [sinAltitude].
     *
     * ⚠️ Not `sinAlt >= 0` once the atmosphere is on. The horizon line is drawn at apparent zero,
     * and refraction lifts a star truly half a degree under it to above the line — so the geometric
     * test would dim that star as "not up" while drawing it over the horizon it is supposed to be
     * under. The line moves to [Refraction.TRUE_AT_APPARENT_HORIZON_DEG], which is where the eye's
     * horizon actually is; with the atmosphere off it is the geometric horizon, exactly as before.
     */
    fun aboveHorizon(sinAlt: Double): Boolean = sinAlt >= sinDrawnHorizon

    private val sinDrawnHorizon: Double = if (refracting) SIN_TRUE_AT_APPARENT_HORIZON else 0.0

    /**
     * How much further out than the screen's own cone a caller must look before culling by angle.
     *
     * ⚠️ [project] lifts a direction TOWARD the zenith by up to [Refraction.MAX_BEND_DEG], so a run
     * of constellation lines whose every vertex truly sits just past the bottom corner of the screen
     * can be lifted onto it — and a cull against the bare cone would throw that run away, leaving a
     * line that stops short of the corner exactly where the horizon is. Zero with the atmosphere
     * off, so the geometric cull is byte-for-byte what it was.
     */
    val cullMarginDeg: Double = if (refracting) Refraction.MAX_BEND_DEG else 0.0

    /**
     * Scratch for [project]. A frame is built per draw pass and used from the one thread that draws,
     * which is what makes a single reusable array safe; it is what keeps refracting nine thousand
     * stars from allocating nine thousand triples.
     */
    private val lifted = DoubleArray(3)

    /** Scratch for [project]'s aberration step, for the same reason as [lifted]. */
    private val aberrated = DoubleArray(3)

    companion object {
        private val SIN_TRUE_AT_APPARENT_HORIZON: Double =
            kotlin.math.sin(Math.toRadians(Refraction.TRUE_AT_APPARENT_HORIZON_DEG))

        /**
         * Build the frame for a view, a place and an instant.
         *
         * Costs two coordinate conversions and a basis, whatever the catalogue holds.
         *
         * @param refracting whether [project] bends toward the zenith — the ATMOSPHERE switch.
         */
        fun of(
            view: SkyProjection.View,
            latitudeDeg: Double,
            longitudeDeg: Double,
            epochMs: Long,
            refracting: Boolean,
        ): SkyFrame {
            val centre = centreOf(view, latitudeDeg, longitudeDeg, epochMs)
            val z = zenithVector(latitudeDeg, longitudeDeg, epochMs)
            val forward =
                SkyProjection.equatorialVector(centre.rightAscensionDeg, centre.declinationDeg)
            return SkyFrame(
                basis = SkyProjection.basisOf(forward, z[0], z[1], z[2], view.fovDeg, view.rollDeg),
                zenithX = z[0], zenithY = z[1], zenithZ = z[2],
                forwardX = forward[0], forwardY = forward[1], forwardZ = forward[2],
                refracting = refracting,
                beta = Ephemeris.aberrationJ2000Equatorial(epochMs),
            )
        }

        /**
         * The frame for a handset aimed somewhere, rather than a chart dragged there.
         *
         * ⚠️ **Three conversions rather than [of]'s two, and none of them is the roll.** The roll is
         * already inside [upEnu] — it arrived there from the handset's own attitude — so the basis
         * is built with a roll of zero, and passing [SkyProjection.View.rollDeg] as well would apply
         * it twice. What costs the third conversion is that the observer's zenith is still needed
         * for [sinAltitude], which is what dims everything below the horizon; the screen-up is not a
         * substitute for it, because it is a property of how the phone is held rather than of where
         * the observer stands.
         *
         * ⚠️ **This is the path that has no seam at the zenith**, and that is the whole reason it
         * exists rather than [of] taking a roll. [of] builds `forward × zenith`, which is the zero
         * vector when the two coincide — aim straight up and `Basis.usable` goes false and the map
         * draws nothing. A screen-up is perpendicular to the look direction by construction, so the
         * cross product cannot vanish however the phone is held. `SkyPointingTest` asserts both
         * halves of that in this frame, not merely in the handset's.
         *
         * ⚠️ Refraction needs nothing special here: the sensor reports where the handset truly
         * points, and what the eye sees along that line is the refracted sky, which is what [project]
         * draws. The zenith the bend is measured toward is the observer's, never the screen's.
         *
         * @param forwardEnu where the camera looks, east/north/up, from `SkyPointing.forward`.
         * @param upEnu which way is up the screen, east/north/up, from `SkyPointing.screenUp`.
         * @param refracting whether [project] bends toward the zenith — the ATMOSPHERE switch.
         */
        fun ofPointing(
            forwardEnu: DoubleArray,
            upEnu: DoubleArray,
            fovDeg: Double,
            latitudeDeg: Double,
            longitudeDeg: Double,
            epochMs: Long,
            refracting: Boolean,
        ): SkyFrame {
            val forward = DoubleArray(3)
            val up = DoubleArray(3)
            SkyPointing.toEquatorialVector(forwardEnu, latitudeDeg, longitudeDeg, epochMs, forward)
            SkyPointing.toEquatorialVector(upEnu, latitudeDeg, longitudeDeg, epochMs, up)
            // ⚠️ `SkyPointing` answers in the equinox of date and stays that way on purpose: its
            // tests assert that the zenith's declination IS the observer's latitude, which is a
            // true statement about that frame and would become an arbitrary rotated number here.
            // The frame change is this class's job, and this is where it happens for both vectors.
            Ephemeris.ofDateVectorToJ2000(forward, epochMs)
            Ephemeris.ofDateVectorToJ2000(up, epochMs)
            val z = zenithVector(latitudeDeg, longitudeDeg, epochMs)
            return SkyFrame(
                basis = SkyProjection.basisOf(forward, up[0], up[1], up[2], fovDeg, 0.0),
                zenithX = z[0], zenithY = z[1], zenithZ = z[2],
                forwardX = forward[0], forwardY = forward[1], forwardZ = forward[2],
                refracting = refracting,
                beta = Ephemeris.aberrationJ2000Equatorial(epochMs),
            )
        }

        /** Where the middle of the screen is pointing, in catalogue coordinates. */
        fun centreOf(
            view: SkyProjection.View,
            latitudeDeg: Double,
            longitudeDeg: Double,
            epochMs: Long,
        ): Ephemeris.Equatorial =
            catalogueOf(view.altitudeDeg, view.azimuthDeg, latitudeDeg, longitudeDeg, epochMs)

        /**
         * The same, for a handset aim.
         *
         * ⚠️ **Not [centreOf] of the equivalent view, and the difference is only visible at the one
         * place it matters.** `SkyPointing.equivalentView` clamps the altitude to
         * [SkyProjection.MAX_ALTITUDE_DEG], so within half a degree of the zenith its centre is off
         * by up to that much. At a wide field that is nothing; at the quarter-degree floor it is
         * twice the whole field, and `StarField` would load a cone that does not contain what is
         * being drawn — a crescent of empty sky that reads as a rendering fault rather than a
         * loading one.
         */
        fun centreOfPointing(
            forwardEnu: DoubleArray,
            latitudeDeg: Double,
            longitudeDeg: Double,
            epochMs: Long,
        ): Ephemeris.Equatorial = catalogueOf(
            SkyPointing.altitudeOf(forwardEnu),
            SkyPointing.azimuthOf(forwardEnu),
            latitudeDeg, longitudeDeg, epochMs,
        )

        /**
         * A horizon direction in the catalogue's frame — the ONE boundary between where somebody is
         * looking and what the map holds.
         *
         * Every entry point above funnels through here rather than restating the two steps, because
         * a frame conversion that exists in four places is a frame conversion three of which will
         * eventually disagree. Public because a tap is the fifth: hit-testing carries the touched
         * direction into the catalogue's frame and compares it against the stars there, which is one
         * conversion rather than one per star.
         *
         * ⚠️ Takes a TRUE altitude. A tap arrives as an apparent one — the drawn sky is refracted —
         * and `SkyMapViewModel.identify` lowers it through [Refraction.trueOf] before coming here,
         * so the direction compared against the catalogue is where the star IS, not where it is seen.
         *
         * ⚠️ **Geometric, not aberrated, on purpose.** The three callers want three different things
         * of that. A tap has to have β taken off as well, because the stars are DRAWN with it on —
         * `identify` does that on the vector this returns. The view centre must NOT: the basis it
         * seeds is what [project] adds β to afterwards, and a centre with β already removed would
         * put the aberration in twice. And the zenith is the observer's, a fact about where they
         * stand and nothing to do with where light comes from.
         */
        fun catalogueOf(
            altitudeDeg: Double,
            azimuthDeg: Double,
            latitudeDeg: Double,
            longitudeDeg: Double,
            epochMs: Long,
        ): Ephemeris.Equatorial {
            // Apparent sidereal time inside, so this answers in the TRUE equinox of date and the
            // rotation to J2000 has to be the true pair to match — see the class KDoc.
            val eq = Ephemeris.toEquatorial(
                Ephemeris.Horizontal(altitudeDeg, azimuthDeg, 0.0),
                latitudeDeg, longitudeDeg, epochMs,
            )
            val j = Ephemeris.trueOfDateToJ2000(
                eq.rightAscensionDeg, eq.declinationDeg, epochMs,
            )
            return Ephemeris.Equatorial(j[0], j[1], 0.0)
        }

        /**
         * The way back: a catalogue position as the TRUE altitude and azimuth it is DRAWN at —
         * aberrated, carried to the true equinox of date, on apparent sidereal time — before the
         * refraction, which the caller adds for display.
         *
         * ⚠️ **The exact inverse of the tap's path, and it has to be.** A tap is answered by
         * carrying the touched direction into the catalogue's frame ([catalogueOf], then β taken
         * off), finding the nearest star there, and then reading that star back out through this to
         * say how high it is — so a pair that were merely nearly inverse would report a star at an
         * altitude it is not drawn at, by however much they disagreed. [Ephemeris.apparentStarHorizontal]
         * is the same arithmetic [project] does per star, in scalar form — one definition, so the
         * card and the dot cannot disagree — and [Ephemeris.trueOfDateToJ2000]/[Ephemeris.j2000ToTrueOfDate]
         * inside it are the exact pair (its warning records the trap: negating the epoch instead
         * comes back within 0.7 arcseconds, which is close enough to look right).
         */
        fun horizonOf(
            rightAscensionDeg: Double,
            declinationDeg: Double,
            latitudeDeg: Double,
            longitudeDeg: Double,
            epochMs: Long,
        ): Ephemeris.Horizontal =
            Ephemeris.apparentStarHorizontal(
                rightAscensionDeg, declinationDeg, latitudeDeg, longitudeDeg, epochMs,
            )

        /**
         * Straight up, as a catalogue-frame unit vector.
         *
         * In the equinox of date its declination is the observer's latitude and its right ascension
         * is the local sidereal time, which is the one number carrying the whole day's rotation.
         * Both entry points need it — [of] as the viewer's up, [ofPointing] only for [sinAltitude],
         * because there the up is the handset's rather than the world's.
         */
        private fun zenithVector(
            latitudeDeg: Double,
            longitudeDeg: Double,
            epochMs: Long,
        ): DoubleArray {
            val z = catalogueOf(90.0, 0.0, latitudeDeg, longitudeDeg, epochMs)
            return SkyProjection.equatorialVector(z.rightAscensionDeg, z.declinationDeg)
        }
    }
}
