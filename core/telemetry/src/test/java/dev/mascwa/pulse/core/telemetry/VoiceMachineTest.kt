package dev.mascwa.pulse.core.telemetry

import dev.mascwa.pulse.core.telemetry.VoiceMachine.Action
import dev.mascwa.pulse.core.telemetry.VoiceMachine.Owner
import dev.mascwa.pulse.core.telemetry.VoiceMachine.State
import dev.mascwa.pulse.core.telemetry.VoiceMachine.console
import dev.mascwa.pulse.core.telemetry.VoiceMachine.interrogator
import dev.mascwa.pulse.core.telemetry.VoiceMachine.micFailed
import dev.mascwa.pulse.core.telemetry.VoiceMachine.settle
import dev.mascwa.pulse.core.telemetry.VoiceMachine.speaking
import dev.mascwa.pulse.core.telemetry.VoiceMachine.wakeHeard
import dev.mascwa.pulse.core.telemetry.VoiceMachine.wants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The arbitration the service could never prove on-device.
 *
 * Written against the real call graph: the events below are the ones `ActiveMatrixService` actually
 * produces, in the orders it actually produces them.
 */
class VoiceMachineTest {

    private val off = State()
    private fun on(): State = off.wants(true).state

    // ---- the defect this file exists for ---------------------------------------------------------

    /**
     * The property. Two paths racing to re-arm — a command timing out as the console closes — must
     * yield ONE wake session. This is the assertion that fails if `to()` ever stops comparing owners.
     */
    @Test
    fun settlingTwiceStartsTheWakeRecogniserOnlyOnce() {
        // Deliberately from `off`, not `on()` — `wants(true)` already settles, so `on()` is listening.
        val first = off.wants(true)
        assertEquals(Action.START_WAKE, first.action)

        val second = first.state.settle()
        assertEquals(Action.NOTHING, second.action)
        assertEquals(Owner.WAKE, second.state.owner)

        // And a third, and a fourth. Idempotence is not a one-shot latch.
        assertEquals(Action.NOTHING, second.state.settle().action)
        assertEquals(Action.NOTHING, second.state.settle().state.settle().action)
    }

    /** Starting the service when it is already listening is the same non-event. */
    @Test
    fun wantingVoiceWhenAlreadyListeningChangesNothing() {
        val listening = on()
        assertEquals(Action.START_WAKE, off.wants(true).action)
        assertEquals(Action.NOTHING, listening.wants(true).action)
        assertEquals(Owner.WAKE, listening.wants(true).state.owner)
    }

    // ---- the ordinary round trip -------------------------------------------------------------------

    @Test
    fun wakeThenCommandThenReplyReturnsToTheWakeWord() {
        val listening = on()
        assertEquals(Owner.WAKE, listening.owner)

        val capturing = listening.wakeHeard()
        assertEquals(Action.START_COMMAND, capturing.action)
        assertEquals(Owner.COMMAND, capturing.state.owner)

        // The mic is released while the reply is spoken, or it hears the reply.
        val talking = capturing.state.speaking()
        assertEquals(Action.RELEASE_MIC, talking.action)
        assertEquals(Owner.SPEAKING, talking.state.owner)

        val done = talking.state.settle()
        assertEquals(Action.START_WAKE, done.action)
        assertEquals(Owner.WAKE, done.state.owner)
    }

    /** Follow-up / conversation mode: the floor stays open, so the next capture needs no wake word. */
    @Test
    fun holdingTheFloorCapturesAgainWithoutTheWakeWord() {
        val talking = on().wakeHeard().state.speaking().state
        val held = talking.settle(holdFloor = true)
        assertEquals(Action.START_COMMAND, held.action)
        assertEquals(Owner.COMMAND, held.state.owner)
    }

    /** A blank capture, a timeout and an error are the same event: nothing is in flight, re-arm. */
    @Test
    fun anEmptyOrFailedCaptureReturnsToTheWakeWord() {
        val capturing = on().wakeHeard().state
        val back = capturing.settle()
        assertEquals(Action.START_WAKE, back.action)
        assertEquals(Owner.WAKE, back.state.owner)
    }

