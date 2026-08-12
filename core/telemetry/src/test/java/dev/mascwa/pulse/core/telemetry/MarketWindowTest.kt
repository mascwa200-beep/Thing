package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [MarketWindow] is what turns "the first 30-90 minutes after the story crossed the wires" from a
 * phrase in a prompt into a measurement — these cases pin the live-window vs reopen split, the
 * boundary inclusivity, and every null (dropped-tape-line) path, so the desk note can never be fed a
 * move the series doesn't actually contain.
 */
class MarketWindowTest {

    private val t0 = 1_700_000_000_000L // an arbitrary session start
    private val min = 60_000L

    /** Bars every 5 minutes from [t0], values 100, 101, 102… (index = bar number). */
    private fun session(count: Int, startMs: Long = t0) =
        List(count) { i -> MarketBar(startMs + i * 5 * min, 100.0 + i) }

    @Test
    fun liveWindowMeasuresBaselineToLastBarInsideTheWindow() {
        val bars = session(40) // t0 .. t0+195m
        val publish = t0 + 17 * min // baseline = bar 3 (t0+15m, value 103)
        val m = MarketWindow.windowMove(bars, publish)
        assertNotNull(m)
        assertFalse(m!!.reopen)
        assertEquals(103.0, m.fromValue, 1e-9)
        // publish+90m = t0+107m -> last bar inside is t0+105m = bar 21 (value 121)
        assertEquals(121.0, m.toValue, 1e-9)
        assertEquals(90, m.minutes) // t0+15m -> t0+105m
    }

    @Test
    fun publishExactlyOnABarUsesThatBarAsBaseline() {
        val bars = session(40)
        val m = MarketWindow.windowMove(bars, publishMs = t0 + 20 * min)
        assertNotNull(m)
        assertEquals(104.0, m!!.fromValue, 1e-9) // the t0+20m bar itself
    }

    @Test
    fun storyBeforeTheWholeSeriesIsNull() {
        assertNull(MarketWindow.windowMove(session(40), publishMs = t0 - 1))
    }

    @Test
    fun publishAtTheLastBarWithNothingAfterIsNull() {
        val bars = session(10) // last bar t0+45m
        assertNull(MarketWindow.windowMove(bars, publishMs = t0 + 45 * min))
    }

    @Test
    fun closedVenueFallsBackToTheReopenReadAgainstPriorClose() {
        // A Friday session, a weekend gap, a Monday session.
        val friday = session(12) // t0 .. t0+55m, last value 111
        val mondayStart = t0 + 48 * 60 * min
        val monday = session(30, mondayStart).map { it.copy(value = it.value + 100) } // 200, 201…
        val publish = t0 + 24 * 60 * min // mid-weekend — baseline (111) is ~23h stale
        val m = MarketWindow.windowMove(friday + monday, publish)
        assertNotNull(m)
        assertTrue(m!!.reopen)
        assertEquals(111.0, m.fromValue, 1e-9) // Friday's close
        // reopen bar = mondayStart; window end = mondayStart+90m lands exactly ON a bar -> included
        // by <= (unlike the live-window test, whose publish+90m falls between bars): bar 18, value 218.
        assertEquals(218.0, m.toValue, 1e-9)
        assertEquals(90, m.minutes)
    }

    @Test
    fun aSingleReopenPrintStillCountsAsTheGapMove() {
        val friday = session(12)
        val oneMondayBar = listOf(MarketBar(t0 + 48 * 60 * min, 205.0))
        val m = MarketWindow.windowMove(friday + oneMondayBar, publishMs = t0 + 24 * 60 * min)
        assertNotNull(m)
        assertTrue(m!!.reopen)
        assertEquals(111.0, m.fromValue, 1e-9)
        assertEquals(205.0, m.toValue, 1e-9)
        assertEquals(0, m.minutes)
    }

    @Test
    fun closedVenueWithNoReopenYetIsNull() {
        val friday = session(12)
        assertNull(MarketWindow.windowMove(friday, publishMs = t0 + 24 * 60 * min))
    }

    @Test
    fun nonFiniteBarsAndUnsortedInputAreHandled() {
        val bars = session(40).shuffled() + listOf(MarketBar(t0 + 2 * min, Double.NaN))
        val m = MarketWindow.windowMove(bars, publishMs = t0 + 17 * min)
        assertNotNull(m)
        assertEquals(103.0, m!!.fromValue, 1e-9) // the NaN bar never becomes the baseline
    }

    @Test
    fun invalidPublishAndEmptySeriesAreNull() {
        assertNull(MarketWindow.windowMove(session(10), publishMs = 0L))
        assertNull(MarketWindow.windowMove(emptyList(), publishMs = t0))
    }

    @Test
    fun pctAndDeltaReadTheMoveCorrectly() {
        val m = MarketWindow.Move(fromValue = 4.281, toValue = 4.265, minutes = 90, reopen = false)
        assertEquals(-0.016, m.delta, 1e-9) // -1.6bp when the unit is percent
        assertEquals(-0.3737, m.pct, 5e-4)
    }
}
