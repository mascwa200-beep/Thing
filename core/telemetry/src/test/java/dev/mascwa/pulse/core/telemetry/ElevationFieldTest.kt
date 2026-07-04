package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests for the DEM elevation-field bilinear sampling + origin anchoring. */
class ElevationFieldTest {

    // A 3×3 grid (res=2) spanning ±60 m. Row-major, iz (south→north) outer, ix (west→east) inner.
    private fun field(vararg e: Float) = ElevationField(60f, 2, e)

    @Test fun flatFieldIsZeroEverywhere() {
        val f = field(100f, 100f, 100f, 100f, 100f, 100f, 100f, 100f, 100f)
        assertTrue(f.isValid)
        assertEquals(0f, f.heightAt(0f, 0f), 1e-4f)
        assertEquals(0f, f.heightAt(-60f, 60f), 1e-4f)  // a corner
        assertEquals(0f, f.heightAt(30f, -20f), 1e-4f)
    }

    @Test fun originIsAlwaysZeroRelative() {
        // Centre sample is 105; heightAt(0,0) subtracts it → 0.
        val f = field(100f, 101f, 102f, 103f, 105f, 107f, 110f, 111f, 112f)
        assertEquals(0f, f.heightAt(0f, 0f), 1e-4f)
    }

    @Test fun eastWestRampIsMonotonicAndAnchored() {
        // Elevation rises west(100)→centre(110)→east(120) on every row; centre = 110.
        val f = field(100f, 110f, 120f, 100f, 110f, 120f, 100f, 110f, 120f)
        assertEquals(-10f, f.heightAt(-60f, 0f), 1e-3f)  // far west, 10 m below the player
        assertEquals(0f, f.heightAt(0f, 0f), 1e-3f)
        assertEquals(10f, f.heightAt(60f, 0f), 1e-3f)    // far east, 10 m above
        assertEquals(5f, f.heightAt(30f, 0f), 1e-3f)     // bilinear midpoint east
    }

    @Test fun northSouthRampUsesMinusZ() {
        // Rows: south(iz0)=200, centre(iz1)=210, north(iz2)=220; centre 210.
        val f = field(200f, 200f, 200f, 210f, 210f, 210f, 220f, 220f, 220f)
        // North is −z, so z = −60 (looking north) should be the +10 m northern row.
        assertEquals(10f, f.heightAt(0f, -60f), 1e-3f)
        assertEquals(-10f, f.heightAt(0f, 60f), 1e-3f)   // z = +60 is south, 10 m below
    }

    @Test fun edgesClampInsteadOfIndexingOutOfBounds() {
        val f = field(0f, 0f, 0f, 0f, 10f, 0f, 0f, 0f, 0f)
        // Well outside the ±60 m span → clamps to the edge sample, never throws.
        assertEquals(f.heightAt(60f, 0f), f.heightAt(1000f, 0f), 1e-4f)
        assertEquals(f.heightAt(-60f, 0f), f.heightAt(-1000f, 0f), 1e-4f)
    }

    @Test fun wrongSizeOrNonFiniteIsInvalidAndFlat() {
        assertFalse(ElevationField(60f, 2, floatArrayOf(1f, 2f, 3f)).isValid)               // too few
        assertFalse(ElevationField(60f, 2, FloatArray(9) { Float.NaN }).isValid)            // non-finite
        assertEquals(0f, ElevationField(60f, 2, floatArrayOf(1f)).heightAt(10f, 10f), 0f)   // invalid → flat 0
    }
}
