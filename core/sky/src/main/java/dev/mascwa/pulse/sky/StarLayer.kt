package dev.mascwa.pulse.sky

import dev.mascwa.pulse.core.telemetry.SkyProjection
import dev.mascwa.pulse.core.telemetry.StarNames

/**
 * Stars in the one form the draw pass wants: primitive arrays, ready to project.
 *
 * ## ⚠️ Why there are two of these and not one catalogue
 *
 * **Gaia holds nothing brighter than magnitude 1.71, and that is measured rather than assumed.**
 * Asked for the 8,404 stars of the bright catalogue, after propagating Gaia's own positions back to
 * the bright set's J2000 epoch and requiring the match to be the star rather than a companion:
 *
 * | bright-set magnitude | present in the deep catalogue |
 * |---|---|
 * | brighter than 1 | 0% |
 * | 1 to 2 | 12% |
 * | 2 to 3 | 70% |
 * | 3 to 4 | 95% |
 * | fainter than 4 | 94–96% |
 *
 * Gaia saturates on bright stars. So the deep catalogue is complete where it matters and blind
 * exactly where the constellations are, and a map drawn from it alone has no Sirius, no Vega and no
 * Orion's belt.
 *
 * ⚠️ **A magnitude seam between the two cannot work, and that was measured too.** Whatever cut is
 * chosen, roughly 170 bright stars have no deep-catalogue counterpart at ANY magnitude — so the
 * seam always leaves a hole that the bright set is forbidden to fill. Sweeping the cut from 3 to 5
 * and the overlap from nothing to two magnitudes never got the hole count below 167.
 *
 * **So both catalogues are drawn whole, and the overlap is simply allowed.** That has no holes by
 * construction, and the duplicate costs nothing visible: the two records for one star sit a median
 * of one arcsecond apart, which on a 1080-wide screen is 0.002 px at the widest field and 1.2 px at
 * the 0.25° floor — the same dot, drawn twice.
 *
 * ## What is stored, and what is resolved before it gets here
 *
 * Unit vectors rather than two angles, because projecting from a vector costs no trigonometry at
 * all; and the colour BAND rather than the colour measurement, because resolving the band is a
 * comparison chain that belongs at load time rather than sixty times a second.
 *
 * Not thread-safe: one layer belongs to one screen, and whoever fills it is the only writer.
 */
class StarLayer(initialCapacity: Int = 1024) {

    /** How many stars are held. Every array below is valid over `0 until count`. */
    var count: Int = 0
        private set

    /** Unit vectors in the catalogue's own frame — equatorial. See [SkyFrame]. */
    var vx: DoubleArray = DoubleArray(initialCapacity)
        private set
    var vy: DoubleArray = DoubleArray(initialCapacity)
        private set
    var vz: DoubleArray = DoubleArray(initialCapacity)
        private set

    /** Apparent magnitude, for the size band and the per-frame cut. */
    var magnitude: FloatArray = FloatArray(initialCapacity)
        private set

    /** [StarNames.COLOUR_BANDS] index, or [StarNames.NO_COLOUR_BAND] where none was measured. */
    var colourBand: IntArray = IntArray(initialCapacity)
        private set

    /** Start filling from empty. */
    fun clear() {
        count = 0
    }

    /**
     * Make room for at least this many stars in total.
     *
     * ⚠️ **This used to throw the old arrays away, on the stated grounds that "every caller fills
     * from scratch after clearing" — and [add] is a caller that does not.** `add` calls this with
     * `count + 1`, so the moment it crosses the capacity every star already written is replaced by
     * a zero vector at magnitude zero: the whole bright set collapsed to one point on the celestial
     * sphere, drawn, with nothing thrown and nothing logged.
     *
     * ⚠️ **Not reachable today, and only by three hundred rows.** The one `add` caller pre-sizes
     * with `ensure(rows.size)` first, so the growth never fires — but `BRIGHT_CAPACITY` is 8,704
     * against a catalogue of 8,404, and nothing in the build would notice the day that asset gains
     * three hundred stars. Copying makes `add`'s own contract true instead of leaving it right by
     * the grace of a caller that need not have been written that way.
     *
     * It costs nothing measurable where it matters: both real call sites reach here with [count] at
     * zero, having just cleared, so the copy is of no elements at all.
     *
     * ⚠️ `maxOf(vx.size, 1)` because zero doubles: a layer constructed with no capacity would
     * otherwise spin here for ever rather than failing. Every construction today passes a positive
     * constant, so this is a guard against a hang that cannot happen yet.
     */
    fun ensure(capacity: Int) {
        if (capacity <= vx.size) return
        var size = maxOf(vx.size, 1)
        while (size < capacity) size *= 2
        vx = vx.copyOf(size)
        vy = vy.copyOf(size)
        vz = vz.copyOf(size)
        magnitude = magnitude.copyOf(size)
        colourBand = colourBand.copyOf(size)
    }

    /**
     * Add one star from its catalogue position.
     *
     * @param colourBand already resolved — [StarNames.bandFromBpRp] for the deep catalogue,
     *   [StarNames.bandFromBv] for the bright one. Each source keeps the measurement it actually
     *   made; neither is converted into the other's scale behind the reader's back.
     */
    fun add(rightAscensionDeg: Double, declinationDeg: Double, magnitude: Float, colourBand: Int) {
        ensure(count + 1)
        val u = SkyProjection.equatorialVector(rightAscensionDeg, declinationDeg)
        vx[count] = u[0]
        vy[count] = u[1]
        vz[count] = u[2]
        this.magnitude[count] = magnitude
        this.colourBand[count] = colourBand
        count++
    }

    /** Publish a count filled by a caller writing the arrays directly, as the reader's fill does. */
    internal fun published(filled: Int) {
        count = filled
    }
}
