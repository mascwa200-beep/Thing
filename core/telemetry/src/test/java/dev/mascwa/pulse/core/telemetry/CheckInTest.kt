package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ⚠️ Every elapsed figure below is computed from `CheckIn.INTERVAL_DAYS` and a day in milliseconds
 * with the arithmetic in the comment, never typed from memory — the habit this project has had to
 * record roughly seventeen times, because an expectation of mine has repeatedly been wrong where the
 * shipped code was right.
 */
class CheckInTest {

    private val day = 86_400_000L
    private val now = 1_700_000_000_000L

    private fun targets(kcal: Int = 2400, p: Int = 160, f: Int = 70, c: Int = 260) =
        MacroTargets.Targets(kcal = kcal, proteinG = p, fatG = f, carbG = c)

    private fun stated(rate: Double = -0.5, mode: String = "BALANCED") = CheckIn.Stated(
        heightCm = 178.0,
        birthYear = 1990,
        sex = "MALE",
        goalKg = 78.0,
        ratePerWeekKg = rate,
        dietMode = mode,
        programMode = "COACHED",
    )

    private fun published(
        atMs: Long = now - 2 * day,
        t: MacroTargets.Targets = targets(),
        s: CheckIn.Stated = stated(),
        expenditure: Double = 2900.0,
        kg: Double = 84.0,
    ) = CheckIn.Published(atMs, t, s, expenditure, kg)

    // -------------------------------------------------------------------------------- the cadence

    @Test
    fun `nothing published yet publishes at once`() {
        val v = CheckIn.verdict(null, stated(), now)
        assertEquals(CheckIn.Verdict.Publish(CheckIn.Reason.FIRST), v)
    }

    @Test
    fun `inside the week it holds and counts the days down`() {
        // Published two days ago: 7 - 2 = 5 days until due.
        val v = CheckIn.verdict(published(atMs = now - 2 * day), stated(), now)
        assertEquals(CheckIn.Verdict.Hold(5), v)
    }

    @Test
    fun `the boundary is inclusive — exactly seven days is due`() {
        // ⚠️ Computed, not guessed: elapsed / DAY_MS == 7, and the rule is `>= INTERVAL_DAYS`.
        val exactly = CheckIn.verdict(published(atMs = now - 7 * day), stated(), now)
        assertEquals(CheckIn.Verdict.Publish(CheckIn.Reason.DUE), exactly)

        // One millisecond short of seven days is still 6 whole days, so it holds with 1 to go.
        val almost = CheckIn.verdict(published(atMs = now - 7 * day + 1), stated(), now)
        assertEquals(CheckIn.Verdict.Hold(1), almost)
    }

    @Test
    fun `a published time in the future is due rather than a very long hold`() {
        // ⚠️ A clock moved back or a restored backup. The alternative freezes the targets until the
        // clock catches up, which can be months and looks exactly like the app being broken.
        val v = CheckIn.verdict(published(atMs = now + 30 * day), stated(), now)
        assertEquals(CheckIn.Verdict.Publish(CheckIn.Reason.DUE), v)
    }

    // --------------------------------------------------------------- stated versus measured change

    @Test
    fun `changing something you chose publishes immediately`() {
        val before = published(atMs = now - 1 * day, s = stated(rate = -0.5))
        val v = CheckIn.verdict(before, stated(rate = -1.0), now)
        assertEquals(CheckIn.Verdict.Publish(CheckIn.Reason.CHANGED), v)
    }

    @Test
    fun `changing the diet mode counts too, not only the rate`() {
        val before = published(atMs = now - 1 * day, s = stated(mode = "BALANCED"))
        val v = CheckIn.verdict(before, stated(mode = "KETO"), now)
        assertEquals(CheckIn.Verdict.Publish(CheckIn.Reason.CHANGED), v)
    }

    @Test
    fun `a new weigh-in does NOT publish, which is the whole mechanism`() {
        // ⚠️ The rule that is easy to get backwards. Body mass is measured, so it is deliberately not
        // a member of `Stated` — a published set that moved on every weigh-in would be exactly the
        // drifting target this file exists to stop.
        val before = published(atMs = now - 1 * day, kg = 84.0)
        val after = before.copy(weightKg = 82.5)
        assertEquals(before.stated, after.stated)
        assertEquals(CheckIn.Verdict.Hold(6), CheckIn.verdict(after, stated(), now))
    }

    @Test
    fun `Stated carries nothing the app measures for you`() {
        // ⚠️ The type is the real guard here and this asserts it, because the mistake is a field
        // ADDED by somebody who did not read the KDoc. A mass, an expenditure or an intake in
        // `Stated` would make the targets republish on measured drift and quietly undo the feature —
        // and the symptom is a target that moves daily, which is what this file was written to stop.
        // ⚠️ Three fields are exempt by NAME, not by accident: a goal is typed in, a rate is picked,
        // and a protein-per-kilogram override is entered by hand — all stated, however much the
        // trailing "kg" makes them look measured. Listing the exemptions rather than loosening the
        // pattern is what keeps the check sharp, and writing this list is what surfaced the third
        // one, which I had not thought of.
        val stated = setOf("goalkg", "rateperweekkg", "proteingperkg")
        val measured = listOf("kg", "weight", "mass", "expenditure", "kcal", "intake", "steps")
        val fields = CheckIn.Stated::class.java.declaredFields
            .map { it.name.lowercase() }
            .filter { !it.contains("$") }
        assertTrue("Stated must have fields at all — reflection found none", fields.isNotEmpty())
        assertTrue("the two exempt names must still be there, or this check is vacuous", fields.containsAll(stated))
        for (f in fields.filter { it !in stated }) {
            for (m in measured) {
                assertTrue(
                    "'$f' looks like something the app MEASURES; Stated is only what a person states",
                    !f.contains(m),
                )
            }
        }
    }

