package dev.mascwa.pulse.data.health

import dev.mascwa.pulse.core.telemetry.CheckIn
import dev.mascwa.pulse.core.telemetry.MacroTargets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ⚠️ The join between a persisted shape and a domain one, which is the reason [PublishedPlan] is its
 * own file with no Android imports. Getting it wrong shows up as a plan that silently reverts, or as
 * adjustments attached to the wrong numbers, and neither is visible from a screenshot.
 */
class PublishedPlanTest {

    private val now = 1_700_000_000_000L

    private fun settings() = HealthSettings(
        heightCm = 178.0,
        birthYear = 1990,
        sex = "MALE",
        goalKg = 78.0,
        ratePerWeekKg = -0.5,
        dietMode = "BALANCED",
        proteinGPerKg = 1.9,
        bodyFatPct = 18.0,
        athlete = true,
        programMode = "COLLABORATIVE",
    )

    private fun plan(
        kcal: Int = 2400,
        adjustments: List<MacroTargets.Adjustment> = emptyList(),
    ) = MacroTargets.Plan.Set(
        targets = MacroTargets.Targets(kcal = kcal, proteinG = 160, fatG = 70, carbG = 260),
        adjustments = adjustments,
        effectiveRatePerWeekKg = -0.42,
        expenditureKcal = 2900.0,
    )

    @Test
    fun `nothing published decodes to null, which is what makes an old blob a check-in`() {
        // ⚠️ A settings blob written before this feature existed has every published field at its
        // default, and `publishedAtMs == 0L` is the single definition of "nothing published". That is
        // what turns the upgrade into a check-in on first open rather than a migration.
        assertNull(PublishedPlan.from(HealthSettings()))
        assertNull(PublishedPlan.from(settings()))

        // ⚠️ And a fixture that actually REACHES the timestamp branch. My first version of this test
        // only used blobs whose calories were also zero, so the second guard caught them and
        // deleting the first changed nothing — the recorded failure where a fixture never reaches
        // the branch under test. A half-written blob has the numbers and no time.
        val halfWritten = settings().copy(publishedAtMs = 0L, publishedKcal = 2400)
        assertNull(PublishedPlan.from(halfWritten))
    }

    @Test
    fun `a stored plan with no calories is not a plan`() {
        val broken = settings().copy(publishedAtMs = now, publishedKcal = 0)
        assertNull(PublishedPlan.from(broken))
    }

    @Test
    fun `store then read gives back exactly what was handed down`() {
        val p = settings()
        val stated = PublishedPlan.statedOf(p)
        val set = plan(
            adjustments = listOf(
                MacroTargets.Adjustment(
                    MacroTargets.AdjustmentKind.PROTEIN_RAISED,
                    "Protein was raised to the floor, which is 1.6 g per kg.",
                ),
                MacroTargets.Adjustment(
                    MacroTargets.AdjustmentKind.KCAL_RAISED_TO_FLOOR,
                    "Calories were raised to 1,200 — nothing below that is planned here.",
                ),
            ),
        )
        val stored = PublishedPlan.store(p, now, set, stated, weightKg = 84.0, report = listOf("a", "b"))
        val back = PublishedPlan.from(stored)
        assertNotNull(back)
        back!!
        assertEquals(now, back.atMs)
        assertEquals(set.targets, back.targets)
        assertEquals(stated.fingerprint(), back.statedFingerprint)
        assertEquals(2900.0, back.expenditureKcal, 1e-9)
        assertEquals(84.0, back.weightKg, 1e-9)
        assertEquals(-0.42, back.effectiveRatePerWeekKg, 1e-9)
        assertEquals(set.adjustments, back.adjustments)
        assertEquals(listOf("a", "b"), stored.publishedReport)
    }

    @Test
    fun `a round trip is immediately a hold, not another publish`() {
        // ⚠️ The property the whole cadence rests on. If storing and reading back did not reproduce
        // the fingerprint exactly, `verdict` would see a changed instruction on the very next state
        // build and republish — forever, on every emission.
        val p = settings()
        val stated = PublishedPlan.statedOf(p)
        val stored = PublishedPlan.store(p, now, plan(), stated, 84.0, emptyList())
        val v = CheckIn.verdict(PublishedPlan.from(stored), PublishedPlan.statedOf(stored), now)
        assertTrue(v.toString(), v is CheckIn.Verdict.Hold)
        assertEquals(CheckIn.INTERVAL_DAYS, (v as CheckIn.Verdict.Hold).daysUntilDue)
    }

