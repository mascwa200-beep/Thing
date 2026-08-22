package dev.mascwa.pulse.desktop.ledger

import dev.mascwa.pulse.core.telemetry.Novelty
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * Every expected value here is computed from the shipped formula with the arithmetic in the comment.
 * The agreement thresholds are also checked against the **real** figures measured from Open-Meteo, so
 * a change to [Backfill.BIAS_LIMIT] that would start admitting a different measurement fails here.
 */
class BackfillTest {

    private fun pairs(archive: List<Double?>, live: List<Double?>) = archive.zip(live)

    /**
     * Two series offset by exactly [bias], where the live side alternates ±[swing] about 10.
     *
     * ⚠️ **Even [n] only.** With an odd count the two halves are unequal, the median lands on one of
     * the two values rather than between them, and more than half the deviations come out zero — so the
     * MAD is zero and the flat-window branch answers instead of whichever rule is under test. That is
     * exactly how two guards here first reported themselves awake while testing nothing; [varied] is
     * for anything that needs an odd length.
     */
    private fun noisy(n: Int, bias: Double, swing: Double): List<Pair<Double?, Double?>> {
        require(n % 2 == 0) { "an odd count makes this fixture's MAD zero — use varied()" }
        return (0 until n).map { i ->
            val live = 10.0 + swing * (if (i % 2 == 0) 1.0 else -1.0)
            (live + bias) as Double? to live as Double?
        }
    }

    /** A three-value cycle, so the MAD stays 1.4826 at any length — odd counts included. */
    private fun varied(n: Int): List<Pair<Double?, Double?>> =
        (0 until n).map { i -> (10.0 + i % 3) as Double? to (10.0 + i % 3) as Double? }

    // ------------------------------------------------------------------ the agreement test

    @Test
    fun aFieldWithNoSystematicOffsetAgrees() {
        val v = Backfill.agrees(noisy(168, bias = 0.0, swing = 4.0))
        assertTrue(v.agrees)
        assertEquals(168, v.overlap)
        assertEquals(0.0, v.bias, 1e-12)
    }

    /**
     * ⚠️ THE LOAD-BEARING ONE. Wind speed measured against the live endpoint in London came out at
     * 0.493 of its own spread — half a MAD of systematic offset. Pouring that under the recorded
     * readings does not look like a bug, it looks like the world having been different last year.
     *
     * A live series alternating ±4 about 10 has median 10 and every deviation 4, so MAD = 4 × 1.4826 =
     * 5.9304. A bias of 2.9652 is exactly half of that.
     */
    @Test
    fun aFieldOffsetByHalfItsOwnSpreadIsRefused() {
        val v = Backfill.agrees(noisy(168, bias = 2.9652, swing = 4.0))
        assertEquals("bias / MAD", 0.5, v.ratio, 1e-6)
        assertTrue("half a MAD is not the same measurement", !v.agrees)
    }

    /**
     * ⚠️ The test is the offset **as a fraction of the field's own spread**, not the offset. Half a
     * degree is nothing on a temperature that swings ten and everything on a dew point that swings two,
     * and the same absolute bias must therefore fall on opposite sides of the line.
     */
    @Test
    fun theSameAbsoluteBiasIsJudgedAgainstEachFieldsOwnSpread() {
        val wide = Backfill.agrees(noisy(168, bias = 0.5, swing = 10.0)) // MAD 14.826 -> 0.034
        val narrow = Backfill.agrees(noisy(168, bias = 0.5, swing = 0.5)) // MAD 0.7413 -> 0.675
        assertTrue("0.5 against a spread of 14.8 is nothing", wide.agrees)
        assertTrue("the very same 0.5 against a spread of 0.74 is everything", !narrow.agrees)
    }

