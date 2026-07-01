package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests for [Companions] + the [SpecialGame] hire/dismiss + resolve integration. */
class CompanionsTest {

    private fun char(caps: Int = 200, companion: String? = null): Character {
        val stats = Special.entries.associateWith { 4 }.toMutableMap()
        stats[Special.LUCK] = 5 // luckMod 0
        return Character(stats = stats, caps = caps, companion = companion)
    }

    private fun perEnc() = Encounter(
        "c_per", "T", "p",
        listOf(Choice("spot", Special.PERCEPTION, 12, Outcome("win", xp = 10), Outcome("lose", hp = -5))),
        repeatable = true,
    )

    @Test fun catalogIsValid() {
        assertTrue(Companions.ALL.isNotEmpty())
        assertEquals(Companions.ALL.size, Companions.ALL.map { it.id }.toSet().size)
        assertNotNull(Companions.byId("hound"))
        assertNull(Companions.byId("nope"))
    }

    @Test fun hireDeductsCapsAndSetsCompanion() {
        val hired = SpecialGame.hireCompanion(char(caps = 100), "hound") // cost 70
        assertEquals(30, hired.caps)
        assertEquals("hound", hired.companion)
    }

    @Test fun hireRejectedWhenTooPoor() {
        val c = char(caps = 50)
        assertEquals(c, SpecialGame.hireCompanion(c, "hound")) // costs 70
    }

    @Test fun hiringAnotherReplacesAndChargesAgain() {
        val a = SpecialGame.hireCompanion(char(caps = 200), "hound") // -70 → 130, hound
        val b = SpecialGame.hireCompanion(a, "merc")                 // -90 → 40, merc
        assertEquals("merc", b.companion)
        assertEquals(40, b.caps)
    }

    @Test fun rehiringSameCompanionIsNoOp() {
        val a = SpecialGame.hireCompanion(char(caps = 200), "hound")
        assertEquals(a, SpecialGame.hireCompanion(a, "hound")) // no double charge
    }

    @Test fun dismissClearsCompanion() {
        val a = char(companion = "hound")
        assertNull(SpecialGame.dismissCompanion(a).companion)
    }

    @Test fun companionStatBonusAppliesInResolve() {
        // PER 4 vs DC 12 at roll 8 → 12 (pass) without help; the hound's +2 PER isn't needed there, so use
        // roll 7: base 4+7=11 fails; with hound +2 → 13 passes.
        val without = SpecialGame.resolve(char(), perEnc(), 0, roll = 7)
        assertFalse(without.success)
        val with = SpecialGame.resolve(char(companion = "hound"), perEnc(), 0, roll = 7)
        assertTrue(with.success)
    }

    @Test fun medicCompanionHealsOnWin() {
        // Field Medic heals +4 on a win. Win an encounter that grants no hp and check the heal landed.
        val enc = Encounter("c_win", "T", "p",
            listOf(Choice("go", Special.PERCEPTION, 1, Outcome("win", xp = 0), Outcome("lose"))), repeatable = true)
        val c = char().copy(hp = 10) // maxHp 40 at END 4
        val r = SpecialGame.resolve(c.copy(companion = "medic"), enc, 0, roll = SpecialGame.DIE)
        assertTrue(r.success)
        assertEquals(14, r.character.hp) // 10 + 4 heal-on-win
    }
}
