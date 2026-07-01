package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Slice-1 tests for the S.P.E.C.I.A.L. world layer: the [Items] economy, the [Environment] real-world
 * modifiers, and their integration into [SpecialGame.resolve]. All pure/deterministic (die roll injected).
 */
class SpecialWorldTest {

    /** A test operative: all stats 4, LUCK 5 (luckMod 0 → clean check maths), overridable. */
    private fun char(
        vararg over: Pair<Special, Int>,
        hp: Int = 40,
        caps: Int = 25,
        inv: Map<String, Int> = emptyMap(),
    ): Character {
        val stats = Special.entries.associateWith { 4 }.toMutableMap()
        stats[Special.LUCK] = 5
        over.forEach { stats[it.first] = it.second }
        return Character(stats = stats, hp = hp, caps = caps, inventory = inv)
    }

    private fun strEnc(diff: Int = 12, drop: Map<String, Int> = emptyMap()) = Encounter(
        "w_str", "Test", "p",
        listOf(Choice("force", Special.STRENGTH, diff, Outcome("win", xp = 10, caps = 5, items = drop), Outcome("lose", hp = -5))),
        repeatable = true,
    )

    private fun endEnc(diff: Int = 12) = Encounter(
        "w_end", "Test", "p",
        listOf(Choice("endure", Special.ENDURANCE, diff, Outcome("win", xp = 10), Outcome("lose", hp = -5))),
        repeatable = true,
    )

    // --- Environment ---

    @Test fun neutralEnvironmentHasNoEffects() {
        val env = EnvContext()
        assertTrue(Environment.effects(env).isEmpty())
        Special.entries.forEach { assertEquals(0, Environment.statBonus(env, it)) }
    }

    @Test fun frigidTaxesEndurance() {
        assertEquals(-2, Environment.statBonus(EnvContext(outdoorTempC = -10.0), Special.ENDURANCE))
    }

    @Test fun coldTaxesEnduranceLess() {
        assertEquals(-1, Environment.statBonus(EnvContext(outdoorTempC = 2.0), Special.ENDURANCE))
    }

    @Test fun heatTaxesEndurance() {
        assertEquals(-1, Environment.statBonus(EnvContext(outdoorTempC = 35.0), Special.ENDURANCE))
        assertEquals(-2, Environment.statBonus(EnvContext(outdoorTempC = 42.0), Special.ENDURANCE))
    }

    @Test fun nightByFlagShiftsPerceptionAndAgility() {
        val env = EnvContext(isDay = false)
        assertEquals(-1, Environment.statBonus(env, Special.PERCEPTION))
        assertEquals(1, Environment.statBonus(env, Special.AGILITY))
    }

    @Test fun darknessByLuxCountsAsNight() {
        val env = EnvContext(lightLux = 5f) // isDay defaults true, but a dark sensor reading overrides
        assertEquals(-1, Environment.statBonus(env, Special.PERCEPTION))
        assertEquals(1, Environment.statBonus(env, Special.AGILITY))
    }

    @Test fun brightDaylightSharpensPerception() {
        assertEquals(1, Environment.statBonus(EnvContext(lightLux = 5000f, isDay = true), Special.PERCEPTION))
    }

    @Test fun thinAirTaxesEndurance() {
        assertEquals(-1, Environment.statBonus(EnvContext(altitudeM = 2000f), Special.ENDURANCE))
    }

    @Test fun motionHelpsAgilityHurtsPerception() {
        val env = EnvContext(motionG = 1.5f)
        assertEquals(1, Environment.statBonus(env, Special.AGILITY))
        assertEquals(-1, Environment.statBonus(env, Special.PERCEPTION))
    }

    @Test fun lowBatteryIsBadLuckUnlessCharging() {
        assertEquals(-1, Environment.statBonus(EnvContext(batteryPct = 10, charging = false), Special.LUCK))
        assertEquals(0, Environment.statBonus(EnvContext(batteryPct = 10, charging = true), Special.LUCK))
    }

    @Test fun environmentEffectsStack() {
        // Frigid (END −2) + thin air (END −1) = −3.
        val env = EnvContext(outdoorTempC = -10.0, altitudeM = 2000f)
        assertEquals(-3, Environment.statBonus(env, Special.ENDURANCE))
    }

    @Test fun tagsReflectConditions() {
        val tags = Environment.tags(EnvContext(outdoorTempC = -10.0, isDay = false, online = false))
        assertTrue("frigid" in tags)
        assertTrue("night" in tags)
        assertTrue("offline" in tags)
    }

    // --- Items / inventory ---

    @Test fun addAndRemoveItems() {
        var c = char()
        c = SpecialGame.addItem(c, "scrap_metal", 3)
        assertEquals(3, c.inventory["scrap_metal"])
        c = SpecialGame.removeItem(c, "scrap_metal", 1)
        assertEquals(2, c.inventory["scrap_metal"])
        c = SpecialGame.removeItem(c, "scrap_metal", 5) // over-remove drops the key
        assertNull(c.inventory["scrap_metal"])
    }

