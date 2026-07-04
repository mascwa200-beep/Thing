package dev.mascwa.pulse.core.telemetry

/**
 * A sampled real-world **elevation field** around the player — the INVISIBLE anchor for the AR wasteland
 * floor. The solid ground is drawn following this topography so it sits on the *real* ground (doesn't float
 * above it or clip through it), but the field itself is never drawn — it's a height map used only to place
 * the wasteland, exactly as the owner asked ("not a visible map, one to use to know where the real ground
 * is... anchored to the real ground"). Pure + deterministic so CI gates it; the DEM fetch lives in the app
 * layer ([dev.mascwa.pulse.data.ar.ElevationRepository]).
 *
 * The samples are a row-major (res+1)×(res+1) grid spanning ±[halfSpanM] metres (east × north) around the
 * origin (the player's GPS fix): index `iz * (res+1) + ix`, where ix runs west→east and iz runs south→north.
 * [heightAt] bilinearly interpolates and returns metres **relative to the origin's own elevation**, so the
 * ground directly under the player is ~0 (the eye-height camera then sits the right height above it).
 */
class ElevationField(
    val halfSpanM: Float,
    val res: Int,                          // cells per axis; the grid is (res+1)² samples
    private val elevations: FloatArray,    // row-major, size (res+1)², metres above sea level
) {
    private val n = res + 1

    /** True only if the sample count matches the grid and every value is finite (a usable field). */
    val isValid: Boolean = res >= 1 && elevations.size == n * n && elevations.all { it.isFinite() }

    // The player's own ground elevation (grid centre), subtracted so heightAt(0,0) ≈ 0.
    private val originElev: Float = if (isValid) sampleRaw(0f, 0f) else 0f

    /**
     * Ground height at local (x = east, z = −north) metres, **relative to the player** (≈ 0 under the player,
     * rising/falling with the real terrain). Returns 0 for an invalid field so the renderer stays flat-anchored.
     */
    fun heightAt(x: Float, z: Float): Float = if (isValid) sampleRaw(x, z) - originElev else 0f

    /** Bilinear sample of the raw DEM (metres ASL) at local (x, z); clamps to the grid at the edges. */
    private fun sampleRaw(x: Float, z: Float): Float {
        val span = 2f * halfSpanM
        val gx = ((x + halfSpanM) / span * res).coerceIn(0f, res.toFloat())
        val gz = ((-z + halfSpanM) / span * res).coerceIn(0f, res.toFloat()) // north = −z
        val x0 = gx.toInt().coerceIn(0, res - 1); val x1 = x0 + 1
        val z0 = gz.toInt().coerceIn(0, res - 1); val z1 = z0 + 1
        val tx = gx - x0
        val tz = gz - z0
        val a = at(x0, z0) + (at(x1, z0) - at(x0, z0)) * tx
        val b = at(x0, z1) + (at(x1, z1) - at(x0, z1)) * tx
        return a + (b - a) * tz
    }

    private fun at(ix: Int, iz: Int): Float = elevations[iz * n + ix]
}
