package dev.mascwa.pulse.data.orbital

import kotlinx.serialization.Serializable

/**
 * One naked-eye planet as [PlanetCalc] computed it.
 *
 * ⚠️ **Here rather than beside the orbital DTOs it was written among, and the move is what let the
 * star map exist without the network.** `PlanetCalc` is a few hundred lines of Schlyter arithmetic
 * with no dependency beyond `kotlin.math`, and this is what it returns — so the two belong in the
 * pure module together. While the pair sat in `:core:feeds` the only way for `:core:sky` to draw a
 * planet was to depend on the HTTP client and twenty-two repositories, which is the opposite of
 * what a bundled, offline star chart is for.
 *
 * ⚠️ Still `@Serializable`, because `OrbitalData` caches a list of these to disk. The pure module
 * already carries the serialization plugin, so nothing was added to make this possible.
 */
@Serializable
data class Planet(
    val name: String,
    val altitudeDeg: Double,   // above horizon if > 0
    val azimuthDeg: Double,    // from true north, clockwise
    val magnitude: Double,     // lower = brighter
    val aboveHorizon: Boolean,
    // ⚠️ Computed by PlanetCalc on the way to the altitude and azimuth above, and thrown away until
    // something needed a coordinate that does not depend on where the observer is standing. Both
    // default to zero so every existing construction still compiles; PlanetCalc fills them in.
    val rightAscensionDeg: Double = 0.0,
    val declinationDeg: Double = 0.0,
    /**
     * How far away, in astronomical units, and the Sun-planet-Earth angle in degrees.
     *
     * ⚠️ **Both were already computed and thrown away — this is the same defect the two fields above
     * record, in the same file, a second time.** `PlanetCalc` needs the geocentric distance and the
     * phase angle to work out an apparent magnitude at all, and then kept neither. Without the
     * distance nothing can say how large a planet LOOKS; without the phase angle nothing can draw
     * Venus as the crescent it plainly is through any telescope.
     *
     * Defaulted to zero so every existing construction still compiles and every cached entry still
     * decodes; a zero means "not stated", which every consumer has to treat as unknown rather than
     * as a planet sitting on top of the observer.
     */
    val distanceAu: Double = 0.0,
    val phaseAngleDeg: Double = 0.0,
)
