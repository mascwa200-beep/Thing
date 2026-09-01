package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ⚠️ Every expected weight is computed from the published conversion, with the arithmetic in the
 * comment beside it.
 */
class RecipeImportTest {

    private fun line(s: String) = RecipeImport.parseLine(s)!!

    // ------------------------------------------------------------------------------- quantities

    @Test
    fun `a plain weight is read whole`() {
        val i = line("200 g plain flour")
        assertEquals(200.0, i.quantity!!, 1e-9)
        assertEquals(RecipeImport.Measure.GRAM, i.measure)
        assertEquals("plain flour", i.name)
        assertEquals(200.0, i.grams!!, 1e-9)
    }

    @Test
    fun `no space between the number and the unit still reads`() {
        val i = line("200g plain flour")
        assertEquals(200.0, i.quantity!!, 1e-9)
        assertEquals(RecipeImport.Measure.GRAM, i.measure)
    }

    @Test
    fun `the published mass conversions are the published ones`() {
        // 1 oz = 28.349523125 g, 1 lb = 453.59237 g, exactly, by international definition.
        assertEquals(28.349523125, line("1 oz butter").grams!!, 1e-9)
        assertEquals(453.59237, line("1 lb mince").grams!!, 1e-9)
        // 1.5 kg = 1500 g.
        assertEquals(1_500.0, line("1.5 kg potatoes").grams!!, 1e-9)
        // 8 oz = 8 * 28.349523125 = 226.796185
        assertEquals(226.796185, line("8 oz cheese").grams!!, 1e-9)
    }

    @Test
    fun `fractions are read, written either way`() {
        assertEquals(0.5, line("1/2 tsp salt").quantity!!, 1e-9)
        assertEquals(0.5, line("½ tsp salt").quantity!!, 1e-9)
        // "1 1/2" and "1½" are one and a half, not one.
        assertEquals(1.5, line("1 1/2 cups milk").quantity!!, 1e-9)
        assertEquals(1.5, line("1½ cups milk").quantity!!, 1e-9)
        // A third, to nine places: 1/3 = 0.333333333
        assertEquals(1.0 / 3.0, line("⅓ cup sugar").quantity!!, 1e-9)
    }

    @Test
    fun `a range takes the lower bound, which is the safe direction`() {
        // ⚠️ Over-counting silently inflates a day against a target somebody eats to; under-counting
        // shows up as a total that looks small. So 2-3 is two.
        assertEquals(2.0, line("2-3 tbsp olive oil").quantity!!, 1e-9)
        assertEquals(2.0, line("2–3 tbsp olive oil").quantity!!, 1e-9)
        assertEquals(RecipeImport.Measure.TABLESPOON, line("2-3 tbsp olive oil").measure)
    }

    @Test
    fun `a comma decimal reads as a decimal, not as two numbers`() {
        assertEquals(1.5, line("1,5 kg potatoes").quantity!!, 1e-9)
    }

    // ----------------------------------------------------------------------------- the refusal

    @Test
    fun `a volume never becomes a mass, whatever the volume is`() {
        // ⚠️ THE RULE THE WHOLE FILE IS SHAPED AROUND. A cup of flour is about 120 g and a cup of
        // water about 240 g — the same measure, twice the weight — so grams per millilitre is a
        // property of the ingredient and this file does not know which ingredient it is looking at.
        for (raw in listOf(
            "2 cups flour", "250 ml milk", "1 l stock", "1 tbsp oil", "2 tsp vanilla", "4 fl oz cream",
        )) {
            val i = line(raw)
            assertNotNull("$raw parsed no quantity", i.quantity)
            assertNull("$raw was converted to grams", i.grams)
        }
    }

    @Test
    fun `a count is not a mass either`() {
        val i = line("2 large onions")
        assertEquals(2.0, i.quantity!!, 1e-9)
        assertEquals(RecipeImport.Measure.PIECE, i.measure)
        assertNull(i.grams)
    }

    @Test
    fun `every volume and count measure declares no conversion, and every mass one does`() {
        // A guard on the table itself, so a future measure cannot be added with a plausible-looking
        // density filled in by whoever adds it.
        for (m in RecipeImport.Measure.entries) {
            val expected = m in listOf(
                RecipeImport.Measure.GRAM,
                RecipeImport.Measure.KILOGRAM,
                RecipeImport.Measure.OUNCE,
                RecipeImport.Measure.POUND,
            )
            assertEquals("$m", expected, m.isMass)
        }
    }

