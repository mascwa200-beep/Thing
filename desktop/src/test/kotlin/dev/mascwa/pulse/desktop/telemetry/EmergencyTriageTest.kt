// MIRROR OF core/telemetry/src/test/java/dev/mascwa/pulse/core/telemetry/EmergencyTriageTest.kt — regenerate with tools/mirror_desktop_cores.py; MirrorDriftTest holds it
package dev.mascwa.pulse.desktop.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The routing that has to be right when everything else can afford to be approximate.
 *
 * The phrasings below are the ones the general ranker was measured failing on against the real
 * library: "stroke symptoms" returned an article about two-stroke engines, "not breathing" one about
 * uphill walking.
 */
class EmergencyTriageTest {

    private fun id(query: String): String? = EmergencyTriage.match(query)?.id

    // ---- the failures this exists to fix ---------------------------------------------------------

    @Test
    fun theQueriesTheRankerGotDangerouslyWrongNowRouteCorrectly() {
        assertEquals("cardiac_arrest", id("he's not breathing"))
        assertEquals("stroke", id("stroke symptoms"))
        assertEquals("anaphylaxis", id("severe allergic reaction"))
        assertEquals("seizure", id("she's having a seizure"))
        assertEquals("fracture", id("broken arm"))
        assertEquals("burn", id("burn from hot oil"))
        assertEquals("heart_attack", id("I think he's having a heart attack"))
    }

    @Test
    fun theOnesThatAlreadyWorkedStillDo() {
        assertEquals("choking", id("someone is choking"))
        assertEquals("severe_bleeding", id("he is bleeding badly"))
        assertEquals("poisoning", id("she took too many pills"))
        assertEquals("drowning", id("a child is drowning"))
    }

    // ---- what must NOT fire ----------------------------------------------------------------------

    /**
     * Triage runs in front of every library search, so a false positive costs an ordinary question
     * its answer. Each of these contains an emergency word in a harmless sense.
     */
    @Test
    fun ordinaryQuestionsAreNotTreatedAsEmergencies() {
        assertNull(id("how does a two stroke engine work"))
        assertNull(id("how many calories do I burn walking"))
        assertNull(id("fitting a shelf to a brick wall"))
        assertNull(id("how do I make bread"))
        assertNull(id("what is compound interest"))
        assertNull(id("breathing technique for uphill walking"))
        assertNull(id(""))
        assertNull(id("   "))
    }

    /** Phrase matching is word-safe: a cue must be whole words, not a substring of a longer one. */
    @Test
    fun cuesMatchWholeWordsRatherThanFragments() {
        assertNull(id("Burnley football club"))       // contains "burn"
        assertNull(id("a hypothetical question"))     // contains "hypo"
        assertNull(id("the cardigan was red"))        // contains "cardi"
        assertEquals("burn", id("I burnt my hand"))
    }

    // ---- ordering ---------------------------------------------------------------------------------

    /** The table is ordered by how fast the situation kills, and the airway comes before the blood. */
    @Test
    fun theFastestKillerWinsWhenAQueryNamesTwo() {
        assertEquals("cardiac_arrest", id("he's not breathing and bleeding badly"))
        assertEquals("choking", id("choking and turning blue, lots of blood"))
    }

    // ---- honesty about gaps ------------------------------------------------------------------------

    /**
     * A recognised emergency the library has no page for must still give the first action, and must
     * say the page does not exist. Offering the nearest lexical match is how "seizure" ends up
     * answered by an article on osmosis.
     */
    @Test
    fun anUncoveredEmergencyGivesTheActionAndAdmitsTheGap() {
        // Low blood sugar is the one recognised emergency with no protocol page written for it yet.
        val hypo = EmergencyTriage.match("low blood sugar")
        assertNotNull(hypo)
        assertFalse(hypo!!.covered)
        assertTrue(hypo.firstAction.isNotBlank())

        val brief = EmergencyTriage.brief(hypo)
        assertTrue("must state the gap: $brief", brief.contains("no page on this yet"))
        assertTrue("the action is to give sugar", brief.contains("give sugar"))
    }

    /** Seizure had no page in the library and now has one; it must route rather than apologise. */
    @Test
    fun theNewlyWrittenProtocolsAreRoutedNotApologisedFor() {
        for (q in listOf("epileptic fit", "he hit his head", "electrocuted", "heat stroke")) {
            val e = EmergencyTriage.match(q)
            assertNotNull("$q no longer matches", e)
            assertTrue("$q should now route to a protocol", e!!.covered)
            assertFalse(EmergencyTriage.brief(e).contains("no page on this yet"))
        }
    }

    @Test
    fun everyEntryIsUsableWhicheverKindItIs() {
        assertTrue(EmergencyTriage.EMERGENCIES.isNotEmpty())
        for (e in EmergencyTriage.EMERGENCIES) {
            assertTrue("${e.id} has no cues", e.cues.isNotEmpty())
            assertTrue("${e.id} has no first action", e.firstAction.isNotBlank())
            assertTrue("${e.id} has no label", e.label.isNotBlank())
            // Covered means BOTH halves; a guide id without a section cannot be opened.
            assertEquals("${e.id} is half-routed", e.guideId != null, e.section != null)
        }
        // Ids are how a caller and a test refer to one; duplicates would make both ambiguous.
        assertEquals(
            EmergencyTriage.EMERGENCIES.size,
            EmergencyTriage.EMERGENCIES.map { it.id }.distinct().size,
        )
        // Cues too: the same phrase in two entries makes which one fires depend on table order alone.
        val cues = EmergencyTriage.EMERGENCIES.flatMap { it.cues }
        assertEquals("duplicate cue across entries: ${cues.groupBy { it }.filter { it.value.size > 1 }.keys}",
            cues.size, cues.distinct().size)
    }

    @Test
    fun theBriefLeadsWithTheActionNotTheExplanation() {
        val e = EmergencyTriage.match("not breathing")!!
        val brief = EmergencyTriage.brief(e)
        // Label, then the action — and the action reaches help before it describes anything.
        assertTrue(brief.startsWith("NOT BREATHING"))
        assertTrue(brief.contains("Call emergency services now"))
        assertTrue(brief.indexOf("Call emergency services") < brief.indexOf("compressions"))
    }

    @Test
    fun normalisationIsWordSafeAndCaseBlind() {
        assertEquals(" not breathing ", EmergencyTriage.normalise("  NOT, breathing!  "))
        assertEquals("cardiac_arrest", id("NOT BREATHING"))
        assertEquals("cardiac_arrest", id("Not Breathing!!"))
    }
}
