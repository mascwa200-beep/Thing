package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A path through the library has to be two things: the right material, and an order that does not
 * lurch between subjects. The cohesion tests below are the second half, and they are written as
 * properties — no category and no supergroup may appear in two separate runs — rather than as a
 * hardcoded list, so they keep holding when the ranker's weights are tuned.
 */
class CurriculumTest {

    private fun e(id: String, title: String, category: String, summary: String, vararg headings: String) =
        GuideSearch.Entry(id, title, category, summary, headings.toList())

    /** Number of contiguous runs in a sequence. Equal to the distinct count exactly when grouped. */
    private fun runs(values: List<String>): Int {
        var n = 0
        var last: String? = null
        for (v in values) {
            if (v != last) {
                n++
                last = v
            }
        }
        return n
    }

    private fun assertGrouped(values: List<String>, what: String) {
        assertEquals("$what is split into separate runs: $values", values.distinct().size, runs(values))
    }

    // A library small enough to reason about, wide enough to have something to group.
    private val rope = listOf(
        e("knots", "Knots and Cordage", "Skills", "Tying rope for load and rescue"),
        e("climb", "Climbing Technique", "Movement", "Uses rope work", "Rope handling"),
        e("sail", "Sailing Basics", "Movement", "Knots for sailing"),
        e("soap", "Making Soap", "Making", "Lye and fat, nothing else"),
    )

    private val supergroups = mapOf(
        "Skills" to "Fieldcraft",
        "Movement" to "Fieldcraft",
        "Essentials" to "Fieldcraft",
        "Hazards" to "Fieldcraft",
        "Chemistry" to "Science",
        "Making" to "Technical",
        "Home & Repair" to "Technical",
    )

    // ---- what a path is ----------------------------------------------------------------------------

    @Test
    fun aPathLeadsWithTheClosestMaterialAndSaysSo() {
        val s = Curriculum.compose("knots and rope", rope, supergroups)
        assertFalse(s.isEmpty)
        assertEquals("knots", s.steps.first().guideId)
        assertEquals("closest match in the library", s.steps.first().why)
        // A guide matching nothing in the goal is not on the path at all.
        assertFalse(s.steps.any { it.guideId == "soap" })
    }

    @Test
    fun positionsAreOneBasedAndContiguous() {
        val s = Curriculum.compose("knots and rope", rope, supergroups)
        assertEquals((1..s.steps.size).toList(), s.steps.map { it.position })
    }

    @Test
    fun everyStepSaysWhyItIsThere() {
        val s = Curriculum.compose("knots and rope", rope, supergroups)
        assertTrue(s.steps.all { it.why.isNotBlank() })
        // The first guide of a subject opens it; the rest continue it.
        val movement = s.steps.filter { it.category == "Movement" }
        assertEquals("opens the Movement material", movement.first().why)
        assertTrue(movement.drop(1).all { it.why == "continues Movement" })
    }

    // ---- cohesion, the reason this is not just a ranked list ------------------------------------------

    /**
     * Pure relevance order interleaves subjects. This library is built so that it would: the second
     * best "fire" guide is chemistry and the third is back in fieldcraft, so an unsorted path would
     * read fieldcraft, science, fieldcraft.
     */
    @Test
    fun relatedSubjectsAreKeptTogether() {
        val fire = listOf(
            e("firelighting", "Fire Lighting", "Essentials", "Tinder, kindling and fuel"),
            e("combustion", "Combustion Chemistry", "Chemistry", "Oxidation and heat", "Fire triangle"),
            e("wildfire", "Wildfire Safety", "Hazards", "Fire behaviour in dry country"),
            e("alarms", "Smoke Alarms", "Home & Repair", "What to do in a fire at home"),
        )
        val s = Curriculum.compose("fire", fire, supergroups)
        assertEquals(4, s.steps.size)
        assertGrouped(s.steps.map { it.category }, "category")
        assertGrouped(s.steps.map { it.supergroup }, "supergroup")
        // The strongest guide still leads, so cohesion has not overruled relevance.
        assertEquals("firelighting", s.steps.first().guideId)
        // And the regrouping is real: the other fieldcraft guide has been pulled up past chemistry.
        val order = s.steps.map { it.guideId }
        assertTrue("expected wildfire before combustion, got $order", order.indexOf("wildfire") < order.indexOf("combustion"))
    }

