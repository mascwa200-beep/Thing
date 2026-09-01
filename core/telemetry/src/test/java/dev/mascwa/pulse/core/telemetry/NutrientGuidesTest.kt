package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ⚠️ Every expected number below was computed from the DEFINING formula before the assertion was
 * written, and the arithmetic is left in the comment. That habit exists because this project has
 * repeatedly had an expectation of mine turn out wrong where the shipped code was right.
 */
class NutrientGuidesTest {

    private val THIS_YEAR = 2026

    // --------------------------------------------------------------------------------- the maths

    @Test
    fun fibreScalesWithTheTarget() {
        // 2000/1000 * 14 = 28.0 ; 1600/1000 * 14 = 22.4
        assertEquals(28.0, NutrientGuides.fibre(2000)!!.amount, 1e-9)
        assertEquals(22.4, NutrientGuides.fibre(1600)!!.amount, 1e-9)
        assertEquals(NutrientGuides.Kind.TARGET, NutrientGuides.fibre(2000)!!.kind)
    }

    @Test
    fun saturatedFatIsATenthOfEnergyInGrams() {
        // 2000 * 0.10 / 9 = 22.222… ; 3000 * 0.10 / 9 = 33.333…
        assertEquals(22.2222222, NutrientGuides.saturatedFat(2000)!!.amount, 1e-6)
        assertEquals(33.3333333, NutrientGuides.saturatedFat(3000)!!.amount, 1e-6)
        assertEquals(NutrientGuides.Kind.LIMIT, NutrientGuides.saturatedFat(2000)!!.kind)
    }

    /** Flat, and the basis says so — a reader who changes their target must not expect this to move. */
    @Test
    fun sodiumDoesNotMoveWithTheTarget() {
        val g = NutrientGuides.sodium(birthYear = 1990, thisYear = THIS_YEAR)!!
        assertEquals(2000.0, g.amount, 1e-9)
        assertEquals(NutrientGuides.Kind.LIMIT, g.kind)
        assertTrue("the basis must say it is flat: ${g.basis}", g.basis.contains("does not move"))
    }

    // ------------------------------------------------------------------------------- the refusals

    /**
     * ⚠️ The load-bearing pair. A share of energy needs an energy figure, and substituting a default
     * would produce a number that looks measured and is invented.
     */
    @Test
    fun anEnergyShareIsWithheldWhenThereIsNoTarget() {
        assertNull(NutrientGuides.fibre(null))
        assertNull(NutrientGuides.saturatedFat(null))
        // A refused plan can also surface as a zero, and zero is not a target either.
        assertNull(NutrientGuides.fibre(0))
        assertNull(NutrientGuides.saturatedFat(0))
    }

    /**
     * ⚠️ An unstated birth year is treated exactly like a child's, because the adult figure is the
     * more generous one and a ceiling that is too high reads as permission.
     */
    @Test
    fun theAdultSodiumFigureIsWithheldFromChildrenAndFromSilence() {
        assertNull("unstated", NutrientGuides.sodium(birthYear = 0, thisYear = THIS_YEAR))
        assertNull("a ten-year-old", NutrientGuides.sodium(birthYear = 2016, thisYear = THIS_YEAR))
        // 2026 - 2008 = 18 exactly, the first year the adult guideline applies.
        assertNotNull("eighteen", NutrientGuides.sodium(birthYear = 2008, thisYear = THIS_YEAR))
        assertNull("seventeen", NutrientGuides.sodium(birthYear = 2009, thisYear = THIS_YEAR))
    }

    /**
     * ⚠️ The most important assertion in the file: sugar gets NO guide.
     *
     * Both sources publish total sugars; the guideline everyone quotes is about added sugars. Drawing
     * one against the other would tell somebody eating fruit they had breached a limit they had not
     * gone near, so the honest output is a total with its limitation attached and no bar beside it.
     */
    @Test
    fun sugarIsNeverGivenALimit() {
        val guides = NutrientGuides.forDay(
            eaten = NutritionDay.Nutrients(sugarG = 90.0),
            targetKcal = 2000,
            birthYear = 1990,
            thisYear = THIS_YEAR,
        )
        assertTrue(
            "no guide may be about sugar: ${guides.map { it.guide.label }}",
            guides.none { it.guide.label.contains("Sugar", ignoreCase = true) },
        )
        assertTrue(NutrientGuides.sugarNote.contains("added sugars"))
        assertTrue(NutrientGuides.sugarNote.contains("no limit"))
    }

    // ----------------------------------------------------------------------------------- the day

    @Test
    fun aFullProfileGetsAllThreeInReachThenAvoidOrder() {
        val guides = NutrientGuides.forDay(
            eaten = NutritionDay.Nutrients(fibreG = 18.0, satFatG = 12.0, sodiumMg = 1400.0),
            targetKcal = 2000, birthYear = 1990, thisYear = THIS_YEAR,
        )
        assertEquals(listOf("Fibre", "Saturated fat", "Sodium"), guides.map { it.guide.label })
        assertEquals(NutrientGuides.Kind.TARGET, guides[0].guide.kind)
        assertEquals(18.0, guides[0].eaten, 1e-9)
        assertEquals(1400.0, guides[2].eaten, 1e-9)
    }

