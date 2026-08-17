package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PracticeSetTest {

    private fun ids(prefix: String, n: Int) = (1..n).map { "$prefix$it" }

    // ---- one skill ----------------------------------------------------------------------------------

    @Test
    fun practiceIsShortBoundedAndHasAVisibleFinishLine() {
        val s = PracticeSet.practice("Knots & Cordage", ids("k", 20))!!
        assertEquals(PracticeSet.PRACTICE_SIZE, s.size)
        assertEquals(PracticeSet.Kind.PRACTICE, s.kind)
        assertTrue(s.passMark in 1..s.size)
        assertTrue(s.describe(), s.describe().contains("to pass"))
    }

    @Test
    fun aSetTooSmallToBeASetIsRefused() {
        assertNull(PracticeSet.practice("Thin", ids("t", 2)))
        assertNull(PracticeSet.practice("Empty", emptyList()))
        // Duplicates do not pad it out to a real set.
        assertNull(PracticeSet.practice("Dupes", listOf("a", "a", "a", "a", "a")))
    }

    /**
     * ⚠️ Demanding perfection on a short set makes one slip erase the whole attempt, which teaches
     * caution rather than the material.
     */
    @Test
    fun thePassMarkIsAClearMajorityAndNeverEverything() {
        for (n in 3..12) {
            val mark = PracticeSet.passMark(n)
            assertTrue("$n questions -> $mark", mark in 1..n)
            assertTrue("perfection demanded at n=$n", mark < n || n <= 3)
        }
        assertEquals(0, PracticeSet.passMark(0))
    }

    // ---- mixed sets ---------------------------------------------------------------------------------

    /**
     * The interleaving is the point, not decoration: ten questions from one guide in a row let context
     * carry you, which is exactly what a unit test is supposed to remove.
     */
    @Test
    fun aMixedSetInterleavesAcrossGuidesRatherThanBlockingByGuide() {
        val s = PracticeSet.mixed(
            PracticeSet.Kind.UNIT_TEST, "First Aid",
            mapOf("cpr" to ids("c", 5), "bleeding" to ids("b", 5), "burns" to ids("u", 5)),
        )!!
        assertEquals(PracticeSet.UNIT_SIZE, s.size)
        // The first three come from three different guides.
        val firstThree = s.questionIds.take(3).map { it.first() }.toSet()
        assertEquals(3, firstThree.size)
    }

    @Test
    fun aMixedSetDrainsWhatIsAvailableWithoutInventingQuestions() {
        val s = PracticeSet.mixed(
            PracticeSet.Kind.CHALLENGE, "Course",
            mapOf("a" to ids("a", 2), "b" to ids("b", 2)),
        )!!
        assertEquals(4, s.size)
        assertEquals(4, s.questionIds.distinct().size)
    }

    @Test
    fun aMixedSetWithNothingBehindItIsRefused() {
        assertNull(PracticeSet.mixed(PracticeSet.Kind.UNIT_TEST, "x", emptyMap()))
        assertNull(PracticeSet.mixed(PracticeSet.Kind.UNIT_TEST, "x", mapOf("a" to emptyList())))
        assertNull(PracticeSet.mixed(PracticeSet.Kind.UNIT_TEST, "x", mapOf("a" to ids("a", 2))))
    }

    // ---- the verdict --------------------------------------------------------------------------------

    /**
     * ⚠️ A miss is never phrased as a failure. The learner has just spent real effort, and the only
     * useful next instruction is what to do about it.
     */
    @Test
    fun theVerdictTeachesRatherThanGrades() {
        val missed = PracticeSet.Result(PracticeSet.Kind.PRACTICE, correct = 2, total = 5, passMark = 4)
        assertTrue(!missed.passed)
        assertTrue(missed.verdict(), missed.verdict().contains("2 more"))
        assertTrue("a miss must not be called a failure", !missed.verdict().lowercase().contains("fail"))

        val perfect = PracticeSet.Result(PracticeSet.Kind.PRACTICE, 5, 5, 4)
        assertTrue(perfect.passed)
        assertEquals(100, perfect.percent)

        val blank = PracticeSet.Result(PracticeSet.Kind.PRACTICE, 0, 5, 4)
        assertTrue(blank.verdict(), blank.verdict().contains("Read it through"))

        assertEquals(0, PracticeSet.Result(PracticeSet.Kind.PRACTICE, 0, 0, 0).percent)
    }

    // ---- the challenge order ------------------------------------------------------------------------

    /**
     * A challenge over material never taught would be a quiz on pages the learner has not seen — a test
     * of the app rather than of them.
     */
    @Test
    fun aChallengeDrawsOnlyFromSkillsWithSomethingToAskWeakestFirst() {
        val skills = listOf(
            CourseMastery.Skill("solid", "Solid", "c", 1, StudyProgress.Level.SOLID, cards = 4),
            CourseMastery.Skill("never", "Never", "c", 2, StudyProgress.Level.UNSEEN, cards = 0),
            CourseMastery.Skill("shaky", "Shaky", "c", 3, StudyProgress.Level.SHAKY, cards = 4),
        )
        val order = PracticeSet.challengeOrder(skills).map { it.guideId }
        assertEquals(listOf("shaky", "solid"), order)
    }

    @Test
    fun everySessionCarriesItsOwnFinishLine() {
        val p = PracticeSet.practice("G", ids("g", 9))
        assertNotNull(p)
        assertEquals(PracticeSet.passMark(p!!.size), p.passMark)
    }
}
