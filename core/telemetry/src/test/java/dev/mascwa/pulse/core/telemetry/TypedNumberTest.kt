package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * ⚠️ **Every case here is a keystroke sequence, driven through the real parser and a real
 * formatter**, rather than a call to [TypedNumber.reseed] with hand-picked arguments. The defect
 * this rule exists for only appears across several frames — type, commit, re-render, type again —
 * and a single-call assertion cannot see it. [type] is the loop the field actually runs.
 */
class TypedNumberTest {

    /** The training card's own renderer, copied so the round trip under test is the shipped one. */
    private fun trim(v: Double): String {
        val hundredths = Math.round(v * 100.0)
        val places = when {
            hundredths % 100L == 0L -> 0
            hundredths % 10L == 0L -> 1
            else -> 2
        }
        return String.format(java.util.Locale.US, "%.${places}f", v)
    }

    /**
     * One field, over a whole sequence of keystrokes.
     *
     * [store] is the model: it takes what was parsed and answers with what it now renders, which is
     * how a real caller behaves — including clamping.
     */
    private class Field(val store: (Double?) -> String) {
        var typed = ""
        var lastSeen = ""
        /** ⚠️ Both assignments unconditional, which is the contract `textFor` documents. */
        fun frame(value: String) {
            typed = TypedNumber.textFor(typed, lastSeen, value)
            lastSeen = value
        }
        fun press(c: Char) {
            typed += c
            frame(store(Decimals.parse(typed)))
        }
        /** The model changed from elsewhere — a different row moved into this slot. */
        fun modelSays(value: String) = frame(value)
    }

    /** A nullable Double field, like a training load: it holds exactly what it is given. */
    private fun loadField() = Field { it?.let(::trim).orEmpty() }

    private fun typeInto(field: Field, keys: String): Field {
        for (c in keys) field.press(c)
        return field
    }

    // ------------------------------------------------------------------- the defect it exists for

    @Test
    fun `a fractional load can be typed at all`() {
        // Before this rule these recorded 125, 25, 5 and 1025 respectively — computed by running the
        // shipped parse-and-render loop, not guessed at.
        for ((keys, expected) in listOf("1.25" to 1.25, "2.5" to 2.5, "0.5" to 0.5, "102.5" to 102.5)) {
            val f = typeInto(loadField(), keys)
            assertEquals("typing $keys", keys, f.typed)
            assertEquals("typing $keys", expected, Decimals.parse(f.typed))
        }
    }

    @Test
    fun `a comma keyboard types the same fraction`() {
        // ⚠️ The half of this that `Decimals` alone did not fix: the separator survived the parse and
        // was then destroyed by the render, exactly like a full stop. And the COMMA survives too —
        // the first rule keeps the typed spelling whenever the model already agrees, so the field
        // does not rewrite the separator under the finger of somebody on a comma keyboard.
        val f = typeInto(loadField(), "1,25")
        assertEquals("1,25", f.typed)
        assertEquals(1.25, Decimals.parse(f.typed))
    }

    @Test
    fun `a lone separator is an intermediate and is left alone`() {
        val f = typeInto(loadField(), "1.")
        assertEquals("a half-typed number must not be re-seeded", "1.", TypedNumber.textFor("1.", "1", "1"))
        assertEquals("1.", f.typed)
    }

    // ------------------------------------------------------- the model changing from somewhere else

    @Test
    fun `a different row moving into this slot replaces what was typed`() {
        val f = typeInto(loadField(), "1.25")
        f.modelSays("60")
        assertEquals("60", f.typed)
    }

    @Test
    fun `an empty box follows the model when the model moves`() {
        // ⚠️ **This is the case the second rule exists for, and the test above does not reach it.**
        // There the box holds a number, so the third rule catches the change on its own and deleting
        // the second one fails nothing — which is exactly what a negative-test run reported. What
        // only the second rule can do is move a box holding something that is NOT a number: clear
        // the servings field, then have the recipe reloaded with a different count, and without it
        // the box stays empty for ever.
        val f = loadField()
        f.typed = ""; f.lastSeen = "4"
        f.modelSays("6")
        assertEquals("6", f.typed)
    }

    @Test
    fun `an empty box survives on a field whose model cannot express it`() {
        // The recipe servings field: a non-null Int, whose handler ignores a blank string. Without
        // the last-seen test the model would keep saying 4 and the field could never be cleared.
        var model = 4
        val f = Field { parsed ->
            // The shipped handler: a blank string parses to null, so the model is told nothing at
            // all and keeps its previous value — which is precisely why it could never be cleared.
            parsed?.toInt()?.let { model = it }
            model.toString()
        }
        f.typed = "4"; f.lastSeen = "4"
        // Backspace: the buffer empties, the model is told nothing, and the field must stay empty.
        f.typed = ""
        f.frame("4")
        assertEquals("", f.typed)
        // And then a new number takes: 4 -> 6, without ever passing through 46 or 64.
        f.press('6')
        assertEquals("6", f.typed)
        assertEquals(6, model)
        // ⚠️ And it can be cleared AGAIN, which is what the unconditional `lastSeen` buys. Updating
        // it only on a re-seed leaves it stale at "4", and the next empty box adopts "6" instead.
        f.typed = ""
        f.frame("6")
        assertEquals("", f.typed)
    }

    // ------------------------------------------------------------------- a clamp must stay visible

    @Test
    fun `a fraction typed into an integer field snaps back`() {
        // Reps: `it?.toInt() ?: 0`, rendered back as a whole number. The model genuinely cannot hold
        // 1.5, and the refusal has to be on screen rather than the field saying one thing while the
        // record says another.
        val reps = Field { parsed -> (parsed?.toInt() ?: 0).takeIf { it > 0 }?.toString().orEmpty() }
        typeInto(reps, "1.5")
        assertEquals("1", reps.typed)
    }

    @Test
    fun `the clamp clause is what catches it, not the last-seen clause`() {
        // ⚠️ Pinned separately because the two clauses are easy to conflate: here the model has NOT
        // changed since it was last adopted ("1" both times), so only the disagreement test can fire.
        assertEquals("1", TypedNumber.textFor(typed = "1.5", lastSeen = "1", value = "1"))
    }

    // --------------------------------------------------------------------------------- boundaries

    @Test
    fun `nothing typed and nothing stored agree`() {
        assertEquals("", TypedNumber.textFor("", "", ""))
    }

    @Test
    fun `text that is not a number at all is left alone`() {
        // `null` from the parser means "not a number YET". Snapping back here would erase a leading
        // minus or a lone separator mid-typing.
        assertEquals("-", TypedNumber.textFor("-", "", ""))
        assertEquals(".", TypedNumber.textFor(".", "", ""))
    }

    @Test
    fun `agreement by value rather than by spelling`() {
        // "1.50" and "1.5" are the same number; the field keeps the spelling that was typed.
        assertEquals("1.50", TypedNumber.textFor(typed = "1.50", lastSeen = "1.5", value = "1.5"))
    }
}
