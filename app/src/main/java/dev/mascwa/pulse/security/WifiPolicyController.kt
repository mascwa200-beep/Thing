package dev.mascwa.pulse.security

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.net.wifi.WifiManager

/**
 * Thin, defensive wrapper over the Wi-Fi radio for Trusted Network Mode. It only ever toggles the radio
 * when Pulse is a **Device Owner** — the documented exception to the Android 10+ ban on apps calling
 * `setWifiEnabled`. On a non-provisioned device [setWifiEnabled] is a no-op that returns `false`, so the
 * monitor can fall back to notifying the user instead of silently doing nothing.
 *
 * Nothing here is privileged beyond the radio toggle + reading Wi-Fi state; every system call is guarded
 * so a missing service or a revoked permission can never crash the monitor.
 */
class WifiPolicyController(context: Context) {

    private val appContext = context.applicationContext
    private val wifi = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private val dpm = appContext.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager

    /** True once Pulse has been provisioned as Device Owner → radio toggling will actually take effect. */
    fun isDeviceOwner(): Boolean =
        runCatching { dpm?.isDeviceOwnerApp(appContext.packageName) == true }.getOrDefault(false)

    /** Whether the Wi-Fi radio is currently enabled (independent of whether it's associated). */
    fun isWifiEnabled(): Boolean =
        runCatching { wifi?.isWifiEnabled == true }.getOrDefault(false)

    /**
     * Try to enable/disable the Wi-Fi radio. Returns `true` only when the call is expected to have taken
     * effect (we are a Device Owner and the platform accepted it). On a non-provisioned device it does
     * nothing and returns `false` so the caller can degrade to a notification.
     */
    @Suppress("DEPRECATION")
    fun setWifiEnabled(enable: Boolean): Boolean {
        val w = wifi ?: return false
        if (!isDeviceOwner()) return false
        return runCatching { w.setWifiEnabled(enable) }.getOrDefault(false)
    }

    /**
     * Best-effort SSID of the associated Wi-Fi network. Needs location permission + an active Wi-Fi
     * association; returns `null` (or the `<unknown ssid>` sentinel that [dev.mascwa.pulse.core.telemetry
     * .TrustedNetwork.normalizeSsid] discards) when unreadable. The monitor also reads the SSID from the
     * connectivity callback's transport info where available.
     */
    @Suppress("DEPRECATION")
    fun currentSsid(): String? = runCatching {
        wifi?.connectionInfo?.ssid?.takeIf { it.isNotBlank() }
    }.getOrNull()
}
