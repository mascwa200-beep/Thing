package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CerebellumTest {

    private fun learnN(
        start: CerebellumState,
        cue: String,
        action: String,
        outcomes: List<Boolean>,
    ): CerebellumState {
        var s = start
        var t = 0L
        for (o in outcomes) s = Cerebellum.learn(s, cue, action, o, t++)
        return s
    }

    @Test
    fun firstObservationInitializesSkill() {
        val s = Cerebellum.learn(CerebellumState.EMPTY, "tool", "web", success = true, nowMs = 5)
        val skill = s.skills.single()
        assertEquals("tool", skill.cue)
        assertEquals("web", skill.action)
        assertEquals(1, skill.attempts)
        assertEquals(1, skill.successes)
        assertEquals(1.0, skill.reliability, 1e-9)
        assertEquals(5L, skill.lastEpochMs)
    }

    @Test
    fun ewmaUpdatesTowardRecentOutcome() {
        // success then failure: 0.7*1.0 + 0.3*0.0 = 0.7
        val s = learnN(CerebellumState.EMPTY, "tool", "fetch", listOf(true, false))
        val skill = s.skills.single()
        assertEquals(2, skill.attempts)
        assertEquals(1, skill.successes)
        assertEquals(0.7, skill.reliability, 1e-9)
    }

    @Test
    fun emptyCueOrActionIsNoOp() {
        val s = Cerebellum.learn(CerebellumState.EMPTY, "  ", "x", true, 1)
        val s2 = Cerebellum.learn(s, "cue", "   ", true, 1)
        assertTrue(s2.skills.isEmpty())
    }

    @Test
    fun notAutomaticUntilPracticedEnough() {
        // One perfect success: reliable but not practiced -> not a reflex.
        val s = Cerebellum.learn(CerebellumState.EMPTY, "tool", "call", true, 1)
        assertFalse(Cerebellum.isAutomatic(s.skills.single()))
        assertNull(Cerebellum.recall(s, "tool"))
    }

    @Test
    fun becomesReflexAfterEnoughReliablePractice() {
        val s = learnN(CerebellumState.EMPTY, "tool", "call", listOf(true, true, true))
        val skill = s.skills.single()
        assertEquals(3, skill.attempts)
        assertTrue(Cerebellum.isAutomatic(skill))
        assertEquals("call", Cerebellum.recall(s, "tool")?.action)
    }

    @Test
    fun lowReliabilityNeverBecomesReflex() {
        // attempts high but mostly failing -> stays below the reliability threshold.
        val s = learnN(CerebellumState.EMPTY, "tool", "flaky", listOf(false, false, false, false))
        val skill = s.skills.single()
        assertEquals(4, skill.attempts)
        assertFalse(Cerebellum.isAutomatic(skill))
        assertNull(Cerebellum.recall(s, "tool"))
    }

    @Test
    fun recallPicksMostReliableAutomaticSkill() {
        var s = learnN(CerebellumState.EMPTY, "tool", "a", listOf(true, true, true, false)) // att4, rel 0.7
        s = learnN(s, "tool", "b", listOf(true, true, true)) // att3, rel 1.0
        assertTrue(Cerebellum.isAutomatic(s.skills.first { it.action == "a" }))
        assertTrue(Cerebellum.isAutomatic(s.skills.first { it.action == "b" }))
        assertEquals("b", Cerebellum.recall(s, "tool")?.action)
    }

    @Test
    fun predictUnseenReturnsPriorWithNoReflexOrDeviation() {
        val p = Cerebellum.predict(CerebellumState.EMPTY, "tool", "novel")
        assertEquals(Cerebellum.PRIOR, p.successProbability, 1e-9)
        assertFalse(p.practiced)
        assertNull(p.reflexAction)
        assertFalse(p.deviation)
    }

    @Test
    fun predictFlagsDeviationFromReflex() {
        val s = learnN(CerebellumState.EMPTY, "tool", "b", listOf(true, true, true)) // reflex = b
        val p = Cerebellum.predict(s, "tool", "c")
        assertEquals("b", p.reflexAction)
        assertTrue(p.deviation)
        // Predicting the reflex itself is not a deviation.
        assertFalse(Cerebellum.predict(s, "tool", "b").deviation)
    }

    @Test
    fun predictReportsPracticedAndReliability() {
        val s = learnN(CerebellumState.EMPTY, "tool", "b", listOf(true, true, true))
        val p = Cerebellum.predict(s, "tool", "b")
        assertTrue(p.practiced)
        assertEquals(1.0, p.successProbability, 1e-9)
    }

    @Test
    fun evictionCapsSizeAndDropsWeakest() {
        var s = CerebellumState.EMPTY
        // c0 is the weakest (single attempt, oldest); the rest are practiced twice.
        s = Cerebellum.learn(s, "c0", "a", true, 0L)
        for (i in 1 until Cerebellum.MAX_SKILLS) {
            s = Cerebellum.learn(s, "c$i", "a", true, i.toLong())
            s = Cerebellum.learn(s, "c$i", "a", true, i.toLong())
        }
        assertEquals(Cerebellum.MAX_SKILLS, s.skills.size)
        // Adding one more triggers eviction of the weakest (c0).
        s = Cerebellum.learn(s, "cNew", "a", true, 9_999L)
        assertEquals(Cerebellum.MAX_SKILLS, s.skills.size)
        assertTrue(s.skills.none { it.cue == "c0" })
        assertTrue(s.skills.any { it.cue == "cNew" })
    }

    @Test
    fun topSkillsRanksByPractice() {
        var s = learnN(CerebellumState.EMPTY, "tool", "a", listOf(true, true, true, true)) // att4
        s = learnN(s, "tool", "b", listOf(true, true)) // att2
        assertEquals("a", Cerebellum.topSkills(s, 1).single().action)
    }

    @Test
    fun signatureNormalizesAndDropsStopwords() {
        assertEquals("weather today", Cerebellum.signature("What's the weather today, Jarvis?"))
        assertEquals("apple stock price", Cerebellum.signature("Show me Apple stock price"))
    }

    @Test
    fun signatureIsDeterministicAndBounded() {
        val a = Cerebellum.signature("Open the markets screen and check Tesla and Nvidia now")
        val b = Cerebellum.signature("Open the markets screen and check Tesla and Nvidia now")
        assertEquals(a, b)
        assertTrue(a.split(" ").size <= 3)
    }
}
