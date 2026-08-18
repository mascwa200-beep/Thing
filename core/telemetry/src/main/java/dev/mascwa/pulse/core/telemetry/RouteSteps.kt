package dev.mascwa.pulse.core.telemetry

import kotlin.math.max

/**
 * The turns a road route is actually made of, and which one is coming next.
 *
 * A router returns two quite different things: a line to draw, and a list of instructions. Only the
 * line was ever asked for, so the map could show a road-snapped path while the banner had nothing
 * to say about it but a compass bearing to the destination — which is a real reading, and is not
 * "bear left at the fork onto Trafalgar Square".
 *
 * ⚠️ **Which turn is next is answered by arithmetic, not by proximity.** The tempting rule — the
 * nearest manoeuvre — is wrong on any route that doubles back or passes near a later junction, and
 * wrong in a way that only shows up in the one place it matters. What is used instead rests on a
 * property the caller already guarantees: the route is requested *from where you are* and re-requested
 * every sixty metres of travel, so the distance you have covered since it was issued is small and
 * measurable, and the step list can simply be walked by that distance.
 *
 * The step model follows OSRM's, because that is what the app queries: each step carries the
 * manoeuvre that *begins* it and the distance travelled along it before the next manoeuvre. So the
 * distance to the next turn is the remainder of the step you are on, never the length of the turn
 * you are heading into.
 *
 * Pure and CI-tested: no clock, no I/O, and the phrasing is fixed English rather than anything
 * generated.
 */
object RouteSteps {

    /**
     * One leg of the route, as the router describes it.
     *
     * [distanceMeters] is the length of *this* step — the ground covered after [type]'s manoeuvre
     * and before the next one. Every string field may be blank; a router that does not name a road
     * is not an error, and a blank name simply drops out of the phrasing.
     */
    data class Step(
        /** OSRM manoeuvre type: `turn`, `fork`, `roundabout`, `arrive`, and so on. */
        val type: String,
        /** OSRM modifier: `left`, `slight right`, `uturn`, `straight`… May be blank. */
        val modifier: String = "",
        /** The road being joined, e.g. "Marlborough Road". May be blank. */
        val name: String = "",
        /** The road's designation, e.g. "A4". Present on some steps only. */
        val ref: String = "",
        val latitude: Double = 0.0,
        val longitude: Double = 0.0,
        /** Compass bearing along the road after the manoeuvre. */
        val bearingAfterDeg: Double = 0.0,
        val distanceMeters: Double = 0.0,
        /** Which exit to take, on a roundabout or rotary. Null everywhere else. */
        val exit: Int? = null,
    )

    /** The next thing to do, how far away it is, and what follows it. */
    data class Guidance(
        val step: Step,
        val metresAway: Double,
        /** The manoeuvre after [step], for a "then …" line. Null at the end of the route. */
        val then: Step? = null,
    ) {
        /** "Turn left onto Marlborough Road". */
        val instruction: String get() = phrase(step)

        /** "Turn left onto Marlborough Road in 200 m". */
        val full: String get() = "$instruction in ${distance(metresAway)}"
    }

    /**
     * The manoeuvre being approached, or null when there is nothing useful to say.
     *
     * [travelledMeters] is how far along the route the traveller has come. The accurate figure is
     * the route's own length minus what [RouteProgress] says is left — that projects the position
     * onto the polyline and so survives a wrong turn — but a straight line from the route's origin
     * is a fair substitute, because the caller re-routes long before it drifts.
     *
     * Returns null for a one-step route (a router that only ever says "arrive" has no instruction
     * in it) and for a route already walked past its end.
     */
    fun upcoming(steps: List<Step>, travelledMeters: Double): Guidance? {
        if (steps.size < 2) return null
        var remaining = max(0.0, travelledMeters)
        var i = 0
        // Consume whole steps. Stops at the last step that has a manoeuvre after it, so a traveller
        // who has run past the end of a stale route is given its final instruction rather than
        // nothing — the next route request is at most sixty metres away.
        while (i < steps.lastIndex - 1 && remaining >= steps[i].distanceMeters) {
            remaining -= steps[i].distanceMeters
            i++
        }
        val next = steps[i + 1]
        val away = max(0.0, steps[i].distanceMeters - remaining)
        return Guidance(next, away, steps.getOrNull(i + 2))
    }

