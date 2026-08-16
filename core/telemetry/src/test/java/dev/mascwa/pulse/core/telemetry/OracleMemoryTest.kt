package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OracleMemoryTest {

    private val NOW = 1_700_000_000_000L

    private fun learning(vararg stats: OracleMemory.RuleStat) =
        OracleMemory.Learning(stats.associateBy { it.family })

    private fun insight(id: String, family: String, score: Double) = Insight(
        id = id, family = family, kind = InsightKind.INSIGHT, urgency = Urgency.NOTABLE,
        title = id, detail = "", score = score,
    )

    // ---- affinity: every expected value computed from the shipped formula, shown in the comment ----

    @Test fun aRuleWithTooLittleEvidenceIsNotJudged() {
        // Under MIN_SHOWN (4) the answer is exactly neutral, however good the record looks.
        val perfectButNew = learning(OracleMemory.RuleStat("leave", shown = 3, acted = 3))
        assertEquals(1.0, OracleMemory.affinity(perfectButNew, "leave"), 1e-9)
        assertEquals(1.0, OracleMemory.affinity(OracleMemory.Learning(), "never_seen"), 1e-9)
    }

    @Test fun actingOnARuleLiftsIt() {
        // shown 4, acted 4 -> rate (4+1)/(4+2) = 0.8333…; 0.75 + 0.60 * 0.8333… = 1.25
        val s = learning(OracleMemory.RuleStat("leave", shown = 4, acted = 4))
        assertEquals(1.25, OracleMemory.affinity(s, "leave"), 1e-9)
    }

    @Test fun ignoringARuleDampensIt() {
        // shown 4, acted 0 -> rate (0+1)/(4+2) = 0.1666…; 0.75 + 0.60 * 0.1666… = 0.85
        val s = learning(OracleMemory.RuleStat("habit", shown = 4, acted = 0))
        assertEquals(0.85, OracleMemory.affinity(s, "habit"), 1e-9)
    }

    @Test fun theBandHoldsAtBothExtremes() {
        // shown 10 / acted 10 -> (11/12) -> 0.75 + 0.55 = 1.30; shown 10 / acted 0 -> (1/12) -> 0.80.
        val loved = learning(OracleMemory.RuleStat("leave", shown = 10, acted = 10))
        val ignored = learning(OracleMemory.RuleStat("habit", shown = 10, acted = 0))
        assertEquals(1.30, OracleMemory.affinity(loved, "leave"), 1e-9)
        assertEquals(0.80, OracleMemory.affinity(ignored, "habit"), 1e-9)
        // And no record, however lopsided, can leave the band.
        val extreme = learning(OracleMemory.RuleStat("x", shown = 100_000, acted = 100_000))
        assertTrue(OracleMemory.affinity(extreme, "x") <= OracleMemory.MAX_WEIGHT)
        val extremeBad = learning(OracleMemory.RuleStat("y", shown = 100_000, acted = 0))
        assertTrue(OracleMemory.affinity(extremeBad, "y") >= OracleMemory.MIN_WEIGHT)
    }

    // ---- recording ----

    @Test fun showingARuleCountsItOnceAndStampsTheTime() {
        var s = OracleMemory.recordShown(OracleMemory.Learning(), listOf("leave", "habit"), NOW)
        s = OracleMemory.recordShown(s, listOf("leave"), NOW + 1)
        assertEquals(2, s.stat("leave").shown)
        assertEquals(1, s.stat("habit").shown)
        assertEquals(NOW + 1, s.stat("leave").lastShownMs)
    }

    @Test fun aRuleShownTwiceInOnePassIsStillOneShowing() {
        val s = OracleMemory.recordShown(OracleMemory.Learning(), listOf("leave", "leave"), NOW)
        assertEquals(1, s.stat("leave").shown)
    }

    @Test fun actsCanNeverOutnumberShowings() {
        // Attribution runs over a window and can see the same visit twice; a hit rate above 1 would
        // push affinity past the band and quietly break the arithmetic.
        var s = OracleMemory.recordShown(OracleMemory.Learning(), listOf("leave"), NOW)
        repeat(5) { s = OracleMemory.recordActed(s, "leave") }
        assertEquals(1, s.stat("leave").acted)
        assertEquals(1, s.stat("leave").shown)
    }

    @Test fun actingOnARuleNeverShownIsIgnored() {
        val s = OracleMemory.recordActed(OracleMemory.Learning(), "ghost")
        assertTrue(s.stats.isEmpty())
    }

    // ---- reweighting ----

    @Test fun aRuleYouActOnOvertakesOneRankedSlightlyAboveIt() {
        // leave: 10.0 * 0.80 = 8.0 · habit: 9.0 * 1.30 = 11.7
        val s = learning(
            OracleMemory.RuleStat("leave", shown = 10, acted = 0),
            OracleMemory.RuleStat("habit", shown = 10, acted = 10),
        )
        val ranked = OracleMemory.reweight(
            listOf(insight("a", "leave", 10.0), insight("b", "habit", 9.0)), s,
        )
        assertEquals(listOf("b", "a"), ranked.map { it.id })
        assertEquals(11.7, ranked[0].score, 1e-9)
        assertEquals(8.0, ranked[1].score, 1e-9)
    }

    @Test fun reweightingWithNothingLearnedChangesNothing() {
        val given = listOf(insight("a", "leave", 10.0), insight("b", "habit", 9.0))
        assertEquals(given, OracleMemory.reweight(given, OracleMemory.Learning()))
    }

    @Test fun theBandCannotCloseALargeGap() {
        // The widest possible swing is MAX/MIN = 1.8x, so reasoning still decides the big calls.
        val s = learning(
            OracleMemory.RuleStat("urgent", shown = 50, acted = 0),
            OracleMemory.RuleStat("trivial", shown = 50, acted = 50),
        )
        val ranked = OracleMemory.reweight(
            listOf(insight("crit", "urgent", 10.0), insight("amb", "trivial", 2.0)), s,
        )
        assertEquals(listOf("crit", "amb"), ranked.map { it.id })
    }

    // ---- attribution ----

    @Test fun goingWhereItPointedCountsAsActingOnIt() {
        val acted = OracleMemory.attribute(
            shownRoutes = mapOf("leave" to ("nav" to NOW)),
            visits = listOf(OracleMemory.Visit("nav", NOW + 60_000)),
        )
        assertEquals(setOf("leave"), acted)
    }

    @Test fun aVisitBeforeOrLongAfterDoesNotCount() {
        val before = OracleMemory.attribute(
            mapOf("leave" to ("nav" to NOW)),
            listOf(OracleMemory.Visit("nav", NOW - 1)),
        )
        val late = OracleMemory.attribute(
            mapOf("leave" to ("nav" to NOW)),
            listOf(OracleMemory.Visit("nav", NOW + OracleMemory.ATTRIBUTION_WINDOW_MS + 1)),
        )
        assertTrue(before.isEmpty())
        assertTrue(late.isEmpty())
        // Exactly on the boundary does count.
        assertEquals(
            setOf("leave"),
            OracleMemory.attribute(
                mapOf("leave" to ("nav" to NOW)),
                listOf(OracleMemory.Visit("nav", NOW + OracleMemory.ATTRIBUTION_WINDOW_MS)),
            ),
        )
    }

    @Test fun aDifferentScreenIsNotCredit() {
        val acted = OracleMemory.attribute(
            mapOf("leave" to ("nav" to NOW)),
            listOf(OracleMemory.Visit("weather", NOW + 60_000)),
        )
        assertTrue(acted.isEmpty())
    }

    @Test fun theRouteYouOpenAnywayIsNeverCredit() {
        // THE guard that separates learning from flattering yourself: if you open the map every
        // morning, a visit to the map after a morning insight pointed there proves nothing, and
        // crediting it would teach the Oracle to rank loudest what you were going to do regardless.
        val acted = OracleMemory.attribute(
            shownRoutes = mapOf("leave" to ("nav" to NOW)),
            visits = listOf(OracleMemory.Visit("nav", NOW + 60_000)),
            habitualRoute = "nav",
        )
        assertTrue(acted.isEmpty())
    }

    @Test fun anInsightWithNowhereToGoIsNeverCredited() {
        val acted = OracleMemory.attribute(
            mapOf("aurora" to ("" to NOW)),
            listOf(OracleMemory.Visit("nav", NOW + 60_000)),
        )
        assertTrue(acted.isEmpty())
    }

    // ---- summary ----

    @Test fun theSummarySaysSoWhenItHasNotLearnedYet() {
        val s = learning(OracleMemory.RuleStat("leave", shown = 2, acted = 2))
        assertTrue(OracleMemory.summary(s).startsWith("Still learning"))
    }

    @Test fun theSummaryNamesTheBestAndWorstRules() {
        val s = learning(
            OracleMemory.RuleStat("leave", shown = 10, acted = 9),
            OracleMemory.RuleStat("habit", shown = 10, acted = 1),
        )
        val text = OracleMemory.summary(s)
        assertTrue(text, text.contains("leave (9/10)"))
        assertTrue(text, text.contains("habit (1/10)"))
    }

    @Test fun oneJudgedRuleReadsAsOneLineNotAComparisonWithItself() {
        val s = learning(OracleMemory.RuleStat("leave", shown = 6, acted = 4))
        assertEquals("leave: acted on 4 of 6.", OracleMemory.summary(s))
    }

    @Test fun clearingForgetsEverything() {
        assertTrue(OracleMemory.cleared().stats.isEmpty())
        assertFalse(OracleMemory.summary(OracleMemory.cleared()).isBlank())
    }
}
