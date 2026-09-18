package dev.mascwa.pulse.data.sensing

import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.PowerManager
import android.os.SystemClock
import dev.mascwa.pulse.core.device.DeviceContextProvider
import dev.mascwa.pulse.core.telemetry.AudioRoute
import dev.mascwa.pulse.core.telemetry.DeviceClass
import dev.mascwa.pulse.core.telemetry.DndFilter
import dev.mascwa.pulse.core.telemetry.Posture
import dev.mascwa.pulse.core.telemetry.RingerState
import dev.mascwa.pulse.core.telemetry.SenseContext
import dev.mascwa.pulse.device.DeviceProbeReader

/**
 * Reads what the phone knows about ITSELF — screen, lock, power, thermal, audio, Do Not Disturb —
 * and hands it to the pure [SenseContext].
 *
 * ⚠️ **Nothing here costs a permission, and that is why this slice was worth doing before anything
 * invasive.** The Sensorium had five hardware senses and no idea whether anybody was holding the
 * phone, whether it was charging, whether it was silenced or whether the person had put it face-down
 * on a table — every one of which is free, and every one of which is stronger evidence about what
 * somebody is doing than the ambient light level it was already reading.
 *
 * ⚠️ **Two of the readings are borrowed rather than re-derived.** `DeviceProbeReader` already reads
 * the thermal status and Doze, and [DeviceContextProvider] already maps the battery intent onto
 * `PowerSource`. A second copy of either is the duplicated-definition drift this repository has
 * corrected seven times; the cost is two extra sets of binder calls per heartbeat, which is nothing
 * at this cadence and would be ruinous per frame — see `DeviceProbeReader.probe`'s own note.
 *
 * Every read is individually defensive. A system service that is missing or throws contributes null,
 * which the core reads as *unknown* — never as a value.
 */
