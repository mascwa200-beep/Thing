package dev.mascwa.pulse.desktop.ledger

import dev.mascwa.pulse.core.telemetry.Novelty
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class WorldLedgerTest {

    private val day = 24L * 60L * 60L * 1000L

    /** Midnight UTC on a real day, so month bucketing is unambiguous. */
    private val t0 = 1_754_006_400_000L // 2025-08-01T00:00:00Z

    private fun tmp(): Path = Files.createTempDirectory("ledger")
    private fun obs(atMs: Long, v: Double, backfilled: Boolean = false) =
        Novelty.Observation(atMs, v, backfilled)

    @Test
    fun whatWasRecordedComesBack() = runBlocking {
        val l = WorldLedger(tmp())
        l.appendAll("weather.temp", (0 until 10).map { obs(t0 + it * 3_600_000L, 10.0 + it) })

        val back = l.read("weather.temp")
        assertEquals(10, back.size)
        assertEquals(t0, back.first().atMs)
        assertEquals(19.0, back.last().value, 1e-9)
        assertTrue("appended in order, returned in order", back.zipWithNext().all { it.first.atMs < it.second.atMs })
    }

    @Test
    fun theBackfilledFlagSurvivesARoundTrip() = runBlocking {
        val l = WorldLedger(tmp())
        l.appendAll("weather.temp", listOf(obs(t0, 1.0, backfilled = true), obs(t0 + 1000, 2.0)))

        val back = l.read("weather.temp")
        assertTrue("fetched history must still say so on the way back", back[0].backfilled)
        assertTrue("and a recorded one must not", !back[1].backfilled)
    }

    /**
     * ⚠️ LOAD-BEARING. A process killed mid-append leaves a partial final line. A ledger that refused
     * to open because of one truncated row would lose everything before it to protect nothing.
     */
    @Test
    fun aTruncatedFinalLineIsSkippedRatherThanFatal() = runBlocking {
        val dir = tmp()
        val l = WorldLedger(dir)
        l.appendAll("space.kp", (0 until 5).map { obs(t0 + it * 3_600_000L, it.toDouble()) })

        val f = dir.resolve("space.kp").resolve("2025-08.csv")
        // Both shapes a killed process really leaves: cut before the comma, and cut after it.
        Files.writeString(f, Files.readString(f) + "17540064")
        val back = l.read("space.kp")
        assertEquals("the five good rows survive the half-written sixth", 5, back.size)

        Files.writeString(f, Files.readString(f) + "\n1754020800,")
        assertEquals("and survive a cut after the comma too", 5, l.read("space.kp").size)
        assertEquals(4.0, back.last().value, 1e-9)
    }

    @Test
    fun garbageAndBlankLinesAreIgnored() = runBlocking {
        val dir = tmp()
        val l = WorldLedger(dir)
        l.appendAll("space.kp", listOf(obs(t0, 3.0)))
        val f = dir.resolve("space.kp").resolve("2025-08.csv")
        Files.writeString(f, Files.readString(f) + "\n\nnot,a,row\n,,\nNaN\n")

        assertEquals(1, l.read("space.kp").size)
    }

    /**
     * ⚠️ Asserted against the FILE, not against [WorldLedger.read]. The reader rejects non-finite rows
     * too, so a read-side assertion passes whether or not the write filter exists — belt and braces
     * that cannot be told apart is belt and braces that cannot be tested.
     */
    @Test
    fun aNonFiniteValueNeverReachesTheDisk() = runBlocking {
        val dir = tmp()
        val l = WorldLedger(dir)
        l.appendAll("x.y", listOf(obs(t0, Double.NaN), obs(t0 + 1000, Double.POSITIVE_INFINITY), obs(t0 + 2000, 5.0)))

        val onDisk = Files.readString(dir.resolve("x.y").resolve("2025-08.csv"))
        assertTrue("NaN must never be written: <$onDisk>", !onDisk.contains("NaN"))
        assertTrue("nor an infinity: <$onDisk>", !onDisk.contains("Infinity"))
        assertEquals("one row, not three", 1, onDisk.trim().lines().size)
        assertEquals(1, l.read("x.y").size)
    }

    @Test
    fun readingFromATimeSkipsWhatCameBefore() = runBlocking {
        val l = WorldLedger(tmp())
        l.appendAll("x.y", (0 until 10).map { obs(t0 + it * day, it.toDouble()) })

        assertEquals(4, l.read("x.y", sinceMs = t0 + 6 * day).size)
    }

    @Test
    fun lastAtFindsTheNewestRowWithoutReadingTheFile() = runBlocking {
        val l = WorldLedger(tmp())
        assertNull("nothing recorded yet", l.lastAt("x.y"))

        // Two months, so it also has to pick the right file rather than the first one.
        l.appendAll("x.y", listOf(obs(t0, 1.0), obs(t0 + 40 * day, 2.0), obs(t0 + 41 * day, 3.0)))
        assertEquals(t0 + 41 * day, l.lastAt("x.y"))
    }

    @Test
    fun lastAtCopesWithASingleRowAndNoTrailingNewline() = runBlocking {
        val dir = tmp()
        val l = WorldLedger(dir)
        Files.createDirectories(dir.resolve("x.y"))
        Files.writeString(dir.resolve("x.y").resolve("2025-08.csv"), "1754006400,7.5")

        assertEquals(t0, l.lastAt("x.y"))
    }

    @Test
    fun monthsAreCutOnUtc() = runBlocking {
        val dir = tmp()
        val l = WorldLedger(dir)
        // 2025-08-31T23:30Z and 2025-09-01T00:30Z — either side of a UTC month boundary.
        l.appendAll("x.y", listOf(obs(1_756_683_000_000L, 1.0), obs(1_756_686_600_000L, 2.0)))

        assertTrue(Files.exists(dir.resolve("x.y").resolve("2025-08.csv")))
        assertTrue(Files.exists(dir.resolve("x.y").resolve("2025-09.csv")))
        assertEquals("and both still read back as one series", 2, l.read("x.y").size)
    }

    @Test
    fun twoMetricsDoNotShareAFile() = runBlocking {
        val l = WorldLedger(tmp())
        l.appendAll("a.one", listOf(obs(t0, 1.0)))
        l.appendAll("a.two", listOf(obs(t0, 2.0)))

        assertEquals(listOf(1.0), l.read("a.one").map { it.value })
        assertEquals(listOf(2.0), l.read("a.two").map { it.value })
        assertEquals(listOf("a.one", "a.two"), l.metricIds())
    }

    /**
     * ⚠️ LOAD-BEARING. Sanitising instead of rejecting would let two ids collapse into one directory,
     * and a silently merged series is worse than a missing one — it still scores, and it scores
     * nonsense.
     */
    @Test
    fun anUnsafeMetricIdIsRejectedRatherThanSanitised() = runBlocking {
        val l = WorldLedger(tmp())
        for (bad in listOf("Weather/Temp", "a b", "", "UPPER", "x:y", "../escape")) {
            var threw = false
            try {
                l.append(bad, obs(t0, 1.0))
            } catch (e: IllegalArgumentException) {
                threw = true
            }
            assertTrue("'$bad' must be refused", threw)
        }
    }

    // ---------------------------------------------------------------- retention

    /**
     * ⚠️ THE LOAD-BEARING ONE FOR RETENTION. Fold a year-old month away and the peak inside it must
     * still be findable. Keeping only a daily mean would erase it, and "highest in three years" would
     * start quietly lying with nothing downstream able to tell.
     */
    @Test
    fun foldingOldMonthsKeepsTheExtremesNotJustTheAverage() = runBlocking {
        val dir = tmp()
        val l = WorldLedger(dir)
        val old = t0 // one day, 24 hourly readings, with one spike
        l.appendAll("x.y", (0 until 24).map { obs(old + it * 3_600_000L, if (it == 13) 99.0 else 10.0) })

        l.prune(nowMs = old + 500 * day)

        val daily = l.dailyExtremes("x.y")
        assertEquals(1, daily.size)
        assertEquals("the spike survives", 99.0, daily[0].max, 1e-9)
        assertEquals("so does the floor", 10.0, daily[0].min, 1e-9)
        assertEquals(24, daily[0].count)
        assertEquals((23 * 10.0 + 99.0) / 24.0, daily[0].mean, 1e-9)
        assertTrue("and the full-resolution rows are gone", l.read("x.y").isEmpty())
    }

    @Test
    fun pruningLeavesTheFullResolutionWindowAlone() = runBlocking {
        val l = WorldLedger(tmp())
        val now = t0 + 400 * day
        l.appendAll("x.y", listOf(obs(t0, 1.0), obs(now - 10 * day, 2.0), obs(now - day, 3.0)))

        l.prune(nowMs = now)

        assertEquals("the two recent rows stay at full resolution", listOf(2.0, 3.0), l.read("x.y").map { it.value })
        assertEquals("only the year-old one was folded", 1, l.dailyExtremes("x.y").size)
    }

    /**
     * ⚠️ A prune interrupted between writing the daily rows and deleting the month files leaves both
     * on disk. The next prune must not count that day twice.
     */
    @Test
    fun foldingADaySeenTwiceMergesRatherThanReplacingOrSkipping() = runBlocking {
        val l = WorldLedger(tmp())
        // ⚠️ SIX then EIGHTEEN, deliberately. With twelve and twelve the count-weighted mean and a
        // plain average of the two averages are both 50, so the fixture could not tell them apart.
        l.appendAll("x.y", (0 until 6).map { obs(t0 + it * 3_600_000L, 10.0) })
        l.prune(nowMs = t0 + 500 * day)

        // What an interrupted prune leaves behind: the daily row written, the month file back, and
        // readings the collector added in between that the first fold never saw.
        l.appendAll("x.y", (6 until 24).map { obs(t0 + it * 3_600_000L, 90.0) })
        l.prune(nowMs = t0 + 500 * day)

        val d = l.dailyExtremes("x.y")
        assertEquals("still one day", 1, d.size)
        assertEquals("the later readings must not be lost", 90.0, d[0].max, 1e-9)
        assertEquals("nor the earlier ones", 10.0, d[0].min, 1e-9)
        assertEquals("every reading counted once", 24, d[0].count)
        // (10 x 6 + 90 x 18) / 24 = 70. An average of averages would say 50.
        assertEquals("weighted by count, not an average of averages", 70.0, d[0].mean, 1e-9)
    }

    /**
     * ⚠️ FOUND BY A FAILING TEST, AND IT WOULD HAVE ROTTED IN SILENCE. The first cut matched month
     * files by "ends with .csv", which also matched daily.csv — so the moment anything was pruned the
     * aggregate was parsed back as full-resolution rows, injecting a garbage observation dated 1970
     * into every distribution. Nothing would have noticed for a year, and then nothing at all.
     */
    @Test
    fun theDailyAggregateIsNeverReadBackAsAnObservation() = runBlocking {
        val l = WorldLedger(tmp())
        l.appendAll("x.y", (0 until 24).map { obs(t0 + it * 3_600_000L, 10.0) })
        l.prune(nowMs = t0 + 500 * day)

        assertTrue("the folded month must leave no full-resolution rows behind", l.read("x.y").isEmpty())
        assertEquals("and it certainly must not appear as a 1970 reading", 1, l.dailyExtremes("x.y").size)
        assertNull("nor as the newest thing recorded", l.lastAt("x.y"))
    }

    @Test
    fun clearingRemovesEverything() = runBlocking {
        val l = WorldLedger(tmp())
        l.appendAll("x.y", (0 until 100).map { obs(t0 + it * 3_600_000L, it.toDouble()) })
        assertTrue(l.sizeBytes() > 0)

        l.clear()

        assertEquals(0L, l.sizeBytes())
        assertTrue(l.metricIds().isEmpty())
        assertTrue(l.read("x.y").isEmpty())
    }

    @Test
    fun anAbsentLedgerReadsAsEmptyRatherThanThrowing() = runBlocking {
        val l = WorldLedger(tmp().resolve("never-created"))
        assertTrue(l.read("x.y").isEmpty())
        assertTrue(l.metricIds().isEmpty())
        assertTrue(l.dailyExtremes("x.y").isEmpty())
        assertNull(l.lastAt("x.y"))
        assertEquals(0L, l.sizeBytes())
    }

    /**
     * The storage claim in the class doc, checked rather than asserted in prose: a record costs about
     * twenty bytes because the metric id is in the path and never on the line.
     */
    @Test
    fun aRecordCostsAboutTwentyBytes() = runBlocking {
        val l = WorldLedger(tmp())
        val n = 1000
        l.appendAll(
            "weather.surface-pressure",
            (0 until n).map { obs(t0 + it * 60_000L, 1013.0 + (it % 40) / 10.0) },
        )

        val perRow = l.sizeBytes().toDouble() / n
        assertTrue("$perRow bytes a row — the id must not be repeated per line", perRow < 25.0)
    }
}
