package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LifeStatsTest {

    // --- Classification ---

    @Test fun blankProfileIsFullyNeutral() {
        val p = LifeProfile()
        assertNull(LifeStats.bmi(p))
        assertEquals(Build.UNSET, LifeStats.build(p))
        assertEquals(AgeBand.UNSET, LifeStats.ageBand(p))
        assertEquals(MoneyTier.BROKE, LifeStats.moneyTier(p))
        assertTrue(LifeStats.effects(p).isEmpty())
        assertEquals(0, LifeStats.capsBonusPct(p))
        Special.entries.forEach { assertEquals(0, LifeStats.statBonus(p, it)) }
    }

    @Test fun bmiComputed() {
        // 180 cm, 90 kg → 90 / 1.8² = 27.78
        val b = LifeStats.bmi(LifeProfile(heightCm = 180, weightKg = 90))!!
        assertEquals(27.78, b, 0.01)
    }

    @Test fun buildBands() {
        assertEquals(Build.FEATHERWEIGHT, LifeStats.build(LifeProfile(heightCm = 180, weightKg = 55)))
        assertEquals(Build.ATHLETIC, LifeStats.build(LifeProfile(heightCm = 180, weightKg = 72)))
        assertEquals(Build.RUGGED, LifeStats.build(LifeProfile(heightCm = 180, weightKg = 90)))
        assertEquals(Build.POWERHOUSE, LifeStats.build(LifeProfile(heightCm = 180, weightKg = 110)))
        // Only one of height/weight set → still UNSET.
        assertEquals(Build.UNSET, LifeStats.build(LifeProfile(heightCm = 180)))
    }

    @Test fun ageBands() {
        assertEquals(AgeBand.YOUNG, LifeStats.ageBand(LifeProfile(ageYears = 20)))
        assertEquals(AgeBand.PRIME, LifeStats.ageBand(LifeProfile(ageYears = 30)))
        assertEquals(AgeBand.SEASONED, LifeStats.ageBand(LifeProfile(ageYears = 50)))
        assertEquals(AgeBand.VETERAN, LifeStats.ageBand(LifeProfile(ageYears = 70)))
    }

    @Test fun moneyTiers() {
        assertEquals(MoneyTier.BROKE, LifeStats.moneyTier(LifeProfile(realMoney = 50.0)))
        assertEquals(MoneyTier.STEADY, LifeStats.moneyTier(LifeProfile(realMoney = 500.0)))
        assertEquals(MoneyTier.COMFORTABLE, LifeStats.moneyTier(LifeProfile(realMoney = 5_000.0)))
        assertEquals(MoneyTier.FLUSH, LifeStats.moneyTier(LifeProfile(realMoney = 50_000.0)))
        assertEquals(MoneyTier.LOADED, LifeStats.moneyTier(LifeProfile(realMoney = 500_000.0)))
    }

    @Test fun capsBonusByTier() {
        assertEquals(0, LifeStats.capsBonusPct(LifeProfile(realMoney = 50.0)))
        assertEquals(0, LifeStats.capsBonusPct(LifeProfile(realMoney = 500.0)))
        assertEquals(10, LifeStats.capsBonusPct(LifeProfile(realMoney = 5_000.0)))
        assertEquals(20, LifeStats.capsBonusPct(LifeProfile(realMoney = 50_000.0)))
        assertEquals(35, LifeStats.capsBonusPct(LifeProfile(realMoney = 500_000.0)))
    }

    // --- Effects ---

    @Test fun athleticBuildBoostsEndurance() {
        assertEquals(1, LifeStats.statBonus(LifeProfile(heightCm = 180, weightKg = 72), Special.ENDURANCE))
    }

    @Test fun powerhouseTradesAgilityForStrength() {
        val p = LifeProfile(heightCm = 180, weightKg = 110)
        assertEquals(2, LifeStats.statBonus(p, Special.STRENGTH))
        assertEquals(-1, LifeStats.statBonus(p, Special.AGILITY))
    }

    @Test fun loadedMoneyBuffsCharismaLuckIntelligence() {
        val p = LifeProfile(realMoney = 250_000.0)
        assertEquals(2, LifeStats.statBonus(p, Special.CHARISMA))
        assertEquals(2, LifeStats.statBonus(p, Special.LUCK))
        assertEquals(1, LifeStats.statBonus(p, Special.INTELLIGENCE))
    }

    @Test fun effectsStackAcrossBuildAndAge() {
        // Athletic (END +1) + prime age (END +1) → END +2.
        val p = LifeProfile(heightCm = 180, weightKg = 72, ageYears = 30)
        assertEquals(2, LifeStats.statBonus(p, Special.ENDURANCE))
    }

    @Test fun lowHydrationTaxesEndurance() {
        assertEquals(-1, LifeStats.statBonus(LifeProfile(hydration = 25), Special.ENDURANCE))
    }

    @Test fun criticalHydrationTaxesEnduranceAndStrength() {
        val p = LifeProfile(hydration = 10)
        assertEquals(-2, LifeStats.statBonus(p, Special.ENDURANCE))
        assertEquals(-1, LifeStats.statBonus(p, Special.STRENGTH))
    }

    @Test fun poorHygieneTaxesCharisma() {
        assertEquals(-1, LifeStats.statBonus(LifeProfile(hygiene = 25), Special.CHARISMA))
        assertEquals(-2, LifeStats.statBonus(LifeProfile(hygiene = 10), Special.CHARISMA))
    }

    @Test fun lowEnergyTaxesAgilityThenIntelligence() {
        assertEquals(-1, LifeStats.statBonus(LifeProfile(energy = 25), Special.AGILITY))
        val spent = LifeProfile(energy = 10)
        assertEquals(-2, LifeStats.statBonus(spent, Special.AGILITY))
        assertEquals(-1, LifeStats.statBonus(spent, Special.INTELLIGENCE))
    }

    @Test fun lowNourishmentTaxesStrengthThenEndurance() {
        assertEquals(-1, LifeStats.statBonus(LifeProfile(nourishment = 25), Special.STRENGTH))
        val starving = LifeProfile(nourishment = 10)
        assertEquals(-2, LifeStats.statBonus(starving, Special.STRENGTH))
        assertEquals(-1, LifeStats.statBonus(starving, Special.ENDURANCE))
    }

    @Test fun moodOnlyBendsAtExtremes() {
        // Neutral mood (default 50) → no effect.
        assertEquals(0, LifeStats.statBonus(LifeProfile(mood = 50), Special.CHARISMA))
        // High spirits → CHA +1, LUCK +1.
        val happy = LifeProfile(mood = 90)
        assertEquals(1, LifeStats.statBonus(happy, Special.CHARISMA))
        assertEquals(1, LifeStats.statBonus(happy, Special.LUCK))
        // Low spirits → CHA −1.
        assertEquals(-1, LifeStats.statBonus(LifeProfile(mood = 10), Special.CHARISMA))
    }

    @Test fun describeLabelsEffects() {
        val lines = LifeStats.describe(LifeProfile(realMoney = 500.0))
        assertTrue(lines.any { it.contains("CHA +1") && it.contains("Coin in your pocket") })
    }

    // --- Needs upkeep ---

    @Test fun needsDecayOverTime() {
        val p = LifeStats.decayNeeds(LifeProfile(hydration = 100, hygiene = 100), elapsedMs = 5L * 3_600_000L)
        assertEquals(80, p.hydration) // 5h × 4/h
        assertEquals(90, p.hygiene)   // 5h × 2/h
    }

    @Test fun needsClampAtZero() {
        val p = LifeStats.decayNeeds(LifeProfile(hydration = 100, hygiene = 100), elapsedMs = 30L * 3_600_000L)
        assertEquals(0, p.hydration)  // would be 100 − 120
        assertEquals(40, p.hygiene)   // 100 − 60
    }

    @Test fun zeroElapsedLeavesNeedsUnchanged() {
        val p = LifeProfile(hydration = 42, hygiene = 17)
        assertEquals(p, LifeStats.decayNeeds(p, 0L))
    }

    @Test fun drinkWashRestEatRestore() {
        assertEquals(100, LifeStats.drink(LifeProfile(hydration = 5)).hydration)
        assertEquals(100, LifeStats.wash(LifeProfile(hygiene = 5)).hygiene)
        assertEquals(100, LifeStats.rest(LifeProfile(energy = 5)).energy)
        assertEquals(100, LifeStats.eat(LifeProfile(nourishment = 5)).nourishment)
    }

    @Test fun nourishmentDecaysToo() {
        val p = LifeStats.decayNeeds(LifeProfile(nourishment = 100), elapsedMs = 10L * 3_600_000L)
        assertEquals(65, p.nourishment) // 10h × 3.5/h
    }

    @Test fun withNameClampsLength() {
        assertEquals("Six", LifeStats.withName(LifeProfile(), "Six").operatorName)
        assertEquals(LifeStats.MAX_NAME, LifeStats.withName(LifeProfile(), "x".repeat(80)).operatorName.length)
    }

    @Test fun energyDecaysToo() {
        val p = LifeStats.decayNeeds(LifeProfile(energy = 100), elapsedMs = 10L * 3_600_000L)
        assertEquals(70, p.energy) // 10h × 3/h
    }

    @Test fun withMoodClamps() {
        assertEquals(0, LifeStats.withMood(LifeProfile(), -20).mood)
        assertEquals(100, LifeStats.withMood(LifeProfile(), 250).mood)
        assertEquals(60, LifeStats.withMood(LifeProfile(), 60).mood)
    }

    @Test fun settersClampInput() {
        assertEquals(0, LifeStats.withHeight(LifeProfile(), -5).heightCm)
        assertEquals(LifeStats.MAX_HEIGHT_CM, LifeStats.withHeight(LifeProfile(), 9999).heightCm)
        assertEquals(180, LifeStats.withHeight(LifeProfile(), 180).heightCm)
        assertEquals(LifeStats.MAX_AGE, LifeStats.withAge(LifeProfile(), 999).ageYears)
        assertEquals(0.0, LifeStats.withMoney(LifeProfile(), -100.0).realMoney, 0.0001)
    }

    // --- Integration with SpecialGame.resolve ---

    @Test fun lifeStatBonusCanFlipACheck() {
        val enc = Encounter(
            "t", "T", "p",
            listOf(Choice("force", Special.STRENGTH, 11, Outcome("win", xp = 20, caps = 10), Outcome("lose", hp = -5))),
        )
        val c = SpecialGame.newCharacter() // STR 4, LUCK 4 → luckMod 0
        // 4 + roll 6 + 0 = 10 < 11 → fail without life.
        assertFalse(SpecialGame.resolve(c, enc, 0, roll = 6).success)
        // Powerhouse build (+2 STR): 6 + 6 + 0 = 12 ≥ 11 → success.
        val powerhouse = LifeProfile(heightCm = 180, weightKg = 110)
        assertTrue(SpecialGame.resolve(c, enc, 0, roll = 6, life = powerhouse).success)
    }

    @Test fun wealthTierBoostsCapsReward() {
        val safe = Encounter(
            "s", "S", "p",
            listOf(Choice("take", null, 0, Outcome("win", caps = 100), Outcome("n"))),
        )
        val c = SpecialGame.newCharacter() // caps 25
        // No life: caps reward 100 → 25 + 100 = 125.
        assertEquals(125, SpecialGame.resolve(c, safe, 0, roll = 5).character.caps)
        // Loaded tier (+35%): 100 → 135 → 25 + 135 = 160.
        val loaded = LifeProfile(realMoney = 500_000.0)
        assertEquals(160, SpecialGame.resolve(c, safe, 0, roll = 5, life = loaded).character.caps)
    }

    @Test fun blankLifeMatchesNoLife() {
        val enc = Encounter(
            "t", "T", "p",
            listOf(Choice("force", Special.STRENGTH, 9, Outcome("win", xp = 20, caps = 10), Outcome("lose", hp = -5))),
        )
        val c = SpecialGame.newCharacter()
        val noLife = SpecialGame.resolve(c, enc, 0, roll = 6)
        val blank = SpecialGame.resolve(c, enc, 0, roll = 6, life = LifeProfile())
        assertEquals(noLife.success, blank.success)
        assertEquals(noLife.character.caps, blank.character.caps)
    }
}
