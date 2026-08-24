package dev.mascwa.pulse.core.telemetry

import dev.mascwa.pulse.core.telemetry.Micronutrients.Micro
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun amounts(vararg p: Pair<Micro, Double>) = Micronutrients.Amounts(mapOf(*p))

private fun amount(m: Micro, sex: Body.Sex, age: Int): Double? =
    (Micronutrients.reference(m, sex, age) as? Micronutrients.Reference.Amount)?.guide?.amount

class MicronutrientsTest {

    // ---- absence is not zero, which is the reason the type exists ---------------------------

    /**
     * ⚠️ **The property everything else rests on.** Three records in four say nothing about
     * calcium, and a double defaulting to 0.0 would render that as "0 mg" — a measurement this app
     * never took, printed with the same confidence as one it did.
     */
    @Test
    fun aFigureNobodyRecordedIsAbsentRatherThanZero() {
        val a = amounts(Micro.IRON to 2.5)
        assertEquals(2.5, a[Micro.IRON]!!, 1e-9)
        assertNull("calcium was never measured, so there is no number to give", a[Micro.CALCIUM])
        assertTrue(Micronutrients.Amounts().isEmpty)
    }

    /** Scaling a portion keeps absences absent rather than inventing zeros to multiply. */
    @Test
    fun scalingAPortionDoesNotInventFiguresItNeverHad() {
        val per100 = amounts(Micro.CALCIUM to 120.0, Micro.IRON to 0.5)
        val eaten = per100.scaled(2.5) // 250 g
        assertEquals(300.0, eaten[Micro.CALCIUM]!!, 1e-9)
        assertEquals(1.25, eaten[Micro.IRON]!!, 1e-9)
        assertNull(eaten[Micro.VITAMIN_D])
        // A nonsensical factor yields nothing at all rather than nonsense figures.
        assertTrue(per100.scaled(Double.NaN).isEmpty)
        assertTrue(per100.scaled(-1.0).isEmpty)
    }

    // ---- a day says how much of itself it measured ------------------------------------------

    /**
     * ⚠️ **A food that reported nothing still counts as a food eaten.** Leaving it out of the
     * denominator would report perfect coverage for a day mostly made of records that say nothing —
     * the exact overstatement `reported` exists to prevent.
     */
    @Test
    fun aFoodThatReportsNothingStillCountsTowardTheDay() {
        val day = Micronutrients.of(
            listOf(
                amounts(Micro.CALCIUM to 200.0),
                Micronutrients.Amounts(),
                Micronutrients.Amounts(),
                Micronutrients.Amounts(),
            )
        )
        assertEquals(4, day.entries)
        assertEquals(200.0, day[Micro.CALCIUM]!!.total, 1e-9)
        assertEquals(1, day[Micro.CALCIUM]!!.reported)
        assertEquals(0.25, day.coverage(Micro.CALCIUM)!!, 1e-9)
    }

    /** Totals accumulate across the foods that reported, and only those. */
    @Test
    fun aDayAddsUpWhatWasReportedAndCountsHowManySaid() {
        val day = Micronutrients.of(
            listOf(
                amounts(Micro.IRON to 1.0, Micro.VITAMIN_C to 10.0),
                amounts(Micro.IRON to 2.5),
                amounts(Micro.VITAMIN_C to 5.0),
            )
        )
        assertEquals(3, day.entries)
        assertEquals(3.5, day[Micro.IRON]!!.total, 1e-9)
        assertEquals(2, day[Micro.IRON]!!.reported)
        assertEquals(15.0, day[Micro.VITAMIN_C]!!.total, 1e-9)
        assertNull("nothing reported vitamin D, so there is no tally", day[Micro.VITAMIN_D])
        assertEquals(0.0, day.coverage(Micro.VITAMIN_D)!!, 1e-9)
    }

    /** Nothing logged is not the same as nothing reported, and they must not render alike. */
    @Test
    fun anEmptyDayHasNoCoverageRatherThanZeroCoverage() {
        val empty = Micronutrients.Day()
        assertNull(empty.coverage(Micro.IRON))
        assertNull(empty.caveat(Micro.IRON))
        assertEquals(0.0, Micronutrients.of(listOf(Micronutrients.Amounts())).coverage(Micro.IRON)!!, 1e-9)
    }

