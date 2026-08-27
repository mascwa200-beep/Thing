package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FoodPhraseTest {

    private fun one(s: String) = FoodPhrase.item(s)

    // -------------------------------------------------------------------------- the whole thing

    @Test
    fun `a described meal comes apart into its things`() {
        val items = FoodPhrase.parse("two eggs, a slice of toast and 200g of chicken")
        assertEquals(3, items.size)
        assertEquals(listOf("eggs", "toast", "chicken"), items.map { it.name })
        assertEquals(2.0, items[0].amount!!, 1e-9)
        assertEquals(FoodPortion.Unit.SERVING, items[1].unit)
        assertEquals(200.0, items[2].amount!!, 1e-9)
        assertEquals(FoodPortion.Unit.GRAM, items[2].unit)
    }

    @Test
    fun `nothing at all yields nothing`() {
        assertTrue(FoodPhrase.parse("").isEmpty())
        assertTrue(FoodPhrase.parse("   \n  ").isEmpty())
    }

    @Test
    fun `a description never becomes an unbounded list`() {
        val many = (1..200).joinToString(", ") { "food$it" }
        assertEquals(FoodPhrase.MAX_ITEMS, FoodPhrase.parse(many).size)
    }

    // ------------------------------------------------------------------------- counts and masses

    @Test
    fun `a number with no unit is a count and not a mass`() {
        // ⚠️ The worst thing this could do, on the commonest phrasing there is. Two eggs read as two
        // grams would log a rounding error as a meal, and it is exactly what defaulting to grams
        // produces. A count is a SERVING, which the food's own record either can price or cannot.
        val e = one("2 eggs")
        assertEquals("eggs", e.name)
        assertEquals(2.0, e.amount!!, 1e-9)
        assertNull("a bare count must not claim a unit", e.unit)
        assertEquals(FoodPortion.Unit.SERVING, e.portion!!.unit)
        assertEquals(2.0, e.portion!!.amount, 1e-9)
    }

    @Test
    fun `nothing stated is one serving, and says so`() {
        val t = one("toast")
        assertNull(t.amount)
        assertNull(t.unit)
        assertFalse(t.stated)
        assertEquals(1.0, t.portion!!.amount, 1e-9)
        assertEquals(FoodPortion.Unit.SERVING, t.portion!!.unit)
        // ⚠️ And the words say a serving was assumed rather than printing "1 serving" as though it
        // had been typed. Those are different claims.
        assertEquals("toast — a serving", FoodPhrase.describe(t))
    }

    @Test
    fun `grams are grams`() {
        val c = one("200g chicken breast")
        assertEquals("chicken breast", c.name)
        assertEquals(200.0, c.amount!!, 1e-9)
        assertEquals(FoodPortion.Unit.GRAM, c.unit)
    }

    @Test
    fun `ounces and pounds convert exactly`() {
        // Defined constants, not estimates: 1 oz = 28.349523125 g and 1 lb = 453.59237 g.
        // 8 x 28.349523125 = 226.796185
        assertEquals(226.796185, one("8 oz steak").amount!!, 1e-9)
        assertEquals(453.59237, one("1 lb mince").amount!!, 1e-9)
        assertEquals(FoodPortion.Unit.GRAM, one("8 oz steak").unit)
    }

    @Test
    fun `a litre is a thousand millilitres`() {
        val m = one("1.5 l of milk")
        assertEquals("milk", m.name)
        assertEquals(1500.0, m.amount!!, 1e-9)
        assertEquals(FoodPortion.Unit.MILLILITRE, m.unit)
    }

    @Test
    fun `a slice is a serving rather than a weight nobody stated`() {
        val h = one("3 slices of ham")
        assertEquals("ham", h.name)
        assertEquals(3.0, h.amount!!, 1e-9)
        assertEquals(FoodPortion.Unit.SERVING, h.unit)
    }

    @Test
    fun `a packet is a package`() {
        val c = one("a packet of crisps")
        assertEquals("crisps", c.name)
        assertEquals(FoodPortion.Unit.PACKAGE, c.unit)
        assertEquals("a pack of crisps", FoodPhrase.describe(c))
    }

    // ----------------------------------------------------------------------------- word numbers

    @Test
    fun `numbers written as words are read`() {
        assertEquals(1.0, one("an apple").amount!!, 1e-9)
        assertEquals("apple", one("an apple").name)
        assertEquals(12.0, one("twelve olives").amount!!, 1e-9)
        assertEquals(0.5, one("half a cup of rice").amount!! / 240.0, 1e-9)
    }

    @Test
    fun `an article between a word number and its unit does not block the unit`() {
        // "half a cup" — the article would otherwise be read as the unit, and the cup lost.
        val r = one("half a cup of rice")
        assertEquals("rice", r.name)
        assertEquals(120.0, r.amount!!, 1e-9)
        assertEquals(FoodPortion.Unit.MILLILITRE, r.unit)
    }

    @Test
    fun `an article after "of" is not part of the food's name`() {
        // ⚠️ "quarter of a pizza" named a food "a pizza" until this was run over real phrasings.
        // Nothing looks that up.
        val p = one("quarter of a pizza")
        assertEquals("pizza", p.name)
        assertEquals(0.25, p.amount!!, 1e-9)
    }

    @Test
    fun `a couple is exactly two`() {
        val e = one("a couple of eggs")
        assertEquals("eggs", e.name)
        assertEquals(2.0, e.amount!!, 1e-9)
    }

    @Test
    fun `a vague quantity is left unstated rather than guessed at`() {
        // ⚠️ Three is a guess, and a guess rendered as a quantity is indistinguishable on screen
        // from something weighed. Unstated, the record's own serving is used — a real figure.
        for (s in listOf("a few biscuits", "some biscuits", "several biscuits")) {
            val b = one(s)
            assertEquals(s, "biscuits", b.name)
            assertNull(s, b.amount)
            assertFalse(s, b.stated)
        }
    }

    // ---------------------------------------------------------------------------------- splitting

    @Test
    fun `a comma between digits is a decimal point and does not separate`() {
        // ⚠️ Most of the world writes 12,5 g. Splitting there yields a food called "12" and five
        // grams of butter — found by running this over real phrasings, and the same trap the label
        // parser documents, arriving from the other direction.
        val items = FoodPhrase.parse("12,5 g of butter")
        assertEquals(1, items.size)
        assertEquals("butter", items[0].name)
        assertEquals(12.5, items[0].amount!!, 1e-9)
    }

    @Test
    fun `comma-and is one separator rather than two`() {
        val items = FoodPhrase.parse("eggs, and toast")
        assertEquals(listOf("eggs", "toast"), items.map { it.name })
    }

    @Test
    fun `"with" does not separate`() {
        // "toast with butter" is one thing somebody would look up as written. Splitting it invents
        // a second food they did not mention.
        val items = FoodPhrase.parse("toast with butter")
        assertEquals(1, items.size)
        assertEquals("toast with butter", items[0].name)
    }

    @Test
    fun `newlines and ampersands separate`() {
        assertEquals(listOf("coffee", "toast"), FoodPhrase.parse("coffee\ntoast").map { it.name })
        assertEquals(2, FoodPhrase.parse("1 cup coffee & 2 biscuits").size)
    }

    // ------------------------------------------------------------------------------ not a quantity

    @Test
    fun `only a leading number counts`() {
        // ⚠️ A parser hunting for a number anywhere would read the 2 in "milk 2%" as two of
        // something, which is a quantity nobody stated.
        val m = one("semi-skimmed milk 2%")
        assertEquals("semi-skimmed milk 2%", m.name)
        assertNull(m.amount)
    }

    @Test
    fun `a food beginning with x keeps its first letter`() {
        // ⚠️ "3 x biscuits" needs the multiplier gone; "2 xylitol" must not become "2 ylitol". The
        // difference is whether it is a whole token.
        assertEquals("biscuits", one("3 x biscuits").name)
        assertEquals("xylitol", one("2 xylitol").name)
        assertEquals(2.0, one("2 xylitol").amount!!, 1e-9)
    }

    @Test
    fun `a word that only looks like a unit is left alone`() {
        // "cupcake" starts with "cup" and is not a cup; the unit has to be a whole word.
        assertEquals("cupcakes", one("3 cupcakes").name)
        assertNull(one("3 cupcakes").unit)
    }

    // ---------------------------------------------------------------------------------- the words

    @Test
    fun `the readback states what was understood`() {
        assertEquals("200 g of chicken", FoodPhrase.describe(one("200g of chicken")))
        assertEquals("1 serving of toast", FoodPhrase.describe(one("a slice of toast")))
        assertEquals("3 × ham", FoodPhrase.describe(one("3 slices of ham")))
        assertEquals("nothing", FoodPhrase.describe(FoodPhrase.item("   ")))
    }
}