    @Test
    fun anUnmappedCategoryStillAppearsRatherThanBeingDropped() {
        val s = Curriculum.compose("knots and rope", rope, supergroups = emptyMap())
        assertFalse(s.isEmpty)
        assertTrue(s.steps.all { it.supergroup == Curriculum.UNGROUPED })
    }

    // ---- nothing to teach ------------------------------------------------------------------------------

    @Test
    fun anEmptyGoalOrAnEmptyLibraryYieldsNothingRatherThanAGuess() {
        assertTrue(Curriculum.compose("", rope, supergroups).isEmpty)
        assertTrue(Curriculum.compose("   ", rope, supergroups).isEmpty)
        assertTrue(Curriculum.compose("knots", emptyList(), supergroups).isEmpty)
    }

    @Test
    fun aGoalTheLibraryCannotAnswerYieldsAnEmptyPath() {
        assertTrue(Curriculum.compose("quantum chromodynamics", rope, supergroups).isEmpty)
    }

    /** An empty path is finished, not zero per cent — there is nothing left to do. */
    @Test
    fun anEmptyPathIsComplete() {
        val s = Curriculum.compose("", rope, supergroups)
        assertEquals(1.0, s.progress(emptySet()), 1e-9)
        assertEquals(0, s.days)
        assertEquals("nothing to study", s.describeProgress(emptySet()))
    }

    // ---- pace and progress -------------------------------------------------------------------------------

    @Test
    fun paceIsClampedAndDaysFollowFromIt() {
        val many = (1..12).map { e("g$it", "Rope Guide $it", "Skills", "About rope") }
        assertEquals(12, Curriculum.compose("rope", many, supergroups, perDay = 1).days)
        // 12 guides, five a sitting: 3 sittings, the last one short.
        assertEquals(3, Curriculum.compose("rope", many, supergroups, perDay = 5).days)
        // Out-of-range paces are clamped, never honoured.
        assertEquals(Curriculum.MAX_PER_DAY, Curriculum.compose("rope", many, supergroups, perDay = 99).perDay)
        assertEquals(1, Curriculum.compose("rope", many, supergroups, perDay = 0).perDay)
    }

    @Test
    fun dayForPutsEachStepInTheRightSitting() {
        val many = (1..12).map { e("g$it", "Rope Guide $it", "Skills", "About rope") }
        val s = Curriculum.compose("rope", many, supergroups, perDay = 5)
        assertEquals(1, s.dayFor(s.steps[0]))
        assertEquals(1, s.dayFor(s.steps[4]))
        assertEquals(2, s.dayFor(s.steps[5]))
        assertEquals(3, s.dayFor(s.steps[10]))
    }

    @Test
    fun progressCountsWhatIsFinishedAndNextSkipsIt() {
        val s = Curriculum.compose("knots and rope", rope, supergroups)
        val first = s.steps.first().guideId
        assertEquals(0, s.done(emptySet()))
        assertEquals(1, s.done(setOf(first)))
        assertEquals(s.steps.size - 1, s.remaining(setOf(first)))
        assertEquals(1.0 / s.steps.size, s.progress(setOf(first)), 1e-9)
        assertFalse(s.next(setOf(first), count = 2).any { it.guideId == first })
        // Ids that are not on this path do not count towards it.
        assertEquals(0, s.done(setOf("something else entirely")))
    }

    @Test
    fun progressReadsAsSomethingAPersonWouldSay() {
        val s = Curriculum.compose("knots and rope", rope, supergroups)
        assertTrue(s.describeProgress(emptySet()).startsWith("not started"))
        assertTrue(s.describeProgress(s.steps.map { it.guideId }.toSet()).startsWith("complete"))
        assertEquals("1 of ${s.steps.size} done", s.describeProgress(setOf(s.steps.first().guideId)))
    }

