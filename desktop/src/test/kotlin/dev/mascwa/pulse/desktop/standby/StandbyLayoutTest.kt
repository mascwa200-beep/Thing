package dev.mascwa.pulse.desktop.standby

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sizing brain, which exists because the first version got this exactly backwards.
 *
 * ⚠️ The rule under test is **a bigger surface shows MORE, not the same thing bigger**. Scaling
 * linearly with the canvas drew the 460 px HUD arrangement at 2.8× on a 1280 px window and clipped
 * two panels off the bottom — and an assertion about any single canvas's numbers would still have
 * passed. That is why the properties below are about the RELATIONSHIP between two canvases rather
 * than about what one of them produces.
 */
class StandbyLayoutTest {

    private val hud = StandbyLayout.forCanvas(460, 620)
    private val lockScreen = StandbyLayout.forCanvas(2560, 1440)
    private val fourK = StandbyLayout.forCanvas(3840, 2160)

    @Test
    fun `a bigger canvas shows more, not merely bigger`() {
        assertTrue("insights should grow with the room", lockScreen.insights > hud.insights)
        assertTrue("headlines should grow with the room", lockScreen.headlines > hud.headlines)
        assertTrue(
            "a lock screen has room for every panel",
            lockScreen.showWire && lockScreen.showMarkets && lockScreen.showStanding,
        )
    }

    @Test
    fun `type grows far more slowly than the canvas`() {
        // 2560 is 5.6x the reference 460. Linear would be 5.6x type — a clock a third of the screen
        // tall. The sub-linear curve is what buys the room the counts above are spent on.
        val ratio = lockScreen.scale / hud.scale
        assertTrue("scale grew ${ratio}x for a 5.6x canvas — expected roughly 2x", ratio in 1.5f..3.0f)
    }

    @Test
    fun `scale is bounded at both ends`() {
        // A postage stamp must not go below legibility, and a video wall must not draw one word.
        assertEquals(StandbyLayout.MIN_SCALE, StandbyLayout.forCanvas(40, 30).scale, 0f)
        assertEquals(StandbyLayout.MAX_SCALE, StandbyLayout.forCanvas(30_000, 30_000).scale, 0f)
    }

    @Test
    fun `a degenerate canvas does not divide by zero`() {
        // Reached in practice: GraphicsEnvironment can report a zero display mode on a headless or
        // half-initialised session, and a layout that threw there would take the whole refresh down.
        val zero = StandbyLayout.forCanvas(0, 0)
        assertTrue("a zero canvas must still produce a usable scale", zero.scale > 0f)
        assertTrue("even the smallest layout shows the one thing it is for", zero.insights >= 1)
    }

    @Test
    fun `scale is keyed on the smaller side`() {
        // ⚠️ A display spanning two monitors is enormously wide and no taller than one screen.
        // Scaling from width would make the type unreadable for the height it has to fit in, so a
        // 5120x1440 canvas must size like a 1440-tall one, not like a 5120-wide one.
        assertEquals(
            StandbyLayout.forCanvas(1440, 1440).scale,
            StandbyLayout.forCanvas(5120, 1440).scale,
            0f,
        )
    }

    @Test
    fun `a short canvas gives panels up in order, keeping the Oracle`() {
        // The order is tightest-first, and the Oracle and the clock are never on the list — they are
        // what the display is for. A wide letterbox is the case that forces the choice.
        val letterbox = StandbyLayout.forCanvas(1600, 260)
        assertFalse("the wire is the first thing given up", letterbox.showWire)
        assertTrue("what the Computer thinks is never given up", letterbox.insights >= 1)
    }

    @Test
    fun `stacking costs height, so a narrow canvas budgets less than it looks`() {
        // ⚠️ This is the defect STACK_COST exists for. Two canvases of the SAME height, one narrow
        // enough to stack: the stacked one must budget fewer rows, because the panels queue up
        // instead of sitting side by side. Without this the HUD claimed room for five panels, fitted
        // two, and drew the third as an empty box — which reads as a feed that answered with nothing
        // rather than as a layout that ran out of room.
        val stacked = StandbyLayout.forCanvas(460, 620)
        val sideBySide = StandbyLayout.forCanvas(1400, 620)
        assertFalse("460px is too narrow for two columns", stacked.twoColumn)
        assertTrue("1400px is wide enough for two columns", sideBySide.twoColumn)
        assertTrue(
            "a stacked layout of the same height must budget less than a side-by-side one",
            stacked.insights < sideBySide.insights || stacked.headlines < sideBySide.headlines,
        )
    }

    @Test
    fun `the right-hand column is budgeted as one thing`() {
        // Conditions, Markets and Standing share a column and a Column clips overflow SILENTLY, so
        // the movers and the standing lines are split from one budget rather than each guessing.
        // Both must stay inside their stated bounds at every size, or the last panel loses a line to
        // nothing that could be seen from the arithmetic.
        listOf(hud, lockScreen, fourK, StandbyLayout.forCanvas(800, 600)).forEach { l ->
            assertTrue("movers ${l.movers} out of range", l.movers in 2..6)
            assertTrue("standing ${l.standing} out of range", l.standing in 2..6)
        }
    }
}
