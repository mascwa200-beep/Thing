package dev.mascwa.pulse.sky

import dev.mascwa.pulse.core.telemetry.Ephemeris
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
    }
}
