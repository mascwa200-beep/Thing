package dev.mascwa.pulse.core.telemetry

import kotlin.math.acos
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** A unit quaternion (x, y, z, w) — the drift-free way to carry device orientation into the 3D camera. */
data class Quat(val x: Float, val y: Float, val z: Float, val w: Float) {
    fun normalized(): Quat {
        val n = sqrt(x * x + y * y + z * z + w * w)
        return if (n < 1e-8f) IDENTITY else Quat(x / n, y / n, z / n, w / n)
    }

    companion object {
        val IDENTITY = Quat(0f, 0f, 0f, 1f)
    }
}

/**
 * Pure orientation maths for the 3D AR camera (Slice 2): convert the device's rotation matrix (from
 * `SensorManager.getRotationMatrixFromVector` + `remapCoordinateSystem`, both Android-side) into a
 * quaternion, smooth it with SLERP so the world doesn't jitter, and derive the true camera field of view
 * from the lens. Quaternion end-to-end (never Euler) so the scene never gimbal-spins at ±90° pitch.
 * CI-tested; no Android types.
 */
object OrientationMath {

    /**
     * A 3×3 row-major rotation matrix (9 floats, `[m00 m01 m02 m10 m11 m12 m20 m21 m22]`) → a unit quaternion.
     * Shepperd's method: pick the largest diagonal term for numerical stability.
     */
    fun matrixToQuaternion(m: FloatArray): Quat {
        val m00 = m[0]; val m01 = m[1]; val m02 = m[2]
        val m10 = m[3]; val m11 = m[4]; val m12 = m[5]
        val m20 = m[6]; val m21 = m[7]; val m22 = m[8]
        val trace = m00 + m11 + m22
        return when {
            trace > 0f -> {
                val s = 0.5f / sqrt(trace + 1f)
                Quat((m21 - m12) * s, (m02 - m20) * s, (m10 - m01) * s, 0.25f / s)
            }
            m00 > m11 && m00 > m22 -> {
                val s = 2f * sqrt(1f + m00 - m11 - m22)
                Quat(0.25f * s, (m01 + m10) / s, (m02 + m20) / s, (m21 - m12) / s)
            }
            m11 > m22 -> {
                val s = 2f * sqrt(1f + m11 - m00 - m22)
                Quat((m01 + m10) / s, 0.25f * s, (m12 + m21) / s, (m02 - m20) / s)
            }
            else -> {
                val s = 2f * sqrt(1f + m22 - m00 - m11)
                Quat((m02 + m20) / s, (m12 + m21) / s, 0.25f * s, (m10 - m01) / s)
            }
        }.normalized()
    }

    /** Spherical-linear interpolation between unit quaternions [a]→[b] by [t] in 0..1, along the shortest arc. */
    fun slerp(a: Quat, b: Quat, t: Float): Quat {
        var bx = b.x; var by = b.y; var bz = b.z; var bw = b.w
        var dot = a.x * bx + a.y * by + a.z * bz + a.w * bw
        // Take the shorter path (q and -q are the same rotation).
        if (dot < 0f) { bx = -bx; by = -by; bz = -bz; bw = -bw; dot = -dot }
        if (dot > 0.9995f) {
            // Nearly parallel — a plain lerp + renormalize avoids a divide-by-~0.
            return Quat(
                a.x + (bx - a.x) * t, a.y + (by - a.y) * t,
                a.z + (bz - a.z) * t, a.w + (bw - a.w) * t,
            ).normalized()
        }
        val theta0 = acos(dot.coerceIn(-1f, 1f))
        val theta = theta0 * t
        val sinTheta0 = sin(theta0)
        val s0 = cos(theta) - dot * sin(theta) / sinTheta0
        val s1 = sin(theta) / sinTheta0
        return Quat(a.x * s0 + bx * s1, a.y * s0 + by * s1, a.z * s0 + bz * s1, a.w * s0 + bw * s1).normalized()
    }

    /**
     * Vertical field of view (degrees) for a lens: `2·atan(sensorHeight / (2·focalLength))`, both in the same
     * units (mm). Feeding the real lens FOV into the projection stops geometry sliding as you pan. Falls back
     * to [ArProjection.DEFAULT_VFOV_DEG] for non-positive inputs (lens data unavailable).
     */
    fun verticalFovDegrees(sensorHeightMm: Float, focalLengthMm: Float): Double {
        if (sensorHeightMm <= 0f || focalLengthMm <= 0f) return ArProjection.DEFAULT_VFOV_DEG
        return Math.toDegrees(2.0 * atan((sensorHeightMm / (2f * focalLengthMm)).toDouble()))
    }
}