    /**
     * ⚠️ A flat live window is the absence of evidence, not evidence of agreement — and the guard
     * against it is **belt and braces, not the only thing refusing it**, which is worth being precise
     * about. With no spread the division yields NaN when the bias is zero and infinity when it is not,
     * and `< BIAS_LIMIT` is false for both, so the arithmetic already refuses. The explicit branch
     * exists because that is accidental correctness: written as `!(ratio >= BIAS_LIMIT)` — the same
     * predicate, negated — NaN would come back **true** and a window that measured nothing would be
     * read as a perfect match. The negative test perturbs both together, because either alone is safe.
     */
    @Test
    fun aFlatLiveWindowIsNotEvidenceOfAgreement() {
        val identical = Backfill.agrees((0 until 168).map { 7.0 as Double? to 7.0 as Double? })
        assertTrue("nothing was learned, so nothing may be claimed", !identical.agrees)
        assertEquals(0.0, identical.spread, 1e-12)

        val offset = Backfill.agrees((0 until 168).map { 9.0 as Double? to 7.0 as Double? })
        assertTrue("nor when the two sides plainly differ", !offset.agrees)
        assertEquals("and the reported ratio must not pretend to be a number", true, offset.ratio.isNaN())
    }

    /**
     * ⚠️ 47 hours is refused because of the overlap floor, and the fixture is a three-value cycle so
     * that it is genuinely refused *by that floor*. An alternating fixture has a zero MAD at any odd
     * length, and this test passed against a deleted overlap guard for precisely that reason.
     */
    @Test
    fun tooFewOverlappingHoursIsRefused() {
        // ⚠️ Measured on the fixture directly, not read off the verdict: a refusal reports a zero
        // spread whatever the data looked like, so asking the verdict would prove nothing.
        val spread = Novelty.describe(varied(47).map { Novelty.Observation(0L, it.second!!) })!!.mad
        assertTrue("the fixture must have real spread, or this tests the wrong guard", spread > 0.0)

        assertTrue("MIN_OVERLAP is 48", !Backfill.agrees(varied(47)).agrees)
        assertTrue(Backfill.agrees(varied(48)).agrees)
    }

    /** Hours either side missed a reading. Only the ones both sides carry may be compared. */
    @Test
    fun onlyHoursBothSidesCarryAreCompared() {
        val archive = List(100) { 10.0 } + List(100) { null }
        val live = List(60) { null } + List(140) { 10.0 }
        val v = Backfill.agrees(pairs(archive, live))
        assertEquals("100 archive rows, 140 live rows, 40 hours in common", 40, v.overlap)
        assertTrue("and 40 is under the floor", !v.agrees)
    }

    // ------------------------------------------------------------------ the real measurement

    /**
     * ⚠️ The thresholds are pinned against what was actually measured over one week in London, because
     * a limit chosen to admit five fields and refuse two is only meaningful next to the numbers that
     * produced it. Each figure below is bias/MAD from the live comparison recorded in [Backfill]'s
     * class note. If [Backfill.BIAS_LIMIT] ever moves, this says which fields it just changed its mind
     * about.
     */
    @Test
    fun theLimitAdmitsAndRefusesTheFieldsItWasMeasuredFrom() {
        val measured = mapOf(
            "surface_pressure" to 0.009,
            "wind_gusts_10m" to 0.006,
            "relative_humidity_2m" to 0.029,
            "temperature_2m" to 0.055,
            "dew_point_2m" to 0.143,
            "cloud_cover" to 0.220,
            "wind_speed_10m" to 0.493,
        )
        val admitted = measured.filterValues { it < Backfill.BIAS_LIMIT }.keys
        assertEquals(
            setOf("surface_pressure", "wind_gusts_10m", "relative_humidity_2m", "temperature_2m", "dew_point_2m"),
            admitted,
        )
        assertTrue("cloud cover is up to a hundred points apart on a single hour", "cloud_cover" !in admitted)
        assertTrue("wind speed is off by half its own spread", "wind_speed_10m" !in admitted)
    }

    // ------------------------------------------------------------------ the field maps

