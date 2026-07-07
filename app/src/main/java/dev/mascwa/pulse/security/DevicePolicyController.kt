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

    // --- Lock the screen on demand (force-lock) ---------------------------------------------------

    /**
     * Immediately lock the screen (as if the power button were pressed). Requires the `<force-lock/>` policy
     * declared in device_admin.xml; DO-only + defensive → false. Used by the user-armed commitment lock to
     * make the phone genuinely lock (the full-screen gate then shows over the lock screen). Never wipes or
     * changes credentials — it only locks, so it can't strand the device.
     */
    fun lockNow(): Boolean = runCatching {
        if (!isDeviceOwner() || dpm == null) return false
        dpm.lockNow()
        true
    }.getOrDefault(false)

    // --- Lock task (kiosk) allow-list — for the self-care lockout ---------------------------------

    /**
     * Whitelist [packages] so they may enter device-owner lock task (kiosk pin) without a user prompt. Used
     * by the self-care lockout to pin itself (and keep the dialer reachable for emergencies). DO-only; true
     * if applied. Passing an empty list clears the allow-list.
     */
    fun setLockTaskPackages(packages: List<String>): Boolean = runCatching {
        if (!isDeviceOwner() || dpm == null) return false
        dpm.setLockTaskPackages(admin, packages.toTypedArray())
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

    // --- Reversible capability locks (used by the self-care phone penalties) --------------------
    // Each of these is a plain Device-Owner flag that toggles a single capability on/off. They are all
    // reversible: passing the opposite value restores the capability. Every call is a defensive no-op
    // unless Pulse is a Device Owner.

    /**
     * Add or clear a user restriction (an `UserManager.DISALLOW_*` key), e.g. block app installs or lock the
     * volume. DO-only; true if applied. The restriction is lifted by calling again with `on = false`.
     */
    fun setUserRestriction(key: String, on: Boolean): Boolean = runCatching {
        if (!isDeviceOwner() || dpm == null) return false
        if (on) dpm.addUserRestriction(admin, key) else dpm.clearUserRestriction(admin, key)
        true
    }.getOrDefault(false)

    /** Disable/enable the notification shade + quick-settings pulldown. DO-only; true if applied. */
    fun setStatusBarDisabled(disabled: Boolean): Boolean = runCatching {
        if (!isDeviceOwner() || dpm == null) return false
        dpm.setStatusBarDisabled(admin, disabled)
        true
    }.getOrDefault(false)

    /** Block/allow screen capture (screenshots + screen recording). DO-only; true if applied. */
    fun setScreenCaptureDisabled(disabled: Boolean): Boolean = runCatching {
        if (!isDeviceOwner() || dpm == null) return false
        dpm.setScreenCaptureDisabled(admin, disabled)
        true
    }.getOrDefault(false)

    /**
     * Suspend/unsuspend [packages] (tapping a suspended app shows the system "app paused" dialog). Reversible;
     * DO-only. Returns the packages that could NOT be suspended (the platform refuses some critical ones), or
     * null on failure. Never call this with Pulse's own package, the launcher or the dialer — the caller filters.
     */
    fun setPackagesSuspended(packages: List<String>, suspended: Boolean): Array<String>? = runCatching {
        if (!isDeviceOwner() || dpm == null || packages.isEmpty()) return emptyArray()
        dpm.setPackagesSuspended(admin, packages.toTypedArray(), suspended)
    }.getOrNull()
}