    @Test
    fun `changing a stated setting after a publish is seen at once`() {
        val p = settings()
        val stored = PublishedPlan.store(p, now, plan(), PublishedPlan.statedOf(p), 84.0, emptyList())
        val edited = stored.copy(ratePerWeekKg = -1.0)
        val v = CheckIn.verdict(PublishedPlan.from(edited), PublishedPlan.statedOf(edited), now)
        assertEquals(CheckIn.Verdict.Publish(CheckIn.Reason.CHANGED), v)
    }

    @Test
    fun `statedOf reads the settings field of the same name`() {
        // ⚠️ The mapping guard. Every value below is distinct, so a copy-paste that reads `goalKg`
        // where it meant `ratePerWeekKg` fails here rather than producing a fingerprint that is
        // stable, plausible and wrong.
        val s = PublishedPlan.statedOf(settings())
        assertEquals(178.0, s.heightCm, 1e-9)
        assertEquals(1990, s.birthYear)
        assertEquals("MALE", s.sex)
        assertEquals(78.0, s.goalKg, 1e-9)
        assertEquals(-0.5, s.ratePerWeekKg, 1e-9)
        assertEquals("BALANCED", s.dietMode)
        assertEquals(1.9, s.proteinGPerKg, 1e-9)
        assertEquals(18.0, s.bodyFatPct, 1e-9)
        assertEquals(true, s.athlete)
        assertEquals("COLLABORATIVE", s.programMode)
    }

    @Test
    fun `the held plan renders as an ordinary plan, adjustments and all`() {
        // ⚠️ This is what stops a held set being visible as a different KIND of thing. Every surface
        // already handles `Plan.Set`; returning anything else would mean each of them learning what
        // a check-in is.
        val adj = MacroTargets.Adjustment(MacroTargets.AdjustmentKind.FAT_RAISED, "Fat was raised.")
        val p = settings()
        val stored = PublishedPlan.store(p, now, plan(adjustments = listOf(adj)), PublishedPlan.statedOf(p), 84.0, emptyList())
        val held = PublishedPlan.asPlan(PublishedPlan.from(stored)!!)
        assertEquals(2400, held.targets.kcal)
        assertEquals(listOf(adj), held.adjustments)
        assertTrue(held.capped)
        assertEquals(-0.42, held.effectiveRatePerWeekKg, 1e-9)
        assertEquals(2900.0, held.expenditureKcal, 1e-9)
    }

    // ------------------------------------------------------------------------- the encoded note

    @Test
    fun `an adjustment sentence survives every punctuation mark it contains`() {
        // ⚠️ The property that holds is FIRST-OCCURRENCE splitting, not the choice of character — see
        // `PublishedPlan.SEP`, whose comment I had to correct after a perturbation to a pipe broke
        // nothing. A kind name cannot contain punctuation, so the first separator is always the right
        // split point and everything after it is the sentence, colons and pipes included.
        val a = MacroTargets.Adjustment(
            MacroTargets.AdjustmentKind.RATE_CAPPED,
            "That rate was capped: 1.2 kg a week is more than 1% of your mass, so | it was reduced.",
        )
        assertEquals(a, PublishedPlan.decodeAdjustment(PublishedPlan.encodeAdjustment(a)))
    }

    @Test
    fun `a malformed or unknown note is dropped rather than fatal`() {
        // ⚠️ An unknown kind is what a DOWNGRADE looks like — a blob written by a build that had an
        // adjustment this one does not. Losing one note is far better than refusing to show the
        // targets it was attached to.
        assertNull(PublishedPlan.decodeAdjustment("no separator at all"))
        assertNull(PublishedPlan.decodeAdjustment("${PublishedPlan.SEP}leading separator"))
        assertNull(PublishedPlan.decodeAdjustment("NOT_A_KIND${PublishedPlan.SEP}a sentence"))

        // ⚠️ A VALID kind with nothing after it, which is the only case the length guard actually
        // catches — every other malformed shape falls through the `runCatching` and returns null on
        // its own. My first version used an invalid kind here, so deleting the guard broke nothing:
        // the fixture never reached the branch under test.
        assertNull(PublishedPlan.decodeAdjustment("FAT_RAISED${PublishedPlan.SEP}"))

        val p = settings()
        val stored = PublishedPlan
            .store(p, now, plan(), PublishedPlan.statedOf(p), 84.0, emptyList())
            .copy(publishedAdjustments = listOf("garbage", "FAT_RAISED${PublishedPlan.SEP}Fat was raised."))
        val back = PublishedPlan.from(stored)!!
        assertEquals(1, back.adjustments.size)
        assertEquals(MacroTargets.AdjustmentKind.FAT_RAISED, back.adjustments[0].kind)
    }
}
