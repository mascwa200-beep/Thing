package dev.mascwa.pulse.security

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context

/**
 * Opt-in **Device-Owner** security policies for Pulse. These powers exist only because the owner
 * provisioned the device over adb; each is surfaced one-by-one in Settings, default-off, and reversible.
 *
 * Every call is fully defensive: it is a no-op returning a safe default unless Pulse is actually a Device
 * Owner, and a missing service or a denied platform call can never crash the caller. The matching
 * `<uses-policies>` for the camera/wipe controls are declared in `res/xml/device_admin.xml`.
 */
class DevicePolicyController(context: Context) {

    private val appContext = context.applicationContext
    private val dpm = appContext.getSystemService(DevicePolicyManager::class.java)
    private val admin = ComponentName(appContext, PulseDeviceAdminReceiver::class.java)

    fun isDeviceOwner(): Boolean =
        runCatching { dpm?.isDeviceOwnerApp(appContext.packageName) == true }.getOrDefault(false)

    // --- USB data signaling (charging-only port; anti-forensic) ----------------------------------

    /** Whether the hardware can disable USB *data* signaling at all (Pixels can). */
    fun usbDataControlSupported(): Boolean =
        runCatching { dpm?.canUsbDataSignalingBeDisabled() == true }.getOrDefault(false)

    /** Whether USB data is currently enabled (true = data works; false = charging-only). */
    fun isUsbDataEnabled(): Boolean =
        runCatching { dpm?.isUsbDataSignalingEnabled() != false }.getOrDefault(true)

    /** Enable/disable USB data lines (charging always works regardless). DO-only; true if applied. */
    fun setUsbDataEnabled(enabled: Boolean): Boolean = runCatching {
        if (!isDeviceOwner() || dpm == null) return false
        dpm.setUsbDataSignalingEnabled(enabled)
        true
    }.getOrDefault(false)

    // --- Camera kill switch ----------------------------------------------------------------------

    fun isCameraDisabled(): Boolean =
        runCatching { dpm?.getCameraDisabled(admin) == true }.getOrDefault(false)

    /** Hardware-disable/enable every camera device-wide. DO-only; true if applied. */
    fun setCameraDisabled(disabled: Boolean): Boolean = runCatching {
        if (!isDeviceOwner() || dpm == null) return false
        dpm.setCameraDisabled(admin, disabled)
        true
    }.getOrDefault(false)

    // --- Wipe after N failed unlocks (anti-theft / anti-coercion) --------------------------------

    /** The failed-unlock count that triggers a factory wipe; 0 = disabled. */
    fun maxFailedForWipe(): Int =
        runCatching { dpm?.getMaximumFailedPasswordsForWipe(admin) ?: 0 }.getOrDefault(0)

    /** Arm/disarm the failed-unlock wipe (0 = off). DO-only; true if applied. */
    fun setMaxFailedForWipe(count: Int): Boolean = runCatching {
        if (!isDeviceOwner() || dpm == null) return false
        dpm.setMaximumFailedPasswordsForWipe(admin, count.coerceAtLeast(0))
        true
    }.getOrDefault(false)
}
