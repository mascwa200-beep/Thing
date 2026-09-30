package dev.mascwa.pulse.feature.controls

import android.Manifest
import android.app.NotificationManager
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.location.LocationManager
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.notification.NotificationListenerService
import dev.mascwa.pulse.feature.controls.ControlsState.Control
import dev.mascwa.pulse.feature.controls.ControlsState.Reading
import dev.mascwa.pulse.feature.controls.ControlsState.Ringer
import dev.mascwa.pulse.feature.shade.ShadeStore
import dev.mascwa.pulse.security.DevicePolicyController
import dev.mascwa.pulse.security.WifiPolicyController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The phone's quick settings, read and written for the home console's control panel.
 *
 * Every rule lives in [ControlsState]; this is the platform boundary only — read a value, write one.
 * Every call is defensive: a missing service, a refused write or a `SecurityException` reads as a
 * refusal and becomes a sentence on screen, never a crash in the home screen.
 *
 * ⚠️ **The torch has no getter.** Android reports the torch's state only through a callback, so it is
 * tracked from [start] to [stop] — the home screen's lifetime — and read from [torch].
 */
class PhoneControls(context: Context) {

    private val app = context.applicationContext
    private val audio = app.getSystemService(AudioManager::class.java)
    private val notifications = app.getSystemService(NotificationManager::class.java)
    private val locations = app.getSystemService(LocationManager::class.java)
    private val camera = app.getSystemService(CameraManager::class.java)
    private val policy = DevicePolicyController(app)
    private val wifi = WifiPolicyController(app)

    /** The flash-bearing camera, or null on a phone with none. */
    private val torchId: String? = runCatching {
        val cam = camera ?: return@runCatching null
        cam.cameraIdList.firstOrNull { id ->
            cam.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        }
    }.getOrNull()

    private val _torch = MutableStateFlow(if (torchId == null) null else false)
    val torch: StateFlow<Boolean?> = _torch.asStateFlow()

