package dev.mascwa.pulse.core.telemetry

/**
 * Finding the genuinely nearest places, given a server that will not sort by distance.
 *
 * Overpass has no notion of "nearest". A query says *how many* elements to return, and the server
 * fills that quota in its own internal order — by element type, then by id — then stops. It also
 * emits every `node` before any `way`. So a query that asks for 80 elements inside a 15 km radius of
 * a dense city gets an arbitrary 80, sorted afterwards by a client that can only rank what it was
 * sent.
 *
 * ⚠️ **The consequence is the part worth understanding, because it is not "the list is a bit
 * short".** In central London the app's own query returned 80 elements, all of them nodes, ids
 * ascending, out of roughly 1,200 matches. Thirty-six of the true nearest forty were absent. The
 * screen is titled "Nearest Help" and its top row was an 853 m walk-in centre while a clinic sat at
 * 290 m. Large hospitals are mapped in OSM as building polygons — `way` elements — so on that
 * response they could not appear at any distance: St Thomas' and Evelina London Children's were both
 * missing for that reason alone.
 *
 * **The fix is not to raise the cap, and not to remove it.** A cap that binds always yields an
 * arbitrary subset, so a *bigger* arbitrary subset is still arbitrary — it just fails less often,
 * which is worse, because it fails invisibly. Removing the cap makes a phone download an unbounded
 * response over a mobile connection.
 *
 * What actually works is to make the cap *not bind*, by choosing the radius instead of the quota.
 * If every match inside radius r is returned, then the nearest few of them are the true nearest few,
 * because everything omitted is by definition farther away than r. So: probe, and let the count tell
 * you where to go next. A count that hit the quota means the answer cannot be trusted and the radius
 * must come down; a count comfortably under it means the answer is complete for that radius.
 *
 * Pure and CI-tested; the caller does the fetching and passes back what it got.
 */
object PoiSearch {

    /** How many places the list actually shows. */
    const val WANT = 40

    /**
     * The most elements one response may carry.
     *
     * A safety bound on a phone's data, not a target. The search is designed so this never binds in
     * practice — if it does, that is the signal to narrow, not a result to display.
     */
    const val HARD_CAP = 250

    /** Where to start looking. Small enough that a dense city answers completely. */
    const val MIN_RADIUS_M = 1_500

    /** How much wider to go when a radius turns up too little. */
    const val WIDEN_FACTOR = 3

    /** How much narrower to go when the quota binds. */
    const val NARROW_DIVISOR = 3

    /** One round trip: the radius asked for, and how many elements came back. */
    data class Probe(val radiusMeters: Int, val returned: Int)

    /**
     * Whether the server ran out of quota rather than out of places.
     *
     * The only honest reading of `returned == cap` is "there may be more, and what you got is an
     * arbitrary slice of them" — Overpass does not say which it was, so equality has to be treated
     * as truncation even in the rare case the counts genuinely coincided.
     */
    fun capBound(returned: Int, cap: Int = HARD_CAP): Boolean = returned >= cap

    /**
     * Whether a probe's results can be ranked and shown.
     *
     * True only when the quota did not bind, because that is exactly the condition under which the
     * response contains *every* match inside the radius — and therefore the nearest ones in it are
     * the nearest ones that exist.
     */
    fun trustworthy(probe: Probe, cap: Int = HARD_CAP): Boolean = !capBound(probe.returned, cap)

    /**
     * The radius to try next, or null to stop and use what the last probe returned.
     *
     * In order, and the order matters:
     *
     * 1. **The quota bound → narrow.** Nothing about this response can be relied on, however many
     *    rows it holds, so a full-looking list is not a reason to stop. Bottoming out at
     *    [minRadius] stops anyway — the caller then knows the result is truncated and must say so.
     * 2. **Enough places, quota clear → stop.** The set is complete for this radius, so its nearest
     *    [want] are the true nearest [want].
     * 3. **Too few, room to grow → widen.** Genuinely sparse country, not a truncated response.
     * 4. **Otherwise stop.** Already at the widest allowed; a short list is the real answer.
     */
    fun nextRadius(
        probe: Probe,
        want: Int = WANT,
        cap: Int = HARD_CAP,
        minRadius: Int = MIN_RADIUS_M,
        maxRadius: Int,
    ): Int? {
        if (capBound(probe.returned, cap)) {
            if (probe.radiusMeters <= minRadius) return null
            return (probe.radiusMeters / NARROW_DIVISOR).coerceAtLeast(minRadius)
        }
        if (probe.returned >= want) return null
        if (probe.radiusMeters >= maxRadius) return null
        return (probe.radiusMeters.toLong() * WIDEN_FACTOR)
            .coerceAtMost(maxRadius.toLong())
            .toInt()
    }

    /**
     * Where to begin, for a category whose own reach is [maxRadius].
     *
     * Never starts wider than the category allows — a category with a 1 km reach should not have its
     * first probe cover 1.5 km and then have to narrow.
     */
    fun startRadius(maxRadius: Int, minRadius: Int = MIN_RADIUS_M): Int =
        minRadius.coerceAtMost(maxRadius).coerceAtLeast(1)

    /**
     * A bound on how many round trips one search may make.
     *
     * Overpass is a free community endpoint and this repository already gates itself to two
     * concurrent requests for that reason. A search that widened forever would be a slow way to earn
     * a ban, so the ladder is short by construction: three steps covers 1.5 km → 4.5 km → 13.5 km,
     * which spans every radius the categories actually use.
     */
    const val MAX_PROBES = 4
}