    // ---- determinism --------------------------------------------------------------------------------------

    /**
     * The store keeps a goal and a set of finished ids, not a frozen list, so the path must recompose
     * identically or yesterday's progress would point at the wrong guides.
     */
    @Test
    fun theSameGoalAlwaysComposesTheSamePath() {
        val a = Curriculum.compose("knots and rope", rope, supergroups)
        val b = Curriculum.compose("knots and rope", rope.reversed(), supergroups)
        assertEquals(a.steps.map { it.guideId }, b.steps.map { it.guideId })
        assertEquals(a, Curriculum.compose("knots and rope", rope, supergroups))
    }

    @Test
    fun lengthIsClampedAtBothEnds() {
        val many = (1..60).map { e("g$it", "Rope Guide $it", "Skills", "About rope") }
        assertEquals(1, Curriculum.compose("rope", many, supergroups, length = 0).steps.size)
        assertEquals(Curriculum.MAX_LENGTH, Curriculum.compose("rope", many, supergroups, length = 999).steps.size)
    }

    // ---- suggestions ----------------------------------------------------------------------------------------

    /** A suggested goal the bundled library cannot actually answer must never be offered. */
    @Test
    fun onlySuggestionsTheLibraryCanSupportAreOffered() {
        assertTrue(Curriculum.suggestions(emptyList()).isEmpty())
        val firstAid = listOf(
            e("fa", "First Aid Basics", "Medical", "Aid for injuries"),
            e("sea", "Emergencies at Sea", "Rescue", "Call for aid"),
            e("bleed", "Bleeding Control", "Medical", "Stop bleeding and give first aid"),
        )
        assertEquals(listOf("first aid and emergencies"), Curriculum.suggestions(firstAid))
    }

    // ---- the goal is a subject, not a question ----------------------------------------------------------------

    /**
     * "learn"/"understand"/"basics" are what someone says around a subject, not the subject. Matching
     * guides on them is how a path about electricity fills up with guides about learning.
     */
    @Test
    fun intentAndFramingWordsAreNotSearchedOn() {
        // Ordinary stopwords ("the", "of", "to") are left in place — dropping those is the ranker's
        // own job, and this must remove only the words the ranker deliberately keeps.
        assertEquals("the of electricity", Curriculum.searchPhrase("learn the basics of electricity"))
        assertEquals("to sail", Curriculum.searchPhrase("Learn To Sail"))
        assertEquals("the weather", Curriculum.searchPhrase("understanding the weather"))
        assertFalse(GuideSearch.tokens(Curriculum.searchPhrase("learn the basics of electricity")).contains("basics"))
    }

    /** A goal made entirely of framing words still searches for something rather than nothing. */
    @Test
    fun aGoalOfNothingButFramingWordsFallsBackToItself() {
        assertEquals("basics", Curriculum.searchPhrase("basics"))
        assertEquals("Learning", Curriculum.searchPhrase("Learning"))
    }

    /** Subject words are never touched — this reduction must not quietly edit what was asked for. */
    @Test
    fun theSubjectItselfSurvivesUntouched() {
        assertEquals("knots and rope", Curriculum.searchPhrase("knots and rope"))
        assertEquals("first aid and emergencies", Curriculum.searchPhrase("first aid and emergencies"))
    }

    /** What the reader typed is what the screen shows, whatever was searched on. */
    @Test
    fun thePathKeepsTheGoalTheReaderActuallyTyped() {
        val s = Curriculum.compose("learn the basics of knots and rope", rope, supergroups)
        assertEquals("learn the basics of knots and rope", s.goal)
        assertEquals("knots", s.steps.first().guideId)
    }

    @Test
    fun theOrderingCaveatIsCarriedWithThePathSoAScreenCannotOmitIt() {
        val s = Curriculum.compose("knots and rope", rope, supergroups)
        assertEquals(Curriculum.ORDERING_NOTE, s.note)
        assertTrue(s.note.contains("not a taught course"))
    }
}
