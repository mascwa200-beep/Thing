package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reading a nutrition panel.
 *
 * ⚠️ The fixtures are real panel layouts rather than convenient ones. A parser tested only against
 * text shaped the way the parser expects proves that the parser agrees with itself, which is the
 * commonest way a text reader passes its tests and fails on a packet.
 */
class NutritionLabelTest {

    /** A United Kingdom panel: two columns, energy stated twice, comma-free decimals. */
    private val ukPanel = """
        Nutrition
        Typical values     per 100g     per 30g serving
        Energy             1046kJ / 250kcal     314kJ / 75kcal
        Fat                12.5g        3.8g
        of which saturates 2.1g         0.6g
        Carbohydrate       28.0g        8.4g
        of which sugars    3.2g         1.0g
        Fibre              4.1g         1.2g
        Protein            8.6g         2.6g
        Salt               0.75g        0.23g
    """.trimIndent()

    /** A United States panel: one column, per serving, calories with no unit beside them. */
    private val usPanel = """
        Nutrition Facts
        Serving size 55g
        Calories 210
        Total Fat 9g
        Saturated Fat 1.5g
        Sodium 180mg
        Total Carbohydrate 27g
        Dietary Fiber 3g
        Total Sugars 12g
        Protein 5g
    """.trimIndent()

    // ------------------------------------------------------------------------------------ numbers

    @Test
    fun `a comma is a decimal point unless it groups exactly three digits`() {
        // ⚠️ The single largest error this parser could make. Most of the world writes 12,5 g.
        assertEquals(listOf(12.5), NutritionLabel.numbers("Fett 12,5 g"))
        // Grouping: three digits then a non-digit. 1,046 kJ is one thousand and forty-six.
        assertEquals(listOf(1046.0), NutritionLabel.numbers("Energie 1,046 kJ"))
        // Four digits after the comma cannot be grouping, so it is a decimal.
        assertEquals(listOf(0.1234), NutritionLabel.numbers("0,1234"))
        // Two digits cannot be grouping either.
        assertEquals(listOf(1.75), NutritionLabel.numbers("1,75"))
        // A point stays a point.
        assertEquals(listOf(12.5), NutritionLabel.numbers("Fat 12.5 g"))
    }

    @Test
    fun `several numbers on a line come back in order`() {
        assertEquals(listOf(12.5, 3.8), NutritionLabel.numbers("Fat 12.5g 3.8g"))
    }

    // ---------------------------------------------------------------------------------- the basis

    @Test
    fun `a panel naming a per-100 column is read as a density`() {
        val r = NutritionLabel.read(ukPanel)!!
        assertEquals(NutritionLabel.Basis.PER_100, r.basis)
        assertTrue(r.notes.contains(NutritionLabel.Note.USED_PER_100_COLUMN))
    }

    @Test
    fun `a panel with no per-100 column is read per serving`() {
        val r = NutritionLabel.read(usPanel)!!
        assertEquals(NutritionLabel.Basis.PER_SERVING, r.basis)
        assertEquals(55.0, r.servingGrams!!, 1e-9)
    }

    // ----------------------------------------------------------------------------------- energy

    @Test
    fun `the calorie figure is preferred over the kilojoule one on the same line`() {
        // ⚠️ 1046 kJ / 4.184 = 250 kcal, so taking the FIRST number gives 1046 — four times too
        // large and still a plausible-looking calorie count, which is why it would go unnoticed.
        val r = NutritionLabel.read(ukPanel)!!
        assertEquals(250.0, r.nutrients.kcal, 1e-9)
        assertFalse(r.notes.contains(NutritionLabel.Note.CONVERTED_FROM_KJ))
    }

    @Test
    fun `kilojoules alone are converted, and the reading says so`() {
        val r = NutritionLabel.read(
            """
            per 100 g
            Energy 1046 kJ
            Protein 8.6 g
            """.trimIndent(),
        )!!
        // 1046 / 4.184 = 250.0 exactly.
        assertEquals(250.0, r.nutrients.kcal, 1e-6)
        assertTrue(r.notes.contains(NutritionLabel.Note.CONVERTED_FROM_KJ))
    }

