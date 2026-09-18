package dev.mascwa.pulse.core.telemetry

import dev.mascwa.pulse.core.telemetry.AmbientReconcile.HoldState
import dev.mascwa.pulse.core.telemetry.AmbientReconcile.ReleaseReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AmbientReconcileTest {

    // Real ceilings, so the arithmetic below is the arithmetic that ships:
    //   TORCH_ON          10 min = 600_000 ms
    //   HOLD_NON_URGENT    4 h   = 14_400_000 ms
    private val torchCeiling = AmbientAction.TORCH_ON.maxHoldMs
    private val holdCeiling = AmbientAction.HOLD_NON_URGENT.maxHoldMs

    private fun want(action: AmbientAction, atMs: Long, why: String = "because") =
        AmbientIntent(action = action, reason = why, fromMs = atMs)

    // ---- the ordinary path ----

    @Test
    fun `nothing wanted and nothing held is quiet`() {
        val r = AmbientReconcile.reconcile(HoldState(), emptyList(), nowMs = 5_000L)
        assertTrue(r.quiet)
        assertTrue(r.state.held.isEmpty())
    }

    @Test
    fun `something newly wanted is asserted`() {
        val r = AmbientReconcile.reconcile(
            HoldState(),
            listOf(want(AmbientAction.HOLD_NON_URGENT, 1_000L, "you are driving")),
            nowMs = 1_000L,
        )
        assertEquals(listOf(AmbientAction.HOLD_NON_URGENT), r.asserted.map { it.action })
        assertTrue(r.state.isHeld(AmbientAction.HOLD_NON_URGENT))
        assertTrue(r.released.isEmpty())
    }

    @Test
    fun `something no longer wanted is released, and says so`() {
        val first = AmbientReconcile.reconcile(
            HoldState(), listOf(want(AmbientAction.PAUSE_VIDEO, 0L)), nowMs = 0L,
        )
        val second = AmbientReconcile.reconcile(first.state, emptyList(), nowMs = 60_000L)
        assertEquals(1, second.released.size)
        assertEquals(AmbientAction.PAUSE_VIDEO, second.released[0].intent.action)
        assertEquals(ReleaseReason.NO_LONGER_WANTED, second.released[0].why)
        assertTrue(second.state.held.isEmpty())
    }

    /**
     * ⚠️ Transition-only. Re-asserting means re-APPLYING, and re-applying a hold on something the
     * person has since changed by hand stomps their choice — the defect the deleted
     * `PhonePenaltyController` shipped and that CLAUDE.md records.
     */
    @Test
    fun `something already held is not asserted again`() {
        val first = AmbientReconcile.reconcile(
            HoldState(), listOf(want(AmbientAction.STAY_SILENT, 0L)), nowMs = 0L,
        )
        assertEquals(1, first.asserted.size)

        // Three more heartbeats, all wanting the same thing. None of them may assert anything.
        var state = first.state
        for (t in listOf(30_000L, 90_000L, 150_000L)) {
            val r = AmbientReconcile.reconcile(state, listOf(want(AmbientAction.STAY_SILENT, t)), t)
            assertTrue("asserted again at $t", r.asserted.isEmpty())
            assertTrue("released at $t", r.released.isEmpty())
            assertTrue("quiet was wrong at $t", r.quiet)
            state = r.state
        }
        assertTrue(state.isHeld(AmbientAction.STAY_SILENT))
    }

    // ---- the backstop, and the three ways it can be defeated ----

    /**
     * ⚠️ **The one that makes the backstop real.** The obvious implementation takes `wanted` as the
     * new truth and keeps ITS intents — which advances `fromMs` every heartbeat, so `untilMs` runs
     * away and the ceiling never arrives. The held intent must keep the instant it was asserted.
     */
    @Test
    fun `a held intent keeps the instant it was asserted`() {
        var state = AmbientReconcile.reconcile(
            HoldState(), listOf(want(AmbientAction.TORCH_ON, 0L, "first reason")), nowMs = 0L,
        ).state

        // Heartbeats at 1, 2 and 3 minutes, each wanting it afresh with a later instant.
        for (t in listOf(60_000L, 120_000L, 180_000L)) {
            state = AmbientReconcile.reconcile(
                state, listOf(want(AmbientAction.TORCH_ON, t, "reason at $t")), t,
            ).state
        }

        val held = state.held.single()
        assertEquals("fromMs moved", 0L, held.fromMs)
        assertEquals("untilMs moved", torchCeiling, held.untilMs)
        assertEquals("the sentence was replaced", "first reason", held.reason)
    }

    @Test
    fun `a hold that outlives its ceiling is released even though the rule still wants it`() {
        var state = AmbientReconcile.reconcile(
            HoldState(), listOf(want(AmbientAction.TORCH_ON, 0L)), nowMs = 0L,
        ).state

        // One second short of the ceiling: still held.
        val justBefore = AmbientReconcile.reconcile(
            state, listOf(want(AmbientAction.TORCH_ON, torchCeiling - 1_000L)), torchCeiling - 1_000L,
        )
        assertTrue("released early", justBefore.released.isEmpty())
        state = justBefore.state

        // At the ceiling exactly: gone.
        val atCeiling = AmbientReconcile.reconcile(
            state, listOf(want(AmbientAction.TORCH_ON, torchCeiling)), torchCeiling,
        )
        assertEquals(1, atCeiling.released.size)
        assertEquals(ReleaseReason.BACKSTOP, atCeiling.released[0].why)
        assertFalse(atCeiling.state.isHeld(AmbientAction.TORCH_ON))
    }

    /**
     * ⚠️ **Without the bar the backstop is a release in name only** — released on one heartbeat and
     * asserted again on the next, for ever, by a rule that never stops asking.
     */
    @Test
    fun `a hold released by the backstop does not come straight back`() {
        var state = AmbientReconcile.reconcile(
            HoldState(), listOf(want(AmbientAction.TORCH_ON, 0L)), nowMs = 0L,
        ).state
        state = AmbientReconcile.reconcile(
            state, listOf(want(AmbientAction.TORCH_ON, torchCeiling)), torchCeiling,
        ).state

        // Four more heartbeats, the rule asking the whole time. Not one of them may re-assert.
        for (t in listOf(torchCeiling + 1_000L, torchCeiling + 90_000L, torchCeiling + 400_000L, torchCeiling * 3)) {
            val r = AmbientReconcile.reconcile(state, listOf(want(AmbientAction.TORCH_ON, t)), t)
            assertTrue("came back at $t", r.asserted.isEmpty())
            assertFalse("held again at $t", r.state.isHeld(AmbientAction.TORCH_ON))
            state = r.state
        }
    }

    /**
     * And the other half: the bar is not permanent. Once the rule stops asking, the situation has
     * genuinely changed and the next ask is a fresh one.
     */
    @Test
    fun `the bar lifts once the rule stops asking`() {
        var state = AmbientReconcile.reconcile(
            HoldState(), listOf(want(AmbientAction.TORCH_ON, 0L)), nowMs = 0L,
        ).state
        state = AmbientReconcile.reconcile(
            state, listOf(want(AmbientAction.TORCH_ON, torchCeiling)), torchCeiling,
        ).state
        assertTrue("the bar was never set", AmbientAction.TORCH_ON in state.expiredWhileWanted)

        // The rule goes quiet for one heartbeat.
        state = AmbientReconcile.reconcile(state, emptyList(), torchCeiling + 60_000L).state
        assertTrue("the bar outlived the want", state.expiredWhileWanted.isEmpty())

        // Now it may be asked for again.
        val again = AmbientReconcile.reconcile(
            state,
            listOf(want(AmbientAction.TORCH_ON, torchCeiling + 120_000L)),
            torchCeiling + 120_000L,
        )
        assertEquals(listOf(AmbientAction.TORCH_ON), again.asserted.map { it.action })
        assertEquals(
            "the fresh hold did not start its own clock",
            torchCeiling + 120_000L,
            again.state.held.single().fromMs,
        )
    }

    /** One action expiring must not disturb another that is nowhere near its own ceiling. */
    @Test
    fun `a backstop touches only the hold that reached it`() {
        var state = AmbientReconcile.reconcile(
            HoldState(),
            listOf(want(AmbientAction.TORCH_ON, 0L), want(AmbientAction.HOLD_NON_URGENT, 0L)),
            nowMs = 0L,
        ).state
        assertEquals(2, state.held.size)

        // The torch's ceiling is 10 minutes; holding non-urgent notices runs to four hours.
        val r = AmbientReconcile.reconcile(
            state,
            listOf(want(AmbientAction.TORCH_ON, torchCeiling), want(AmbientAction.HOLD_NON_URGENT, torchCeiling)),
            torchCeiling,
        )
        assertEquals(listOf(AmbientAction.TORCH_ON), r.released.map { it.intent.action })
        assertTrue(r.state.isHeld(AmbientAction.HOLD_NON_URGENT))
        assertEquals(setOf(AmbientAction.TORCH_ON), r.state.expiredWhileWanted)
        assertTrue("the survivor's ceiling is not near", torchCeiling < holdCeiling)
    }

    // ---- standing down, and letting go by hand ----

    @Test
    fun `standing down lets go of everything and bars nothing`() {
        val state = AmbientReconcile.reconcile(
            HoldState(),
            listOf(
                want(AmbientAction.HOLD_NON_URGENT, 0L),
                want(AmbientAction.STAY_SILENT, 0L),
                want(AmbientAction.PAUSE_VIDEO, 0L),
            ),
            nowMs = 0L,
        ).state

        val down = AmbientReconcile.releaseAll(state, ReleaseReason.STOOD_DOWN)
        assertEquals(3, down.released.size)
        assertTrue(down.released.all { it.why == ReleaseReason.STOOD_DOWN })
        assertTrue(down.state.held.isEmpty())
        // ⚠️ Nothing here judged a rule to have misbehaved, so nothing may be barred from asking
        // again once sensing comes back.
        assertTrue(down.state.expiredWhileWanted.isEmpty())
    }

    @Test
    fun `letting go by hand bars it from coming straight back`() {
        val state = AmbientReconcile.reconcile(
            HoldState(),
            listOf(want(AmbientAction.STAY_SILENT, 0L), want(AmbientAction.PAUSE_VIDEO, 0L)),
            nowMs = 0L,
        ).state

        val byHand = AmbientReconcile.release(state, AmbientAction.STAY_SILENT)
        assertEquals(1, byHand.released.size)
        assertEquals(ReleaseReason.BY_HAND, byHand.released[0].why)
        assertTrue("the other hold went with it", byHand.state.isHeld(AmbientAction.PAUSE_VIDEO))

        // The rule is still asking. The button would look broken if this came back.
        val next = AmbientReconcile.reconcile(
            byHand.state,
            listOf(want(AmbientAction.STAY_SILENT, 60_000L), want(AmbientAction.PAUSE_VIDEO, 60_000L)),
            60_000L,
        )
        assertTrue("it came back on the next heartbeat", next.asserted.isEmpty())
        assertFalse(next.state.isHeld(AmbientAction.STAY_SILENT))
    }

    @Test
    fun `letting go of something that is not held changes nothing`() {
        val state = AmbientReconcile.reconcile(
            HoldState(), listOf(want(AmbientAction.PAUSE_VIDEO, 0L)), nowMs = 0L,
        ).state
        val r = AmbientReconcile.release(state, AmbientAction.TORCH_ON)
        assertTrue(r.quiet)
        assertEquals(state.held, r.state.held)
        assertTrue("it barred something it never held", r.state.expiredWhileWanted.isEmpty())
    }

    // ---- what a surface reads ----

    @Test
    fun `a hold reads as what it did and why`() {
        val i = want(AmbientAction.HOLD_NON_URGENT, 0L, "you are driving")
        assertEquals("held back non-urgent notices · you are driving", AmbientReconcile.describe(i))
        // ⚠️ The undo sentence is read straight off the action and is deliberately NOT restated
        // here as a second function: one way of saying a thing is the whole lesson of the label
        // hoist one slice ago.
        assertEquals("let notices through again", i.action.undo)
    }

    @Test
    fun `the set form matches what is held`() {
        val state = AmbientReconcile.reconcile(
            HoldState(),
            listOf(want(AmbientAction.STAY_SILENT, 0L), want(AmbientAction.LOWER_SENSE_RATE, 0L)),
            nowMs = 0L,
        ).state
        assertEquals(
            setOf(AmbientAction.STAY_SILENT, AmbientAction.LOWER_SENSE_RATE),
            state.actions(),
        )
    }
}