    /**
     * The caveat is silent when the figure stands on its own, and says so when it does not.
     *
     * ⚠️ Silence above the threshold is deliberate: a caveat on every row is one nobody reads.
     */
    @Test
    fun theCaveatSpeaksOnlyWhenTheFigureNeedsIt() {
        // 5 of 5 -> coverage 1.0, comfortably above WELL_COVERED (0.8), so no caveat.
        val full = Micronutrients.of(List(5) { amounts(Micro.IRON to 1.0) })
        assertNull(full.caveat(Micro.IRON))
        // 2 of 5 = 0.4 -> below the threshold, so the reader is told.
        val thin = Micronutrients.of(
            List(2) { amounts(Micro.IRON to 1.0) } + List(3) { Micronutrients.Amounts() }
        )
        assertEquals("From 2 of 5 foods — the rest do not record it.", thin.caveat(Micro.IRON))
        assertEquals("None of today's food records this.", thin.caveat(Micro.VITAMIN_A))
    }

    // ---- reference intakes -------------------------------------------------------------------

    /**
     * ⚠️ **The two refusals are the most important thing this file says.**
     *
     * The 300 mg cholesterol ceiling was withdrawn from the US Dietary Guidelines in 2015, so
     * printing it would be quoting a rule that no longer exists. Trans fat has no allowance at all
     * — the guidance is elimination — and a budget on screen reads as permission to spend it.
     */
    @Test
    fun cholesterolAndTransFatHaveNoFigureAndSayWhy() {
        // ⚠️ The EXACT note, not merely "it refused". An earlier version of this test asserted only
        // `is None` with a length bound, and a perturbation that deleted the cholesterol branch
        // passed it: the value fell through to a generic "no reference intake is published"
        // and the test could not tell the difference. Refusing is half the job — refusing for the
        // stated, correct reason is the half a reader actually gets.
        for ((m, note) in listOf(
            Micro.CHOLESTEROL to Micronutrients.CHOLESTEROL_NOTE,
            Micro.TRANS_FAT to Micronutrients.TRANS_FAT_NOTE,
        )) {
            for (age in listOf(25, 60, 80)) {
                for (s in Body.Sex.entries) {
                    val r = Micronutrients.reference(m, s, age)
                    assertTrue("$m/$s/$age must refuse", r is Micronutrients.Reference.None)
                    assertEquals(
                        "$m/$s/$age must give its own reason",
                        note,
                        (r as Micronutrients.Reference.None).why,
                    )
                }
            }
        }
        assertTrue(Micronutrients.CHOLESTEROL_NOTE.contains("2015"))
        assertTrue(Micronutrients.TRANS_FAT_NOTE.contains("no daily allowance"))
    }

    /**
     * Every figure here is written for adults, so a child — or an unstated birth year, which is
     * indistinguishable from one — gets the explanation rather than a misapplied number.
     */
    @Test
    fun anAdultFigureIsWithheldFromSomebodyWhoMayNotBeOne() {
        for (age in listOf(0, 12, 18)) {
            val r = Micronutrients.reference(Micro.CALCIUM, Body.Sex.FEMALE, age)
            assertTrue("age $age must be withheld", r is Micronutrients.Reference.None)
        }
        assertTrue(
            Micronutrients.reference(Micro.CALCIUM, Body.Sex.FEMALE, Micronutrients.ADULT_FROM)
                is Micronutrients.Reference.Amount
        )
    }

