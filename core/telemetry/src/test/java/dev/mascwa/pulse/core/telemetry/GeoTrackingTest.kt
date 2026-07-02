package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests for the elevation-climb + exploration-cell maths. */
class GeoTrackingTest {

    @Test fun elevationCountsOnlyRealAscent() {
        assertEquals(100, GeoTracking.elevationGain(200.0, 300.0)) // +100 m climb
        assertEquals(0, GeoTracking.elevationGain(300.0, 200.0))   // descent → 0
        assertEquals(0, GeoTracking.elevationGain(200.0, 201.0))   // 1 m < noise floor → 0
        assertEquals(0, GeoTracking.elevationGain(null, 300.0))    // missing → 0
        assertEquals(0, GeoTracking.elevationGain(200.0, null))    // missing → 0
    }

    @Test fun sameSpotIsSameCell() {
        assertEquals(GeoTracking.cellId(51.5074, -0.1278), GeoTracking.cellId(51.5074, -0.1278))
        // A tiny nudge inside the ~111 m cell stays the same cell.
        assertEquals(GeoTracking.cellId(51.50741, -0.12781), GeoTracking.cellId(51.50749, -0.12789))
    }

    @Test fun differentAreasAreDifferentCells() {
        assertNotEquals(GeoTracking.cellId(51.5074, -0.1278), GeoTracking.cellId(51.5200, -0.1278))
        assertNotEquals(GeoTracking.cellId(51.5074, -0.1278), GeoTracking.cellId(51.5074, -0.1500))
    }

    @Test fun distinctCellsCountExploration() {
        val visited = mutableSetOf<String>()
        // Walk across three ~111 m cells, doubling back over one.
        listOf(0.0 to 0.0, 0.0 to 0.0, 0.0011 to 0.0, 0.0022 to 0.0, 0.0 to 0.0).forEach { (la, lo) ->
            visited += GeoTracking.cellId(la, lo)
        }
        assertEquals(3, visited.size) // three distinct cells despite the revisit
    }

    @Test fun negativeCoordsBucketConsistently() {
        val c = GeoTracking.cellId(-33.8688, 151.2093) // southern + eastern
        assertTrue(c.contains("_"))
        assertEquals(c, GeoTracking.cellId(-33.8688, 151.2093))
    }
}
