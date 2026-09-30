package dev.mascwa.pulse.feature.controls

/**
 * The home console's control panel, as decisions: what each control reads, whether LCARS can change
 * it from here, why not when it cannot, and what the next setting is when it is tapped.
 *
 * Pure — no Android type — so every rule runs under JUnit. Reading the phone and writing to it is
 * `PhoneControls`; nothing there decides anything decided here.
 *
 * ⚠️ **A control LCARS cannot change is never a dead switch.** It says why, in the owner's words, and
 * a tap opens Android's own control for the same thing. Android keeps several of these for the device
 * owner alone (Wi-Fi, location, brightness, screen timeout, and since Android 13 Bluetooth), which is
 * why [Reading.deviceOwner] decides so much here.
 *
 * ⚠️ **Null means "could not be read", never "off".** A phone with no flash, a Bluetooth adapter that
 * is absent, a setting the platform would not return — each shows as unknown rather than as a switch
 * sitting in the off position, because an off switch invites a tap that cannot work.
 */
object ControlsState {

    enum class Control { TORCH, RINGER, DND, WIFI, BLUETOOTH, LOCATION, BRIGHTNESS, TIMEOUT }

    enum class Ringer { NORMAL, VIBRATE, SILENT }

    data class Reading(
        val torch: Boolean? = null,
        val ringer: Ringer? = null,
        val dnd: Boolean? = null,
        val wifi: Boolean? = null,
        val bluetooth: Boolean? = null,
        val location: Boolean? = null,
        /** 0..255, Android's legacy brightness scale. */
        val brightness: Int? = null,
        val autoBrightness: Boolean? = null,
        val timeoutMs: Long? = null,
        val deviceOwner: Boolean = false,
        /** Whether the notification reader is bound — what Do Not Disturb is switched through. */
        val listener: Boolean = false,
        /** Whether Android lets this app set the ringer to silent (the notification-policy grant). */
        val policyAccess: Boolean = false,
        val bluetoothPermission: Boolean = false,
    )

    data class Tile(
        val control: Control,
        val label: String,
        val state: String,
        /** Lit or dark on screen; null when the state is unknown. */
        val on: Boolean?,
        val canAct: Boolean,
        /** Why LCARS cannot change it here; null when it can. */
        val why: String?,
    )

    fun tiles(r: Reading): List<Tile> = listOf(
        Tile(
            Control.TORCH, "Torch", onOff(r.torch), r.torch,
            canAct = r.torch != null,
            why = if (r.torch == null) NO_FLASH else null,
        ),
        Tile(
            Control.RINGER, "Ringer", ringerWord(r.ringer), r.ringer?.let { it == Ringer.NORMAL },
            canAct = r.ringer != null,
            why = if (r.ringer == null) UNREADABLE else null,
        ),
        Tile(
            Control.DND, "Do not disturb", onOff(r.dnd), r.dnd,
            canAct = r.listener,
            why = if (r.listener) null else NEEDS_NOTICES,
        ),
        ownerTile(Control.WIFI, "Wi-Fi", onOff(r.wifi), r.wifi, r),
        bluetoothTile(r),
        ownerTile(Control.LOCATION, "Location", onOff(r.location), r.location, r),
        ownerTile(Control.BRIGHTNESS, "Brightness", brightnessWord(r.brightness, r.autoBrightness), null, r),
        ownerTile(Control.TIMEOUT, "Screen off", timeoutWord(r.timeoutMs), null, r),
    )

    private fun ownerTile(control: Control, label: String, state: String, on: Boolean?, r: Reading) =
        Tile(control, label, state, on, canAct = r.deviceOwner, why = if (r.deviceOwner) null else OWNER_ONLY)

    private fun bluetoothTile(r: Reading): Tile {
        val why = when {
            r.bluetooth == null -> NO_BLUETOOTH
            !r.deviceOwner -> OWNER_ONLY
            !r.bluetoothPermission -> BLUETOOTH_PERMISSION
            else -> null
        }
        return Tile(Control.BLUETOOTH, "Bluetooth", onOff(r.bluetooth), r.bluetooth, canAct = why == null, why = why)
    }

    /**
     * The ringer's next setting on a tap: ring, vibrate, silent, and round again.
     *
     * ⚠️ Silent is skipped without the policy grant — Android refuses it and throws — so a phone that
     * cannot go silent cycles between the two settings it can reach instead of sticking.
     */
    fun nextRinger(current: Ringer?, policyAccess: Boolean): Ringer = when (current) {
        null, Ringer.SILENT -> Ringer.NORMAL
        Ringer.NORMAL -> Ringer.VIBRATE
        Ringer.VIBRATE -> if (policyAccess) Ringer.SILENT else Ringer.NORMAL
    }

    /** Brightness steps a tap walks through, as 0..255 levels; [AUTO] is the automatic setting. */
    val BRIGHTNESS_STEPS: List<Int> = listOf(AUTO, 26, 64, 128, 191, 255)

    const val AUTO = -1

    /**
     * The brightness step after the current one. The current reading is matched to the NEAREST step,
     * because the phone's own slider can leave it anywhere, and matching only exact values would send
     * every tap back to the first step.
     */
    fun nextBrightness(level: Int?, auto: Boolean?): Int {
        if (auto == true) return BRIGHTNESS_STEPS[1]
        if (level == null) return BRIGHTNESS_STEPS[1]
        val manual = BRIGHTNESS_STEPS.drop(1)
        val nearest = manual.minBy { kotlin.math.abs(it - level) }
        val i = manual.indexOf(nearest)
        return if (i == manual.lastIndex) AUTO else manual[i + 1]
    }

    val TIMEOUT_STEPS_MS: List<Long> = listOf(15_000, 30_000, 60_000, 120_000, 300_000, 600_000)

    /** The screen-off time after the current one, nearest-matched for the same reason as brightness. */
    fun nextTimeout(currentMs: Long?): Long {
        if (currentMs == null) return TIMEOUT_STEPS_MS.first()
        val nearest = TIMEOUT_STEPS_MS.minBy { kotlin.math.abs(it - currentMs) }
        val i = TIMEOUT_STEPS_MS.indexOf(nearest)
        return TIMEOUT_STEPS_MS[(i + 1) % TIMEOUT_STEPS_MS.size]
    }

    fun brightnessWord(level: Int?, auto: Boolean?): String = when {
        auto == true -> "Auto"
        level == null -> UNKNOWN
        else -> "${(level.coerceIn(0, 255) * 100 + 127) / 255}%"
    }

    fun timeoutWord(ms: Long?): String = when {
        ms == null -> UNKNOWN
        ms < 60_000 -> "${ms / 1_000}s"
        else -> "${ms / 60_000}m"
    }

    private fun onOff(v: Boolean?): String = when (v) {
        true -> "On"
        false -> "Off"
        null -> UNKNOWN
    }

    private fun ringerWord(r: Ringer?): String = when (r) {
        Ringer.NORMAL -> "Ring"
        Ringer.VIBRATE -> "Vibrate"
        Ringer.SILENT -> "Silent"
        null -> UNKNOWN
    }

    const val UNKNOWN = "—"
    const val NO_FLASH = "This phone has no flash."
    const val NO_BLUETOOTH = "This phone has no Bluetooth."
    const val UNREADABLE = "Android would not say. Tap for its own control."
    const val NEEDS_NOTICES = "Needs notification access. Tap to switch it on."
    const val OWNER_ONLY = "Android lets only the device owner change this. Tap for its own control."
    const val BLUETOOTH_PERMISSION = "Needs the nearby-devices permission. Tap to allow it."
}