    // ---- the console ---------------------------------------------------------------------------------

    @Test
    fun theConsoleTakesTheMicAndGivesItBack() {
        val listening = on()
        val taken = listening.console(true)
        assertEquals(Action.RELEASE_MIC, taken.action)
        assertEquals(Owner.CONSOLE, taken.state.owner)

        // Repeated emissions of the same value from the console's StateFlow must not re-release.
        assertEquals(Action.NOTHING, taken.state.console(true).action)

        val given = taken.state.console(false)
        assertEquals(Action.START_WAKE, given.action)
        assertEquals(Owner.WAKE, given.state.owner)
    }

    /**
     * The console opening mid-capture must take the mic. The service releases only the offline
     * recogniser today and leaves a system-recogniser capture running alongside it.
     */
    @Test
    fun theConsoleInterruptsACaptureInFlight() {
        val capturing = on().wakeHeard().state
        assertEquals(Owner.COMMAND, capturing.owner)

        val taken = capturing.console(true)
        assertEquals(Action.RELEASE_MIC, taken.action)
        assertEquals(Owner.CONSOLE, taken.state.owner)
    }

    /** While the console holds the mic, re-arming must not fight it for it. */
    @Test
    fun settlingWhileTheConsoleHoldsTheMicDoesNotStartTheWakeWord() {
        val inConsole = on().console(true).state
        assertEquals(Action.NOTHING, inConsole.settle().action)
        assertEquals(Action.NOTHING, inConsole.settle(holdFloor = true).action)
        assertEquals(Owner.CONSOLE, inConsole.settle().state.owner)
    }

    /** A late wake partial after the console took the mic must not open a capture underneath it. */
    @Test
    fun aStaleWakePhraseIsIgnoredUnlessTheWakeRecogniserHeardIt() {
        for (s in listOf(
            on().console(true).state,          // console holds it
            on().wakeHeard().state,            // already capturing
            on().wakeHeard().state.speaking().state, // speaking
            off,                                // voice is off entirely
        )) {
            val step = s.wakeHeard()
            assertEquals("wake accepted from ${s.owner}", Action.NOTHING, step.action)
            assertEquals("state moved from ${s.owner}", s, step.state)
        }
    }

    // ---- teardown and failure -------------------------------------------------------------------------

    @Test
    fun stoppingReleasesTheMicAndKeepsItReleased() {
        val listening = on()
        val stopped = listening.wants(false)
        assertEquals(Action.RELEASE_MIC, stopped.action)
        assertEquals(Owner.NONE, stopped.state.owner)

        // Nothing re-arms after a stop, however many idle paths fire on the way down.
        assertEquals(Action.NOTHING, stopped.state.settle().action)
        assertEquals(Action.NOTHING, stopped.state.settle(holdFloor = true).action)
        assertEquals(Action.NOTHING, stopped.state.console(false).action)
    }

    /**
     * A recogniser error must not itself re-arm — the caller backs off first, and a `START_WAKE`
     * here is the tight retry storm the backoff exists to prevent. But it must drop the owner, or
     * the settle after the backoff decides it is already listening and voice dies silently.
     */
    @Test
    fun aFailedRecogniserDoesNotRetryImmediatelyButDoesLetTheBackoffRetry() {
        val listening = on()
        val failed = listening.micFailed()
        assertEquals(Action.NOTHING, failed.action)
        assertEquals(Owner.NONE, failed.state.owner)

        val retried = failed.state.settle()
        assertEquals(Action.START_WAKE, retried.action)
    }