class SenseContextReader(
    context: Context,
    private val probe: DeviceProbeReader,
    private val deviceContext: DeviceContextProvider,
) {

    private val app = context.applicationContext

    /**
     * When the phone was last unlocked, on the elapsed-realtime clock.
     *
     * ⚠️ **Elapsed realtime, not the wall clock.** A wall clock moves — a time-zone change, an NTP
     * correction, a user editing the date — and each of those would make the gap since the last
     * unlock jump by hours in either direction. Elapsed realtime counts since boot and only ever
     * goes forward.
     *
     * ⚠️ Zero means "no unlock has been observed since this reader started", which [SenseContext]
     * documents as a genuinely different fact from "not unlocked recently" and handles explicitly.
     */
    @Volatile
    private var lastUnlockElapsed: Long = 0L

    private var receiver: BroadcastReceiver? = null

    /**
     * Start listening for unlocks.
     *
     * ⚠️ **`ACTION_USER_PRESENT` CANNOT be declared in the manifest.** The platform delivers it only
     * to receivers registered at runtime — a manifest entry compiles, installs and silently never
     * fires, which would leave [SenseContext.msSinceUnlock] null for ever with nothing to show for it.
     * `ACTION_SCREEN_ON` and `ACTION_SCREEN_OFF` are the same kind of broadcast.
     *
     * ⚠️ **Those two are NOT registered, and the first draft of this registered both and handled
     * neither.** They would have been two actions in the filter whose events were received and
     * dropped — the computed-and-never-used shape this whole arc exists to remove, in brand-new code.
     * Whether the screen is on is read live from `PowerManager` where it is wanted, which cannot go
     * stale; the one thing a live read genuinely cannot answer is *when the phone was last unlocked*,
     * and that is the one broadcast here.
     *
     * ⚠️ No `RECEIVER_EXPORTED` flag, for the same reason [DeviceContextProvider.updates] needs
     * none: it is a protected system broadcast, which Android 14's registration rule exempts. Adding
     * `RECEIVER_NOT_EXPORTED` would be harmless; adding `RECEIVER_EXPORTED` would not, so the
     * exemption is stated rather than left to whoever edits this next.
     */
    fun start() {
        if (receiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                if (intent?.action == Intent.ACTION_USER_PRESENT) {
                    lastUnlockElapsed = SystemClock.elapsedRealtime()
                }
            }
        }
        runCatching { app.registerReceiver(r, IntentFilter(Intent.ACTION_USER_PRESENT)) }
            .onSuccess { receiver = r }
    }

    fun stop() {
        receiver?.let { r -> runCatching { app.unregisterReceiver(r) } }
        receiver = null
    }

    /**
     * One reading.
     *
     * [posture] comes from the fusion snapshot rather than being read here, because the accelerometer
     * is already registered there and a second registration would be a second cost for the same
     * numbers. [awayFromHome] is passed in for the same reason: the app already derives it for the
     * Oracle, and re-deriving it here would be a second definition of the same place.
     *
     * ⚠️ [calendarBusy] is passed in rather than read here because it is the one thing in this class
     * that costs a permission AND blocks: `CalendarRepository.upcoming` is a ContentResolver query,
     * not a suspend function, so it has to be dispatched to IO and cached on a slower cadence than
     * this reader runs at. The engine owns that; this class stays a set of cheap synchronous reads.
     */
    fun read(
        posture: Posture? = null,
        awayFromHome: Boolean? = null,
        calendarBusy: Boolean? = null,
    ): SenseContext {
        val pm = runCatching { app.getSystemService(Context.POWER_SERVICE) as? PowerManager }.getOrNull()
        val km = runCatching { app.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager }.getOrNull()
        val am = runCatching { app.getSystemService(Context.AUDIO_SERVICE) as? AudioManager }.getOrNull()

        val p = runCatching { probe.probe() }.getOrNull()
        val dc = runCatching { deviceContext.snapshot() }.getOrNull()

        val unlocked = lastUnlockElapsed
        return SenseContext(
            // ⚠️ `SensoriumService` reads `isInteractive` too, for the throttle ladder's
            // screen-off MINUTES, and the two are deliberately not unified. They answer different
            // questions — "how long has nobody touched this" against "is somebody looking right
            // now" — and they share no threshold, so there is nothing here that can drift. Folding
            // them together would mean the ladder's input arriving through a broadcast, which has a
            // cold-start hole the service's polling does not: a service that starts while the screen
            // is already off has never seen the event that would tell it when that happened.
            screenOn = runCatching { pm?.isInteractive }.getOrNull(),
            locked = runCatching { km?.isKeyguardLocked }.getOrNull(),
            msSinceUnlock = if (unlocked > 0L) SystemClock.elapsedRealtime() - unlocked else null,
            idle = p?.deviceIdle,
            posture = posture,
            charging = dc?.isCharging,
            power = dc?.powerSource,
            thermal = p?.thermalStatus?.let { DeviceClass.thermalPressure(it) },
            onCall = am?.let { runCatching { callOf(it) }.getOrNull() },
            musicPlaying = runCatching { am?.isMusicActive }.getOrNull(),
            ringer = am?.let { runCatching { ringerOf(it) }.getOrNull() },
            route = am?.let { runCatching { routeOf(it) }.getOrNull() },
            dnd = runCatching { dndOf() }.getOrNull(),
            awayFromHome = awayFromHome,
            calendarBusy = calendarBusy,
        )
    }

    /**
     * ⚠️ Both call modes count, and missing the second is the commoner mistake. `MODE_IN_CALL` is a
     * cellular call; a WhatsApp, Signal or Meet call is `MODE_IN_COMMUNICATION`, and on a phone used
     * the way most people use one that is the mode a rule will actually meet.
     */
    private fun callOf(am: AudioManager): Boolean =
        am.mode == AudioManager.MODE_IN_CALL || am.mode == AudioManager.MODE_IN_COMMUNICATION

    private fun ringerOf(am: AudioManager): RingerState? = when (am.ringerMode) {
        AudioManager.RINGER_MODE_SILENT -> RingerState.SILENT
        AudioManager.RINGER_MODE_VIBRATE -> RingerState.VIBRATE
        AudioManager.RINGER_MODE_NORMAL -> RingerState.NORMAL
        else -> null
    }

    /**
     * The most notable output device CONNECTED to this phone.
     *
     * ⚠️ **Connected, not necessarily in use, and the distinction is real.** `getDevices` lists
     * everything available for output — the built-in speaker is always in it — so this reports that
     * earbuds are paired and awake, not that sound is going to them. That is still the useful fact
     * for what reads it: Bluetooth audio connected while the phone is moving fast is evidence of a
     * car, and headphones connected is evidence somebody is listening to something.
     *
     * ⚠️ **Do not "improve" this with `getDevicesForAttributes`.** It would genuinely answer which
     * device would be used — and the local compile gate could not tell you whether it is public API,
     * because the Robolectric `android-all` jar this project checks against contains `@hide` members
     * as well as public ones. A hidden API compiles here, compiles in CI and throws on the phone.
     * `getDevices` has been public since API 23 and is not in any doubt.
     *
     * The order is deliberate: the built-in speaker is present on every phone, so it must be the
     * last thing considered or it would win every time.
     */
    private fun routeOf(am: AudioManager): AudioRoute {
        val types = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { it.type }.toSet()
        return when {
            types.any { it in BLUETOOTH_OUT } -> AudioRoute.BLUETOOTH
            types.any { it in WIRED_OUT } -> AudioRoute.WIRED
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER in types -> AudioRoute.SPEAKER
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE in types -> AudioRoute.EARPIECE
            else -> AudioRoute.UNKNOWN
        }
    }

    /**
     * Do Not Disturb, as a sensing input.
     *
     * ⚠️ **The platform's own constants are the trap, and they are renamed at exactly this
     * boundary.** `INTERRUPTION_FILTER_ALL` means DND is OFF — everything gets through — and
     * `INTERRUPTION_FILTER_NONE` means total silence. Reading those two the wrong way round inverts
     * every rule that ever consults this, so the mapping lives in one place and the enum is named
     * for what a person would call it.
     *
     * ⚠️ **`INTERRUPTION_FILTER_UNKNOWN` is the platform refusing to say**, and it must not collapse
     * into "DND is off": a rule that made a noise because it read a refusal as permission would be
     * doing the one thing this whole feature exists to avoid.
     */
    private fun dndOf(): DndFilter? {
        val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return null
        return when (nm.currentInterruptionFilter) {
            NotificationManager.INTERRUPTION_FILTER_ALL -> DndFilter.OFF
            NotificationManager.INTERRUPTION_FILTER_PRIORITY -> DndFilter.PRIORITY
            NotificationManager.INTERRUPTION_FILTER_ALARMS -> DndFilter.ALARMS_ONLY
            NotificationManager.INTERRUPTION_FILTER_NONE -> DndFilter.TOTAL_SILENCE
            else -> DndFilter.UNKNOWN
        }
    }

    private companion object {
        /** Every Bluetooth output a phone can have. SCO is a headset on a call; A2DP is media. */
        val BLUETOOTH_OUT = setOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER,
            AudioDeviceInfo.TYPE_HEARING_AID,
        )

        /** Anything on the end of a cable, USB audio included — a USB-C headset is a headset. */
        val WIRED_OUT = setOf(
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_ACCESSORY,
        )
    }
}