    @Test
    fun `measured expenditure moving does not publish either`() {
        val before = published(atMs = now - 3 * day, expenditure = 2900.0)
        val after = before.copy(expenditureKcal = 3100.0)
        assertEquals(CheckIn.Verdict.Hold(4), CheckIn.verdict(after, stated(), now))
    }

    // --------------------------------------------------------------------------------- what moved

    @Test
    fun `nothing worth saying produces no sentences`() {
        // 2400 -> 2410 is 10 kcal, under KCAL_WORTH_SAYING = 25. 160 -> 161 is 1 g, under 3.
        val out = CheckIn.changes(targets(), targets(kcal = 2410, p = 161))
        assertTrue(out.toString(), out.isEmpty())
    }

    @Test
    fun `a real move is reported with its direction and its new value`() {
        // 2400 -> 2490 is +90, over 25. Protein 160 -> 152 is -8, over 3.
        val out = CheckIn.changes(targets(), targets(kcal = 2490, p = 152))
        assertEquals(2, out.size)
        assertEquals("Calories up 90 kcal, to 2490.", out[0])
        assertEquals("Protein down 8 g, to 152.", out[1])
    }

    @Test
    fun `the order is calories then protein then fat then carbohydrate`() {
        val out = CheckIn.changes(targets(), targets(kcal = 2500, p = 200, f = 100, c = 300))
        assertEquals(4, out.size)
        assertTrue(out[0].startsWith("Calories"))
        assertTrue(out[1].startsWith("Protein"))
        assertTrue(out[2].startsWith("Fat"))
        assertTrue(out[3].startsWith("Carbohydrate"))
    }

    @Test
    fun `no sentence praises or scolds`() {
        // ⚠️ The register the whole feature holds to. A check-in that congratulates somebody for a
        // number the app moved on their behalf is a judgement the data cannot support.
        val out = CheckIn.changes(targets(), targets(kcal = 2100, p = 190)) +
            CheckIn.changes(targets(), targets(kcal = 2900))
        val banned = listOf(
            "well done", "great", "good job", "unfortunately", "sadly", "should", "failed",
            "slipped", "reward", "deserve",
        )
        for (s in out) {
            for (w in banned) {
                assertTrue("must not say '$w': $s", !s.lowercase().contains(w))
            }
        }
    }

    // ------------------------------------------------------------------------------------- the why

    @Test
    fun `the expenditure sentence names the measurement, never the metabolism`() {
        val s = CheckIn.whyCaloriesMoved(published(expenditure = 2900.0), 2990.0)!!
        // 2990 - 2900 = 90.
        assertTrue(s, s.contains("90 kcal"))
        assertTrue(s, s.contains("measured expenditure"))
        assertTrue(s, s.contains("higher"))
        // ⚠️ It has only ever measured a ledger, so it must not make a claim about a body.
        assertTrue(s, !s.lowercase().contains("you burned"))
        assertTrue(s, !s.lowercase().contains("metabolism"))
    }

    @Test
    fun `a move under the floor and a non-finite figure both say nothing`() {
        assertNull(CheckIn.whyCaloriesMoved(published(expenditure = 2900.0), 2910.0))
        assertNull(CheckIn.whyCaloriesMoved(published(expenditure = Double.NaN), 2900.0))
        assertNull(CheckIn.whyCaloriesMoved(published(expenditure = 2900.0), Double.NaN))
    }

    @Test
    fun `the weight sentence reads in the reader's own unit`() {
        val before = published(kg = 84.0)
        val kg = CheckIn.weightMoved(before, 83.2, BodyTrend.MassUnit.KG)!!
        // 83.2 - 84.0 = -0.8 kg.
        assertTrue(kg, kg.contains("down 0.8 kg"))

        // The same move in pounds: 0.8 * 2.2046226218 = 1.7637, which rounds to 1.8.
        val lb = CheckIn.weightMoved(before, 83.2, BodyTrend.MassUnit.LB)!!
        assertTrue(lb, lb.contains("down 1.8 lb"))
    }

    @Test
    fun `an unmeasurable weight says nothing and a tiny one says it held`() {
        assertNull(CheckIn.weightMoved(published(kg = 0.0), 84.0, BodyTrend.MassUnit.KG))
        assertNull(CheckIn.weightMoved(published(kg = 84.0), Double.NaN, BodyTrend.MassUnit.KG))
        // 84.03 - 84.0 = 0.03 kg, under the tenth a weekly trend can resolve.
        val held = CheckIn.weightMoved(published(kg = 84.0), 84.03, BodyTrend.MassUnit.KG)!!
        assertTrue(held, held.contains("held steady"))
    }
}