    @Test
    fun `a bare calorie count with no unit is read`() {
        val r = NutritionLabel.read(usPanel)!!
        assertEquals(210.0, r.nutrients.kcal, 1e-9)
        assertFalse(r.notes.contains(NutritionLabel.Note.CONVERTED_FROM_KJ))
    }

    // ------------------------------------------------------------------------------- the macros

    @Test
    fun `the UK panel's per-100 column is read across every field`() {
        val r = NutritionLabel.read(ukPanel)!!
        assertEquals(12.5, r.nutrients.fatG, 1e-9)
        assertEquals(2.1, r.nutrients.satFatG, 1e-9)
        assertEquals(28.0, r.nutrients.carbG, 1e-9)
        assertEquals(3.2, r.nutrients.sugarG, 1e-9)
        assertEquals(4.1, r.nutrients.fibreG, 1e-9)
        assertEquals(8.6, r.nutrients.proteinG, 1e-9)
    }

    @Test
    fun `the US panel is read across every field`() {
        val r = NutritionLabel.read(usPanel)!!
        assertEquals(9.0, r.nutrients.fatG, 1e-9)
        assertEquals(1.5, r.nutrients.satFatG, 1e-9)
        assertEquals(27.0, r.nutrients.carbG, 1e-9)
        assertEquals(12.0, r.nutrients.sugarG, 1e-9)
        assertEquals(3.0, r.nutrients.fibreG, 1e-9)
        assertEquals(5.0, r.nutrients.proteinG, 1e-9)
    }

    @Test
    fun `saturates are never read as the total fat`() {
        // ⚠️ The collision this parser is most likely to get wrong in the direction that flatters
        // the food, and the reason a table of EVERY known phrase exists rather than one list per
        // nutrient. The saturates line is FIRST on purpose, and the total is written bare "Fat" —
        // both are load-bearing. With "Total Fat" the fat family's own longest-first ordering finds
        // the right line and the cross-family guard is never consulted, so a fixture written that
        // way passes whether the guard is there or not. Measured: that is exactly how this test
        // sat asleep. A bare total is also the commoner spelling outside the United States.
        val r = NutritionLabel.read(
            """
            per 100g
            Calories 210
            Saturated fat 1.5g
            Fat 9g
            Protein 5g
            """.trimIndent(),
        )!!
        assertEquals(9.0, r.nutrients.fatG, 1e-9)
        assertEquals(1.5, r.nutrients.satFatG, 1e-9)
    }

    @Test
    fun `sugars are never read as the carbohydrate total`() {
        val r = NutritionLabel.read(
            """
            per 100g
            Calories 210
            of which sugars 3.2g
            Carbohydrate 28.0g
            """.trimIndent(),
        )!!
        assertEquals(28.0, r.nutrients.carbG, 1e-9)
        assertEquals(3.2, r.nutrients.sugarG, 1e-9)
    }

    // ------------------------------------------------------------------------------ salt v sodium

    @Test
    fun `salt is converted to sodium rather than recorded as it`() {
        // ⚠️ 0.75 g of salt is 750 mg of salt, and 750 / 2.5421 = 295.0 mg of sodium. Recording the
        // salt figure as sodium overstates it by two and a half times, on the one figure a person
        // watching their blood pressure is reading the panel FOR.
        val r = NutritionLabel.read(ukPanel)!!
        assertEquals(750.0 / NutritionLabel.SALT_TO_SODIUM, r.nutrients.sodiumMg, 1e-6)
        assertEquals(295.03, r.nutrients.sodiumMg, 0.01)
    }

    @Test
    fun `a sodium figure in milligrams is taken as it stands`() {
        val r = NutritionLabel.read(usPanel)!!
        assertEquals(180.0, r.nutrients.sodiumMg, 1e-9)
    }

    @Test
    fun `a milligram figure is not recorded as grams`() {
        // A panel stating one nutrient in milligrams beside grams of everything else is ordinary,
        // and 380 GRAMS of anything is not a plausible food.
        val r = NutritionLabel.read(
            """
            per 100g
            Calories 100
            Protein 380mg
            """.trimIndent(),
        )!!
        assertEquals(0.38, r.nutrients.proteinG, 1e-9)
    }