    /**
     * The published adult figures, each one written here from the reference table it comes from.
     *
     * Iron: men 8 mg at every adult age; women 18 mg to 50 and 8 mg from 51.
     * Vitamin C: men 90 mg, women 75 mg. Vitamin A: 900 and 700 µg RAE.
     * Potassium (Adequate Intake): 3,400 and 2,600 mg.
     * Calcium: 1,000 mg, rising to 1,200 for women from 51 and for men from 71.
     * Vitamin D: 15 µg to 70, 20 µg from 71 — the same for both.
     */
    @Test
    fun theAdultFiguresAreTheOnesThatArePublished() {
        assertEquals(8.0, amount(Micro.IRON, Body.Sex.MALE, 30)!!, 1e-9)
        assertEquals(18.0, amount(Micro.IRON, Body.Sex.FEMALE, 30)!!, 1e-9)
        assertEquals(8.0, amount(Micro.IRON, Body.Sex.FEMALE, 60)!!, 1e-9)

        assertEquals(90.0, amount(Micro.VITAMIN_C, Body.Sex.MALE, 30)!!, 1e-9)
        assertEquals(75.0, amount(Micro.VITAMIN_C, Body.Sex.FEMALE, 30)!!, 1e-9)
        assertEquals(900.0, amount(Micro.VITAMIN_A, Body.Sex.MALE, 30)!!, 1e-9)
        assertEquals(700.0, amount(Micro.VITAMIN_A, Body.Sex.FEMALE, 30)!!, 1e-9)
        assertEquals(3400.0, amount(Micro.POTASSIUM, Body.Sex.MALE, 30)!!, 1e-9)
        assertEquals(2600.0, amount(Micro.POTASSIUM, Body.Sex.FEMALE, 30)!!, 1e-9)

        assertEquals(1000.0, amount(Micro.CALCIUM, Body.Sex.FEMALE, 40)!!, 1e-9)
        assertEquals(1200.0, amount(Micro.CALCIUM, Body.Sex.FEMALE, 55)!!, 1e-9)
        assertEquals(1000.0, amount(Micro.CALCIUM, Body.Sex.MALE, 55)!!, 1e-9)
        assertEquals(1200.0, amount(Micro.CALCIUM, Body.Sex.MALE, 75)!!, 1e-9)

        assertEquals(15.0, amount(Micro.VITAMIN_D, Body.Sex.MALE, 40)!!, 1e-9)
        assertEquals(20.0, amount(Micro.VITAMIN_D, Body.Sex.FEMALE, 75)!!, 1e-9)
    }

    /**
     * ⚠️ **An unstated sex takes the HIGHER figure, and that is only safe because every figure here
     * is a target rather than a limit.** Quietly halving somebody's iron target would be a real
     * disservice; aiming a little high on a nutrient obtained from food costs nothing.
     */
    @Test
    fun anUnstatedSexAimsHighAndSaysThatIsWhatItDid() {
        assertEquals(18.0, amount(Micro.IRON, Body.Sex.UNSPECIFIED, 30)!!, 1e-9)
        assertEquals(3400.0, amount(Micro.POTASSIUM, Body.Sex.UNSPECIFIED, 30)!!, 1e-9)
        assertEquals(900.0, amount(Micro.VITAMIN_A, Body.Sex.UNSPECIFIED, 30)!!, 1e-9)
        val r = Micronutrients.reference(Micro.IRON, Body.Sex.UNSPECIFIED, 30)
        assertTrue((r as Micronutrients.Reference.Amount).guide.basis.contains("higher"))
        assertEquals(NutrientGuides.Kind.TARGET, r.guide.kind)
    }

    /**
     * ⚠️ ...and it does NOT claim to have chosen when there was nothing to choose between.
     *
     * Vitamin D is the same 15 µg for everyone, so "the higher of the two adult references" would
     * describe a decision that was never made — which reads as a fault rather than as care. Iron
     * from 51 is the same shape: both figures are 8 mg.
     */
    @Test
    fun itDoesNotClaimAChoiceItNeverHadToMake() {
        val d = Micronutrients.reference(Micro.VITAMIN_D, Body.Sex.UNSPECIFIED, 40)
        assertEquals(
            "The usual reference for an adult.",
            (d as Micronutrients.Reference.Amount).guide.basis,
        )
        val iron51 = Micronutrients.reference(Micro.IRON, Body.Sex.UNSPECIFIED, 60)
        assertEquals(
            "The usual reference for an adult.",
            (iron51 as Micronutrients.Reference.Amount).guide.basis,
        )
    }

    /** Every micronutrient the corpus carries can be asked about without throwing. */
    @Test
    fun everyMicroAnswersForEveryProfile() {
        for (m in Micro.entries) {
            for (s in Body.Sex.entries) {
                for (age in listOf(0, 25, 60, 80)) {
                    assertNotNull("$m/$s/$age", Micronutrients.reference(m, s, age))
                }
            }
            assertTrue(m.label.isNotBlank())
            assertTrue(m.unit.isNotBlank())
        }
    }

    /** The row text carries both halves of the comparison, and drops the second when there is none. */
    @Test
    fun theReadoutSaysBothNumbersOrJustTheOne() {
        val g = (Micronutrients.reference(Micro.CALCIUM, Body.Sex.MALE, 40)
            as Micronutrients.Reference.Amount).guide
        assertEquals("310 of 1000 mg", Micronutrients.readout(Micro.CALCIUM, 310.0, g))
        assertEquals("310 mg", Micronutrients.readout(Micro.CALCIUM, 310.0, null))
        assertEquals("2.5 mg", Micronutrients.readout(Micro.IRON, 2.46, null))
    }
}