    /**
     * A recogniser dying while the console holds the mic must not steal it back on the retry — and
     * must not issue a release either.
     *
     * The console and the wake loop share one recogniser instance, so "release the mic" while the
     * console owns it would stop the console's recognition. Nothing of ours is open here, so there
     * is nothing to stop.
     */
    @Test
    fun aFailureUnderTheConsoleDefersToItAndDoesNotStopItsRecogniser() {
        val failed = on().console(true).state.micFailed().state
        assertEquals(Action.NOTHING, failed.settle().action)
        assertEquals(Owner.CONSOLE, failed.settle().state.owner)
    }

    /** The same rule, stated directly: a release is only ever issued against a mic we hold. */
    @Test
    fun releasingIsOnlyEverIssuedAgainstAMicWeActuallyHold() {
        // Held: the wake recogniser is ours, so stopping is real work.
        assertEquals(Action.RELEASE_MIC, on().console(true).action)
        assertEquals(Action.RELEASE_MIC, on().wakeHeard().state.wants(false).action)
        // Not held: nothing of ours is open, so there is nothing to stop.
        assertEquals(Action.NOTHING, off.console(true).action)
        assertEquals(Action.NOTHING, off.speaking().action)
        assertEquals(Action.NOTHING, on().console(true).state.micFailed().state.wants(false).action)
    }

    // ---- conversation budget ----------------------------------------------------------------------------

    @Test
    fun theModelsExplicitCloseBeatsEveryReasonToStayOpen() {
        assertFalse(
            VoiceMachine.holdFloor(
                followUp = true, conversation = true, modelClosed = true, modelOpened = true,
                turns = 0, maxTurns = 8, elapsedMs = 0, maxMs = 120_000,
            ),
        )
    }

    @Test
    fun theBudgetBeatsTheModelsRequestToStayOpen() {
        // Turn budget spent.
        assertFalse(
            VoiceMachine.holdFloor(
                followUp = true, conversation = true, modelClosed = false, modelOpened = true,
                turns = 8, maxTurns = 8, elapsedMs = 0, maxMs = 120_000,
            ),
        )
        // Wall-clock budget spent.
        assertFalse(
            VoiceMachine.holdFloor(
                followUp = true, conversation = true, modelClosed = false, modelOpened = true,
                turns = 0, maxTurns = 8, elapsedMs = 120_001, maxMs = 120_000,
            ),
        )
    }

    @Test
    fun followUpHoldsTheFloorAndConversationNeedsTheModelToAskForIt() {
        // Follow-up mode: unconditional within budget, which is the whole point of the setting.
        assertTrue(
            VoiceMachine.holdFloor(
                followUp = true, conversation = false, modelClosed = false, modelOpened = false,
                turns = 1, maxTurns = 8, elapsedMs = 1_000, maxMs = 120_000,
            ),
        )
        // Conversation mode without a request to stay open: the floor closes.
        assertFalse(
            VoiceMachine.holdFloor(
                followUp = false, conversation = true, modelClosed = false, modelOpened = false,
                turns = 1, maxTurns = 8, elapsedMs = 1_000, maxMs = 120_000,
            ),
        )
        // Conversation mode with one: it stays.
        assertTrue(
            VoiceMachine.holdFloor(
                followUp = false, conversation = true, modelClosed = false, modelOpened = true,
                turns = 1, maxTurns = 8, elapsedMs = 1_000, maxMs = 120_000,
            ),
        )
        // Neither mode on: a single answer, then back to the wake word.
        assertFalse(
            VoiceMachine.holdFloor(
                followUp = false, conversation = false, modelClosed = false, modelOpened = true,
                turns = 0, maxTurns = 8, elapsedMs = 0, maxMs = 120_000,
            ),
        )
    }

    @Test
    fun overBudgetIsTheBoundaryTheBudgetIsSpentOn() {
        assertFalse(VoiceMachine.overBudget(7, 8, 119_999, 120_000))
        assertTrue(VoiceMachine.overBudget(8, 8, 0, 120_000))          // >= turns
        assertFalse(VoiceMachine.overBudget(0, 8, 120_000, 120_000))   // > ms, not >=
        assertTrue(VoiceMachine.overBudget(0, 8, 120_001, 120_000))
    }

