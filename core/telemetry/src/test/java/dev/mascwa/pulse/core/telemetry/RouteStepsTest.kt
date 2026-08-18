package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The step list below is a real OSRM response, taken verbatim from the public demo server for a
 * drive across central London (Trafalgar Square to Buckingham Palace). Nine steps, two forks, two
 * unnamed-ref roads, an A-road carried across three steps, and an `arrive` with a modifier that
 * means nothing — all of which a hand-written fixture would have been too tidy to contain.
 *
 * Cumulative distance to each manoeuvre, from the response itself:
 * `0, 21.0, 131.2, 177.1, 357.0, 876.8, 1073.5, 1412.3, 1577.5`.
 */
class RouteStepsTest {

    private val london = listOf(
        RouteSteps.Step("depart", "right", "King Charles I Island", "", 51.507478, -0.127965, 37.0, 21.0),
        RouteSteps.Step("fork", "slight right", "Charing Cross", "", 51.50757, -0.12771, 82.0, 110.2),
        RouteSteps.Step("fork", "slight left", "Trafalgar Square", "A4", 51.507289, -0.128015, 316.0, 45.9),
        RouteSteps.Step("new name", "straight", "Cockspur Street", "A4", 51.507441, -0.128607, 275.0, 179.9),
        RouteSteps.Step("new name", "straight", "Pall Mall", "A4", 51.507639, -0.131129, 240.0, 519.8),
        RouteSteps.Step("turn", "left", "Marlborough Road", "", 51.505246, -0.137437, 149.0, 196.7),
        RouteSteps.Step("turn", "right", "The Mall", "", 51.503732, -0.136072, 236.0, 338.8),
        RouteSteps.Step("turn", "right", "Queens Gardens", "", 51.502222, -0.140708, 324.0, 165.2),
        RouteSteps.Step("arrive", "left", "Constitution Hill", "", 51.503364, -0.142161, 0.0, 0.0),
    )

    // ---- which turn is next ----------------------------------------------------------------

    @Test fun atTheStartTheNextTurnIsTheFirstManoeuvreNotTheDeparture() {
        // Step 0 is the departure, which has already happened; step 1 is the first thing to do, and
        // step 0's own length (21 m) is the ground between them.
        val g = RouteSteps.upcoming(london, travelledMeters = 0.0)
        assertNotNull(g)
        g!!
        assertEquals("fork", g.step.type)
        assertEquals("Charing Cross", g.step.name)
        assertEquals(21.0, g.metresAway, 1e-9)
        assertEquals("Bear right at the fork onto Charing Cross in 20 m", g.full)
    }

    @Test fun movingAlongTheRouteConsumesWholeStepsAndCountsDownTheRest() {
        // 25 m travelled: step 0 (21 m) is behind, 4 m into step 1, so 110.2 - 4 = 106.2 m to the
        // second fork. Both the name and the ref are known there, and a sign carries both.
        val g = RouteSteps.upcoming(london, travelledMeters = 25.0)!!
        assertEquals("Trafalgar Square", g.step.name)
        assertEquals(106.2, g.metresAway, 1e-9)
        assertEquals("Bear left at the fork onto A4 Trafalgar Square in 100 m", g.full)

        // 900 m: 21 + 110.2 + 45.9 + 179.9 + 519.8 = 876.8 consumed, which is the left turn ONTO
        // Marlborough Road — already behind you. 23.2 m along that road, and its leg is 196.7 m, so
        // the next thing to do is the right turn at the end of it, 173.5 m away.
        val h = RouteSteps.upcoming(london, travelledMeters = 900.0)!!
        assertEquals("The Mall", h.step.name)
        assertEquals(173.5, h.metresAway, 1e-9)
        assertEquals("Turn right onto The Mall in 170 m", h.full)
        assertEquals("Queens Gardens", h.then?.name)
    }

    @Test fun aStaleRouteWalkedPastItsEndStillGivesItsLastInstruction() {
        // ⚠️ Silence here would be the wrong answer, not the safe one. The caller re-routes every
        // sixty metres, so this state lasts moments — and during them the final instruction is
        // still the best thing known.
        val g = RouteSteps.upcoming(london, travelledMeters = 99_999.0)!!
        assertEquals("arrive", g.step.type)
        assertEquals(0.0, g.metresAway, 1e-9)
        assertEquals("Arrive in now", g.full)
        assertNull("nothing follows the arrival", g.then)
    }

    @Test fun negativeTravelIsTreatedAsNoneRatherThanRunningBackwards() {
        val g = RouteSteps.upcoming(london, travelledMeters = -500.0)!!
        assertEquals("Charing Cross", g.step.name)
        assertEquals(21.0, g.metresAway, 1e-9)
    }

    @Test fun aRouteWithNothingToSayReturnsNothing() {
        assertNull(RouteSteps.upcoming(emptyList(), 0.0))
        // A single `arrive` is a route with no instruction in it — you are already there.
        assertNull(RouteSteps.upcoming(listOf(london.last()), 0.0))
    }

    // ---- phrasing ---------------------------------------------------------------------------

