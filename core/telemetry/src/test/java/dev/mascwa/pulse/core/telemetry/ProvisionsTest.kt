package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProvisionsTest {

    private fun sick(vararg critical: Pair<NeedKind, Int>): AfflictionState {
        var p = LifeProfile()
        critical.forEach { (k, v) ->
            p = when (k) {
                NeedKind.HYDRATION -> p.copy(hydration = v)
                NeedKind.NOURISHMENT -> p.copy(nourishment = v)
                NeedKind.ENERGY -> p.copy(energy = v)
                NeedKind.HYGIENE -> p.copy(hygiene = v)
                NeedKind.BRUSHING -> p.copy(brushing = v)
                NeedKind.FLOSSING -> p.copy(flossing = v)
            }
        }
        return Afflictions.advance(AfflictionState(), p, Afflictions.ONSET_MS)
    }

    // --- Catalog integrity ---

    @Test fun everyProvisionIsCoherent() {
        val provisions = Items.ALL.filter { it.kind == ItemKind.PROVISION }
        assertTrue(provisions.size >= 8)
        provisions.forEach { p ->
            // A provision either restores a need or cures afflictions (or both) — never a no-op.
            val restores = p.restoreNeed != null && p.restoreAmt > 0
            val cures = p.cureMs > 0L
            assertTrue("${p.id} must restore or cure", restores || cures)
        }
        // Ids unique + resolvable.
        assertEquals(Items.ALL.size, Items.ALL.map { it.id }.toSet().size)
        provisions.forEach { assertEquals(it, Items.byId(it.id)) }
        // Every survival need has at least one provision that restores it.
        NeedKind.entries.forEach { need ->
            assertTrue("no provision restores $need", provisions.any { it.restoreNeed == need })
        }
    }

    // --- LifeStats.restoreNeed ---

    @Test fun restoreNeedTopsUpTheRightBarAndClamps() {
        assertEquals(70, LifeStats.restoreNeed(LifeProfile(hydration = 25), NeedKind.HYDRATION, 45).hydration)
        assertEquals(100, LifeStats.restoreNeed(LifeProfile(brushing = 80), NeedKind.BRUSHING, 100).brushing) // clamps
        assertEquals(60, LifeStats.restoreNeed(LifeProfile(flossing = 10), NeedKind.FLOSSING, 50).flossing)
        // Only the named need changes.
        val before = LifeProfile(hydration = 30, nourishment = 30)
        assertEquals(30, LifeStats.restoreNeed(before, NeedKind.HYDRATION, 40).nourishment)
    }

    // --- Afflictions.shorten (medicine) ---

    @Test fun medicineFullyClearsATargetedAffliction() {
        val s = sick(NeedKind.HYDRATION to 10)
        assertTrue(Affliction.DEHYDRATION in s.active)
        val cured = Afflictions.shorten(s, Afflictions.ONSET_MS, NeedKind.HYDRATION)
        assertFalse(Affliction.DEHYDRATION in cured.active)
    }

    @Test fun medicineOnlyTouchesItsTarget() {
        val s = sick(NeedKind.HYDRATION to 10, NeedKind.ENERGY to 10)
        assertTrue(Affliction.DEHYDRATION in s.active && Affliction.EXHAUSTION in s.active)
        // Antibiotics-style (targets the hydration affliction) leaves exhaustion untouched.
        val after = Afflictions.shorten(s, Afflictions.ONSET_MS, NeedKind.HYDRATION)
        assertFalse(Affliction.DEHYDRATION in after.active)
        assertTrue(Affliction.EXHAUSTION in after.active)
    }

    @Test fun broadMedicineClearsEverything() {
        val s = sick(NeedKind.HYDRATION to 10, NeedKind.ENERGY to 10, NeedKind.FLOSSING to 8)
        val cured = Afflictions.shorten(s, Afflictions.ONSET_MS, null) // field medicine
        assertTrue(cured.active.isEmpty())
    }

    @Test fun partialMedicineShortensButKeepsItActive() {
        val s = sick(NeedKind.HYDRATION to 10)
        val after = Afflictions.shorten(s, Afflictions.ONSET_MS / 2, NeedKind.HYDRATION)
        assertTrue(Affliction.DEHYDRATION in after.active) // hysteresis: still active until fully drained
        assertNotEquals(s.meterMs[Affliction.DEHYDRATION], after.meterMs[Affliction.DEHYDRATION])
    }

    @Test fun shortenIsANoOpWhenHealthyOrZero() {
        assertEquals(AfflictionState(), Afflictions.shorten(AfflictionState(), Afflictions.ONSET_MS))
        val s = sick(NeedKind.HYDRATION to 10)
        assertEquals(s, Afflictions.shorten(s, 0L, NeedKind.HYDRATION))
    }

    // --- SpecialGame.useProvision ---

    @Test fun useProvisionConsumesOneAndHeals() {
        val c = SpecialGame.newCharacter().copy(hp = 10, inventory = mapOf("hearty_stew" to 2))
        assertTrue(SpecialGame.canUseProvision(c, "hearty_stew"))
        val after = SpecialGame.useProvision(c, "hearty_stew")
        assertEquals(16, after.hp)                       // +6 heal
        assertEquals(1, after.inventory["hearty_stew"])  // one consumed
    }

    @Test fun useProvisionCapsHpAndIgnoresNonProvisions() {
        val c = SpecialGame.newCharacter().copy(inventory = mapOf("hearty_stew" to 1, "clean_water" to 1))
        // At full HP, the heal caps (no overheal); item still consumed.
        val full = c.copy(hp = c.maxHp)
        assertEquals(c.maxHp, SpecialGame.useProvision(full, "hearty_stew").hp)
        // clean_water is an AID, not a PROVISION → not usable via this path.
        assertFalse(SpecialGame.canUseProvision(c, "clean_water"))
        assertEquals(c, SpecialGame.useProvision(c, "clean_water"))
        // Not held → no-op.
        assertFalse(SpecialGame.canUseProvision(c, "field_medicine"))
    }
}