    /**
     * What to say about a manoeuvre.
     *
     * ⚠️ An unrecognised type falls back to the modifier rather than to silence *or* to invention:
     * routers add manoeuvre types over time, and "Bear right" is true of a `fork` this code has
     * never heard of. Only when there is neither a known type nor a modifier does it decline.
     */
    fun phrase(step: Step): String {
        val road = roadOf(step)
        val onto = if (road.isEmpty()) "" else " onto $road"
        val turn = turnOf(step.modifier)
        return when (step.type.lowercase()) {
            "depart" -> if (road.isEmpty()) "Set off" else "Set off on $road"
            "arrive" -> "Arrive"
            "roundabout", "rotary" -> roundabout(step, road)
            "exit roundabout", "exit rotary" -> if (road.isEmpty()) "Leave the roundabout" else "Leave the roundabout onto $road"
            "merge" -> if (road.isEmpty()) "Merge" else "Merge onto $road"
            "on ramp" -> if (road.isEmpty()) "Take the slip road" else "Take the slip road onto $road"
            "off ramp" -> if (road.isEmpty()) "Take the exit" else "Take the exit for $road"
            "fork" -> if (turn == null) "Keep going$onto" else "$turn at the fork$onto"
            "end of road" -> if (turn == null) "Continue$onto" else "$turn at the end of the road$onto"
            "new name", "continue", "notification" -> if (road.isEmpty()) "Continue" else "Continue on $road"
            "turn" -> if (turn == null) "Continue$onto" else "$turn$onto"
            // Unknown to this code, but the modifier still says something true.
            else -> turn?.let { "$it$onto" } ?: if (road.isEmpty()) "Continue" else "Continue on $road"
        }
    }

    /** "200 m" / "1.4 km", in the unit a walking or driving instruction is given in. */
    fun distance(metres: Double): String = when {
        metres < 20 -> "now"
        metres < 1000 -> "${(metres / 10).toInt() * 10} m"
        else -> {
            // One decimal, hand-formatted: a locale that renders a comma decimal would otherwise
            // turn "1.4 km" into "1,4 km" in a string the rest of the app writes with a point.
            val tenths = Math.round(metres / 100.0).toInt()
            "${tenths / 10}.${tenths % 10} km"
        }
    }

    private fun roundabout(step: Step, road: String): String {
        val exit = step.exit
        val onto = if (road.isEmpty()) "" else " onto $road"
        return if (exit == null || exit < 1) {
            "Take the roundabout$onto"
        } else {
            "Take the ${ordinal(exit)} exit$onto"
        }
    }

    private fun ordinal(n: Int): String {
        val suffix = when {
            n % 100 in 11..13 -> "th"
            n % 10 == 1 -> "st"
            n % 10 == 2 -> "nd"
            n % 10 == 3 -> "rd"
            else -> "th"
        }
        return "$n$suffix"
    }

    /**
     * The road to name, preferring the one a sign will actually show.
     *
     * A name and a ref together read as "A4 Pall Mall", which is how the road is signed; either
     * alone stands on its own. Both blank is common and simply means the road is unnamed.
     */
    private fun roadOf(step: Step): String {
        val name = step.name.trim()
        val ref = step.ref.trim()
        return when {
            ref.isNotEmpty() && name.isNotEmpty() && !name.contains(ref) -> "$ref $name"
            name.isNotEmpty() -> name
            else -> ref
        }
    }

    private fun turnOf(modifier: String): String? = when (modifier.trim().lowercase()) {
        "left" -> "Turn left"
        "right" -> "Turn right"
        "slight left" -> "Bear left"
        "slight right" -> "Bear right"
        "sharp left" -> "Turn sharp left"
        "sharp right" -> "Turn sharp right"
        "uturn" -> "Turn around"
        // "straight" is a modifier, not an instruction: at a fork it means keep going, and phrasing
        // it as a turn would be wrong. The callers above supply their own wording for that case.
        else -> null
    }
}
