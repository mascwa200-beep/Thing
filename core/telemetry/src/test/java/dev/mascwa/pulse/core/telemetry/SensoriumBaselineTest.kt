package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the learned-normality engine: a young cell must refuse to judge, an established cell must flag
 * only real deviations, weekday and weekend normals must not contaminate each other, and the adaptive
 * threshold must widen for environments that are naturally variable at that hour.
 */
class SensoriumBaselineTest {

    private val quietNight = EnvMetrics(noise = 1f, light = 0.3f, motion = 0.01f, crowd = 0f)
    private val loudNight = EnvMetrics(noise = 4f, light = 0.3f, motion = 0.01f, crowd = 0f)

    private fun learned(metrics: EnvMetrics, hour: Int, weekend: Boolean, n: Int): BaselineState {
        var s = BaselineState()
        repeat(n) { s = SensoriumBaseline.update(s, metrics, hour, weekend) }
        return s
    }

    @Test
    fun aYoungCellJudgesNothing() {
        val s = learned(quietNight, 3, false, SensoriumBaseline.MIN_SAMPLES - 1)
        assertTrue(SensoriumBaseline.anomalies(s, loudNight, 3, false).isEmpty())
        assertNull(SensoriumBaseline.describeNormal(s, 3, false))
    }

    @Test
    fun anEstablishedQuietNightFlagsLoudness() {
        val s = learned(quietNight, 3, false, 40)
        val anomalies = SensoriumBaseline.anomalies(s, loudNight, 3, false)
        assertTrue(anomalies.isNotEmpty())
        val noise = anomalies.first { it.metric == "noise" }
        assertTrue(noise.text.contains("unusually loud"))
        assertTrue(noise.text.contains("03:00"))
        assertTrue(noise.text.contains("weekday"))
    }

    @Test
    fun theSameLevelAtTheSameHourFlagsNothing() {
        val s = learned(quietNight, 3, false, 40)
        assertTrue(SensoriumBaseline.anomalies(s, quietNight, 3, false).isEmpty())
    }

    @Test
    fun weekendAndWeekdayNormalsAreSeparate() {
        // Loud learned ONLY on weekends; the same loudness on a weekday night must flag.
        val s = learned(loudNight, 23, true, 40)
        assertTrue(SensoriumBaseline.anomalies(s, loudNight, 23, true).isEmpty())
        // The weekday cell has no samples at all — refuses to judge rather than judging wrongly.
        assertTrue(SensoriumBaseline.anomalies(s, loudNight, 23, false).isEmpty())
    }

    @Test
    fun aNaturallyVariableHourWidensItsOwnThreshold() {
        // Alternate quiet and lively for 60 samples — the learned deviation grows, so LIVELY (3) must
        // not flag in an environment that routinely swings between 1 and 3 at this hour.
        var s = BaselineState()
        repeat(60) { i ->
            val m = if (i % 2 == 0) quietNight else quietNight.copy(noise = 3f)
            s = SensoriumBaseline.update(s, m, 18, false)
        }
        val lively = quietNight.copy(noise = 3f)
        assertTrue(SensoriumBaseline.anomalies(s, lively, 18, false).none { it.metric == "noise" })
    }

    @Test
    fun describeNormalReadsPlainly() {
        val s = learned(quietNight, 15, false, 40)
        val line = SensoriumBaseline.describeNormal(s, 15, false)
        assertNotNull(line)
        assertTrue(line!!.contains("weekday 15:00"))
        assertTrue(line.contains("alone"))
    }

    @Test
    fun bucketsCoverTheFullWeekAndClampBadHours() {
        assertEquals(SensoriumBaseline.bucket(3, false), SensoriumBaseline.bucket(3, false))
        assertTrue(SensoriumBaseline.bucket(3, false) != SensoriumBaseline.bucket(3, true))
        assertTrue(SensoriumBaseline.bucket(3, false) != SensoriumBaseline.bucket(4, false))
        assertEquals(SensoriumBaseline.bucket(23, true), SensoriumBaseline.bucket(99, true))
    }

    @Test
    fun anomalyStrengthOrdersTheWorstFirst() {
        val s = learned(quietNight, 3, false, 40)
        val wild = EnvMetrics(noise = 4f, light = 3.5f, motion = 0.01f, crowd = 0f)
        val anomalies = SensoriumBaseline.anomalies(s, wild, 3, false)
        assertTrue(anomalies.size >= 2)
        assertTrue(anomalies[0].strength >= anomalies[1].strength)
    }
}
