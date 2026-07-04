package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests for the OSM footprint → local-AR-frame projection maths + height estimate. */
class BuildingFootprintsTest {

    @Test fun metersPerDegLonShrinksWithLatitude() {
        assertEquals(111_320.0, BuildingFootprints.metersPerDegLon(0.0), 1e-6)          // full at the equator
        assertEquals(111_320.0 * 0.5, BuildingFootprints.metersPerDegLon(60.0), 1.0)    // ~half at 60°N
    }

    @Test fun toLocalPutsEastOnPlusXAndNorthOnMinusZ() {
        // A point one lon-degree east at the equator → ~111.32 km east, on the ground line (z≈0).
        val east = BuildingFootprints.toLocal(0.0, 0.0, 0.0, 1.0)
        assertEquals(111_320f, east.x, 1f)
        assertEquals(0f, east.z, 1f)
        // A point to the north → −Z (ahead when you face north), x≈0.
        val north = BuildingFootprints.toLocal(0.0, 0.0, 1.0, 0.0)
        assertTrue("north should be −Z", north.z < 0f)
        assertEquals(-111_320f, north.z, 1f)
        assertEquals(0f, north.x, 1f)
    }

    @Test fun toLocalOriginIsZero() {
        val p = BuildingFootprints.toLocal(51.5, -0.1, 51.5, -0.1)
        assertEquals(0f, p.x, 0.01f)
        assertEquals(0f, p.z, 0.01f)
    }

    @Test fun projectTrimsRepeatedClosingVertexAndDropsDegenerate() {
        val closed = BuildingFootprint(
            listOf(GeoPoint(0.0, 0.0), GeoPoint(0.0, 0.001), GeoPoint(0.001, 0.001), GeoPoint(0.0, 0.0)),
            heightM = 9f,
        )
        val degenerate = BuildingFootprint(listOf(GeoPoint(0.0, 0.0)), heightM = 9f)
        val out = BuildingFootprints.project(0.0, 0.0, listOf(closed, degenerate))
        assertEquals("degenerate ring dropped", 1, out.size)
        assertEquals("closing duplicate trimmed (4 → 3)", 3, out[0].points.size)
        assertEquals(9f, out[0].heightM, 0f)
    }

    @Test fun estimateHeightPrefersExplicitHeightThenLevelsThenDefault() {
        assertEquals(15f, BuildingFootprints.estimateHeight("15", null), 0f)
        assertEquals(15f, BuildingFootprints.estimateHeight("15 m", null), 0f)   // tolerates a unit suffix
        assertEquals(12.5f, BuildingFootprints.estimateHeight("12.5", null), 0f)
        assertEquals(12f, BuildingFootprints.estimateHeight(null, "4"), 0f)      // 4 levels × 3 m
        assertEquals(BuildingFootprints.DEFAULT_HEIGHT, BuildingFootprints.estimateHeight(null, null), 0f)
        assertEquals(BuildingFootprints.DEFAULT_HEIGHT, BuildingFootprints.estimateHeight("garbage", "nope"), 0f)
    }

    @Test fun estimateHeightClampsAbsurdValues() {
        assertEquals(120f, BuildingFootprints.estimateHeight("99999", null), 0f) // clamped high
        assertEquals(2f, BuildingFootprints.estimateHeight("0.1", null), 0f)     // clamped low
        assertEquals(120f, BuildingFootprints.estimateHeight(null, "500"), 0f)   // 500 levels → clamped
    }
}
