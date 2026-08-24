package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private fun read(raw: String): List<MealPhoto.Item> =
    (MealPhoto.parse(raw) as MealPhoto.Outcome.Read).items

class MealPhotoTest {

    /** The shape the prompt asks for, read back whole. */
    @Test
    fun anAnswerInTheRequestedShapeIsReadBackWhole() {
        val items = read(
            """
            scrambled eggs | 120 | sure
            wholemeal toast | 40 | sure
            butter | 8 | guess
            """.trimIndent()
        )
        assertEquals(3, items.size)
        assertEquals("scrambled eggs", items[0].name)
        assertEquals(120.0, items[0].grams, 1e-9)
        assertEquals(MealPhoto.Confidence.SEEN, items[0].confidence)
        assertEquals(MealPhoto.Confidence.GUESSED, items[2].confidence)
    }

    /**
     * ⚠️ **A line that is not an item is skipped, not guessed at.**
     *
     * Models add a closing sentence more often than not, and reading "Let me know if you'd like the
     * totals!" as a food is how a plate ends up with an item nobody can explain. A line only counts
     * when it carries a name and a number in the shape that was asked for.
     */
    @Test
    fun proseAroundTheListIsIgnoredRatherThanReadAsFood() {
        val items = read(
            """
            Here is what I can see on the plate:

            grilled chicken breast | 150 | sure
            steamed broccoli | 90 | sure

            Let me know if you would like the totals!
            That comes to roughly 300 calories.
            """.trimIndent()
        )
        assertEquals(2, items.size)
        assertEquals("grilled chicken breast", items[0].name)
        assertEquals("steamed broccoli", items[1].name)
    }

    /** A numbered or bulleted list is a list, and the marker is not part of the food's name. */
    @Test
    fun aListMarkerIsNotPartOfTheName() {
        val items = read(
            """
            1. brown rice | 180 | sure
            2) black beans | 130 | guess
            - salsa | 30 | guess
            * sour cream | 25 | guess
            """.trimIndent()
        )
        assertEquals(listOf("brown rice", "black beans", "salsa", "sour cream"), items.map { it.name })
    }

    /** The model looking and finding no food is its own answer, not a failure to parse. */
    @Test
    fun aPictureThatIsNotFoodSaysSoRatherThanFailing() {
        assertTrue(MealPhoto.parse("NONE") is MealPhoto.Outcome.NotFood)
        assertTrue(MealPhoto.parse("  none  ") is MealPhoto.Outcome.NotFood)
        assertTrue(
            MealPhoto.parse("NONE\nThat looks like a photograph of a bicycle.")
                is MealPhoto.Outcome.NotFood
        )
    }

    /** Nothing readable at all is distinct from "no food here", because they mean different things. */
    @Test
    fun anAnswerWithNoItemsInItIsUnreadableRatherThanEmpty() {
        assertTrue(MealPhoto.parse("") is MealPhoto.Outcome.Unreadable)
        assertTrue(MealPhoto.parse("I am not able to help with that.") is MealPhoto.Outcome.Unreadable)
        val u = MealPhoto.parse("a wall of prose with no pipes in it at all")
        assertEquals("a wall of prose with no pipes in it at all", (u as MealPhoto.Outcome.Unreadable).raw)
    }

    /**
     * ⚠️ **An absurd weight is DROPPED, not clamped.**
     *
     * Clamping 50,000 g to the 3,000 g ceiling would invent a portion out of a value that was
     * plainly wrong, and it would look exactly like a measured one. Dropping the line leaves the
     * person to add what they actually ate.
     */
    @Test
    fun anImpossibleWeightIsDroppedRatherThanClampedIntoSomethingPlausible() {
        val items = read(
            """
            rice | 50000 | guess
            chicken | 0 | guess
            broccoli | -20 | guess
            carrots | 85 | sure
            """.trimIndent()
        )
        assertEquals(1, items.size)
        assertEquals("carrots", items[0].name)
        assertEquals(85.0, items[0].grams, 1e-9)
    }

    /** A line with no number, or nothing but a number, is not an item. */
    @Test
    fun aLineMissingEitherHalfIsNotAnItem() {
        assertTrue(MealPhoto.parse("just a name with no weight") is MealPhoto.Outcome.Unreadable)
        assertTrue(MealPhoto.parse("| 120 | sure") is MealPhoto.Outcome.Unreadable)
        assertTrue(MealPhoto.parse("chicken | not a number | sure") is MealPhoto.Outcome.Unreadable)
    }

    /** A model describing a buffet is capped rather than allowed to fill the log. */
    @Test
    fun theNumberOfItemsIsBounded() {
        val many = (1..40).joinToString("\n") { "food $it | 50 | guess" }
        assertEquals(MealPhoto.MAX_ITEMS, read(many).size)
    }

    /**
     * The summary says how many were guessed, because that is the number deciding how carefully
     * somebody should read the list before confirming it.
     */
    @Test
    fun theSummarySaysHowMuchOfItWasEstimated() {
        val seen = listOf(MealPhoto.Item("a", 10.0, MealPhoto.Confidence.SEEN))
        val guessed = listOf(MealPhoto.Item("a", 10.0), MealPhoto.Item("b", 10.0))
        val mixed = seen + guessed
        assertEquals("1 item — check the weights before logging.", MealPhoto.summary(seen))
        assertEquals(
            "2 items, all estimated — check every weight before logging.",
            MealPhoto.summary(guessed),
        )
        assertEquals(
            "3 items, 2 estimated — check the weights before logging.",
            MealPhoto.summary(mixed),
        )
        assertEquals("Nothing recognised.", MealPhoto.summary(emptyList()))
    }

    /**
     * ⚠️ **The prompt must never ask for nutrient figures**, and this is the guard on the whole
     * design. A model answering "320 kcal" has weighed nothing and read no label, and that number
     * would enter the log beside laboratory analyses and be indistinguishable from them. The model
     * names foods; the numbers come from real records.
     */
    @Test
    fun thePromptAsksForNamesAndWeightsAndNeverForNutrition() {
        val p = MealPhoto.PROMPT.lowercase()
        for (forbidden in listOf("calorie", "kcal", "protein", "carb", "fat ", "nutrition")) {
            assertTrue(
                "the prompt must not ask for '$forbidden' — a model cannot measure it",
                !p.contains(forbidden),
            )
        }
        assertTrue(p.contains("grams"))
        assertTrue(p.contains("one line per food"))
    }
}
