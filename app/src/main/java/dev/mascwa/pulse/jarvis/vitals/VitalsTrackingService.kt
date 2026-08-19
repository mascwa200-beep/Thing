package dev.mascwa.pulse.jarvis.vitals

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.ParcelUuid
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.mascwa.pulse.MainActivity
import dev.mascwa.pulse.R
import dev.mascwa.pulse.core.telemetry.VitalsAnalyzer
import java.util.UUID

/**
 * Optional foreground service that listens to a Bluetooth LE Heart-Rate strap (GATT service
 * 0x180D, characteristic 0x2A37), feeds samples to the pure [VitalsAnalyzer], and raises an
 * on-device check-in notification when heart-rate acceleration looks anomalous. It is an
 * honest no-op when Bluetooth is off, permissions are missing, or no strap is paired —
 * nothing is faked and nothing leaves the device.
 */
class VitalsTrackingService : Service() {

    private val analyzer = VitalsAnalyzer()
    private var gatt: BluetoothGatt? = null
    private var scanning = false

    /**
     * Am I moving? Smoothed |accelG − 1|, or null until the first reading arrives.
     *
     * ⚠️ **Registered here rather than read from the Sensorium's fusion controller, and the reason
     * is the bug this fixes.** That controller only runs while ambient sensing is switched on, and
     * its snapshot is a StateFlow whose `movement` sits at `0f` when nothing has fed it — which
     * reads as "definitely still" and is precisely the false certainty being removed. A listener
     * this service owns lives exactly as long as the service, so "no reading yet" stays null and
     * the analyzer is told the truth. The accelerometer needs no permission, which matters:
     * ACTIVITY_RECOGNITION is not in the manifest, so the step counter — the better signal, and the
     * one the analyzer was designed around — is unavailable to this app at all.
     */
    @Volatile private var movement: Double? = null
    private var movementEwma = 0.0

    private val sensorManager by lazy {
        getSystemService(Context.SENSOR_SERVICE) as? android.hardware.SensorManager
    }

    private val motionListener = object : android.hardware.SensorEventListener {
        override fun onSensorChanged(e: android.hardware.SensorEvent) {
            val (x, y, z) = e.values
            val g = kotlin.math.sqrt(x * x + y * y + z * z) /
                android.hardware.SensorManager.GRAVITY_EARTH
            // The same EWMA and the same smoothing constant the Sensorium settled on, over the
            // deviation from rest rather than the raw ~1 g magnitude — the recorded fix for
            // "it thinks I'm moving while stationary".
            movementEwma = MOTION_SMOOTH * movementEwma + (1 - MOTION_SMOOTH) * kotlin.math.abs(g - 1.0)
            movement = movementEwma
        }

        override fun onAccuracyChanged(sensor: android.hardware.Sensor?, accuracy: Int) = Unit
    }

