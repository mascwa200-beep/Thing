package dev.mascwa.pulse.security

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import dev.mascwa.pulse.core.telemetry.TrustedNetwork
import dev.mascwa.pulse.data.settings.SettingsRepository
import dev.mascwa.pulse.data.usage.UsageRepository
import dev.mascwa.pulse.notifications.Notifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * On-device driver for **Trusted Network Mode**. Watches connectivity, builds a
 * [TrustedNetwork.Observation], runs the pure [TrustedNetwork.evaluate] state machine, and applies its
 * decision through [WifiPolicyController] — logging every transition to the activity log and, when the
 * radio can't be toggled because Pulse isn't a Device Owner, notifying the user (once).
 *
 * Reactive: [begin] collects the security settings and registers/unregisters a default-network callback
 * as the mode flips, re-evaluating on every connectivity change and every settings change. Process-scoped
 * (a follow-up could host it in a foreground service for persistence beyond process death; the radio
 * control is most relevant while the phone is in active use). Fully defensive — a missing system service
 * or revoked permission degrades to no-op, never a crash.
 */
class TrustedNetworkMonitor(
    context: Context,
    private val settingsRepository: SettingsRepository,
    private val usage: UsageRepository,
    private val notifier: Notifier,
    private val wifi: WifiPolicyController,
) {
    private val appContext = context.applicationContext
    private val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    private var state = TrustedNetwork.State()
    private var lastReason: String? = null
    private var notifiedUnprovisioned = false
    private var registered = false
    private var collectorStarted = false

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = trigger()
        override fun onLost(network: Network) = trigger()
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = trigger()
    }

    /** Start watching. Idempotent; safe to call from [PulseApplication.onCreate]. */
    fun begin() {
        if (collectorStarted) return
        collectorStarted = true
        scope.launch {
            settingsRepository.settings
                .map { it.security }
                .distinctUntilChanged()
                .collect { sec ->
                    if (sec.trustedNetworkMode) register() else unregister()
                    evaluateOnce()
                }
        }
    }

    private fun register() {
        val c = cm ?: return
        if (registered) return
        runCatching { c.registerDefaultNetworkCallback(networkCallback) }
            .onSuccess { registered = true }
    }

    private fun unregister() {
        val c = cm ?: return
        if (!registered) return
        runCatching { c.unregisterNetworkCallback(networkCallback) }
        registered = false
    }

    private fun trigger() {
        scope.launch { mutex.withLock { evaluateOnce() } }
    }

    private suspend fun evaluateOnce() = mutex.withLock { evaluateLocked() }

    private suspend fun evaluateLocked() {
        val sec = settingsRepository.settings.first().security
        val config = TrustedNetwork.Config(
            enabled = sec.trustedNetworkMode,
            homeSsids = sec.homeSsids.toSet(),
            reprobeAfterMs = sec.reprobeMinutes.coerceIn(1, 24 * 60) * 60_000L,
        )
        val obs = observe()
        val decision = TrustedNetwork.evaluate(config, obs, state, System.currentTimeMillis())
        state = decision.state

        if (decision.reason != lastReason) {
            usage.log("network", decision.reason)
            lastReason = decision.reason
        }

        when (decision.action) {
            TrustedNetwork.Action.HOLD -> Unit
            TrustedNetwork.Action.DISABLE_WIFI -> applyToggle(enable = false, sec.notifyWhenUnprovisioned)
            TrustedNetwork.Action.ENABLE_WIFI -> applyToggle(enable = true, sec.notifyWhenUnprovisioned)
        }
    }

    private fun applyToggle(enable: Boolean, notifyWhenUnprovisioned: Boolean) {
        val applied = wifi.setWifiEnabled(enable)
        val verb = if (enable) "enable" else "disable"
        if (applied) {
            usage.log("network", "Wi-Fi $verb applied (Trusted Network Mode)")
            notifiedUnprovisioned = false
        } else {
            usage.log("network", "Wi-Fi $verb skipped — Pulse is not a Device Owner")
            if (notifyWhenUnprovisioned && !notifiedUnprovisioned) {
                notifier.notifySecurity(
                    id = 7610,
                    title = "Trusted Network Mode needs Device Owner",
                    body = "Pulse wanted to $verb Wi-Fi but can't toggle the radio until it's provisioned " +
                        "as a Device Owner. See Settings → Security for the one-time setup.",
                )
                notifiedUnprovisioned = true
            }
        }
    }

    private fun observe(): TrustedNetwork.Observation {
        val onWifi = activeHasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        val ssid = if (onWifi) wifi.currentSsid() else null
        return TrustedNetwork.Observation(
            onWifi = onWifi,
            ssid = ssid,
            cellularUp = anyCellularInternet(),
            wifiRadioOn = wifi.isWifiEnabled(),
        )
    }

    private fun activeHasTransport(transport: Int): Boolean {
        val c = cm ?: return false
        val active = c.activeNetwork ?: return false
        return c.getNetworkCapabilities(active)?.hasTransport(transport) == true
    }

    /** Whether any present network is cellular with internet — the fallback that must exist before we disable Wi-Fi. */
    @Suppress("DEPRECATION")
    private fun anyCellularInternet(): Boolean {
        val c = cm ?: return false
        return runCatching {
            c.allNetworks.any { net ->
                val caps = c.getNetworkCapabilities(net) ?: return@any false
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            }
        }.getOrDefault(false)
    }
}