    // ------------------------------------------------------------------------------ the aliases

    @Test
    fun `a fluid ounce is a volume, not an ounce`() {
        // ⚠️ "fl oz" CONTAINS "oz". A shorter alias matched first would read this as four ounces of
        // mass — 113 g of cream that is really about 118 ml, and wrong for anything denser.
        val i = line("4 fl oz cream")
        assertEquals(RecipeImport.Measure.FLUID_OUNCE, i.measure)
        assertNull(i.grams)
    }

    @Test
    fun `a unit has to be a whole word`() {
        // "gram" is inside "programme" and "l" is inside almost everything. A line with no unit at
        // the front is a count of the thing it names.
        assertEquals(RecipeImport.Measure.PIECE, line("2 granny smith apples").measure)
        assertEquals("granny smith apples", line("2 granny smith apples").name)
    }

    @Test
    fun `filler between the measure and the food comes off`() {
        val i = line("2 cups of plain flour")
        assertEquals("plain flour", i.name)
        assertEquals(RecipeImport.Measure.CUP, i.measure)
    }

    // -------------------------------------------------------------------------------- the name

    @Test
    fun `a parenthetical is a note and never part of the quantity`() {
        // ⚠️ "(about 2 cups)" read as the quantity would give two cups of a 250 g bag.
        val i = line("250 g plain flour (about 2 cups)")
        assertEquals(250.0, i.quantity!!, 1e-9)
        assertEquals("plain flour", i.name)
        assertTrue(i.note!!, i.note!!.contains("about 2 cups"))
    }

    @Test
    fun `a trailing clause is a note, not part of the food`() {
        val i = line("1 onion, finely chopped")
        assertEquals("onion", i.name)
        assertEquals("finely chopped", i.note)
    }

    @Test
    fun `a bullet character is not part of the ingredient`() {
        assertEquals("plain flour", line("• 200 g plain flour").name)
        assertEquals("plain flour", line("- 200 g plain flour").name)
    }

    @Test
    fun `a line it cannot read is kept whole rather than dropped`() {
        // ⚠️ A dropped line is an ingredient somebody does not know is missing. "Salt and pepper to
        // taste" has no quantity and is still part of the recipe.
        val i = line("Salt and pepper to taste")
        assertNull(i.quantity)
        assertEquals("Salt and pepper to taste", i.name)
    }

    @Test
    fun `an empty line is nothing at all`() {
        assertNull(RecipeImport.parseLine("   "))
        assertNull(RecipeImport.parseLine(""))
    }

    // ------------------------------------------------------------------------------- the page

    private fun bullets(vararg items: String) = Readability.Block.Bullets(items.toList(), false)
    private fun heading(t: String) = Readability.Block.Heading(t, 2)
    private fun para(t: String) = Readability.Block.Paragraph(t)

    @Test
    fun `a list under an Ingredients heading wins outright`() {
        // ⚠️ The method list here has MORE lines beginning with a number ("1. Heat the oven"), so a
        // pure count heuristic picks it. A page that labels its own list is telling us something no
        // heuristic can beat.
        val blocks = listOf(
            heading("Ingredients"),
            bullets("200 g flour", "2 eggs", "100 ml milk"),
            heading("Method"),
            bullets("1 Heat the oven", "2 Mix it", "3 Bake it", "4 Cool it", "5 Serve it"),
        )
        val import = RecipeImport.fromBlocks(blocks, "Pancakes", "https://example.test/p")!!
        assertEquals(3, import.ingredients.size)
        assertEquals("flour", import.ingredients[0].name)
    }

    @Test
    fun `a heading governs only the list directly under it`() {
        // Otherwise "Ingredients" would still be in force when the method list arrives, and a method
        // heading this file does not recognise would hand back the steps as ingredients.
        val blocks = listOf(
            heading("Ingredients"),
            bullets("200 g flour", "2 eggs", "100 ml milk"),
            bullets("Heat the oven", "Mix it", "Bake it"),
        )
        val import = RecipeImport.fromBlocks(blocks, "Pancakes", "https://example.test/p")!!
        assertEquals(3, import.ingredients.size)
        assertEquals("flour", import.ingredients[0].name)
    }

