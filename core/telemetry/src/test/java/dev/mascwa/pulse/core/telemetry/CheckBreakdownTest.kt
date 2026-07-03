package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The attributable [CheckBreakdown] — proves every real-world modifier shows up, named, with the sum still
 * equal to what the check maths uses (so the display is the truth, not decoration).
 */
class CheckBreakdownTest {

    private fun charAll(v: Int) = Character(stats = Special.entries.associateWith { v })

    private fun strChoice(dc: Int) = Choice(
        text = "Heave it", stat = Special.STRENGTH, difficulty = dc,
        success = Outcome("win", xp = 10, caps = 5), failure = Outcome("lose", hp = -5),
    )

    private fun enc(choice: Choice) = Encounter("t", "Test", "prompt", listOf(choice), repeatable = true)

    // BMI 180cm/110kg ≈ 34 → POWERHOUSE (STRENGTH +2, AGILITY −1).
    private val powerhouse = LifeProfile(heightCm = 180, weightKg = 110)

    @Test fun breakdownNamesTheBaseAndLifeEffect() {
        val mods = SpecialGame.statMods(charAll(6), strChoice(12), life = powerhouse)
        assertEquals(6, mods.first { it.source == ModSource.BASE }.amount)
        assertTrue(mods.any { it.source == ModSource.LIFE && it.amount == 2 && it.label == "Powerhouse build" })
        assertEquals(8, mods.sumOf { it.amount }) // 6 base + 2 Powerhouse
    }

    @Test fun statValueEqualsTheAggregateMaths() {
        val c = charAll(6)
        val choice = strChoice(12)
        val mods = SpecialGame.statMods(c, choice, life = powerhouse)
        val expected = c.stat(Special.STRENGTH) + LifeStats.statBonus(powerhouse, Special.STRENGTH)
        assertEquals(expected, mods.sumOf { it.amount })
    }

    @Test fun resolveCarriesTheBreakdownAndItSwingsTheRoll() {
        // statValue 8 + roll 5 + luckMod (6−5)/2=0 = 13 ≥ DC 12 → PASS (the +2 Powerhouse is what carries it).
        val r = SpecialGame.resolve(charAll(6), enc(strChoice(12)), 0, roll = 5, life = powerhouse)
        assertTrue(r.success)
        assertEquals(Special.STRENGTH, r.breakdown.stat)
        assertEquals(8, r.breakdown.statValue)
        assertEquals(13, r.breakdown.total)
        assertTrue(r.breakdown.bonuses.none { it.source == ModSource.BASE })
        assertTrue(r.breakdown.bonuses.any { it.label == "Powerhouse build" })
        // Without the +2, 6 + 5 = 11 < 12 → it would have failed. Prove the effect actually mattered.
        val noLife = SpecialGame.resolve(charAll(6), enc(strChoice(12)), 0, roll = 5, life = null)
        assertFalse(noLife.success)
    }

    @Test fun safeChoiceHasNoStatBreakdown() {
        val safe = Choice("wait", stat = null, difficulty = 0, success = Outcome("ok"), failure = Outcome("ok"))
        val r = SpecialGame.resolve(charAll(5), enc(safe), 0, roll = 3)
        assertNull(r.breakdown.stat)
        assertTrue(r.breakdown.mods.isEmpty())
        assertTrue(r.success)
    }

    @Test fun realWorldEnvironmentShowsUpNamed() {
        val env = EnvContext(movement = 1.0f) // ≥ MOVING_INTENSITY → AGILITY +1 "On the move"
        val agi = Choice("dodge", Special.AGILITY, 10, Outcome("ok"), Outcome("no"))
        val mods = SpecialGame.statMods(charAll(6), agi, env = env)
        assertTrue(mods.any { it.source == ModSource.ENVIRONMENT && it.amount == 1 })
    }
}