    // -------------------------------------------------------------------- the reference-intake column

    @Test
    fun `the reference-intake percentage is not read as a quantity`() {
        // ⚠️ The 18 in `Fat 12.5g 18%` is not grams of anything, and while the quantity is read
        // alongside it the strip changes NOTHING — the first number on the line is the one taken.
        // The guard earns its place the moment the quantity is missing, which is ordinary rather
        // than hypothetical: on a United States panel the gram figure is bold against a shaded
        // rule and the percentage is plain text in its own column, so a photograph that loses one
        // of them loses that one. `Fat 18%` then reads as eighteen grams of fat, which is a number
        // nobody would query. So the fat line here carries only a percentage, and the protein line
        // carries both — the second half pins that the strip never eats a figure that IS a
        // quantity, which is the way a fix for this could go wrong.
        val r = NutritionLabel.read(
            """
            Serving size 30g
            Calories 210
            Fat 18%
            Protein 8.6g 17%
            """.trimIndent(),
        )!!
        assertEquals(0.0, r.nutrients.fatG, 1e-9)
        assertEquals(8.6, r.nutrients.proteinG, 1e-9)
    }

    // ------------------------------------------------------------------------------------ refusals

    @Test
    fun `text with no panel in it yields nothing`() {
        assertNull(NutritionLabel.read(""))
        assertNull(NutritionLabel.read("Ingredients: wheat flour, water, salt, yeast."))
    }

    @Test
    fun `per-serving figures with no serving weight are blocked rather than offered`() {
        // ⚠️ The most important refusal in the file. Without a weight these cannot become a density
        // by any honest route, and the app stores densities — so offering them would record a
        // portion as if it were a hundred grams.
        val r = NutritionLabel.read(
            """
            Nutrition Facts
            Serving size 2 biscuits
            Calories 210
            Total Fat 9g
            Protein 5g
            """.trimIndent(),
        )!!
        assertEquals(NutritionLabel.Basis.PER_SERVING, r.basis)
        assertNull("a count is not a weight", r.servingGrams)
        assertTrue(r.notes.contains(NutritionLabel.Note.SERVING_WITHOUT_WEIGHT))
        assertFalse(r.confident)
        assertNull("and it must not produce a density", NutritionLabel.per100g(r))
    }

    @Test
    fun `a stated weight lets the same panel become a density`() {
        val r = NutritionLabel.read(usPanel)!!
        val per100 = NutritionLabel.per100g(r)!!
        // 55 g serving, 210 kcal. Per 100 g: 210 * 100 / 55 = 381.8 kcal.
        assertEquals(381.818, per100.kcal, 0.01)
        // 9 g fat: 9 * 100 / 55 = 16.36 g.
        assertEquals(16.363, per100.fatG, 0.01)
    }

    @Test
    fun `a weight supplied by the caller overrides an absent one`() {
        val r = NutritionLabel.read(
            """
            Serving size 2 biscuits
            Calories 210
            Total Fat 9g
            """.trimIndent(),
        )!!
        assertNull(NutritionLabel.per100g(r))
        // 210 kcal in a 30 g serving is 700 kcal per 100 g.
        assertEquals(700.0, NutritionLabel.per100g(r, servingGramsOverride = 30.0)!!.kcal, 1e-6)
    }

    @Test
    fun `a per-100 panel is already a density and is handed back unchanged`() {
        val r = NutritionLabel.read(ukPanel)!!
        assertEquals(r.nutrients, NutritionLabel.per100g(r))
    }

    // -------------------------------------------------------------------------- the energy check

    @Test
    fun `macros that do not come to the stated calories are flagged, not refused`() {
        // 10 g protein (40) + 10 g fat (90) + 10 g carbohydrate (40) = 170 kcal. A stated 400 is
        // far outside what rounding or fibre could explain.
        val r = NutritionLabel.read(
            """
            per 100g
            Calories 400
            Total Fat 10g
            Total Carbohydrate 10g
            Protein 10g
            """.trimIndent(),
        )!!
        assertTrue(r.notes.contains(NutritionLabel.Note.ENERGY_DISAGREES))
        // ⚠️ Flagged and NOT blocking: the numbers are still four fields somebody does not have to
        // type, and a panel can round.
        assertTrue(r.confident)
    }