    @Test
    fun `with no heading the list with the most quantities wins`() {
        val blocks = listOf(
            bullets("Breakfast", "Lunch", "Dinner", "Pudding"),
            bullets("200 g flour", "2 eggs", "100 ml milk", "1 tsp salt"),
        )
        val import = RecipeImport.fromBlocks(blocks, "Pancakes", "https://example.test/p")!!
        assertEquals("flour", import.ingredients[0].name)
    }

    @Test
    fun `a short list is navigation rather than a recipe`() {
        // MIN_INGREDIENT_LINES is 3 and a share bar is two or three links. With nothing that clears
        // the bar, nothing is offered — which beats offering a share bar as an ingredient list.
        val blocks = listOf(bullets("Share", "Print"))
        assertNull(RecipeImport.fromBlocks(blocks, "Pancakes", "https://example.test/p"))
    }

    @Test
    fun `a page with lists but no quantities and no heading yields nothing`() {
        val blocks = listOf(bullets("Breakfast", "Lunch", "Dinner", "Pudding", "Snacks"))
        assertNull(RecipeImport.fromBlocks(blocks, "Recipes", "https://example.test/p"))
    }

    @Test
    fun `servings are read only when the page states them`() {
        val stated = listOf(
            para("Serves 4 as a main."),
            heading("Ingredients"),
            bullets("200 g flour", "2 eggs", "100 ml milk"),
        )
        assertEquals(4, RecipeImport.fromBlocks(stated, "P", "https://example.test/p")!!.servings)

        val silent = listOf(
            heading("Ingredients"),
            bullets("200 g flour", "2 eggs", "100 ml milk"),
        )
        // ⚠️ Null rather than a guess. Servings divides every number the builder produces, so a
        // wrong one is wrong in every direction at once.
        assertNull(RecipeImport.fromBlocks(silent, "P", "https://example.test/p")!!.servings)
    }

    @Test
    fun `the several ways a page says how many it feeds`() {
        for ((text, n) in listOf("Makes 12 buns" to 12, "8 servings" to 8, "6 portions" to 6)) {
            val blocks = listOf(
                para(text),
                heading("Ingredients"),
                bullets("200 g flour", "2 eggs", "100 ml milk"),
            )
            assertEquals(text, n, RecipeImport.fromBlocks(blocks, "P", "https://x.test")!!.servings)
        }
    }

    @Test
    fun `the import counts what still needs a weight, and says so`() {
        val blocks = listOf(
            heading("Ingredients"),
            bullets("200 g flour", "2 eggs", "100 ml milk", "1 tsp salt"),
        )
        val import = RecipeImport.fromBlocks(blocks, "Pancakes", "https://example.test/p")!!
        // Only the flour is a mass. Eggs are a count, the milk a volume, the salt a spoon.
        assertEquals(1, import.weighed)
        assertEquals(3, import.needsWeights)
        assertTrue(RecipeImport.sentence(import), RecipeImport.sentence(import).contains("3 need"))
    }

    @Test
    fun `an all-weighed import says nothing about needing weights`() {
        val blocks = listOf(
            heading("Ingredients"),
            bullets("200 g flour", "50 g butter", "100 g sugar"),
        )
        val import = RecipeImport.fromBlocks(blocks, "Shortbread", "https://example.test/p")!!
        assertEquals(3, import.weighed)
        assertEquals(0, import.needsWeights)
        assertTrue(RecipeImport.sentence(import), RecipeImport.sentence(import).contains("all weighed"))
    }

    @Test
    fun `a blank title is replaced rather than shown blank`() {
        val blocks = listOf(heading("Ingredients"), bullets("200 g flour", "2 eggs", "100 ml milk"))
        assertEquals("Imported recipe", RecipeImport.fromBlocks(blocks, "  ", "https://x.test")!!.title)
    }

    // ------------------------------------------------------------------------------ describing

    @Test
    fun `a line reads back the way the page wrote it`() {
        assertEquals("200 g plain flour", RecipeImport.describe(line("200 g plain flour")))
        assertEquals("2 onions", RecipeImport.describe(line("2 onions")))
        assertEquals("1.5 cup milk", RecipeImport.describe(line("1 1/2 cups milk")))
        assertEquals("Salt and pepper to taste", RecipeImport.describe(line("Salt and pepper to taste")))
    }
}
