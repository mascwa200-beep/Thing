package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpecialGameTest {

    private val enc = Encounter(
        id = "t1", title = "Test", prompt = "p",
        choices = listOf(
            Choice("force", Special.STRENGTH, 8, Outcome("win", xp = 20, caps = 10), Outcome("lose", hp = -10)),
            Choice("safe", null, 0, Outcome("safe", xp = 5), Outcome("n/a")),
        ),
    )

    @Test
    fun newCharacterHasExpectedStart() {
        val c = SpecialGame.newCharacter()
        assertEquals(7, c.stats.size)
        Special.entries.forEach { assertEquals(Character.START_STAT, c.stat(it)) }
        assertEquals(Character.START_POINTS, c.unspent)
        assertEquals(1, c.level)
        assertEquals(0, c.xp)
        assertEquals(c.maxHp, c.hp) // starts at full
        assertFalse(c.down)
    }

    @Test
    fun maxHpScalesWithEndurance() {
        val c = SpecialGame.newCharacter()
        val base = c.maxHp
        val tougher = SpecialGame.allocate(c, Special.ENDURANCE)
        assertEquals(base + Character.HP_PER_END, tougher.maxHp)
    }

    @Test
    fun checkNaturalMaxAlwaysCritSucceeds() {
        // Even against an impossible difficulty with weak stat/luck, a natural DIE succeeds and crits.
        val r = SpecialGame.check(statValue = 1, difficulty = 99, luck = 1, roll = SpecialGame.DIE)
        assertTrue(r.success)
        assertTrue(r.crit)
    }

    @Test
    fun checkNaturalOneAlwaysFails() {
        val r = SpecialGame.check(statValue = 10, difficulty = 2, luck = 10, roll = 1)
        assertFalse(r.success)
    }

    @Test
    fun checkUsesStatPlusRollPlusLuck() {
        assertFalse(SpecialGame.check(4, 12, 5, 5).success) // 4+5+0 = 9 < 12
        assertTrue(SpecialGame.check(4, 12, 5, 8).success)  // 4+8+0 = 12 >= 12
    }

    @Test
    fun luckTiltsTheOdds() {
        // Same stat/roll/difficulty; only LUCK differs.
        assertFalse(SpecialGame.check(4, 12, 1, 8).success) // luckMod -2 -> 10 < 12
        assertTrue(SpecialGame.check(4, 12, 10, 8).success) // luckMod +2 -> 14 >= 12
    }

    @Test
    fun resolveSuccessAppliesWinOutcomeAndMarksSeen() {
        val c = SpecialGame.newCharacter()
        val r = SpecialGame.resolve(c, enc, choiceIndex = 0, roll = 8) // 4+8+? passes 8
        assertTrue(r.success)
        assertEquals(c.caps + 10, r.character.caps)
        assertTrue("t1" in r.character.seen)
        assertNull(r.character.currentEncounterId)
    }

    @Test
    fun resolveFailureAppliesLossOutcome() {
        val c = SpecialGame.newCharacter()
        val r = SpecialGame.resolve(c, enc, choiceIndex = 0, roll = 1) // natural fumble
        assertFalse(r.success)
        assertEquals(c.hp - 10, r.character.hp)
    }

    @Test
    fun critDoublesXpAndAddsBonusCaps() {
        val c = SpecialGame.newCharacter()
        val r = SpecialGame.resolve(c, enc, choiceIndex = 0, roll = SpecialGame.DIE) // natural crit
        assertTrue(r.crit)
        assertEquals(40, r.outcome.xp)   // 20 * 2
        assertEquals(15, r.outcome.caps) // 10 + 10/2
    }

    @Test
    fun noCheckChoiceAlwaysSucceeds() {
        val c = SpecialGame.newCharacter()
        val r = SpecialGame.resolve(c, enc, choiceIndex = 1, roll = 1) // safe option, even on a 1
        assertTrue(r.success)
        assertEquals(5, r.outcome.xp)
    }

    @Test
    fun repeatableEncounterIsNotRetired() {
        val rep = enc.copy(id = "rep", repeatable = true)
        val c = SpecialGame.newCharacter()
        val r = SpecialGame.resolve(c, rep, 1, 5)
        assertFalse("rep" in r.character.seen)
    }

    @Test
    fun gainXpLevelsUpGrantsPointAndHeals() {
        val hurt = SpecialGame.newCharacter().copy(hp = 1, unspent = 0)
        val leveled = SpecialGame.gainXp(hurt, hurt.xpToNext) // exactly enough for level 2
        assertEquals(2, leveled.level)
        assertEquals(1, leveled.unspent)          // one point granted
        assertEquals(leveled.maxHp, leveled.hp)   // healed on level-up
    }

    @Test
    fun gainXpCascadesMultipleLevels() {
        val c = SpecialGame.newCharacter().copy(unspent = 0)
        // L1->L2 needs 100, L2->L3 needs 200; 350 total -> level 3 with 50 carried.
        val leveled = SpecialGame.gainXp(c, 350)
        assertEquals(3, leveled.level)
        assertEquals(50, leveled.xp)
        assertEquals(2, leveled.unspent)
    }

    @Test
    fun allocateRaisesStatAndSpendsPoint() {
        val c = SpecialGame.newCharacter()
        val after = SpecialGame.allocate(c, Special.STRENGTH)
        assertEquals(c.stat(Special.STRENGTH) + 1, after.stat(Special.STRENGTH))
        assertEquals(c.unspent - 1, after.unspent)
    }

    @Test
    fun allocateNoOpWithoutPointsOrAtCap() {
        val broke = SpecialGame.newCharacter().copy(unspent = 0)
        assertEquals(broke, SpecialGame.allocate(broke, Special.LUCK))
        val maxed = SpecialGame.newCharacter().copy(
            stats = SpecialGame.newCharacter().stats.toMutableMap().apply { put(Special.LUCK, 10) },
            unspent = 5,
        )
        assertEquals(10, SpecialGame.allocate(maxed, Special.LUCK).stat(Special.LUCK)) // stays at cap
        assertEquals(5, SpecialGame.allocate(maxed, Special.LUCK).unspent)             // point not spent
    }

    @Test
    fun nextEncounterAvoidsSeenThenFallsBackToRepeatable() {
        val a = enc.copy(id = "a")
        val b = enc.copy(id = "b")
        val rep = enc.copy(id = "rep", repeatable = true)
        val all = listOf(a, b, rep)
        val fresh = SpecialGame.newCharacter()
        assertNotNull(SpecialGame.nextEncounter(fresh, all, 0))
        // Everything non-repeatable seen -> only the repeatable remains.
        val seenAll = fresh.copy(seen = setOf("a", "b"))
        assertEquals("rep", SpecialGame.nextEncounter(seenAll, all, 3)?.id)
        assertNull(SpecialGame.nextEncounter(fresh, emptyList(), 0))
    }

    @Test
    fun reviveRestoresHpAndTakesCapsToll() {
        val downed = SpecialGame.newCharacter().copy(hp = 0, caps = 100)
        assertTrue(downed.down)
        val up = SpecialGame.revive(downed)
        assertEquals(up.maxHp, up.hp)
        assertEquals(75, up.caps) // lost a quarter
        assertFalse(up.down)
    }

    @Test
    fun capsNeverGoNegativeAndHpClampsToMax() {
        val poor = SpecialGame.newCharacter().copy(caps = 5)
        val drained = SpecialGame.resolve(poor, enc.copy(choices = listOf(
            Choice("x", null, 0, Outcome("w", caps = -100, hp = 999), Outcome("l")),
        )), 0, 5)
        assertEquals(0, drained.character.caps)                 // floored at 0
        assertEquals(drained.character.maxHp, drained.character.hp) // clamped to max
    }

    @Test
    fun contentLibraryIsSaneAndCoversEveryStat() {
        val all = SpecialEncounters.ALL
        assertTrue(all.size >= 12)
        assertEquals(all.size, all.map { it.id }.toSet().size) // unique ids
        all.forEach { e ->
            assertTrue("${e.id} needs >=2 choices", e.choices.size >= 2)
            e.choices.forEach { assertTrue(it.difficulty > 0) }
        }
        val gated = all.flatMap { it.choices }.mapNotNull { it.stat }.toSet()
        assertEquals("every S.P.E.C.I.A.L. stat should gate at least one choice", Special.entries.toSet(), gated)
        assertTrue("needs a repeatable encounter so it never runs dry", all.any { it.repeatable })
    }
}