    // ---- the interrogator, a third continuous claimant ---------------------------------------

    /**
     * ⚠️ THE TRADEOFF, ASSERTED SO IT CANNOT DRIFT SILENTLY. Switching the interrogator on suspends
     * the wake word, because whether two AudioRecord clients in one app both receive real audio is a
     * device-specific question that cannot be answered from a build machine — and shipping a feature
     * that silently records silence is worse than shipping one that says what it costs.
     */
    @Test
    fun theInterrogatorSuspendsTheWakeWord() {
        val armed = VoiceMachine.State().wants(true)
        assertEquals(VoiceMachine.Owner.WAKE, armed.state.owner)

        val on = armed.state.interrogator(true)
        assertEquals(VoiceMachine.Owner.INTERROGATOR, on.state.owner)
        assertEquals(VoiceMachine.Action.START_INTERROGATOR, on.action)

        // And it is reversible without restarting anything.
        val off = on.state.interrogator(false)
        assertEquals(VoiceMachine.Owner.WAKE, off.state.owner)
        assertEquals(VoiceMachine.Action.START_WAKE, off.action)
    }

    /** It runs with the voice assistant entirely off — the two are independent features. */
    @Test
    fun theInterrogatorRunsWithVoiceOff() {
        val step = VoiceMachine.State().interrogator(true)
        assertEquals(VoiceMachine.Owner.INTERROGATOR, step.state.owner)
        assertEquals(VoiceMachine.Action.START_INTERROGATOR, step.action)
        assertFalse(step.state.wanted)
    }

    /**
     * ⚠️ A deliberate request always outranks an ambient one. Somebody tapping to talk is asking for
     * the microphone explicitly, and a background listener that could outrank that would make the
     * console silently useless.
     */
    @Test
    fun theConsoleOutranksTheInterrogator() {
        val listening = VoiceMachine.State().interrogator(true)
        val opened = listening.state.console(true)
        assertEquals(VoiceMachine.Owner.CONSOLE, opened.state.owner)
        assertEquals("the ambient stream must actually be closed", VoiceMachine.Action.RELEASE_MIC, opened.action)

        val closed = opened.state.console(false)
        assertEquals(VoiceMachine.Owner.INTERROGATOR, closed.state.owner)
    }

    /** Speaking silences it, or the computer transcribes its own replies as somebody's argument. */
    @Test
    fun speakingTakesTheMicFromTheInterrogator() {
        val listening = VoiceMachine.State().interrogator(true)
        val speaking = listening.state.speaking()
        assertEquals(VoiceMachine.Owner.SPEAKING, speaking.state.owner)
        assertEquals(VoiceMachine.Action.RELEASE_MIC, speaking.action)
        assertEquals(VoiceMachine.Owner.INTERROGATOR, speaking.state.settle().state.owner)
    }

    /** The file's central property, extended to the new owner. */
    @Test
    fun settlingTwiceStartsTheInterrogatorOnce() {
        val first = VoiceMachine.State().interrogator(true)
        assertEquals(VoiceMachine.Action.START_INTERROGATOR, first.action)
        assertEquals(VoiceMachine.Action.NOTHING, first.state.settle().action)
        assertEquals(VoiceMachine.Action.NOTHING, first.state.settle().state.settle().action)
    }

    /** A conversation already in flight finishes before the interrogator takes over again. */
    @Test
    fun aConversationInFlightOutranksTheInterrogator() {
        val s = VoiceMachine.State(wanted = true, interrogating = true)
        assertEquals(VoiceMachine.Owner.COMMAND, s.settle(holdFloor = true).state.owner)
        // But holding the floor means nothing when the voice assistant is off entirely.
        val voiceOff = VoiceMachine.State(wanted = false, interrogating = true)
        assertEquals(VoiceMachine.Owner.INTERROGATOR, voiceOff.settle(holdFloor = true).state.owner)
    }
}