    /**
     * ⚠️ A map from a metric to a column that does not exist would be silent: the column comes back
     * null, the metric is skipped, and the wall shows "not enough history yet" forever with nothing
     * anywhere saying why.
     */
    @Test
    fun everyMappedColumnIsOneTheResponseCanCarry() {
        val empty = Backfill.OmSeries.Hourly()
        for ((metric, field) in Backfill.WEATHER_MAP + Backfill.AIR_MAP) {
            assertTrue("$metric maps to '$field', which the response shape has no field for",
                runCatching { empty.column(field) }.isSuccess)
            // The column exists in the `when`, so on an empty response it is null rather than falling
            // through to the else branch — which is what an unknown name would do.
            assertNull(empty.column(field))
        }
        assertNull("an unknown column must be null, not an exception", empty.column("no_such_field"))
    }

    @Test
    fun everyMappedMetricIsARegisteredOne() {
        for ((metric, _) in Backfill.WEATHER_MAP + Backfill.AIR_MAP) {
            assertTrue("$metric is backfilled but is not in the registry", metric in MetricRegistry.BY_ID)
        }
    }

    /**
     * ⚠️ Visibility is deliberately absent — the archive does not carry it, probed rather than assumed.
     * Listing it would mean fetching a column that is never there and skipping it on every pass.
     */
    @Test
    fun visibilityIsNotClaimedToBeBackfillable() {
        assertTrue("weather.visibility" in MetricRegistry.BY_ID)
        assertTrue(Backfill.WEATHER_MAP.none { it.first == "weather.visibility" })
    }

    /** Every air metric is filled, because that history is the same endpoint at a wider window. */
    @Test
    fun everyAirMetricIsBackfilled() {
        assertEquals(
            MetricRegistry.AIR.map { it.id }.toSet(),
            Backfill.AIR_MAP.map { it.first }.toSet(),
        )
    }

    // ------------------------------------------------------------------ timestamps

    @Test
    fun anHourStampParsesToUtcMilliseconds() {
        // 1970-01-02T00:00 UTC is exactly one day after the epoch.
        assertEquals(86_400_000L, Backfill.hourMs("1970-01-02T00:00"))
        assertNull("anything unexpected is null, never an exception", Backfill.hourMs("not a time"))
        assertNull(Backfill.hourMs(""))
    }

    // ------------------------------------------------------------------ the marker

    /**
     * ⚠️ The marker lives with the data it describes. A preference would remember a backfill whose rows
     * no longer exist and refuse to do it a second time, leaving a wiped ledger permanently unable to
     * fill itself.
     */
    @Test
    fun clearingTheLedgerForgetsThatItWasFilled() = runBlocking {
        val ledger = WorldLedger(Files.createTempDirectory("backfill"))
        assertTrue(!ledger.isBackfilled("weather.temp"))
        ledger.markBackfilled("weather.temp")
        assertTrue(ledger.isBackfilled("weather.temp"))
        ledger.clear()
        assertTrue("a wiped ledger must be able to fill itself again", !ledger.isBackfilled("weather.temp"))
    }

    /**
     * ⚠️ The marker must be invisible to every reader, and it shares a directory with the month files.
     *
     * The observation below is stamped **2026-01-15**, so it lands in `2026-01.csv` — a name the marker
     * must not collide with in either order. Marking after writing would truncate the month file;
     * marking before it would be parsed as an empty month and then overwritten. A fixture in some other
     * month could not tell either apart, which is the third recorded way a green test proves nothing.
     */
    @Test
    fun theMarkerIsNotMistakenForData() = runBlocking {
        val january = 1_768_478_400_000L // 2026-01-15T12:00Z

        val after = WorldLedger(Files.createTempDirectory("backfill"))
        after.append("weather.temp", Novelty.Observation(january, 12.5))
        after.markBackfilled("weather.temp")
        assertEquals("marking must not touch the month file", 1, after.read("weather.temp").size)
        assertEquals(12.5, after.read("weather.temp").first().value, 1e-9)
        assertEquals(january, after.lastAt("weather.temp"))

        val before = WorldLedger(Files.createTempDirectory("backfill"))
        before.markBackfilled("weather.temp")
        before.append("weather.temp", Novelty.Observation(january, 12.5))
        assertEquals("nor must the month file overwrite the marker", 1, before.read("weather.temp").size)
        assertTrue(before.isBackfilled("weather.temp"))
    }
}
