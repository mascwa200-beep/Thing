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
            val centre = Ephemeris.toEquatorial(
                Ephemeris.Horizontal(view.altitudeDeg, view.azimuthDeg, 0.0),
                latitudeDeg, longitudeDeg, epochMs,
            )
            // Straight up. Its declination is the observer's latitude and its right ascension is the
            // local sidereal time, which is the one number that carries the whole day's rotation.
            val zenith = Ephemeris.toEquatorial(
                Ephemeris.Horizontal(90.0, 0.0, 0.0),
                latitudeDeg, longitudeDeg, epochMs,
            )
            val z = SkyProjection.equatorialVector(zenith.rightAscensionDeg, zenith.declinationDeg)
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
            val zenith = Ephemeris.toEquatorial(
                Ephemeris.Horizontal(90.0, 0.0, 0.0),
                latitudeDeg, longitudeDeg, epochMs,
            )
            val z = SkyProjection.equatorialVector(zenith.rightAscensionDeg, zenith.declinationDeg)
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
        ): Ephemeris.Equatorial = Ephemeris.toEquatorial(
            Ephemeris.Horizontal(view.altitudeDeg, view.azimuthDeg, 0.0),
            latitudeDeg, longitudeDeg, epochMs,
        )

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
        ): Ephemeris.Equatorial = Ephemeris.toEquatorial(
            Ephemeris.Horizontal(
                SkyPointing.altitudeOf(forwardEnu),
                SkyPointing.azimuthOf(forwardEnu),
                0.0,
            ),
            latitudeDeg, longitudeDeg, epochMs,
        )
    }
}
