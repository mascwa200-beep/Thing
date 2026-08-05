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

    override fun onCreate() {
        super.onCreate()
        createChannels()
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
            analyzer.addSample(System.currentTimeMillis(), bpm)?.let { event ->
                raiseCheckIn(event.bpm)
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

    private fun raiseCheckIn(bpm: Int) {
        val open = PendingIntent.getActivity(
            this, 2, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_CHECKIN)
            .setSmallIcon(R.drawable.ic_stat_pulse)
            .setContentTitle("Computer · everything OK?")
            .setContentText("Heart rate jumped to $bpm bpm without movement. Tap if you need help.")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "Heart rate jumped to $bpm bpm with no rise in movement. Tap to open the Computer " +
                        "if you'd like a check-in or to reach an emergency contact.",
                ),
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        notificationManager().notify(CHECKIN_ID, notification)
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
        if (nm.getNotificationChannel(CHANNEL_CHECKIN) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_CHECKIN, "Vitals check-in", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Proactive check-in when heart-rate looks anomalous."
                },
            )
        }
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
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ONGOING = "jarvis_vitals_ongoing"
        private const val CHANNEL_CHECKIN = "jarvis_vitals_checkin"
        private const val NOTIF_ID = 7311
        private const val CHECKIN_ID = 7312
        private const val ACTION_STOP = "dev.mascwa.pulse.jarvis.vitals.STOP"

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
