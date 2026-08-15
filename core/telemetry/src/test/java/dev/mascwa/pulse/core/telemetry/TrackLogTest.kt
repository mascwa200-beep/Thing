package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackLogTest {

    private val t0 = 1_786_752_000_000L

    private fun p(lat: Double, lon: Double, atMs: Long = t0, alt: Double? = null) =
        TrackLog.TrackPoint(lat, lon, atMs, alt)

    @Test fun aTrackHasToStartSomewhere() {
        assertTrue(TrackLog.accept(null, p(51.5, -0.1), accuracyM = 12.0))
        // But not from a fix the receiver itself admits is a guess.
        assertFalse(TrackLog.accept(null, p(51.5, -0.1), accuracyM = 400.0))
        assertFalse(TrackLog.accept(null, p(51.5, -0.1), accuracyM = Double.NaN))
        // An unreported accuracy is not the same as a bad one.
        assertTrue(TrackLog.accept(null, p(51.5, -0.1), accuracyM = null))
    }

    @Test fun aPhoneOnATableDoesNotWalkAnywhere() {
        // Successive fixes a few metres apart, which is what a stationary receiver produces all
        // day. Recording them would draw a walk that never happened.
        val first = p(51.500000, -0.100000, t0)
        val jitter = p(51.500030, -0.100010, t0 + 5_000)  // about 3.5 m
        assertFalse(TrackLog.accept(first, jitter, accuracyM = 8.0))
        // Real movement past the floor is kept.
        val walked = p(51.500180, -0.100000, t0 + 20_000)  // about 20 m
        assertTrue(TrackLog.accept(first, walked, accuracyM = 8.0))
    }

    @Test fun theThresholdFollowsHowWellThePositionIsKnown() {
        val first = p(51.500000, -0.100000, t0)
        val step = p(51.500135, -0.100000, t0 + 30_000)  // about 15 m
        // With a sharp fix that is a real step.
        assertTrue(TrackLog.accept(first, step, accuracyM = 5.0))
        // With a 30 m accuracy the same step is indistinguishable from noise.
        assertFalse(TrackLog.accept(first, step, accuracyM = 30.0))
    }

    @Test fun aFixFromTheNextCountyIsNotAJourney() {
        val here = p(51.5, -0.1, t0)
        val teleport = p(52.5, -1.9, t0 + 10_000)  // ~160 km in ten seconds
        assertFalse(TrackLog.accept(here, teleport, accuracyM = 10.0))
        // The same distance over a plausible span is fine — that is a flight, not a glitch.
        assertTrue(TrackLog.accept(here, p(52.5, -1.9, t0 + 3_600_000), accuracyM = 10.0))
    }

    @Test fun fixesThatArriveOutOfOrderAreJudgedOnDistanceAlone() {
        val here = p(51.5, -0.1, t0)
        // A timestamp at or before the previous one cannot be speed-checked; dividing by it would
        // be a crash or a nonsense verdict, so only the distance rule applies.
        assertTrue(TrackLog.accept(here, p(51.502, -0.1, t0), accuracyM = 5.0))
        assertTrue(TrackLog.accept(here, p(51.502, -0.1, t0 - 5_000), accuracyM = 5.0))
    }

    @Test fun impossibleCoordinatesAreRefused() {
        assertFalse(TrackLog.accept(null, p(91.0, 0.0)))
        assertFalse(TrackLog.accept(null, p(0.0, 181.0)))
        assertFalse(TrackLog.accept(null, p(Double.NaN, 0.0)))
    }

    @Test fun distanceAndDurationAddUpAlongTheTrack() {
        val track = listOf(
            p(51.5000, -0.1000, t0),
            p(51.5090, -0.1000, t0 + 600_000),
            p(51.5180, -0.1000, t0 + 1_200_000),
        )
        // A degree of latitude is about 111 km, so 0.018 of one is a shade over 2 km.
        assertEquals(2001.0, TrackLog.distanceMeters(track), 25.0)
        assertEquals(1_200_000L, TrackLog.durationMs(track))
        // Degenerate tracks are zero, not a crash.
        assertEquals(0.0, TrackLog.distanceMeters(emptyList()), 0.0)
        assertEquals(0L, TrackLog.durationMs(listOf(track.first())))
    }

    @Test fun climbIgnoresTheNoiseInGpsAltitude() {
        // A flat walk whose reported altitude wobbles by a metre or two either way.
        val flat = listOf(
            p(51.500, -0.1, t0, 100.0), p(51.501, -0.1, t0 + 1, 101.5),
            p(51.502, -0.1, t0 + 2, 99.0), p(51.503, -0.1, t0 + 3, 100.8),
        )
        assertEquals(0.0, TrackLog.ascentMeters(flat), 0.001)
        // A real climb of 40 m, taken in steps that each clear the floor.
        val hill = listOf(
            p(51.500, -0.1, t0, 100.0), p(51.501, -0.1, t0 + 1, 120.0),
            p(51.502, -0.1, t0 + 2, 140.0),
        )
        assertEquals(40.0, TrackLog.ascentMeters(hill), 0.001)
        // Coming back down does not subtract from the climb.
        assertEquals(40.0, TrackLog.ascentMeters(hill + p(51.503, -0.1, t0 + 3, 100.0)), 0.001)
        // Points with no altitude at all are simply not climbed.
        assertEquals(0.0, TrackLog.ascentMeters(flat.map { it.copy(altitudeM = null) }), 0.001)
    }

    @Test fun aTrackIsNotAnArchive() {
        val many = (0 until 100).map { p(51.5 + it * 0.001, -0.1, t0 + it * 1000L) }
        val kept = TrackLog.capped(many, 10)
        assertEquals(10, kept.size)
        // The most recent ones survive, which is the half anyone is looking at.
        assertEquals(many.last(), kept.last())
        assertEquals(many[90], kept.first())
        assertEquals(many, TrackLog.capped(many, 1000))
    }
}