    @Test fun addUnknownItemIsNoOp() {
        val c = char()
        assertEquals(c, SpecialGame.addItem(c, "does_not_exist", 1))
    }

    @Test fun useAidHealsAndConsumes() {
        val c = char(hp = 10, inv = mapOf("medkit" to 1))
        val healed = SpecialGame.useAid(c, "medkit")
        assertEquals(10 + Items.MEDKIT.healAmt, healed.hp)
        assertNull(healed.inventory["medkit"])
    }

    @Test fun useAidClampsToMaxHp() {
        val c = char(hp = 38, inv = mapOf("auto_injector" to 1)) // maxHp = 40 at END 4
        val healed = SpecialGame.useAid(c, "auto_injector")
        assertEquals(40, healed.hp)
        assertNull(healed.inventory["auto_injector"])
    }

    @Test fun useAidRejectsNonAid() {
        val c = char(inv = mapOf("scrap_metal" to 1))
        assertEquals(c, SpecialGame.useAid(c, "scrap_metal")) // JUNK isn't AID
    }

    @Test fun sellItemGainsHalfValue() {
        val c = char(caps = 25, inv = mapOf("circuit_board" to 1))
        val sold = SpecialGame.sellItem(c, "circuit_board")
        assertEquals(25 + Items.CIRCUIT.value / 2, sold.caps)
        assertNull(sold.inventory["circuit_board"])
    }

    @Test fun buyItemSpendsCaps() {
        val c = char(caps = 100)
        val bought = SpecialGame.buyItem(c, "medkit")
        assertEquals(100 - Items.MEDKIT.value, bought.caps)
        assertEquals(1, bought.inventory["medkit"])
    }

    @Test fun buyItemRejectedWhenTooPoor() {
        val c = char(caps = 10)
        assertEquals(c, SpecialGame.buyItem(c, "medkit")) // costs 40
    }

    @Test fun gearGivesPassiveBonus() {
        val c = char(inv = mapOf("grip_gloves" to 1))
        assertEquals(1, SpecialGame.gearStatBonus(c, Special.STRENGTH))
    }

    @Test fun distinctGearStacksButDuplicatesDoNot() {
        val two = char(inv = mapOf("grip_gloves" to 1, "leather_rig" to 1))
        assertEquals(1, SpecialGame.gearStatBonus(two, Special.STRENGTH))
        assertEquals(1, SpecialGame.gearStatBonus(two, Special.ENDURANCE))
        val dup = char(inv = mapOf("grip_gloves" to 2))
        assertEquals(1, SpecialGame.gearStatBonus(dup, Special.STRENGTH)) // wearing two doesn't double
    }

    // --- resolve() integration ---

    @Test fun resolveWithoutExtrasIsUnchanged() {
        // STR 4 vs difficulty 12 at roll 7: 4 + 7 = 11 < 12 → fail (baseline behaviour).
        val r = SpecialGame.resolve(char(), strEnc(), 0, roll = 7)
        assertFalse(r.success)
    }

    @Test fun chemBoostsCheckAndIsConsumed() {
        val c = char(inv = mapOf("brute_serum" to 1)) // +3 STRENGTH
        val r = SpecialGame.resolve(c, strEnc(), 0, roll = 7, useItemId = "brute_serum")
        assertTrue(r.success) // 4 + 3 + 7 = 14 >= 12
        assertNull(r.character.inventory["brute_serum"]) // consumed
    }

    @Test fun mismatchedChemIsNotConsumedOrApplied() {
        val c = char(inv = mapOf("focus_tabs" to 1)) // INT chem on a STR check
        val r = SpecialGame.resolve(c, strEnc(), 0, roll = 7, useItemId = "focus_tabs")
        assertFalse(r.success) // no bonus: 4 + 7 = 11 < 12
        assertEquals(1, r.character.inventory["focus_tabs"]) // not consumed
    }

    @Test fun gearBoostsCheckWithoutConsuming() {
        val c = char(inv = mapOf("grip_gloves" to 1)) // +1 STRENGTH passive
        val r = SpecialGame.resolve(c, strEnc(), 0, roll = 7)
        assertTrue(r.success) // 4 + 1 + 7 = 12 >= 12
        assertEquals(1, r.character.inventory["grip_gloves"]) // gear stays
    }

    @Test fun coldWeatherCanFlipAnEnduranceCheck() {
        val withCold = SpecialGame.resolve(char(), endEnc(), 0, roll = 8, env = EnvContext(outdoorTempC = -10.0))
        assertFalse(withCold.success) // END 4 − 2 + 8 = 10 < 12
        val noEnv = SpecialGame.resolve(char(), endEnc(), 0, roll = 8)
        assertTrue(noEnv.success) // END 4 + 8 = 12 >= 12
    }

    @Test fun outcomeDropsLootIntoInventory() {
        val enc = strEnc(diff = 1, drop = mapOf("scrap_metal" to 2))
        val r = SpecialGame.resolve(char(), enc, 0, roll = SpecialGame.DIE) // natural 10 → guaranteed win
        assertTrue(r.success)
        assertEquals(2, r.character.inventory["scrap_metal"])
    }
}
