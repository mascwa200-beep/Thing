package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** Tests for the 3D-AR orientation maths (quaternion / SLERP / lens FOV). */
class OrientationMathTest {

    private fun close(a: Quat, b: Quat, eps: Float = 1e-4f): Boolean =
        // q and -q are the same rotation, so accept either sign.
        (abs(a.x - b.x) < eps && abs(a.y - b.y) < eps && abs(a.z - b.z) < eps && abs(a.w - b.w) < eps) ||
            (abs(a.x + b.x) < eps && abs(a.y + b.y) < eps && abs(a.z + b.z) < eps && abs(a.w + b.w) < eps)

    @Test fun identityMatrixIsIdentityQuaternion() {
        val id = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        assertTrue(close(Quat.IDENTITY, OrientationMath.matrixToQuaternion(id)))
    }

    @Test fun ninetyAboutZ() {
        // Rotation of +90° about Z: [0 -1 0; 1 0 0; 0 0 1].
        val m = floatArrayOf(0f, -1f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)
        val q = OrientationMath.matrixToQuaternion(m)
        // Expected quaternion: (0, 0, sin45, cos45).
        assertTrue(close(q, Quat(0f, 0f, 0.70710677f, 0.70710677f)))
    }

    @Test fun quaternionStaysUnit() {
        val m = floatArrayOf(0f, -1f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)
        val q = OrientationMath.matrixToQuaternion(m)
        val n = q.x * q.x + q.y * q.y + q.z * q.z + q.w * q.w
        assertEquals(1f, n, 1e-4f)
    }

    @Test fun slerpEndpointsAndMidpoint() {
        val a = Quat.IDENTITY
        val b = Quat(0f, 0f, 0.70710677f, 0.70710677f) // 90° about Z
        assertTrue(close(a, OrientationMath.slerp(a, b, 0f)))
        assertTrue(close(b, OrientationMath.slerp(a, b, 1f)))
        // Halfway is a 45° rotation about Z: (0, 0, sin22.5, cos22.5).
        val mid = OrientationMath.slerp(a, b, 0.5f)
        assertTrue(close(mid, Quat(0f, 0f, 0.38268343f, 0.9238795f)))
    }

    @Test fun slerpTakesTheShortPath() {
        val a = Quat.IDENTITY
        // -b represents the same rotation as b; slerp must still track the short arc.
        val b = Quat(0f, 0f, -0.70710677f, -0.70710677f)
        val mid = OrientationMath.slerp(a, b, 0.5f)
        // Result is unit and near the 45°-about-Z rotation (either sign).
        val n = mid.x * mid.x + mid.y * mid.y + mid.z * mid.z + mid.w * mid.w
        assertEquals(1f, n, 1e-4f)
        assertTrue(close(mid, Quat(0f, 0f, 0.38268343f, 0.9238795f)))
    }

    @Test fun lensFovMatchesFormula() {
        // 24 mm sensor height, 50 mm focal → 2·atan(24/100) ≈ 27.0°.
        assertEquals(26.99, OrientationMath.verticalFovDegrees(24f, 50f), 0.1)
        // A wider (shorter focal) lens gives a larger FOV.
        assertTrue(OrientationMath.verticalFovDegrees(24f, 20f) > OrientationMath.verticalFovDegrees(24f, 50f))
    }

    @Test fun lensFovFallsBackWhenUnknown() {
        assertEquals(ArProjection.DEFAULT_VFOV_DEG, OrientationMath.verticalFovDegrees(0f, 50f), 1e-9)
        assertEquals(ArProjection.DEFAULT_VFOV_DEG, OrientationMath.verticalFovDegrees(24f, 0f), 1e-9)
    }
}
