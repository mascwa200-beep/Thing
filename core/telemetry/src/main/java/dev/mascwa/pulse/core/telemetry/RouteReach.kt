package dev.mascwa.pulse.core.telemetry

/**
 * Whether a road route actually reaches where you asked to go.
 *
 * A routing server does not route to a coordinate. It routes between the nearest points on the road
 * network, and it reports how far it had to move each end to get there. When that snap is a few
 * metres it is the width of a pavement and means nothing. When it is kilometres, the road network
 * does not go where you are pointing — and the server still answers `code: Ok` with a full distance
 * and a confident ETA, because from its point of view it did the job it was asked.
 *
 * ⚠️ **Measured, not supposed.** Asking for London → New York returns `Ok`, 2,149 km, 28 hours, and
 * a destination snapped 5,534 km away onto the coast of Portugal. A control across central London
 * snaps 14 m and 44 m. The app parsed only `routes` and never `waypoints`, so it had the ETA and not
 * the fact that the ETA was for somewhere else entirely.
 *
 * That matters more than it sounds, because the map is the *mitigation*, not the guarantee: the
 * objective marker is drawn where the user put it and the route line visibly stops short — but at a
 * 300 km zoom that gap is invisible, and the departure alert derived from the same duration has no
 * map at all.
 *
 * Pure and CI-tested; the caller passes the snap distance straight from the response.
 */
object RouteReach {

    /** How well the road network connects to the place that was asked for. */
    enum class Reach {
        /** The route ends at the destination. Ordinary case, nothing to say. */
        ON_ROAD,

        /** The road stops short; the rest is on foot. A real answer, with a caveat worth printing. */
        WALK_LAST_LEG,

        /** The road network does not reach this place. The distance and ETA are for somewhere else. */
        UNREACHABLE,

        /** No snap distance was reported, so there is nothing to judge. */
        UNKNOWN,
    }

    /**
     * Under this, the snap is pavement width and kerb geometry rather than a gap worth mentioning.
     *
     * The measured control snapped 14 m and 44 m in central London; a driveway or a car park entrance
     * lands in the same range.
     */
    const val ON_ROAD_M = 250.0

    /**
     * Past this, treat the destination as off the road network entirely.
     *
     * Chosen against real places rather than picked round: Ben Nevis snaps 2,161 m and Snowdon
     * 2,755 m, and both are genuine "drive to the trailhead and walk" destinations that a navigation
     * app should still route to. Lundy — an island — snaps 19,256 m and is not reachable by road at
     * all. Five kilometres sits in the gap between those two kinds of place.
     */
    const val WALK_LIMIT_M = 5_000.0

    /**
     * Whether the route is a road route to somewhere else.
     *
     * The rules, and why each:
     *
     * 1. **No snap reported → [Reach.UNKNOWN].** A server that omits `waypoints` tells us nothing,
     *    and silence is the right response to that — never a claim in either direction.
     * 2. **Snap past [WALK_LIMIT_M] → [Reach.UNREACHABLE].** The absolute case: islands, open water,
     *    the wrong continent.
     * 3. **The final gap is longer than the whole drive → [Reach.UNREACHABLE].** A scale-free
     *    absurdity check that catches what a fixed threshold cannot. London → New York is the
     *    example: 5,534 km of snap against a 2,149 km "route". If the walk at the end exceeds the
     *    drive, it was not a route to where you asked.
     *
     *    ⚠️ Deliberately `>=` the *whole* distance rather than a fraction of it. A half-distance rule
     *    would fire on a legitimate short hop — 260 m of snap on a 500 m journey is an ordinary
     *    park-and-walk, not a failure — and wrongly refusing to route somewhere reachable is a worse
     *    failure than the one this exists to prevent.
     * 4. **Snap within [ON_ROAD_M] → [Reach.ON_ROAD].** The overwhelmingly common case, unchanged.
     * 5. **Otherwise the road stops short**, which is worth saying and is still a useful route.
     */
    fun classify(snapMeters: Double?, routeMeters: Double): Reach {
        if (snapMeters == null || snapMeters < 0 || !snapMeters.isFinite()) return Reach.UNKNOWN
        if (snapMeters >= WALK_LIMIT_M) return Reach.UNREACHABLE
        if (routeMeters > 0 && snapMeters >= routeMeters) return Reach.UNREACHABLE
        if (snapMeters <= ON_ROAD_M) return Reach.ON_ROAD
        return Reach.WALK_LAST_LEG
    }

    /** Whether a distance and ETA from this route may be presented as reaching the destination. */
    fun trustworthy(reach: Reach): Boolean =
        reach == Reach.ON_ROAD || reach == Reach.WALK_LAST_LEG || reach == Reach.UNKNOWN

    /**
     * The caveat to show beside the route, or null when there is nothing to add.
     *
     * Null for [Reach.ON_ROAD] and [Reach.UNKNOWN] — an ordinary route needs no apology, and an
     * absent measurement is not grounds for one either.
     */
    fun describe(reach: Reach, snapMeters: Double?): String? = when (reach) {
        Reach.UNREACHABLE -> "No road goes to this spot — the driving time is to the nearest road, not to here."
        Reach.WALK_LAST_LEG -> snapMeters
            ?.let { "The road stops about ${roundedDistance(it)} short — the last stretch is on foot." }
        Reach.ON_ROAD, Reach.UNKNOWN -> null
    }

    /**
     * A distance rounded the way someone would say it aloud.
     *
     * Kept here rather than borrowed from the app's formatter because this is a pure module and the
     * sentence reads better with one significant figure: "about 3 km short", not "3.14 km short".
     */
    internal fun roundedDistance(meters: Double): String = when {
        meters < 1_000 -> "${(meters / 50).toInt() * 50} m"
        else -> {
            val km = meters / 1_000.0
            if (km < 10) "${(km * 10).toInt() / 10.0} km" else "${km.toInt()} km"
        }
    }
}
