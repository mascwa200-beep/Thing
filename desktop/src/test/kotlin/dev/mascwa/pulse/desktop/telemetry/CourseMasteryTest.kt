// MIRROR OF core/telemetry/src/test/java/dev/mascwa/pulse/core/telemetry/CourseMasteryTest.kt — regenerate with tools/mirror_desktop_cores.py; MirrorDriftTest holds it
package dev.mascwa.pulse.desktop.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A nested enum class is not a value, so this has to be a typealias rather than a `val`. */
private typealias L = StudyProgress.Level

class CourseMasteryTest {

    private fun step(id: String, pos: Int) = Curriculum.Step(
        guideId = id, title = id.replaceFirstChar { it.uppercase() }, category = "First Aid",
        supergroup = "Health", position = pos, why = "because",
    )

    private fun syllabus(vararg ids: String) =
        Curriculum.Syllabus("first aid", ids.mapIndexed { i, id -> step(id, i + 1) })

    // ---- the map ------------------------------------------------------------------------------------

    @Test
    fun everyStepOfThePathBecomesASkillCarryingHowWellItIsKnown() {
        val c = CourseMastery.course(
            syllabus("cpr", "bleeding", "burns"),
            levels = mapOf("cpr" to L.MASTERED, "bleeding" to L.SHAKY),
            cardsPerGuide = mapOf("cpr" to 5, "bleeding" to 4),
            duePerGuide = mapOf("bleeding" to 2),
            taughtIds = setOf("cpr", "bleeding"),
        )
        assertEquals(3, c.total)
        assertEquals(1, c.mastered)
        assertEquals(2, c.started)
        assertEquals(L.UNSEEN, c.skills.first { it.guideId == "burns" }.level)
        assertEquals(2, c.skills.first { it.guideId == "bleeding" }.due)
    }

    /**
     * ⚠️ The percentage is points-weighted, not a count of finished skills. Counting only "mastered"
     * leaves the bar at zero for weeks, which is the fastest way to make somebody stop.
     */
    @Test
    fun realProgressInsideASkillMovesTheBar() {
        val untouched = CourseMastery.course(syllabus("a", "b", "c", "d"), emptyMap())
        assertEquals(0, untouched.percent)

        val begun = CourseMastery.course(
            syllabus("a", "b", "c", "d"),
            mapOf("a" to L.INTRODUCED, "b" to L.LEARNING),
        )
        assertTrue("being under way must not read as zero", begun.percent > 0)
        assertTrue(begun.percent < 100)

        val done = CourseMastery.course(
            syllabus("a", "b", "c", "d"),
            listOf("a", "b", "c", "d").associateWith { L.MASTERED },
        )
        assertEquals(100, done.percent)
    }

    @Test
    fun anEmptyCourseIsZeroRatherThanADivisionByNothing() {
        val c = CourseMastery.course(Curriculum.Syllabus("nothing", emptyList()), emptyMap())
        assertEquals(0, c.percent)
        assertTrue(c.isEmpty)
        assertNull(c.nextUp())
    }

    // ---- what to do next ----------------------------------------------------------------------------

    /**
     * A skill slipping away costs more than one not yet begun, so review outranks starting. That
     * ordering is the whole value of the recommendation — anything else is just the list again.
     */
    @Test
    fun whatToDoNextPrefersReviewThenShakyThenSomethingNew() {
        val withDue = CourseMastery.course(
            syllabus("known", "shaky", "fresh"),
            mapOf("known" to L.SOLID, "shaky" to L.SHAKY),
            cardsPerGuide = mapOf("known" to 3, "shaky" to 3),
            duePerGuide = mapOf("known" to 2),
        )
        assertEquals("known", withDue.nextUp()?.guideId)

        // Nothing due: the shaky one, not the untouched one.
        val noDue = CourseMastery.course(
            syllabus("known", "shaky", "fresh"),
            mapOf("known" to L.SOLID, "shaky" to L.SHAKY),
        )
        assertEquals("shaky", noDue.nextUp()?.guideId)

        // Nothing due and nothing shaky: begin at the start of the path.
        val fresh = CourseMastery.course(syllabus("a", "b"), mapOf("a" to L.SOLID))
        assertEquals("b", fresh.nextUp()?.guideId)
    }

    @Test
    fun aFullyMasteredCourseHasNothingLeftToRecommend() {
        val c = CourseMastery.course(syllabus("a", "b"), mapOf("a" to L.MASTERED, "b" to L.MASTERED))
        assertNull(c.nextUp())
        assertTrue(c.describe(), c.describe().contains("mastered"))
    }

    @Test
    fun eachSkillSaysWhatToDoAboutItInTheImperative() {
        fun cta(level: StudyProgress.Level, cards: Int, due: Int, taught: Boolean) =
            CourseMastery.Skill("g", "G", "c", 1, level, cards, due, taught).callToAction()

        assertEquals("Start", cta(L.UNSEEN, 0, 0, false))
        assertEquals("Practise", cta(L.LEARNING, 4, 2, true))
        assertEquals("Shore up", cta(L.SHAKY, 4, 0, true))
        assertEquals("Keep sharp", cta(L.MASTERED, 4, 0, true))
    }
}
