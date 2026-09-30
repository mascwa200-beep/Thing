package dev.mascwa.pulse.feature.keyboard

import dev.mascwa.pulse.feature.keyboard.KeyboardModel.Key
import dev.mascwa.pulse.feature.keyboard.KeyboardModel.Layer
import dev.mascwa.pulse.feature.keyboard.KeyboardModel.Output
import dev.mascwa.pulse.feature.keyboard.KeyboardModel.ShiftState
import dev.mascwa.pulse.feature.keyboard.KeyboardModel.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class KeyboardModelTest {

    private val punctuations = listOf(",", "@", "/")

    private fun keysOf(layer: Layer, punctuation: String = ",", offerSwitch: Boolean = true): List<Key> =
        KeyboardModel.rows(layer, punctuation, offerSwitch).flatten().map { it.key }

    private fun textsOf(layer: Layer, punctuation: String = ","): List<String> =
        keysOf(layer, punctuation).filterIsInstance<Key.Text>().map { it.value }

    /** Presses [keys] in order from [start], [gapMs] apart, and returns every output and the end state. */
    private fun type(start: State, vararg keys: Key, gapMs: Long = 1_000L): Pair<List<Output>, State> {
        var state = start
        val out = keys.mapIndexed { i, key ->
            val r = KeyboardModel.press(state, key, 10_000L + i * gapMs)
            state = r.state
            r.output
        }
        return out to state
    }

    // ── the layout ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun `every row of every layer is the same width, with and without the switch key`() {
        for (layer in Layer.entries) for (p in punctuations) for (switch in listOf(true, false)) {
            KeyboardModel.rows(layer, p, switch).forEachIndexed { i, row ->
                val width = row.sumOf { it.weight.toDouble() }
                assertTrue("$layer row $i (switch=$switch) is $width wide", abs(width - KeyboardModel.ROW_WEIGHT) < 1e-6)
            }
        }
    }

    @Test
    fun `a character with no one-letter capital is typed as it is under shift`() {
        val out = KeyboardModel.press(State(shift = ShiftState.ONCE), Key.Text("ß"), 0L).output
        assertEquals(Output.Commit("ß"), out)
        assertEquals(Output.Commit("É"), KeyboardModel.press(State(shift = ShiftState.ONCE), Key.Text("é"), 0L).output)
    }

    @Test
    fun `the letters layer carries every letter exactly once`() {
        val letters = textsOf(Layer.LETTERS).filter { it.length == 1 && it[0] in 'a'..'z' }
        assertEquals(('a'..'z').map { it.toString() }, letters.sorted())
    }

    @Test
    fun `the symbols layer carries every digit`() {
        val digits = textsOf(Layer.SYMBOLS).filter { it.length == 1 && it[0].isDigit() }
        assertEquals(('0'..'9').map { it.toString() }, digits.sorted())
    }

    @Test
    fun `no layer carries the same character twice`() {
        for (layer in Layer.entries) for (p in punctuations) {
            val texts = textsOf(layer, p)
            assertEquals("$layer with '$p': ${texts.groupBy { it }.filterValues { it.size > 1 }.keys}", texts.size, texts.toSet().size)
        }
    }

    @Test
    fun `every layer has one space, one enter and one delete`() {
        for (layer in Layer.entries) {
            val keys = keysOf(layer)
            assertEquals("$layer space", 1, keys.count { it == Key.Space })
            assertEquals("$layer enter", 1, keys.count { it == Key.Enter })
            assertEquals("$layer delete", 1, keys.count { it == Key.Delete })
        }
    }

    @Test
    fun `every layer can reach every other, and get back to the letters`() {
        fun next(layer: Layer) = keysOf(layer).filterIsInstance<Key.ToLayer>().map { it.layer }.toSet()
        val reached = mutableSetOf(Layer.LETTERS)
        var frontier = listOf(Layer.LETTERS)
        while (frontier.isNotEmpty()) {
            frontier = frontier.flatMap { next(it) }.filter { reached.add(it) }
        }
        assertEquals(Layer.entries.toSet(), reached)
        for (layer in Layer.entries - Layer.LETTERS) assertTrue("$layer has no way back", Layer.LETTERS in next(layer))
    }

    @Test
    fun `the switch key is drawn only when there is another keyboard to switch to`() {
        for (layer in Layer.entries) {
            assertEquals(1, keysOf(layer, offerSwitch = true).count { it == Key.SwitchIme })
            assertEquals(0, keysOf(layer, offerSwitch = false).count { it == Key.SwitchIme })
        }
    }

    @Test
    fun `the key beside the space bar is the one the field needs`() {
        assertTrue(Key.Text("@") in keysOf(Layer.LETTERS, "@"))
        assertTrue(Key.Text("/") in keysOf(Layer.LETTERS, "/"))
        // The symbols layers carry their own @ and / already.
        assertEquals(1, textsOf(Layer.SYMBOLS, "@").count { it == "@" })
    }

    // ── shift ───────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `one tap on shift capitalises one letter`() {
        val (out, end) = type(State(), Key.Shift, Key.Text("a"), Key.Text("b"))
        assertEquals(listOf(Output.None, Output.Commit("A"), Output.Commit("b")), out)
        assertEquals(ShiftState.OFF, end.shift)
    }

    @Test
    fun `two quick taps lock shift until it is tapped again`() {
        val (_, locked) = type(State(), Key.Shift, Key.Shift, gapMs = 200L)
        assertEquals(ShiftState.LOCKED, locked.shift)
        val (out, stillLocked) = type(locked, Key.Text("a"), Key.Text("b"))
        assertEquals(listOf(Output.Commit("A"), Output.Commit("B")), out)
        assertEquals(ShiftState.LOCKED, stillLocked.shift)
        val (_, off) = type(stillLocked, Key.Shift)
        assertEquals(ShiftState.OFF, off.shift)
    }

    @Test
    fun `two slow taps turn shift on and then off`() {
        val (_, end) = type(State(), Key.Shift, Key.Shift, gapMs = KeyboardModel.DOUBLE_TAP_MS + 1)
        assertEquals(ShiftState.OFF, end.shift)
    }

    @Test
    fun `a letter between two taps is not a double tap`() {
        val (_, end) = type(State(), Key.Shift, Key.Text("a"), Key.Shift, gapMs = 100L)
        assertEquals(ShiftState.ONCE, end.shift)
    }

    @Test
    fun `a third quick tap unlocks rather than locking again`() {
        val (_, end) = type(State(), Key.Shift, Key.Shift, Key.Shift, gapMs = 100L)
        assertEquals(ShiftState.OFF, end.shift)
    }

    @Test
    fun `a space does not use up shift`() {
        val (out, end) = type(State(), Key.Shift, Key.Space)
        assertEquals(Output.Commit(" "), out.last())
        assertEquals(ShiftState.ONCE, end.shift)
    }

    @Test
    fun `shift changes letters and nothing else`() {
        val (out, end) = type(State(layer = Layer.SYMBOLS), Key.Shift, Key.Text("1"))
        assertEquals(Output.Commit("1"), out.last())
        assertEquals("a symbol must not use up a shift meant for a letter", ShiftState.ONCE, end.shift)
        // A digit has no capital, so it cannot show a symbol being wrongly capitalised; π can.
        val (more, _) = type(State(layer = Layer.MORE), Key.Shift, Key.Text("π"))
        assertEquals(Output.Commit("π"), more.last())
    }

    // ── automatic capitals ──────────────────────────────────────────────────────────────────────

    @Test
    fun `the start of a sentence turns shift on, and the keyboard can take that back`() {
        val on = KeyboardModel.autoCaps(State(), capsMode = 0x2000)
        assertEquals(ShiftState.ONCE, on.shift)
        assertTrue(on.auto)
        assertEquals(ShiftState.OFF, KeyboardModel.autoCaps(on, capsMode = 0).shift)
    }

    @Test
    fun `a shift the person pressed is never taken back`() {
        val (_, pressed) = type(State(), Key.Shift)
        assertFalse(pressed.auto)
        assertEquals(ShiftState.ONCE, KeyboardModel.autoCaps(pressed, capsMode = 0).shift)
    }

    @Test
    fun `a lock is never touched`() {
        val locked = State(shift = ShiftState.LOCKED)
        assertEquals(locked, KeyboardModel.autoCaps(locked, capsMode = 0))
        assertEquals(locked, KeyboardModel.autoCaps(locked, capsMode = 0x2000))
    }

    @Test
    fun `typing the capital a sentence asked for clears the automatic shift`() {
        val on = KeyboardModel.autoCaps(State(), capsMode = 0x2000)
        val r = KeyboardModel.press(on, Key.Text("h"), 0L)
        assertEquals(Output.Commit("H"), r.output)
        assertEquals(ShiftState.OFF, r.state.shift)
        assertFalse(r.state.auto)
    }

    // ── the field ───────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a number, a phone number or a date opens on the digits`() {
        for (cls in listOf(KeyboardModel.TYPE_CLASS_NUMBER, KeyboardModel.TYPE_CLASS_PHONE, KeyboardModel.TYPE_CLASS_DATETIME)) {
            assertEquals(Layer.SYMBOLS, KeyboardModel.startLayer(cls))
            // A signed or decimal number carries flags above the class; the class is still a number.
            assertEquals(Layer.SYMBOLS, KeyboardModel.startLayer(cls or 0x1000))
        }
        assertEquals(Layer.LETTERS, KeyboardModel.startLayer(KeyboardModel.TYPE_CLASS_TEXT))
        assertEquals(Layer.LETTERS, KeyboardModel.startLayer(0))
    }

    @Test
    fun `an email field gets an at sign and a web address a slash`() {
        val text = KeyboardModel.TYPE_CLASS_TEXT
        assertEquals("@", KeyboardModel.punctuation(text or KeyboardModel.TYPE_TEXT_VARIATION_EMAIL_ADDRESS))
        assertEquals("@", KeyboardModel.punctuation(text or KeyboardModel.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS))
        assertEquals("/", KeyboardModel.punctuation(text or KeyboardModel.TYPE_TEXT_VARIATION_URI))
        assertEquals(",", KeyboardModel.punctuation(text))
        // A multi-line flag must not hide the variation beneath it.
        assertEquals("@", KeyboardModel.punctuation(text or KeyboardModel.TYPE_TEXT_VARIATION_EMAIL_ADDRESS or 0x20000))
        // A password is not an address, and a number with the same variation bits is not text.
        assertEquals(",", KeyboardModel.punctuation(text or 0x80))
        assertEquals(",", KeyboardModel.punctuation(KeyboardModel.TYPE_CLASS_NUMBER or KeyboardModel.TYPE_TEXT_VARIATION_EMAIL_ADDRESS))
    }

    // ── the other keys ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `a layer key changes the layer and types nothing`() {
        val r = KeyboardModel.press(State(), Key.ToLayer(Layer.SYMBOLS, "?123"), 0L)
        assertEquals(Layer.SYMBOLS, r.state.layer)
        assertEquals(Output.None, r.output)
    }

    @Test
    fun `delete, enter and the switch key each say so`() {
        assertEquals(Output.Delete, KeyboardModel.press(State(), Key.Delete, 0L).output)
        assertEquals(Output.Enter, KeyboardModel.press(State(), Key.Enter, 0L).output)
        assertEquals(Output.SwitchIme, KeyboardModel.press(State(), Key.SwitchIme, 0L).output)
    }

    @Test
    fun `labels follow shift, and a locked shift says so`() {
        assertEquals("q", KeyboardModel.label(Key.Text("q"), State()))
        assertEquals("Q", KeyboardModel.label(Key.Text("q"), State(shift = ShiftState.ONCE)))
        assertEquals("1", KeyboardModel.label(Key.Text("1"), State(layer = Layer.SYMBOLS, shift = ShiftState.LOCKED)))
        assertEquals("CAPS", KeyboardModel.label(Key.Shift, State(shift = ShiftState.LOCKED)))
        assertEquals("SHIFT", KeyboardModel.label(Key.Shift, State(shift = ShiftState.ONCE)))
    }
}
