package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrustedNetworkTest {

    private val home = TrustedNetwork.Config(
        enabled = true,
        homeSsids = setOf("HomeNet"),
        reprobeAfterMs = 10 * 60_000L,
    )

    private fun obs(
        onWifi: Boolean = false,
        ssid: String? = null,
        cellularUp: Boolean = false,
        wifiRadioOn: Boolean = true,
    ) = TrustedNetwork.Observation(onWifi, ssid, cellularUp, wifiRadioOn)

    // --- SSID normalisation ---

    @Test
    fun normalizeStripsQuotesAndCase() {
        assertEquals("homenet", TrustedNetwork.normalizeSsid("\"HomeNet\""))
        assertEquals("homenet", TrustedNetwork.normalizeSsid("  homenet "))
    }

    @Test
    fun normalizeRejectsBlankAndUnknown() {
        assertEquals(null, TrustedNetwork.normalizeSsid(null))
        assertEquals(null, TrustedNetwork.normalizeSsid("   "))
        assertEquals(null, TrustedNetwork.normalizeSsid("<unknown ssid>"))
    }

    @Test
    fun isHomeMatchesCaseInsensitivelyAndQuoted() {
        assertTrue(TrustedNetwork.isHome(home, "\"HomeNet\""))
        assertTrue(TrustedNetwork.isHome(home, "homenet"))
        assertFalse(TrustedNetwork.isHome(home, "CoffeeShop"))
        assertFalse(TrustedNetwork.isHome(home, null))
    }

    // --- Disable: dual verification ---

    @Test
    fun disablesWhenAwayAndCellularUp() {
        val d = TrustedNetwork.evaluate(
            home,
            obs(onWifi = true, ssid = "CoffeeShop", cellularUp = true, wifiRadioOn = true),
            TrustedNetwork.State(wasHome = true), // we'd confirmed home before leaving
            nowMs = 1_000L,
        )
        assertEquals(TrustedNetwork.Action.DISABLE_WIFI, d.action)
        assertTrue(d.state.disabledByUs)
        assertEquals(1_000L, d.state.lastDisableMs)
    }

    // --- Safety guards against false "away" (the home-Wi-Fi-disconnect bug) ---

    @Test
    fun doesNotDisableWhenOnWifiButSsidUnreadable() {
        // Sitting on home Wi-Fi but the SSID can't be read (no location permission) — must NOT be mistaken
        // for "away" and disable the radio, even with cellular up and a prior confirmed-home.
        val d = TrustedNetwork.evaluate(
            home,
            obs(onWifi = true, ssid = null, cellularUp = true, wifiRadioOn = true),
            TrustedNetwork.State(wasHome = true),
            nowMs = 1_000L,
        )
        assertEquals(TrustedNetwork.Action.HOLD, d.action)
        assertFalse(d.state.disabledByUs)
    }

    @Test
    fun doesNotDisableBeforeHomeEverConfirmed() {
        // Cold start while already on a foreign network — never disable until we've actually seen home.
        val d = TrustedNetwork.evaluate(
            home,
            obs(onWifi = true, ssid = "CoffeeShop", cellularUp = true, wifiRadioOn = true),
            TrustedNetwork.State(), // wasHome = false
            nowMs = 1_000L,
        )
        assertEquals(TrustedNetwork.Action.HOLD, d.action)
        assertFalse(d.state.disabledByUs)
    }

    @Test
    fun doesNotDisableWithNoHomeNetworksConfigured() {
        val noHome = home.copy(homeSsids = emptySet())
        val d = TrustedNetwork.evaluate(
            noHome,
            obs(onWifi = false, ssid = null, cellularUp = true, wifiRadioOn = true),
            TrustedNetwork.State(wasHome = true),
            nowMs = 1_000L,
        )
        assertEquals(TrustedNetwork.Action.HOLD, d.action)
        assertFalse(d.state.disabledByUs)
    }

    @Test
    fun restoresWifiWhenHomeNetworksClearedAfterWeDisabledIt() {
        // The user removed all home networks while we had the radio off — never strand it.
        val noHome = home.copy(homeSsids = emptySet())
        val d = TrustedNetwork.evaluate(
            noHome,
            obs(onWifi = false, ssid = null, cellularUp = true, wifiRadioOn = false),
            TrustedNetwork.State(disabledByUs = true, lastDisableMs = 0L, wasHome = true),
            nowMs = 1_000L,
        )
        assertEquals(TrustedNetwork.Action.ENABLE_WIFI, d.action)
        assertFalse(d.state.disabledByUs)
    }

    @Test
    fun doesNotDisableOnLossOfHomeAloneWithoutCellular() {
        // Left home but no cellular fallback confirmed yet — must hold, never disable.
        val d = TrustedNetwork.evaluate(
            home,
            obs(onWifi = true, ssid = "CoffeeShop", cellularUp = false, wifiRadioOn = true),
            TrustedNetwork.State(),
            nowMs = 1_000L,
        )
        assertEquals(TrustedNetwork.Action.HOLD, d.action)
        assertFalse(d.state.disabledByUs)
    }

    @Test
    fun doesNotDisableWhenOnHomeWifi() {
        val d = TrustedNetwork.evaluate(
            home,
            obs(onWifi = true, ssid = "HomeNet", cellularUp = true, wifiRadioOn = true),
            TrustedNetwork.State(),
            nowMs = 1_000L,
        )
        assertEquals(TrustedNetwork.Action.HOLD, d.action)
        assertFalse(d.state.disabledByUs)
    }

    @Test
    fun doesNotDisableWhenRadioAlreadyOff() {
        val d = TrustedNetwork.evaluate(
            home,
            obs(onWifi = false, ssid = null, cellularUp = true, wifiRadioOn = false),
            TrustedNetwork.State(),
            nowMs = 1_000L,
        )
        // Radio already off and we didn't disable it — nothing to do.
        assertEquals(TrustedNetwork.Action.HOLD, d.action)
    }

    // --- Re-probe + re-enable ---

    @Test
    fun holdsWhileReprobeWindowPending() {
        val state = TrustedNetwork.State(disabledByUs = true, lastDisableMs = 0L)
        val d = TrustedNetwork.evaluate(
            home,
            obs(onWifi = false, ssid = null, cellularUp = true, wifiRadioOn = false),
            state,
            nowMs = 5 * 60_000L, // 5 min < 10 min window
        )
        assertEquals(TrustedNetwork.Action.HOLD, d.action)
        assertTrue(d.state.disabledByUs)
    }

    @Test
    fun reenablesToReprobeAfterWindow() {
        val state = TrustedNetwork.State(disabledByUs = true, lastDisableMs = 0L)
        val d = TrustedNetwork.evaluate(
            home,
            obs(onWifi = false, ssid = null, cellularUp = true, wifiRadioOn = false),
            state,
            nowMs = 11 * 60_000L, // past the 10 min window
        )
        assertEquals(TrustedNetwork.Action.ENABLE_WIFI, d.action)
        assertFalse(d.state.disabledByUs)
    }

    @Test
    fun holdsOnHomeAfterReprobeReconnects() {
        // After re-probe re-enabled the radio and it associated to home: hold it on, clear the flag.
        val state = TrustedNetwork.State(disabledByUs = false, lastDisableMs = 11 * 60_000L)
        val d = TrustedNetwork.evaluate(
            home,
            obs(onWifi = true, ssid = "HomeNet", cellularUp = true, wifiRadioOn = true),
            state,
            nowMs = 12 * 60_000L,
        )
        assertEquals(TrustedNetwork.Action.HOLD, d.action)
        assertFalse(d.state.disabledByUs)
    }

    // --- Mode off: never strand the radio ---

    @Test
    fun restoresWifiWhenModeTurnedOffAfterWeDisabledIt() {
        val off = home.copy(enabled = false)
        val state = TrustedNetwork.State(disabledByUs = true, lastDisableMs = 0L)
        val d = TrustedNetwork.evaluate(
            off,
            obs(onWifi = false, ssid = null, cellularUp = true, wifiRadioOn = false),
            state,
            nowMs = 1_000L,
        )
        assertEquals(TrustedNetwork.Action.ENABLE_WIFI, d.action)
        assertFalse(d.state.disabledByUs)
    }

    @Test
    fun modeOffHoldsWhenRadioAlreadyOn() {
        val off = home.copy(enabled = false)
        val d = TrustedNetwork.evaluate(
            off,
            obs(onWifi = true, ssid = "CoffeeShop", cellularUp = true, wifiRadioOn = true),
            TrustedNetwork.State(disabledByUs = false),
            nowMs = 1_000L,
        )
        assertEquals(TrustedNetwork.Action.HOLD, d.action)
    }

    // --- A full simulated transition sequence ---

    @Test
    fun simulatedLeaveHomeThenReturnSequence() {
        var state = TrustedNetwork.State()
        // 1. At home — hold.
        var d = TrustedNetwork.evaluate(home, obs(true, "HomeNet", cellularUp = false, wifiRadioOn = true), state, 0L)
        assertEquals(TrustedNetwork.Action.HOLD, d.action); state = d.state
        // 2. Walk out: associated to a foreign AP, cellular not yet validated — hold (dual verification).
        d = TrustedNetwork.evaluate(home, obs(true, "Street", cellularUp = false, wifiRadioOn = true), state, 60_000L)
        assertEquals(TrustedNetwork.Action.HOLD, d.action); state = d.state
        // 3. Cellular comes up while still away — disable.
        d = TrustedNetwork.evaluate(home, obs(true, "Street", cellularUp = true, wifiRadioOn = true), state, 120_000L)
        assertEquals(TrustedNetwork.Action.DISABLE_WIFI, d.action); state = d.state
        // 4. Radio now off, still away, window pending — hold off.
        d = TrustedNetwork.evaluate(home, obs(false, null, cellularUp = true, wifiRadioOn = false), state, 180_000L)
        assertEquals(TrustedNetwork.Action.HOLD, d.action); state = d.state
        // 5. Window elapses — re-probe.
        d = TrustedNetwork.evaluate(home, obs(false, null, cellularUp = true, wifiRadioOn = false), state, 900_000L)
        assertEquals(TrustedNetwork.Action.ENABLE_WIFI, d.action); state = d.state
        // 6. Home back in range, radio associated — hold on.
        d = TrustedNetwork.evaluate(home, obs(true, "HomeNet", cellularUp = true, wifiRadioOn = true), state, 960_000L)
        assertEquals(TrustedNetwork.Action.HOLD, d.action)
        assertFalse(d.state.disabledByUs)
    }
}
