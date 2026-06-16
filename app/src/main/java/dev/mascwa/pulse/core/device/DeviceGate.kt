package dev.mascwa.pulse.core.device

import android.os.Build
import dev.mascwa.pulse.BuildConfig

/**
 * Pulse is an exclusive build for the Google Pixel 10 Pro XL.
 *
 * The gate matches the runtime [Build.MODEL] / [Build.DEVICE] against the
 * target declared in `BuildConfig.TARGET_DEVICE_MODEL`. Because Google's exact
 * MODEL string can vary by region/firmware, the match is a case-insensitive
 * substring and we also accept the Pixel 10 Pro XL device codenames. The sole
 * owner is never hard-locked: [evaluate] returns a status the UI can let the
 * user dismiss, so a slightly different model string can never brick the app.
 */
object DeviceGate {

    /** Known codenames/board strings for the Pixel 10 Pro XL family. */
    private val TARGET_CODENAMES = listOf("mustang", "pixel 10 pro xl")

    enum class Status { MATCH, MISMATCH }

    data class Result(
        val status: Status,
        val detectedModel: String,
        val detectedDevice: String,
    ) {
        val isMatch: Boolean get() = status == Status.MATCH
    }

    fun evaluate(): Result {
        val model = (Build.MODEL ?: "").trim()
        val device = (Build.DEVICE ?: "").trim()
        val product = (Build.PRODUCT ?: "").trim()
        val target = BuildConfig.TARGET_DEVICE_MODEL.trim()

        val haystack = listOf(model, device, product).joinToString(" ").lowercase()
        val matches = haystack.contains(target.lowercase()) ||
            TARGET_CODENAMES.any { haystack.contains(it) }

        return Result(
            status = if (matches) Status.MATCH else Status.MISMATCH,
            detectedModel = model.ifBlank { "unknown" },
            detectedDevice = device.ifBlank { "unknown" },
        )
    }
}
