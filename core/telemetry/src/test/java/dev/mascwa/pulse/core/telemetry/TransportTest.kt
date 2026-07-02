package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Test

/** Tests for the transport-mode classifier + per-mode distance accounting. */
class TransportTest {

    @Test fun classifyBySpeedBuckets() {
        assertEquals(TransportMode.STILL, Transport.classify(0.1))
        assertEquals(TransportMode.WALK, Transport.classify(1.4))   // ~5 km/h
        assertEquals(TransportMode.RUN, Transport.classify(3.5))    // ~13 km/h
        assertEquals(TransportMode.CYCLE, Transport.classify(6.0))  // ~22 km/h
        assertEquals(TransportMode.DRIVE, Transport.classify(20.0)) // ~72 km/h
    }

    @Test fun stillOverridesAnyHint() {
        // Not moving → STILL even if the platform still thinks you're in a vehicle (just parked).
        assertEquals(TransportMode.STILL, Transport.classify(0.0, TransportMode.DRIVE))
    }

    @Test fun clearlyFastOverridesAWalkHint() {
        // 30 km/h+ can't be walking, whatever the stale hint says.
        assertEquals(TransportMode.DRIVE, Transport.classify(12.0, TransportMode.WALK))
    }

    @Test fun movingHintDisambiguatesRunVsCycle() {
        // 4.0 m/s reads as RUN by speed, but the platform says bicycle → trust the hint.
        assertEquals(TransportMode.RUN, Transport.classify(4.0))
        assertEquals(TransportMode.CYCLE, Transport.classify(4.0, TransportMode.CYCLE))
    }

    @Test fun accrueAddsPerModeAndIgnoresStillAndNegatives() {
        var t = emptyMap<TransportMode, Long>()
        t = Transport.accrue(t, TransportMode.WALK, 500)
        t = Transport.accrue(t, TransportMode.WALK, 300)
        t = Transport.accrue(t, TransportMode.DRIVE, 4000)
        t = Transport.accrue(t, TransportMode.STILL, 999)   // ignored
        t = Transport.accrue(t, TransportMode.RUN, -50)     // ignored
        assertEquals(800L, Transport.distance(t, TransportMode.WALK))
        assertEquals(4000L, Transport.distance(t, TransportMode.DRIVE))
        assertEquals(0L, Transport.distance(t, TransportMode.STILL))
        assertEquals(0L, Transport.distance(t, TransportMode.RUN))
    }

    @Test fun onFootSumsWalkAndRun() {
        var t = emptyMap<TransportMode, Long>()
        t = Transport.accrue(t, TransportMode.WALK, 1200)
        t = Transport.accrue(t, TransportMode.RUN, 800)
        t = Transport.accrue(t, TransportMode.CYCLE, 5000)
        assertEquals(2000L, Transport.onFoot(t))
        assertEquals(7000L, Transport.total(t)) // walk + run + cycle
    }
}