    /** No target and no birth year is a brand-new install, and it must say nothing rather than guess. */
    @Test
    fun anEmptyProfileGetsNoGuidesAtAll() {
        assertTrue(
            NutrientGuides.forDay(NutritionDay.Nutrients(), null, birthYear = 0, thisYear = THIS_YEAR)
                .isEmpty(),
        )
    }

    /** With a target but no birth year, the two energy-based figures still stand on their own. */
    @Test
    fun apartialProfileGetsThePartItCanAnswer() {
        val guides = NutrientGuides.forDay(
            NutritionDay.Nutrients(), targetKcal = 2000, birthYear = 0, thisYear = THIS_YEAR,
        )
        assertEquals(listOf("Fibre", "Saturated fat"), guides.map { it.guide.label })
    }

    // ------------------------------------------------------------------------------- the sentence

    @Test
    fun theSentenceTurnsOnTheRightSideOfEachBoundary() {
        val fibre = NutrientGuides.fibre(2000)!!          // 28 g
        // 19/28 = 0.679 — below the 0.7 "most of the way" mark; 20/28 = 0.714 — above it.
        assertTrue(NutrientGuides.sentence(fibre, 19.0).startsWith("The usual reference"))
        assertTrue(NutrientGuides.sentence(fibre, 20.0).startsWith("Most of the way"))
        assertTrue(NutrientGuides.sentence(fibre, 28.0).startsWith("Past the usual reference"))

        val sodium = NutrientGuides.sodium(1990, THIS_YEAR)!!  // 2000 mg
        // 1600/2000 = 0.80 — below the 0.85 mark; 1700/2000 = 0.85 — exactly on it.
        assertTrue(NutrientGuides.sentence(sodium, 1600.0).startsWith("The usual ceiling"))
        assertTrue(NutrientGuides.sentence(sodium, 1700.0).startsWith("Close to the usual ceiling"))
        assertTrue(NutrientGuides.sentence(sodium, 2100.0).startsWith("Past the usual ceiling"))
    }

    /**
     * ⚠️ A TARGET reached and a LIMIT breached must never read alike. Both are `fraction >= 1`, and a
     * single shared wording would congratulate somebody for going past their sodium ceiling.
     */
    @Test
    fun reachingAFloorAndBreachingACeilingReadDifferently() {
        val reached = NutrientGuides.sentence(NutrientGuides.fibre(2000)!!, 30.0)
        val breached = NutrientGuides.sentence(NutrientGuides.sodium(1990, THIS_YEAR)!!, 2400.0)
        assertTrue(reached.contains("reference"))
        assertTrue(breached.contains("ceiling"))
        assertFalse("a floor reached must not be described as a ceiling", reached.contains("ceiling"))
    }

    /**
     * ⚠️ Not a style note. Praise on a day somebody has not finished eating is a judgement the data
     * cannot support, and scolding is the tone that gets a tracker deleted. This tab already tells a
     * real person how much to eat.
     */
    @Test
    fun noSentenceEverPraisesOrScolds() {
        val words = listOf(
            "well done", "good job", "great", "too much", "should", "shouldn't",
            "bad", "failed", "excessive", "unhealthy",
        )
        val everySentence = buildList {
            for (kcal in listOf(1500, 2000, 3000)) {
                for (eaten in listOf(0.0, 5.0, 20.0, 28.0, 60.0)) {
                    add(NutrientGuides.sentence(NutrientGuides.fibre(kcal)!!, eaten))
                    add(NutrientGuides.sentence(NutrientGuides.saturatedFat(kcal)!!, eaten))
                }
            }
            for (mg in listOf(0.0, 900.0, 1700.0, 2500.0)) {
                add(NutrientGuides.sentence(NutrientGuides.sodium(1990, THIS_YEAR)!!, mg))
            }
        }
        for (s in everySentence) {
            for (w in words) {
                assertFalse("must not say '$w': $s", s.lowercase().contains(w))
            }
        }
    }

    /** A guide with no amount cannot be divided by, and a NaN would render as a bar of nothing. */
    @Test
    fun aZeroAmountDoesNotProduceNaN() {
        val broken = NutrientGuides.Guide("X", 0.0, "g", NutrientGuides.Kind.TARGET, "", "")
        assertEquals(0.0, broken.fractionOf(10.0), 1e-9)
    }

    @Test
    fun theReadoutIsTheWholeComparison() {
        assertEquals("18 of 28 g", NutrientGuides.fibre(2000)!!.readout(18.0))
        assertEquals("1400 of 2000 mg", NutrientGuides.sodium(1990, THIS_YEAR)!!.readout(1400.0))
    }

