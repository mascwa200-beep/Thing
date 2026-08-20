package dev.mascwa.pulse.desktop.feature.settings

import dev.mascwa.pulse.desktop.location.IpLocationService
import dev.mascwa.pulse.desktop.settings.DesktopSettings
import dev.mascwa.pulse.desktop.settings.DesktopSettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The settings screen's state.
 *
 * Thin on purpose: the store is already the single source of truth with its own flow, so this exists
 * to name the writes rather than to hold a copy of them. The one piece of real state is [locating],
 * which belongs to the screen and not to what is persisted.
 */
class SettingsViewModel(
    private val scope: CoroutineScope,
    private val store: DesktopSettingsStore,
    private val ipLocation: IpLocationService,
) {
    val settings: StateFlow<DesktopSettings> = store.settingsFlow

    private val _locating = MutableStateFlow(false)
    val locating: StateFlow<Boolean> = _locating.asStateFlow()

    /**
     * Ask the internet roughly where this machine is.
     *
     * ⚠️ Deliberately only ever run from a button. Guessing on launch would mean silently overwriting
     * a place someone corrected, every time they opened the app — and since a VPN moves the answer, it
     * would do so with a different wrong value each time.
     *
     * A failure leaves everything exactly as it was. Not knowing where you are is the ordinary state
     * of a machine with no location hardware, and it is not an error worth a dialog.
     */
    fun locate() {
        if (_locating.value) return
        _locating.value = true
        scope.launch {
            val fix = ipLocation.locate()
            if (fix != null) {
                store.update {
                    it.copy(
                        latitude = fix.latitude,
                        longitude = fix.longitude,
                        // The service that answered is named in the label, so a wrong place is
                        // traceable to which service said so rather than merely being wrong.
                        placeLabel = "${fix.label} (via ${fix.source})",
                        // ⚠️ NOT set by hand — this is the guess. Leaving the flag alone is what
                        // keeps "guess again" available and keeps the amber note off the screen.
                        placeSetByHand = false,
                    )
                }
            }
            _locating.value = false
        }
    }

    /** Typing a place is what makes it authoritative — that is the whole meaning of the flag. */
    fun setPlaceLabel(v: String) = write { it.copy(placeLabel = v, placeSetByHand = true) }

    fun setLatitude(v: Double?) = write { it.copy(latitude = v, placeSetByHand = true) }
    fun setLongitude(v: Double?) = write { it.copy(longitude = v, placeSetByHand = true) }

    /** Give the guess permission to fill this in again. Clears the values too, so the next guess is
     *  visibly a fresh answer rather than something that might or might not have changed. */
    fun clearManualPlace() = write {
        it.copy(latitude = null, longitude = null, placeLabel = "", placeSetByHand = false)
    }

    fun setFahrenheit(v: Boolean) = write { it.copy(fahrenheit = v) }
    fun setMiles(v: Boolean) = write { it.copy(miles = v) }
    fun setTwelveHourClock(v: Boolean) = write { it.copy(twelveHourClock = v) }
    fun setRefreshMinutes(v: Int) = write { it.copy(refreshMinutes = v.coerceIn(1, 240)) }
    fun setRefreshOnOpen(v: Boolean) = write { it.copy(refreshOnOpen = v) }
    fun setBootSequence(v: Boolean) = write { it.copy(bootSequence = v) }
    fun setConsoleSounds(v: Boolean) = write { it.copy(consoleSounds = v) }
    fun setCommunityChannels(v: Boolean) = write { it.copy(communityChannels = v) }
    fun setAutoCheckUpdates(v: Boolean) = write { it.copy(autoCheckUpdates = v) }
    fun setGithubToken(v: String) = write { it.copy(githubToken = v.trim()) }

    private fun write(transform: (DesktopSettings) -> DesktopSettings) {
        scope.launch { store.update(transform) }
    }
}