    private val torchCallback = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
            if (cameraId == torchId) _torch.value = enabled
        }
    }

    fun start() {
        if (torchId == null) return
        runCatching { camera?.registerTorchCallback(torchCallback, Handler(Looper.getMainLooper())) }
    }

    fun stop() {
        runCatching { camera?.unregisterTorchCallback(torchCallback) }
    }

    /** Everything the panel shows, read now. Call off the main thread. */
    fun read(listenerConnected: Boolean): Reading = Reading(
        torch = _torch.value,
        ringer = runCatching { audio?.ringerMode?.let(::ringerOf) }.getOrNull(),
        dnd = runCatching {
            notifications?.currentInterruptionFilter?.let { it != NotificationManager.INTERRUPTION_FILTER_ALL }
        }.getOrNull(),
        wifi = runCatching { wifi.isWifiEnabled() }.getOrNull(),
        bluetooth = runCatching { adapter()?.isEnabled }.getOrNull(),
        location = runCatching { locations?.isLocationEnabled }.getOrNull(),
        brightness = runCatching { Settings.System.getInt(app.contentResolver, Settings.System.SCREEN_BRIGHTNESS) }.getOrNull(),
        autoBrightness = runCatching {
            Settings.System.getInt(app.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE) ==
                Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
        }.getOrNull(),
        timeoutMs = runCatching { Settings.System.getLong(app.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT) }.getOrNull(),
        deviceOwner = policy.isDeviceOwner(),
        listener = listenerConnected,
        policyAccess = runCatching { notifications?.isNotificationPolicyAccessGranted == true }.getOrDefault(false),
        bluetoothPermission = app.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED,
    )

    /** What happened when a control was tapped. */
    sealed interface Outcome {
        data object Done : Outcome

        /** LCARS could not do it; Android's own control for the same thing is [intent]. */
        data class Panel(val intent: Intent, val note: String?) : Outcome

        /** Ask for the nearby-devices permission, then tap again. */
        data object AskBluetooth : Outcome

        /** Switch notification access on, which Do Not Disturb goes through. */
        data object AskNotices : Outcome
    }

    /**
     * Change [control] to its next setting, given what [r] said a moment ago.
     *
     * ⚠️ A tile that [ControlsState] says cannot act is not attempted: it goes straight to Android's
     * own control, because trying a write the platform will refuse only delays the one thing that
     * works.
     */
    fun act(control: Control, r: Reading): Outcome {
        val tile = ControlsState.tiles(r).single { it.control == control }
        if (!tile.canAct) {
            return when {
                control == Control.DND -> Outcome.AskNotices
                control == Control.BLUETOOTH && r.bluetooth != null && r.deviceOwner && !r.bluetoothPermission -> Outcome.AskBluetooth
                else -> Outcome.Panel(panelFor(control), null)
            }
        }
        val ok = when (control) {
            Control.TORCH -> torchId?.let { id -> runCatching { camera?.setTorchMode(id, r.torch != true) }.isSuccess } ?: false
            Control.RINGER -> runCatching {
                audio?.ringerMode = platformOf(ControlsState.nextRinger(r.ringer, r.policyAccess))
            }.isSuccess
            Control.DND -> ShadeStore.requestInterruptionFilter(
                if (r.dnd == true) NotificationListenerService.INTERRUPTION_FILTER_ALL
                else NotificationListenerService.INTERRUPTION_FILTER_PRIORITY,
            )
            Control.WIFI -> wifi.setWifiEnabled(r.wifi != true)
            Control.BLUETOOTH -> setBluetooth(r.bluetooth != true)
            Control.LOCATION -> policy.setLocationEnabled(r.location != true)
            Control.BRIGHTNESS -> setBrightness(ControlsState.nextBrightness(r.brightness, r.autoBrightness))
            Control.TIMEOUT -> policy.setSystemSetting(
                Settings.System.SCREEN_OFF_TIMEOUT,
                ControlsState.nextTimeout(r.timeoutMs).toString(),
            )
        }
        return if (ok) Outcome.Done else Outcome.Panel(panelFor(control), REFUSED)
    }

    /** One of the three volumes the panel shows. */
    enum class Stream(val platform: Int, val label: String) {
        MEDIA(AudioManager.STREAM_MUSIC, "Media"),
        RING(AudioManager.STREAM_RING, "Ring"),
        ALARM(AudioManager.STREAM_ALARM, "Alarm"),
    }

    data class Volume(val stream: Stream, val level: Int, val max: Int)

    fun volumes(): List<Volume> = Stream.entries.mapNotNull { s ->
        runCatching {
            val a = audio ?: return@mapNotNull null
            Volume(s, a.getStreamVolume(s.platform), a.getStreamMaxVolume(s.platform))
        }.getOrNull()
    }

    /**
     * One step up or down. False when Android refused — the ring volume cannot be moved while Do Not
     * Disturb holds it, for one.
     */
    fun adjust(stream: Stream, up: Boolean): Boolean = runCatching {
        val a = audio ?: return false
        a.adjustStreamVolume(stream.platform, if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER, 0)
        true
    }.getOrDefault(false)

    @Suppress("DEPRECATION")
    private fun setBluetooth(on: Boolean): Boolean = runCatching {
        val a = adapter() ?: return false
        if (on) a.enable() else a.disable()
    }.getOrDefault(false)

    private fun setBrightness(level: Int): Boolean =
        if (level == ControlsState.AUTO) {
            policy.setSystemSetting(
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC.toString(),
            )
        } else {
            policy.setSystemSetting(
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL.toString(),
            ) && policy.setSystemSetting(Settings.System.SCREEN_BRIGHTNESS, level.toString())
        }

    private fun adapter() = runCatching { app.getSystemService(BluetoothManager::class.java)?.adapter }.getOrNull()

    private fun ringerOf(mode: Int): Ringer? = when (mode) {
        AudioManager.RINGER_MODE_NORMAL -> Ringer.NORMAL
        AudioManager.RINGER_MODE_VIBRATE -> Ringer.VIBRATE
        AudioManager.RINGER_MODE_SILENT -> Ringer.SILENT
        else -> null
    }

    private fun platformOf(r: Ringer): Int = when (r) {
        Ringer.NORMAL -> AudioManager.RINGER_MODE_NORMAL
        Ringer.VIBRATE -> AudioManager.RINGER_MODE_VIBRATE
        Ringer.SILENT -> AudioManager.RINGER_MODE_SILENT
    }

    /** Android's own control for each thing, where LCARS cannot change it itself. */
    private fun panelFor(control: Control): Intent = Intent(
        when (control) {
            Control.WIFI -> Settings.Panel.ACTION_WIFI
            Control.BLUETOOTH -> Settings.ACTION_BLUETOOTH_SETTINGS
            Control.LOCATION -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
            Control.BRIGHTNESS, Control.TIMEOUT -> Settings.ACTION_DISPLAY_SETTINGS
            Control.RINGER -> Settings.ACTION_SOUND_SETTINGS
            Control.DND -> Settings.ACTION_ZEN_MODE_PRIORITY_SETTINGS
            Control.TORCH -> Settings.ACTION_SETTINGS
        },
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private companion object {
        const val REFUSED = "Android refused that. Here is its own control."
    }
}
