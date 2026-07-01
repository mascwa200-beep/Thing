package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationGateTest {

    private val shop = GameLocation("s1", "Mick's", LocationKind.TRADER, 40.0, -74.0)

    @Test fun atTheLocationWhenOnTopOfIt() {
        assertTrue(LocationGate.isAtLocation(40.0, -74.0, shop))
        assertNull(LocationGate.reachHint(40.0, -74.0, shop))
    }

    @Test fun withinReachCounts() {
        // ~22 m north (0.0002° lat ≈ 22 m) — inside the 60 m reach radius.
        assertTrue(LocationGate.isAtLocation(40.0002, -74.0, shop))
    }

    @Test fun outOfReachIsBlocked() {
        // ~111 m north (0.001° lat) — outside the reach radius.
        assertFalse(LocationGate.isAtLocation(40.001, -74.0, shop))
        val hint = LocationGate.reachHint(40.001, -74.0, shop)
        assertTrue(hint != null && hint.endsWith("m away"))
    }

    @Test fun farAwayReadsInKm() {
        // ~1° lat ≈ 111 km away.
        assertFalse(LocationGate.isAtLocation(41.0, -74.0, shop))
        val hint = LocationGate.reachHint(41.0, -74.0, shop)
        assertTrue(hint != null && hint.endsWith("km away"))
    }

    @Test fun unknownPositionIsNeverAtLocation() {
        assertFalse(LocationGate.isAtLocation(null, null, shop))
        assertFalse(LocationGate.isAtLocation(40.0, null, shop))
        assertNull(LocationGate.distanceTo(null, null, shop))
        assertTrue(LocationGate.reachHint(null, null, shop) == "location unknown")
    }
}