    /**
     * ⚠️ **The point of [NutrientGuides.Nutrient] is that a caller can tell which of its own rows a
     * guide belongs to WITHOUT matching on display prose.** That only holds while every member is
     * actually produced and no two producers claim the same one — so both halves are pinned here.
     * A fourth member added without a producer, or a copy-paste that stamps saturated fat as fibre,
     * fails this rather than silently rendering one nutrient twice and another not at all.
     */
    @Test
    fun everyNutrientIsProducedExactlyOnceWhenEveryFactIsKnown() {
        val stamped = NutrientGuides.forDay(
            eaten = NutritionDay.Nutrients(),
            targetKcal = 2000,
            birthYear = 1990,
            thisYear = THIS_YEAR,
        ).map { it.guide.nutrient }
        assertEquals(NutrientGuides.Nutrient.entries.toSet(), stamped.toSet())
        assertEquals(NutrientGuides.Nutrient.entries.size, stamped.size)
    }

    /**
     * ⚠️ The property that makes [NutrientGuides.whyAbsent] trustworthy: a reason appears exactly
     * when the guide does not. If the two could disagree, a screen would show a comparison AND a
     * sentence saying no comparison could be made, or worse, neither.
     */
    @Test
    fun aReasonIsGivenExactlyWhenTheGuideIsMissing() {
        for (n in NutrientGuides.Nutrient.entries) {
            for (target in listOf(null, 0, 2000)) {
                for (birthYear in listOf(0, THIS_YEAR - 10, 1990)) {
                    val stated = NutrientGuides.forDay(
                        eaten = NutritionDay.Nutrients(),
                        targetKcal = target,
                        birthYear = birthYear,
                        thisYear = THIS_YEAR,
                    ).any { it.guide.nutrient == n }
                    val why = NutrientGuides.whyAbsent(n, target, birthYear, THIS_YEAR)
                    assertEquals(
                        "$n at target=$target birthYear=$birthYear: stated=$stated, why=$why",
                        stated,
                        why == null,
                    )
                }
            }
        }
    }

    /** And the reason names the fact that is actually missing, not a generic one. */
    @Test
    fun theReasonNamesTheMissingFact() {
        // No target: fibre and saturated fat are shares of energy; sodium is not, so with an adult
        // birth year it is stated and has no reason at all.
        assertEquals(
            NutrientGuides.NO_TARGET,
            NutrientGuides.whyAbsent(NutrientGuides.Nutrient.FIBRE, null, 1990, THIS_YEAR),
        )
        assertEquals(
            NutrientGuides.NO_TARGET,
            NutrientGuides.whyAbsent(NutrientGuides.Nutrient.SATURATED_FAT, null, 1990, THIS_YEAR),
        )
        assertNull(NutrientGuides.whyAbsent(NutrientGuides.Nutrient.SODIUM, null, 1990, THIS_YEAR))

        // No birth year: the reverse. Sodium alone is withheld.
        assertNull(NutrientGuides.whyAbsent(NutrientGuides.Nutrient.FIBRE, 2000, 0, THIS_YEAR))
        assertEquals(
            NutrientGuides.NO_ADULT_AGE,
            NutrientGuides.whyAbsent(NutrientGuides.Nutrient.SODIUM, 2000, 0, THIS_YEAR),
        )
    }

    /**
     * ⚠️ [NutrientGuides.Nutrient.eatenIn] and [NutrientGuides.forDay] must read the same field, or
     * a screen showing a total beside a comparison would show two different numbers for one
     * nutrient. Distinct values on every field, so a copy-paste that reads fibre for saturated fat
     * fails rather than passing on a fixture where they happen to match.
     */
    @Test
    fun theEnumReadsTheSameFieldTheServingDoes() {
        val day = NutritionDay.Nutrients(fibreG = 11.0, satFatG = 22.0, sodiumMg = 3300.0)
        assertEquals(11.0, NutrientGuides.Nutrient.FIBRE.eatenIn(day), 1e-9)
        assertEquals(22.0, NutrientGuides.Nutrient.SATURATED_FAT.eatenIn(day), 1e-9)
        assertEquals(3300.0, NutrientGuides.Nutrient.SODIUM.eatenIn(day), 1e-9)

        for (s in NutrientGuides.forDay(day, targetKcal = 2000, birthYear = 1990, thisYear = THIS_YEAR)) {
            assertEquals(s.guide.label, s.guide.nutrient!!.eatenIn(day), s.eaten, 1e-9)
            // And the guide's display strings come from the enum rather than a second copy.
            assertEquals(s.guide.nutrient!!.label, s.guide.label)
            assertEquals(s.guide.nutrient!!.unit, s.guide.unit)
        }
    }

    /** And each producer stamps its own, which is what makes the lookup mean anything. */
    @Test
    fun eachProducerStampsItsOwnNutrient() {
        assertEquals(NutrientGuides.Nutrient.FIBRE, NutrientGuides.fibre(2000)!!.nutrient)
        assertEquals(
            NutrientGuides.Nutrient.SATURATED_FAT,
            NutrientGuides.saturatedFat(2000)!!.nutrient,
        )
        assertEquals(
            NutrientGuides.Nutrient.SODIUM,
            NutrientGuides.sodium(1990, THIS_YEAR)!!.nutrient,
        )
    }
}
