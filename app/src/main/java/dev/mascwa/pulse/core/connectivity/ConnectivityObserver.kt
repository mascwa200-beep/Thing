package dev.mascwa.pulse.core.connectivity

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.core.content.getSystemService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Live online/offline state from any transport (WiFi or cellular). Drives the
 * automatic Offline Survival Mode.
 */
class ConnectivityObserver(context: Context) {

    private val cm = context.getSystemService<ConnectivityManager>()
    private val _isOnline = MutableStateFlow(computeOnline())
    val isOnline: StateFlow<Boolean> = _isOnline.asStateFlow()

    init {
        runCatching {
            cm?.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { _isOnline.value = computeOnline() }
                override fun onLost(network: Network) { _isOnline.value = computeOnline() }
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    _isOnline.value = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                }
            })
        }
    }

    private fun computeOnline(): Boolean {
        val net = cm?.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