    override fun onCreate() {
        super.onCreate()
        createChannels()
        // Cheap: NORMAL rate with a long batch latency, so the FIFO coalesces deliveries and the
        // AP sleeps between them. A missing accelerometer simply leaves `movement` null forever,
        // which the analyzer and the notification copy both handle honestly.
        runCatching {
            sensorManager?.let { sm ->
                sm.getDefaultSensor(android.hardware.Sensor.TYPE_ACCELEROMETER)?.let {
                    sm.registerListener(
                        motionListener, it,
                        android.hardware.SensorManager.SENSOR_DELAY_NORMAL, MOTION_BATCH_US,
                    )
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            startForegroundCompat(ongoing("Listening for a heart-rate strap…"))
        } catch (t: Throwable) {
            stopSelf()
            return START_NOT_STICKY
        }
        beginIfPossible()
        return START_STICKY
    }

    private fun beginIfPossible() {
        if (Build.VERSION.SDK_INT < 33) {
            updateOngoing("Vitals tracking needs Android 13+.")
            return
        }
        if (!hasBlePermissions()) {
            updateOngoing("Grant Bluetooth permission to pair a heart-rate strap.")
            return
        }
        val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        if (adapter == null || !adapter.isEnabled) {
            updateOngoing("Turn on Bluetooth to pair a heart-rate strap.")
            return
        }
        startScan(adapter.bluetoothLeScanner)
    }

    @SuppressLint("MissingPermission") // gated by hasBlePermissions()
    private fun startScan(scanner: android.bluetooth.le.BluetoothLeScanner?) {
        if (scanner == null || scanning) return
        scanning = true
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(HR_SERVICE)).build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
            .build()
        // startScan can throw SecurityException (permission revoked mid-run) or IllegalState
        // (Bluetooth turned off) even past the gate — fail honestly instead of crashing.
        runCatching { scanner.startScan(listOf(filter), settings, scanCallback) }
            .onFailure {
                scanning = false
                updateOngoing("Couldn't start the Bluetooth scan.")
            }
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device ?: return
            // First strap wins; stop scanning and connect.
            val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
            runCatching { adapter?.bluetoothLeScanner?.stopScan(this) }
            scanning = false
            updateOngoing("Connecting to ${device.name ?: "heart-rate strap"}…")
            runCatching {
                gatt = device.connectGatt(this@VitalsTrackingService, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            }.onFailure { updateOngoing("Couldn't connect to the strap.") }
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                updateOngoing("Strap disconnected. Listening again…")
                analyzer.reset()
                beginIfPossible()
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val characteristic = g.getService(HR_SERVICE)?.getCharacteristic(HR_MEASUREMENT) ?: return
            g.setCharacteristicNotification(characteristic, true)
            val cccd = characteristic.getDescriptor(CCCD) ?: return
            // API 33+ descriptor write (the service is gated to 33+ in beginIfPossible()).
            g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            updateOngoing("Strap connected. Monitoring heart rate on-device.")
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            if (characteristic.uuid != HR_MEASUREMENT) return
            val bpm = parseHeartRate(value) ?: return
            analyzer.addSample(System.currentTimeMillis(), bpm, movement = movement)?.let { event ->
                raiseCheckIn(event)
            }
        }
    }

    /** Heart Rate Measurement: flags byte, then uint8 or uint16 BPM (Bluetooth GATT spec). */
    private fun parseHeartRate(value: ByteArray): Int? {
        if (value.isEmpty()) return null
        val flags = value[0].toInt()
        return if (flags and 0x01 == 0) {
            if (value.size < 2) null else value[1].toInt() and 0xFF
        } else {
            if (value.size < 3) null
            else (value[1].toInt() and 0xFF) or ((value[2].toInt() and 0xFF) shl 8)
        }
    }

    private fun startForegroundCompat(notification: Notification) {
        ServiceCompat.startForeground(
            this, NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
    }

    private fun hasBlePermissions(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) ==
            PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    private fun ongoing(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, VitalsTrackingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ONGOING)
            .setSmallIcon(R.drawable.ic_stat_pulse)
            .setColor(androidx.core.content.ContextCompat.getColor(this, R.color.lcars_condition_routine))
            .setSubText("VITALS")
            .setContentTitle("Computer Vitals")
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun updateOngoing(text: String) {
        notificationManager().notify(NOTIF_ID, ongoing(text))
    }

    private fun raiseCheckIn(event: dev.mascwa.pulse.core.telemetry.CheckInEvent) {
        // ⚠️ "without movement" is said ONLY when movement was actually measured and found absent.
        // It used to be said unconditionally while nothing fed the analyzer's exertion gate, so the
        // device asserted a fact it had never checked — on its highest-severity channel, about the
        // wearer's heart.
        val stillness = if (event.motionChecked) " without movement" else ""
        runCatching {
            (application as dev.mascwa.pulse.PulseApplication).container.notifier.notifyUrgentLine(
                headline = "Everything OK?",
                detail = "Heart rate jumped to ${event.bpm} bpm$stillness. Tap if you need help.",
                key = "vitals:${event.bpm}",
                red = true,
            )
        }
    }

    private fun notificationManager() =
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun createChannels() {
        val nm = notificationManager()
        if (nm.getNotificationChannel(CHANNEL_ONGOING) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ONGOING, "Vitals monitor", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Ongoing heart-rate monitoring status."
                    setShowBadge(false)
                },
            )
        }
        // The old CHANNEL_CHECKIN ("jarvis_vitals_checkin") is retired — anomaly check-ins now ride the
        // one LCARS board's alerting channel; NotificationChannels.ensure() deletes the stale channel id.
    }

    @SuppressLint("MissingPermission")
    override fun onDestroy() {
        runCatching {
            val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
            if (scanning) adapter?.bluetoothLeScanner?.stopScan(scanCallback)
            gatt?.close()
        }
        gatt = null
        scanning = false
        runCatching { sensorManager?.unregisterListener(motionListener) }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ONGOING = "jarvis_vitals_ongoing"
        private const val NOTIF_ID = 7311
        private const val ACTION_STOP = "dev.mascwa.pulse.jarvis.vitals.STOP"

        /** Same smoothing as SensorFusionController, so "moving" means one thing across the app. */
        private const val MOTION_SMOOTH = 0.8
        /** 10 s of batching: the EWMA does not need 60 ms updates and the AP should sleep. */
        private const val MOTION_BATCH_US = 10_000_000

        private val HR_SERVICE: UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
        private val HR_MEASUREMENT: UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
        private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, VitalsTrackingService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, VitalsTrackingService::class.java))
        }
    }
}