    @Test fun eachManoeuvreTypeIsGivenItsOwnWording() {
        fun p(type: String, modifier: String = "", name: String = "", ref: String = "", exit: Int? = null) =
            RouteSteps.phrase(RouteSteps.Step(type, modifier, name, ref, exit = exit))

        assertEquals("Set off on King Charles I Island", p("depart", "right", "King Charles I Island"))
        assertEquals("Set off", p("depart", "right"))
        assertEquals("Arrive", p("arrive", "left", "Constitution Hill"))
        assertEquals("Turn left onto Marlborough Road", p("turn", "left", "Marlborough Road"))
        assertEquals("Turn sharp right", p("turn", "sharp right"))
        assertEquals("Turn around", p("turn", "uturn"))
        assertEquals("Bear right at the fork onto Charing Cross", p("fork", "slight right", "Charing Cross"))
        assertEquals("Continue on A4 Pall Mall", p("new name", "straight", "Pall Mall", "A4"))
        assertEquals("Turn right at the end of the road", p("end of road", "right"))
        assertEquals("Merge onto M4", p("merge", "slight left", ref = "M4"))
        assertEquals("Take the slip road onto A40", p("on ramp", "slight right", ref = "A40"))
        assertEquals("Take the exit for Heathrow", p("off ramp", "slight left", "Heathrow"))
    }

    @Test fun aRoundaboutCountsItsExitsAndOrdinalsThemProperly() {
        fun r(exit: Int?, name: String = "") =
            RouteSteps.phrase(RouteSteps.Step("roundabout", "right", name, exit = exit))
        assertEquals("Take the 1st exit", r(1))
        assertEquals("Take the 2nd exit onto Station Road", r(2, "Station Road"))
        assertEquals("Take the 3rd exit", r(3))
        assertEquals("Take the 4th exit", r(4))
        // A roundabout the router did not count exits for still gets an instruction.
        assertEquals("Take the roundabout onto Station Road", r(null, "Station Road"))
        assertEquals("Take the roundabout", r(0))
        // 11th/12th/13th are the exceptions every ordinal function gets wrong. No roundabout has
        // thirteen exits; the rule is pinned because it is cheaper than finding out later.
        assertEquals("Take the 11th exit", r(11))
        assertEquals("Take the 12th exit", r(12))
        assertEquals("Take the 13th exit", r(13))
        assertEquals("Take the 21st exit", r(21))
    }

    @Test fun aManoeuvreTypeThisCodeHasNeverHeardOfStillSaysSomethingTrue() {
        // ⚠️ Routers add manoeuvre types. Falling back to the modifier keeps the instruction
        // correct where silence would drop it and a guess would invent it.
        assertEquals(
            "Turn left onto Sandy Lane",
            RouteSteps.phrase(RouteSteps.Step("some-future-type", "left", "Sandy Lane")),
        )
        // Nothing known at all: say the least that is still true.
        assertEquals("Continue", RouteSteps.phrase(RouteSteps.Step("some-future-type", "")))
        assertEquals(
            "Continue on Sandy Lane",
            RouteSteps.phrase(RouteSteps.Step("some-future-type", "straight", "Sandy Lane")),
        )
    }

    @Test fun aRoadIsNamedTheWayItIsSigned() {
        // Ref and name together, ref alone, name alone, and neither.
        assertEquals("Continue on A4 Pall Mall", RouteSteps.phrase(RouteSteps.Step("continue", "", "Pall Mall", "A4")))
        assertEquals("Continue on A4", RouteSteps.phrase(RouteSteps.Step("continue", "", "", "A4")))
        assertEquals("Continue on Pall Mall", RouteSteps.phrase(RouteSteps.Step("continue", "", "Pall Mall")))
        assertEquals("Continue", RouteSteps.phrase(RouteSteps.Step("continue", "")))
        // A name that already carries the ref must not repeat it.
        assertEquals("Continue on A4", RouteSteps.phrase(RouteSteps.Step("continue", "", "A4", "A4")))
    }

    // ---- distance ---------------------------------------------------------------------------

    @Test fun distanceIsRoundedToWhatAnInstructionCanUsefullyCarry() {
        // Under twenty metres there is no point counting: it is happening.
        assertEquals("now", RouteSteps.distance(0.0))
        assertEquals("now", RouteSteps.distance(19.9))
        // Tens of metres up to a kilometre.
        assertEquals("20 m", RouteSteps.distance(21.0))
        assertEquals("100 m", RouteSteps.distance(106.2))
        assertEquals("170 m", RouteSteps.distance(173.5))
        assertEquals("990 m", RouteSteps.distance(999.0))
        // Then tenths of a kilometre. 1577.5 / 100 = 15.775, rounded to 16.
        assertEquals("1.0 km", RouteSteps.distance(1000.0))
        assertEquals("1.6 km", RouteSteps.distance(1577.5))
        assertEquals("12.4 km", RouteSteps.distance(12_350.0))
    }

    @Test fun theKilometreFigureCarriesAPointWhateverTheDevicesLocaleIs() {
        // ⚠️ The recurring trap in this repository: a default-locale format renders "1,6 km" on
        // much of Europe, in a string the rest of the app writes with a point.
        val previous = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMANY)
            assertEquals("1.6 km", RouteSteps.distance(1577.5))
            assertTrue(!RouteSteps.distance(1577.5).contains(","))
        } finally {
            java.util.Locale.setDefault(previous)
        }
    }
}
