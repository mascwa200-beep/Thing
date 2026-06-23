package dev.mascwa.pulse.core.telemetry

/**
 * Pure decision core for **Trusted Network Mode**: given the live network picture plus the user's home
 * network config, decide whether to disable Wi-Fi (we've left home AND cellular is carrying us — the
 * dual verification), re-enable it (home is back, it's time to re-probe, or the mode was turned off), or
 * hold. Kept free of Android so the state machine is unit-tested in CI; the SSID read, the radio toggle
 * and the cellular/validation check are supplied by the caller (the on-device monitor), which is the only
 * part that needs real permissions/Device-Owner privilege.
 *
 * Two invariants this core guarantees:
 *  - **Dual verification before any disable** — never DISABLE_WIFI unless we are *not* on the home network
 *    AND a cellular transport is up to carry us. Losing home alone is never enough.
 *  - **Never strand the radio off** — when the mode is turned off, if we were the ones who turned Wi-Fi
 *    off, we ask for it back on. The user can never be left without Wi-Fi because the mode is disabled.
 */
object TrustedNetwork {

    enum class Action { DISABLE_WIFI, ENABLE_WIFI, HOLD }

    data class Config(
        val enabled: Boolean,
        /** SSIDs the user designates as "home" (compared case-insensitively, quotes stripped). */
        val homeSsids: Set<String>,
        /** While away with Wi-Fi held off, re-enable the radio after this long to re-probe for home. */
        val reprobeAfterMs: Long = 10 * 60_000L,
    )

    /** A snapshot of the current network, gathered by the on-device monitor. */
    data class Observation(
        /** Connected to a Wi-Fi network right now. */
        val onWifi: Boolean,
        /** The connected Wi-Fi SSID, if readable (needs location permission); null otherwise. */
        val ssid: String?,
        /** A cellular transport is up and validated as a usable fallback. */
        val cellularUp: Boolean,
        /** The Wi-Fi radio is currently enabled (on), regardless of whether it's associated. */
        val wifiRadioOn: Boolean,
    )

    /** Carried between evaluations so re-probe timing + "did we disable it" survive across cycles. */
    data class State(
        val disabledByUs: Boolean = false,
        val lastDisableMs: Long = 0L,
    )

    data class Decision(
        val action: Action,
        val state: State,
        /** Human-readable transition reason, for the network/encryption event log. */
        val reason: String,
    )

    /** Strip the surrounding quotes Android wraps SSIDs in, trim, lowercase; blank/`<unknown ssid>` → null. */
    fun normalizeSsid(raw: String?): String? {
        if (raw == null) return null
        val s = raw.trim().removePrefix("\"").removeSuffix("\"").trim()
        if (s.isBlank() || s.equals("<unknown ssid>", ignoreCase = true)) return null
        return s.lowercase()
    }

    /** Whether [ssid] is one of the configured home networks. */
    fun isHome(config: Config, ssid: String?): Boolean {
        val n = normalizeSsid(ssid) ?: return false
        return config.homeSsids.any { normalizeSsid(it) == n }
    }

    /**
     * The single decision step. Pure: same inputs → same output. Callers persist the returned [State] and
     * feed it back on the next observation.
     */
    fun evaluate(config: Config, obs: Observation, state: State, nowMs: Long): Decision {
        if (!config.enabled) {
            // Mode off — never leave the radio stuck off because we disabled it earlier.
            return if (state.disabledByUs && !obs.wifiRadioOn) {
                Decision(Action.ENABLE_WIFI, State(), "mode off — restoring Wi-Fi we had disabled")
            } else {
                Decision(Action.HOLD, State(), "mode off")
            }
        }

        val atHome = obs.onWifi && isHome(config, obs.ssid)
        if (atHome) {
            // Home is back (or we never left): hold the radio on and clear our disabled flag.
            return Decision(Action.HOLD, State(disabledByUs = false), "on home Wi-Fi")
        }

        // --- We are away from home. ---

        // If we turned the radio off, it can't see home while off; re-enable to re-probe once the window passes.
        if (state.disabledByUs && !obs.wifiRadioOn) {
            return if (nowMs - state.lastDisableMs >= config.reprobeAfterMs) {
                Decision(Action.ENABLE_WIFI, state.copy(disabledByUs = false), "re-probing for home Wi-Fi")
            } else {
                Decision(Action.HOLD, state, "away — Wi-Fi held off, re-probe pending")
            }
        }

        // Dual verification: lost home (above) AND cellular is up AND the radio is still on to turn off.
        if (obs.wifiRadioOn && obs.cellularUp) {
            return Decision(
                Action.DISABLE_WIFI,
                State(disabledByUs = true, lastDisableMs = nowMs),
                "left home + cellular active — disabling Wi-Fi",
            )
        }

        // Away but no confirmed cellular fallback yet — never disable on loss of home alone.
        return Decision(Action.HOLD, state, "away — awaiting cellular fallback before disabling Wi-Fi")
    }
}
