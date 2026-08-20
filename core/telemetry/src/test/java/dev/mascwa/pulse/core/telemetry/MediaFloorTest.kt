package dev.mascwa.pulse.core.telemetry

import dev.mascwa.pulse.core.telemetry.MediaFloor.Action
import dev.mascwa.pulse.core.telemetry.MediaFloor.Owner
import dev.mascwa.pulse.core.telemetry.MediaFloor.State
import dev.mascwa.pulse.core.telemetry.MediaFloor.claim
import dev.mascwa.pulse.core.telemetry.MediaFloor.released
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Written against the real call graph: the events below are the ones the radio controller and the
 * live-video controller actually produce, in the orders they actually produce them.
 */
class MediaFloorTest {

    private val silent = State()

    // ---- the two properties this file exists for -------------------------------------------------

    /**
     * ⚠️ **The one that matters.** The exact sequence the app produces, in order.
     *
     * Video claims the speaker, so the radio is told to stop. Performing that runs the radio's
     * teardown, and the radio's teardown reports its own release — arriving *after* the floor has
     * already moved. If that late report were honoured, the floor would read NONE while video plays,
     * the next claim would find nobody to stop, and both would sound at once.
     */
    @Test
    fun theLateReportFromADisplacedOwnerCannotBlankTheFloor() {
        var state = State(Owner.RADIO)

        val claim = state.claim(Owner.VIDEO)
        assertEquals("the radio must be told to stop", Action.STOP_RADIO, claim.action)
        state = claim.state
        assertEquals(Owner.VIDEO, state.owner)

        // Performing STOP_RADIO runs the radio's teardown, which reports its release. Too late.
        val late = state.released(Owner.RADIO)
        assertEquals("a report is never a request", Action.NOTHING, late.action)
        state = late.state
        assertEquals("the floor still belongs to video", Owner.VIDEO, state.owner)

        // And so the next claim still knows there is something to stop.
        assertEquals(Action.STOP_VIDEO, state.claim(Owner.RADIO).action)
    }

    /**
     * Tapping a channel that is already playing must not tear it down and rebuild it. This is the
     * assertion that fails if `to()` ever stops comparing owners.
     */
    @Test
    fun claimingTwiceStopsThePreviousHolderOnlyOnce() {
        val first = State(Owner.RADIO).claim(Owner.VIDEO)
        assertEquals(Action.STOP_RADIO, first.action)

        val second = first.state.claim(Owner.VIDEO)
        assertEquals(Action.NOTHING, second.action)
        assertEquals(Owner.VIDEO, second.state.owner)
    }

    // ---- ordinary behaviour ----------------------------------------------------------------------

    @Test
    fun claimingASilentSpeakerStopsNobody() {
        val step = silent.claim(Owner.VIDEO)
        assertEquals(Action.NOTHING, step.action)
        assertEquals(Owner.VIDEO, step.state.owner)
    }

    @Test
    fun theHolderReleasingFreesTheFloorAndAsksForNothing() {
        val step = State(Owner.VIDEO).released(Owner.VIDEO)
        assertEquals(Action.NOTHING, step.action)
        assertEquals(Owner.NONE, step.state.owner)
        // Now nothing is playing, so the next claim has nobody to displace.
        assertEquals(Action.NOTHING, step.state.claim(Owner.RADIO).action)
    }

    @Test
    fun releasingWhenNobodyHoldsTheFloorChangesNothing() {
        val step = silent.released(Owner.RADIO)
        assertEquals(Action.NOTHING, step.action)
        assertEquals(Owner.NONE, step.state.owner)
    }

    @Test
    fun theFloorPassesBothWays() {
        var state = silent
        state = state.claim(Owner.RADIO).state
        val toVideo = state.claim(Owner.VIDEO)
        assertEquals(Action.STOP_RADIO, toVideo.action)
        val backToRadio = toVideo.state.claim(Owner.RADIO)
        assertEquals(Action.STOP_VIDEO, backToRadio.action)
        assertEquals(Owner.RADIO, backToRadio.state.owner)
    }

    // ---- what the user is told --------------------------------------------------------------------

    @Test
    fun theUserIsToldOnlyWhenSomethingWasActuallyDisplaced() {
        // Audio stopping on its own is alarming unless it is explained.
        assertNotNull(MediaFloor.displacedNote(Owner.RADIO, Owner.VIDEO))
        assertNotNull(MediaFloor.displacedNote(Owner.VIDEO, Owner.RADIO))
        // Nothing was playing, so nothing stopped — saying so would be noise on every first play.
        assertNull(MediaFloor.displacedNote(Owner.NONE, Owner.VIDEO))
        assertNull(MediaFloor.displacedNote(Owner.RADIO, Owner.NONE))
        assertNull(MediaFloor.displacedNote(Owner.RADIO, Owner.RADIO))
    }

    // ---- the fourth claimant ----------------------------------------------------------------------

    /**
     * On-demand playback is a full citizen of the floor: it displaces, is displaced, and its late
     * release report is ignored exactly as the radio's is. Written against the sequence the app will
     * actually produce — the player starts while the radio is on, then the radio takes it back.
     */
    @Test
    fun onDemandDisplacesAndIsDisplacedLikeAnyOther() {
        var state = State(Owner.RADIO)

        val play = state.claim(Owner.ONDEMAND)
        assertEquals(Action.STOP_RADIO, play.action)
        state = play.state
        assertEquals(Owner.ONDEMAND, state.owner)

        // The radio's teardown reports its release late — must not blank the floor.
        state = state.released(Owner.RADIO).state
        assertEquals(Owner.ONDEMAND, state.owner)

        val back = state.claim(Owner.RADIO)
        assertEquals(Action.STOP_ONDEMAND, back.action)
        assertEquals(Owner.RADIO, back.state.owner)

        // And every displacement involving it is explained.
        assertNotNull(MediaFloor.displacedNote(Owner.RADIO, Owner.ONDEMAND))
        assertNotNull(MediaFloor.displacedNote(Owner.ONDEMAND, Owner.RADIO))
        assertNotNull(MediaFloor.displacedNote(Owner.VIDEO, Owner.ONDEMAND))
        assertNull(MediaFloor.displacedNote(Owner.ONDEMAND, Owner.ONDEMAND))
    }
}
