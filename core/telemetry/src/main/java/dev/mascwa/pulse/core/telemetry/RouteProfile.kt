package dev.mascwa.pulse.core.telemetry

/**
 * Turning a route into a profile: where to sample it, and what the heights mean once you have them.
 *
 * A road route is a list of shape points spaced by whatever the router felt like — dense round a
 * roundabout, sparse along a motorway. Sampling those points directly gives a profile whose
 * horizontal axis is the router's drawing style rather than distance, so a mile of straight road
 * becomes a single pixel next to a junction that fills a third of the chart. [sample] fixes that by
 * walking the route at even intervals of real distance.
 */
object RouteProfile {

    /** A point on the profile: how far along the route, and how high (once known). */
    data class Sample(val distanceM: Double, val latitudeDeg: Double, val longitudeDeg: Double)

    /** What the profile says as a whole. */
    data class Summary(
        val lengthM: Double,
        val minElevationM: Double,
        val maxElevationM: Double,
        val ascentM: Double,
        val descentM: Double,
    ) {
        /** The steepest sustained direction, as a plain-English line, or null when it barely moves. */
        fun describe(): String? {
            val relief = maxElevationM - minElevationM
            if (relief < 10.0) return null
            return "▲${ascentM.toInt()} m  ▼${descentM.toInt()} m  ·  ${minElevationM.toInt()}–${maxElevationM.toInt()} m"
        }
    }

    /**
     * [count] points spread evenly along the route by distance, ends included.
     *
     * Positions between shape points are interpolated linearly in latitude and longitude. Over the
     * few hundred metres that separate consecutive route points that is indistinguishable from
     * great-circle interpolation, and the elevation service rounds to a terrain grid far coarser
     * than the difference.
     */
    fun sample(route: List<Pair<Double, Double>>, count: Int): List<Sample> {
        if (route.size < 2 || count < 2) {
            return route.take(1).map { Sample(0.0, it.first, it.second) }
        }
        // Cumulative distance to each shape point, so a target distance can be located by walking
        // the list once rather than re-measuring from the start each time.
        val cumulative = DoubleArray(route.size)
        for (i in 1 until route.size) {
            cumulative[i] = cumulative[i - 1] + Geodesy.distanceMeters(
                route[i - 1].first, route[i - 1].second, route[i].first, route[i].second,
            )
        }
        val total = cumulative.last()
        if (total <= 0.0) return listOf(Sample(0.0, route.first().first, route.first().second))

        val out = ArrayList<Sample>(count)
        var segment = 1
        for (i in 0 until count) {
            val target = total * i / (count - 1).toDouble()
            while (segment < route.size - 1 && cumulative[segment] < target) segment++
            val spanStart = cumulative[segment - 1]
            val spanEnd = cumulative[segment]
            val span = spanEnd - spanStart
            val t = if (span <= 0.0) 0.0 else ((target - spanStart) / span).coerceIn(0.0, 1.0)
            val (lat1, lon1) = route[segment - 1]
            val (lat2, lon2) = route[segment]
            out += Sample(
                distanceM = target,
                latitudeDeg = lat1 + (lat2 - lat1) * t,
                longitudeDeg = lon1 + (lon2 - lon1) * t,
            )
        }
        return out
    }

    /**
     * Summarise sampled heights.
     *
     * Climb and descent ignore changes below [noiseFloorM]. The elevation service returns values
     * off a terrain grid, and stepping between neighbouring grid cells produces small alternating
     * differences that are an artefact of the grid, not of the road; summing them reports hundreds
     * of metres of climb on a flat route.
     */
    fun summarise(
        samples: List<Sample>,
        elevations: List<Double>,
        noiseFloorM: Double = 4.0,
    ): Summary? {
        val n = minOf(samples.size, elevations.size)
        if (n < 2) return null
        val heights = elevations.subList(0, n).filter { it.isFinite() }
        if (heights.size < 2) return null

        var ascent = 0.0
        var descent = 0.0
        var reference = heights.first()
        for (h in heights) {
            val delta = h - reference
            if (delta >= noiseFloorM) {
                ascent += delta
                reference = h
            } else if (delta <= -noiseFloorM) {
                descent += -delta
                reference = h
            }
        }
        return Summary(
            lengthM = samples[n - 1].distanceM,
            minElevationM = heights.min(),
            maxElevationM = heights.max(),
            ascentM = ascent,
            descentM = descent,
        )
    }
}
