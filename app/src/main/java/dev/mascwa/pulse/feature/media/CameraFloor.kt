package dev.mascwa.pulse.feature.media

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Who currently holds the camera.
 *
 * ⚠️ **This exists because `ProcessCameraProvider.unbindAll()` unbinds EVERYTHING, not just the
 * caller's own use cases.** Until the barcode scanner, the Sensorium's [dev.mascwa.pulse.data.sensing.AmbientCameraSampler]
 * was the app's only CameraX client, and its own note says as much — it calls `unbindAll()` before
 * each burst and again on release precisely because it had nothing to fight.
 *
 * A second client makes that a real defect in both directions, and neither direction announces
 * itself:
 *
 *  - the sampler's next burst tears down the scanner's preview and analysis mid-scan, and the
 *    viewfinder simply freezes with nothing on screen to say why;
 *  - the scanner's teardown kills the sampler's binding, and the sampler carries on believing it is
 *    sampling while every frame stops arriving.
 *
 * The same shape as [MicFloor] and [MediaFloor], for the same reason: two claimants on one exclusive
 * device need somewhere to look. The scanner takes precedence while it is open — it is a deliberate
 * act lasting seconds, where ambient sensing is a background habit that loses nothing by pausing.
 */
object CameraFloor {

    private val _scanning = MutableStateFlow(false)

    /** True while a barcode scanner holds the camera. Ambient sensing yields for the duration. */
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    /** Claim the camera. Idempotent — a StateFlow does not re-emit an unchanged value. */
    fun claim() {
        _scanning.value = true
    }

    /**
     * Hand it back.
     *
     * ⚠️ Must be called from a Compose `onDispose` or an equivalent teardown that runs even when the
     * screen is left by the back gesture, a process-death recreation, or a navigation the scanner
     * did not initiate. A floor left claimed is ambient sensing switched off silently and for ever,
     * which is much worse than the collision it was written to prevent.
     */
    fun release() {
        _scanning.value = false
    }
}
