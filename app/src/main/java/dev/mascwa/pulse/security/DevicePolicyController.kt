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

    fun isDeviceOwner(): Boolean = DevicePolicyController.isDeviceOwner(appContext)

    /**
     * Why the device-policy controls cannot take effect, or null when they can.
     *
     * ⚠️ Every setter below returns a bare `false` on refusal, which conflates three situations a
     * person needs told apart: this build has no policy service at all, LCARS was never provisioned,
     * and the platform rejected a value it would normally accept. The first two are answered here in
     * words a reader can act on; the third is left to the caller, which is the only place that knows
     * what was being attempted.
     */
    fun unavailableReason(): String? = DevicePolicyController.unavailableReason(appContext)

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
     * declared in device_admin.xml; DO-only + defensive → false. Never wipes or changes credentials — it
     * only locks, so it cannot strand the device.
     *
     * ⚠️ **No call site.** It was written for the user-armed commitment lock, which was deleted with
     * the game. `<force-lock/>` therefore remains declared in `res/xml/device_admin.xml` for a power
     * nothing currently uses — worth knowing before that file is read as a list of live capabilities.
     */
    fun lockNow(): Boolean = runCatching {
        if (!isDeviceOwner() || dpm == null) return false
        dpm.lockNow()
        true
    }.getOrDefault(false)

    // --- Lock task (kiosk) allow-list — for the self-care lockout ---------------------------------

    /**
     * Whitelist [packages] so they may enter device-owner lock task (kiosk pin) without a user prompt.
     * DO-only; true if applied. Passing an empty list clears the allow-list.
     *
     * ⚠️ **No call site.** It was written for the self-care lockout, which was deleted with the game.
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

    // --- Reversible capability locks -------------------------------------------------------------
    // Each of these is a plain Device-Owner flag that toggles a single capability on/off. They are all
    // reversible: passing the opposite value restores the capability. Every call is a defensive no-op
    // unless Pulse is a Device Owner.
    //
    // ⚠️ **These four have NO call site, and the comment that used to sit here named one that no
    // longer exists.** They were written for the self-care phone-penalty system, which was deleted
    // with the game; a grep confirms setUserRestriction / setStatusBarDisabled /
    // setScreenCaptureDisabled / setPackagesSuspended are called from nowhere in the app. Kept rather
    // than deleted because each is a correct, tested-by-inspection wrapper over one platform call and
    // re-deriving the argument shapes later costs more than the lines do — but nothing here is live,
    // and a reader should not have to discover that by searching.

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

    companion object {
        /**
         * The ONE definition of "is LCARS this device's owner".
         *
         * ⚠️ There were two, byte-for-byte identical — this class and [WifiPolicyController] each kept
         * their own — which is the duplicated-definition drift this repository has corrected seven
         * times. They cannot disagree now.
         *
         * On the companion so a caller does not have to build a controller to ask. Fully defensive: a
         * missing policy service or a denied call reads as "not the owner", which is the safe
         * direction — every capability behind it is then simply unavailable rather than attempted.
         */
        fun isDeviceOwner(context: Context): Boolean = runCatching {
            val app = context.applicationContext
            app.getSystemService(DevicePolicyManager::class.java)
                ?.isDeviceOwnerApp(app.packageName) == true
        }.getOrDefault(false)

        /**
         * Why the device-policy controls cannot take effect on this phone, in words, or null when they
         * can.
         *
         * ⚠️ **This is the sentence, and it lives in one place on purpose.** Settings used to carry its
         * own hand-written version, so the explanation could drift from the check that produces it. Any
         * surface that disables a device-owner control should render this beside it rather than writing
         * its own account of why.
         *
         * The provisioning command is stated in full because the precondition is the part people get
         * wrong: `dpm set-device-owner` is refused on a device with ANY account signed in, so in
         * practice it means a factory reset first. Saying "provision LCARS as Device Owner" without
         * that is an instruction nobody can follow.
         */
        fun unavailableReason(context: Context): String? {
            val app = context.applicationContext
            val dpm = runCatching {
                app.getSystemService(DevicePolicyManager::class.java)
            }.getOrNull()
            if (dpm == null) {
                return "This Android build has no device-policy service, so these controls cannot " +
                    "exist here at all."
            }
            if (!isDeviceOwner(app)) {
                return "These are hardware-backed protections that only a device owner may set, and " +
                    "LCARS is not this device's owner. Provisioning it needs a phone with NO accounts " +
                    "signed in — in practice a factory reset — and then, over adb:\n\n" +
                    "adb shell dpm set-device-owner " +
                    "${app.packageName}/dev.mascwa.pulse.security.PulseDeviceAdminReceiver\n\n" +
                    "Everything else in LCARS works without it."
            }
            return null
        }
    }
}
