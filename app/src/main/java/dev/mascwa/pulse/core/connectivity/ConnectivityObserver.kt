package dev.mascwa.pulse.core.connectivity

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.core.content.getSystemService
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Live online/offline state for composables (e.g. the cached-data banner). */
val LocalIsOnline = staticCompositionLocalOf { true }

/**
 * Whether the current connection is one the user pays for by the megabyte.
 *
 * ⚠️ A **positive** fact, not the negation of [LocalIsOnline]'s sibling, and that is the whole point
 * of the shape. Defaulting an "is it unmetered" local either way states something we do not know —
 * either implying free data on a phone plan or claiming mobile data on home Wi-Fi. Defaulting
 * *metered* to false says nothing at all, so a screen with no provider above it stays silent rather
 * than becoming wrong.
 */
val LocalIsMetered = staticCompositionLocalOf { false }

/**
 * Live online/offline state from any transport (WiFi or cellular). Drives the
 * automatic Offline Survival Mode.
 */
class ConnectivityObserver(context: Context) {

    private val cm = context.getSystemService<ConnectivityManager>()
    private val _isOnline = MutableStateFlow(computeOnline())
    val isOnline: StateFlow<Boolean> = _isOnline.asStateFlow()

    /**
     * Whether the connection is one the user is not paying by the megabyte for.
     *
     * ⚠️ **False when unknown**, deliberately. Everything reading this is deciding whether to warn
     * about data, and guessing "free" on a connection we cannot classify spends someone's allowance
     * on our assumption. Guessing the other way costs a warning that turns out to be unnecessary.
     */
    private val _isUnmetered = MutableStateFlow(computeUnmetered())
    val isUnmetered: StateFlow<Boolean> = _isUnmetered.asStateFlow()

    init {
        runCatching {
            cm?.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    _isOnline.value = computeOnline()
                    _isUnmetered.value = computeUnmetered()
                }
                override fun onLost(network: Network) {
                    _isOnline.value = computeOnline()
                    _isUnmetered.value = computeUnmetered()
                }
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    _isOnline.value = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    _isUnmetered.value = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                }
            })
        }
    }

    private fun computeOnline(): Boolean = capability(NetworkCapabilities.NET_CAPABILITY_INTERNET)

    private fun computeUnmetered(): Boolean = capability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)

    private fun capability(which: Int): Boolean {
        val net = cm?.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(which)
    }
}
