package dev.mascwa.pulse.sky

import dev.mascwa.pulse.core.telemetry.Ephemeris
import dev.mascwa.pulse.core.telemetry.SkyPointing
import dev.mascwa.pulse.core.telemetry.SkyProjection

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
) {

    /**
     * The sine of a star's altitude — positive above the horizon, negative below.
     *
     * ⚠️ One dot product, no trigonometry, and no horizon coordinates anywhere. A star's altitude is
     * by definition the angle between it and the observer's horizon plane, so its sine is exactly
     * the projection onto the zenith. The map dims what is below the horizon rather than dropping it
     * — you can look at where a constellation is in daylight — so this is asked of every drawn star.
     */
    fun sinAltitude(vx: Double, vy: Double, vz: Double): Double =
        vx * zenithX + vy * zenithY + vz * zenithZ

    companion object {
        /**
         * Build the frame for a view, a place and an instant.
         *
         * Costs two coordinate conversions and a basis, whatever the catalogue holds.
         */
        fun of(
            view: SkyProjection.View,
            latitudeDeg: Double,
            longitudeDeg: Double,
            epochMs: Long,
        ): SkyFrame {
            val centre = centreOf(view, latitudeDeg, longitudeDeg, epochMs)
            val z = zenithVector(latitudeDeg, longitudeDeg, epochMs)
            val forward =
                SkyProjection.equatorialVector(centre.rightAscensionDeg, centre.declinationDeg)
            return SkyFrame(
                basis = SkyProjection.basisOf(forward, z[0], z[1], z[2], view.fovDeg, view.rollDeg),
                zenithX = z[0], zenithY = z[1], zenithZ = z[2],
                forwardX = forward[0], forwardY = forward[1], forwardZ = forward[2],
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
         * @param forwardEnu where the camera looks, east/north/up, from `SkyPointing.forward`.
         * @param upEnu which way is up the screen, east/north/up, from `SkyPointing.screenUp`.
         */
        fun ofPointing(
            forwardEnu: DoubleArray,
            upEnu: DoubleArray,
            fovDeg: Double,
            latitudeDeg: Double,
            longitudeDeg: Double,
            epochMs: Long,
        ): SkyFrame {
            val forward = DoubleArray(3)
            val up = DoubleArray(3)
            SkyPointing.toEquatorialVector(forwardEnu, latitudeDeg, longitudeDeg, epochMs, forward)
            SkyPointing.toEquatorialVector(upEnu, latitudeDeg, longitudeDeg, epochMs, up)
            // ⚠️ `SkyPointing` answers in the equinox of date and stays that way on purpose: its
            // tests assert that the zenith's declination IS the observer's latitude, which is a
            // true statement about that frame and would become an arbitrary rotated number here.
            // The frame change is this class's job, and this is where it happens for both vectors.
            Ephemeris.precessVectorToJ2000(forward, epochMs)
            Ephemeris.precessVectorToJ2000(up, epochMs)
            val z = zenithVector(latitudeDeg, longitudeDeg, epochMs)
            return SkyFrame(
                basis = SkyProjection.basisOf(forward, up[0], up[1], up[2], fovDeg, 0.0),
                zenithX = z[0], zenithY = z[1], zenithZ = z[2],
                forwardX = forward[0], forwardY = forward[1], forwardZ = forward[2],
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
         */
        fun catalogueOf(
            altitudeDeg: Double,
            azimuthDeg: Double,
            latitudeDeg: Double,
            longitudeDeg: Double,
            epochMs: Long,
        ): Ephemeris.Equatorial {
            val eq = Ephemeris.toEquatorial(
                Ephemeris.Horizontal(altitudeDeg, azimuthDeg, 0.0),
                latitudeDeg, longitudeDeg, epochMs,
            )
            val j = Ephemeris.meanOfDateToJ2000(
                eq.rightAscensionDeg, eq.declinationDeg, epochMs,
            )
            return Ephemeris.Equatorial(j[0], j[1], 0.0)
        }

        /**
         * The way back: a catalogue position as an altitude and an azimuth.
         *
         * ⚠️ **The exact inverse of [catalogueOf], and it has to be.** A tap is answered by carrying
         * the touched direction into the catalogue's frame, finding the nearest star there, and then
         * reading that star back out to say how high it is — so a pair that were merely nearly
         * inverse would report a star at an altitude it is not drawn at, by however much they
         * disagreed. [Ephemeris.meanOfDateToJ2000] and [Ephemeris.j2000ToMeanOfDate] are the exact
         * pair, and its warning records the trap: negating the epoch instead comes back within
         * 0.7 arcseconds, which is close enough to look right.
         */
        fun horizonOf(
            rightAscensionDeg: Double,
            declinationDeg: Double,
            latitudeDeg: Double,
            longitudeDeg: Double,
            epochMs: Long,
        ): Ephemeris.Horizontal {
            val d = Ephemeris.j2000ToMeanOfDate(rightAscensionDeg, declinationDeg, epochMs)
            return Ephemeris.toHorizontal(
                Ephemeris.Equatorial(d[0], d[1], 0.0), latitudeDeg, longitudeDeg, epochMs,
            )
        }

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