    @Test
    fun `a panel whose macros agree is not flagged`() {
        // 5 g protein (20) + 9 g fat (81) + 27 g carbohydrate (108) = 209 against a stated 210.
        val r = NutritionLabel.read(usPanel)!!
        assertFalse(r.notes.contains(NutritionLabel.Note.ENERGY_DISAGREES))
    }

    @Test
    fun `a partly-read panel is not flagged for disagreeing with itself`() {
        // ⚠️ A sum missing one of its terms disagrees by construction. Warning about that would fire
        // on every partial read and teach the reader to ignore the warning that matters.
        val r = NutritionLabel.read(
            """
            per 100g
            Calories 400
            Protein 10g
            """.trimIndent(),
        )!!
        assertFalse(r.notes.contains(NutritionLabel.Note.ENERGY_DISAGREES))
    }

    // ------------------------------------------------------------------------------------ summary

    @Test
    fun `the summary leads with the blocking note rather than burying it`() {
        val r = NutritionLabel.read(
            """
            Serving size 2 biscuits
            Calories 210
            Total Fat 9g
            """.trimIndent(),
        )!!
        assertEquals(NutritionLabel.Note.SERVING_WITHOUT_WEIGHT.sentence, NutritionLabel.summary(r))
    }

    @Test
    fun `the summary states the basis and the figures`() {
        val s = NutritionLabel.summary(NutritionLabel.read(ukPanel)!!)
        assertEquals("Read per 100 g — 250 kcal · P 8.6 · F 12.5 · C 28.", s)
    }

    // -------------------------------------------------------------------------- recogniser noise

    @Test
    fun `case and missing spaces do not stop a panel being read`() {
        val r = NutritionLabel.read(
            """
            PER 100G
            ENERGY 250KCAL
            FAT 12.5G
            PROTEIN 8.6G
            """.trimIndent(),
        )!!
        assertEquals(250.0, r.nutrients.kcal, 1e-9)
        assertEquals(12.5, r.nutrients.fatG, 1e-9)
        assertEquals(8.6, r.nutrients.proteinG, 1e-9)
    }

    @Test
    fun `a per-100 column is recognised however the panel writes it`() {
        for (t in listOf("per 100g", "per100 g", "/100ml", "per 100 ML", "100 g")) {
            assertTrue(t, NutritionLabel.mentionsPerHundred("Energy 250kcal\n$t"))
        }
        assertFalse(NutritionLabel.mentionsPerHundred("Serving size 55g\nCalories 210"))
    }

    @Test
    fun `a serving weight is found wherever the panel puts it`() {
        assertEquals(55.0, NutritionLabel.servingGrams(listOf("Serving size 55g"))!!, 1e-9)
        assertEquals(30.0, NutritionLabel.servingGrams(listOf("per 30g serving"))!!, 1e-9)
        assertEquals(250.0, NutritionLabel.servingGrams(listOf("Portion 250 ml"))!!, 1e-9)
        assertNull(NutritionLabel.servingGrams(listOf("Serving size 2 biscuits")))
        assertNull(NutritionLabel.servingGrams(listOf("Total Fat 9g")))
    }

    @Test
    fun `a reading with no energy at all is empty whatever else it holds`() {
        // ⚠️ Every panel in the world states energy. Grams of fat and no calories is a misread
        // panel, not a food — and a plausible-looking half-answer is worse than none.
        val r = NutritionLabel.Reading(
            basis = NutritionLabel.Basis.PER_100,
            nutrients = NutritionDay.Nutrients(proteinG = 8.6, fatG = 12.5),
        )
        assertTrue(r.isEmpty)
        assertFalse(r.confident)
        assertNull(NutritionLabel.per100g(r))
    }

    @Test
    fun `a real panel read end to end is confident`() {
        val r = NutritionLabel.read(ukPanel)
        assertNotNull(r)
        assertTrue(r!!.confident)
    }
}
