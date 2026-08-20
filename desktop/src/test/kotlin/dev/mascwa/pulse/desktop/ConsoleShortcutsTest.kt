package dev.mascwa.pulse.desktop

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The keyboard shortcuts.
 *
 * ⚠️ These are exactly the kind of rule that stops working silently — a modifier changes, an event
 * type is missed, a key gets consumed somewhere higher up — and none of it is visible by reading the
 * composable it would otherwise be buried in. That is why [consoleCommandFor] is a pure function and
 * why it has a test at all.
 *
 * ⚠️ **What this covers, and what it cannot, stated rather than implied.** It exercises the decision —
 * which combinations the console claims — over exactly the values the runtime supplies. It does NOT
 * exercise the decoding of a physical keystroke into those values, because Compose Desktop lets
 * nothing outside its own module build a `KeyEvent`: the platform converter is internal and the
 * value-class factory is marked unstable-between-modules. That is why the shipped code takes the three
 * facts and `ConsoleKeys.handle` is three property reads — the untestable part is as small as it can
 * be made, and a fault there shows up as a shortcut that never fires rather than as a wrong one.
 */
class ConsoleShortcutsTest {

    private fun type(released: Boolean) =
        if (released) KeyEventType.KeyUp else KeyEventType.KeyDown

    @Test
    fun ctrlKAndCtrlPBothOpenTheCommandBar() {
        assertEquals(
            ConsoleCommand.OPEN_COMMAND_BAR,
            consoleCommandFor(Key.K, type(false), ctrl = true, overlayOpen = false),
        )
        assertEquals(
            ConsoleCommand.OPEN_COMMAND_BAR,
            consoleCommandFor(Key.P, type(false), ctrl = true, overlayOpen = false),
        )
    }

    /**
     * ⚠️ The whole point of requiring the modifier. Without it, typing the letter K into any field in
     * the program would open the command bar over what you were writing.
     */
    @Test
    fun theSameLettersWithoutControlAreNotOurs() {
        assertNull(consoleCommandFor(Key.K, type(false), ctrl = false, overlayOpen = false))
        assertNull(consoleCommandFor(Key.P, type(false), ctrl = false, overlayOpen = false))
        assertNull(consoleCommandFor(Key.O, type(false), ctrl = false, overlayOpen = false))
    }

    /**
     * ⚠️ Every press produces a down AND an up. Acting on both fires each shortcut twice, and for the
     * toggles that is invisible rather than merely wrong: the ops wall would open on the down and shut
     * again on the up, so pressing F11 would appear to do nothing at all.
     */
    @Test
    fun onlyKeyDownCounts() {
        assertNull(consoleCommandFor(Key.K, type(true), ctrl = true, overlayOpen = false))
        assertNull(consoleCommandFor(Key.F11, type(true), ctrl = false, overlayOpen = false))
        assertNull(consoleCommandFor(Key.Escape, type(true), ctrl = false, overlayOpen = true))
    }

    @Test
    fun f11TogglesTheOpsWallWithNoModifier() {
        assertEquals(
            ConsoleCommand.TOGGLE_OPS_WALL,
            consoleCommandFor(Key.F11, type(false), ctrl = false, overlayOpen = false),
        )
    }

    @Test
    fun ctrlOTearsOffTheCurrentScreen() {
        assertEquals(
            ConsoleCommand.POP_OUT_CURRENT,
            consoleCommandFor(Key.O, type(false), ctrl = true, overlayOpen = false),
        )
    }

    /**
     * ⚠️ Escape is claimed only when there is something to close.
     *
     * Swallowing it unconditionally would take it away from every screen that might want it — a search
     * box clearing itself, a picker closing — and those are ordinary things for Escape to do. Leaving
     * it unclaimed when nothing is over the page costs nothing and keeps that possibility open.
     */
    @Test
    fun escapeIsOnlyOursWhileSomethingIsOverThePage() {
        assertEquals(
            ConsoleCommand.CLOSE_OVERLAY,
            consoleCommandFor(Key.Escape, type(false), ctrl = false, overlayOpen = true),
        )
        assertNull(consoleCommandFor(Key.Escape, type(false), ctrl = false, overlayOpen = false))
    }

    /** An ordinary letter, an arrow, a digit: none of it is the console's business. */
    @Test
    fun ordinaryTypingIsNeverClaimed() {
        listOf(
            Key.A, Key.Z, Key.Five, Key.Spacebar, Key.Enter,
            Key.DirectionLeft, Key.DirectionRight, Key.Backspace,
        ).forEach { assertNull("$it", consoleCommandFor(it, type(false), ctrl = false, overlayOpen = false)) }
    }

    /**
     * ⚠️ Ctrl with a letter the console does not claim must stay unclaimed, or every editing shortcut
     * in the program — copy, paste, select-all — would be eaten on the way past.
     */
    @Test
    fun theUsualEditingShortcutsPassStraightThrough() {
        listOf(Key.C, Key.V, Key.X, Key.A, Key.Z).forEach {
            assertNull("ctrl+$it", consoleCommandFor(it, type(false), ctrl = true, overlayOpen = false))
        }
    }

    /** The bridge only acts when the shell has published a handler — before that there is nowhere to go. */
    @Test
    fun theBridgeDoesNothingUntilTheShellHasWiredItUp() {
        val keys = ConsoleKeys()
        assertFalse(keys.dispatch(Key.K, type(false), ctrl = true))

        var seen: ConsoleCommand? = null
        keys.onCommand = { seen = it }
        assertTrue(keys.dispatch(Key.K, type(false), ctrl = true))
        assertEquals(ConsoleCommand.OPEN_COMMAND_BAR, seen)
    }

    /** A key the console does not want is reported as not consumed, so the window passes it on. */
    @Test
    fun anUnclaimedKeyIsReportedAsNotConsumed() {
        val keys = ConsoleKeys()
        keys.onCommand = { throw AssertionError("should not have been called") }
        assertFalse(keys.dispatch(Key.A, type(false), ctrl = false))
    }

    /** Escape reaches the shell only while the shell says something is open. */
    @Test
    fun theBridgeAsksTheShellWhetherAnythingIsOpen() {
        val keys = ConsoleKeys()
        var seen: ConsoleCommand? = null
        keys.onCommand = { seen = it }

        assertFalse(keys.dispatch(Key.Escape, type(false), ctrl = false))
        assertNull(seen)

        keys.overlayOpen = true
        assertTrue(keys.dispatch(Key.Escape, type(false), ctrl = false))
        assertEquals(ConsoleCommand.CLOSE_OVERLAY, seen)
    }
}
