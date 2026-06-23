package dev.mascwa.pulse.security

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent

/**
 * The device-admin component that lets Pulse be provisioned as a **Device Owner**. Provisioning is a
 * one-time, owner-driven step over adb on a device with no other accounts:
 *
 *   `adb shell dpm set-device-owner dev.mascwa.pulse.debug/dev.mascwa.pulse.security.PulseDeviceAdminReceiver`
 *
 * Device-Owner status is the documented exception to the Android 10+ rule that bars ordinary apps from
 * calling `WifiManager.setWifiEnabled`, so it is what makes Trusted Network Mode's radio control actually
 * work — on stock Android and GrapheneOS alike. The receiver itself holds **no** policy logic and requests
 * no intrusive powers (no wipe/lock/password); it exists only so the privilege can be granted.
 * [WifiPolicyController] re-checks `isDeviceOwnerApp` before ever touching the radio.
 */
class PulseDeviceAdminReceiver : DeviceAdminReceiver() {
    override fun onEnabled(context: Context, intent: Intent) { /* provisioning marker only */ }
    override fun onDisabled(context: Context, intent: Intent) { /* provisioning marker only */ }
}
