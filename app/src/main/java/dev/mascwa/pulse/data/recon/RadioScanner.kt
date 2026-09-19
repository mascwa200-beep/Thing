package dev.mascwa.pulse.data.recon

import android.Manifest
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import androidx.core.content.ContextCompat
import dev.mascwa.pulse.core.telemetry.Recon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * The radios advertising around you, enumerated rather than counted.
 *
 * The Sensorium already samples Wi-Fi and BLE for a crowd-density *count* — deliberately per-burst and
 * anonymous, because MAC randomization makes cross-burst identity meaningless for that job. TROVE wants
 * the opposite: the actual list of what is in range, named where naming is honest. This is a separate,
 * self-contained scanner rather than a widening of the Sensorium's controller — that one is a live
 * subsystem with its own sensor lifecycle, and its counting job is not this listing job.
 *
 * ## Honesty (the same rules as the network scanner)
 *
 * - **Wi-Fi** needs location permission on Android 10+; without it [Radios.wifiAvailable] is false and the
 *   collection says "grant location to scan" rather than "no networks", which are different facts.
 * - **BLE** needs BLUETOOTH_SCAN and the adapter on; the vast majority of BLE addresses are randomized
 *   privacy addresses, so most devices resolve to "randomized (private)" and that is correct, not a gap.
 * - **Signal** is a band, never a false metre figure (see [Recon.rssiBand]).
 */
class RadioScanner(context: Context, private val oui: OuiTable) {
    private val appContext = context.applicationContext

    data class WifiNet(
        val ssid: String,
        val bssid: String,
        val rssi: Int,
        val band: String,
        val channel: Int?,
        val security: String,
        val isOpen: Boolean,
    )

    data class BleDevice(
        val name: String?,
        val address: String,
        val rssi: Int,
        val band: String,
        val vendor: String,
        val services: List<String>,
    )

    data class Radios(
        val wifi: List<WifiNet>,
        val ble: List<BleDevice>,
        /** Location is granted, so the Wi-Fi list is real (an empty list means nothing in range). */
        val wifiAvailable: Boolean,
        /** BLUETOOTH_SCAN is granted and the adapter is on. */
        val bleAvailable: Boolean,
    )

    private fun granted(p: String) =
        ContextCompat.checkSelfPermission(appContext, p) == PackageManager.PERMISSION_GRANTED

    private fun hasLocation() =
        granted(Manifest.permission.ACCESS_FINE_LOCATION) || granted(Manifest.permission.ACCESS_COARSE_LOCATION)

    @Suppress("DEPRECATION")
    suspend fun wifiNetworks(): Pair<List<WifiNet>, Boolean> = withContext(Dispatchers.IO) {
        if (!hasLocation()) return@withContext emptyList<WifiNet>() to false
        val wifi = appContext.getSystemService(WifiManager::class.java) ?: return@withContext emptyList<WifiNet>() to false
        val nets = runCatching {
            runCatching { wifi.startScan() } // best-effort; throttling just serves cached results
            wifi.scanResults.map { r ->
                val freq = r.frequency
                WifiNet(
                    ssid = r.SSID?.takeIf { it.isNotBlank() } ?: "(hidden network)",
                    bssid = r.BSSID.orEmpty(),
                    rssi = r.level,
                    band = Recon.rssiBand(r.level),
                    channel = Recon.channelFor(freq),
                    security = Recon.wifiSecurity(r.capabilities.orEmpty()),
                    isOpen = Recon.isOpenNetwork(r.capabilities.orEmpty()),
                )
            }.sortedByDescending { it.rssi }
        }.getOrDefault(emptyList())
        nets to true
    }

    suspend fun bleDevices(): Pair<List<BleDevice>, Boolean> = withContext(Dispatchers.IO) {
        if (!granted(Manifest.permission.BLUETOOTH_SCAN)) return@withContext emptyList<BleDevice>() to false
        val adapter = appContext.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) return@withContext emptyList<BleDevice>() to false
        val scanner = adapter.bluetoothLeScanner ?: return@withContext emptyList<BleDevice>() to false

        val oui = oui.map()
        // Keep the strongest sighting of each address, and the richest name seen for it.
        val best = java.util.Collections.synchronizedMap(HashMap<String, ScanResult>())
        val callback = object : ScanCallback() {
            private fun keep(r: ScanResult) {
                val a = r.device.address ?: return
                val prev = best[a]
                if (prev == null || r.rssi > prev.rssi || (prev.scanRecord?.deviceName == null && r.scanRecord?.deviceName != null)) {
                    best[a] = r
                }
            }
            override fun onScanResult(callbackType: Int, result: ScanResult) = keep(result)
            override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach(::keep)
        }
        // One empty filter defeats the screen-off unfiltered-scan suppression (as the Sensorium does).
        val filters = listOf(ScanFilter.Builder().build())
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        runCatching {
            scanner.startScan(filters, settings, callback)
            delay(BURST_MS)
        }
        runCatching { scanner.stopScan(callback) }

        val list = synchronized(best) { best.values.toList() }.map { r ->
            val addr = r.device.address.orEmpty()
            @Suppress("MissingPermission")
            val name = (r.scanRecord?.deviceName ?: runCatching { r.device.name }.getOrNull())?.takeIf { it.isNotBlank() }
            BleDevice(
                name = name,
                address = addr,
                rssi = r.rssi,
                band = Recon.rssiBand(r.rssi),
                vendor = Recon.resolveMac(addr, oui).describe(),
                services = r.scanRecord?.serviceUuids?.map { it.uuid.toString() }.orEmpty(),
            )
        }.sortedByDescending { it.rssi }
        list to true
    }

    /** Both scans, for the dossier's "Radios in range" collection. */
    suspend fun scan(): Radios {
        val (wifi, wifiOk) = wifiNetworks()
        val (ble, bleOk) = bleDevices()
        return Radios(wifi, ble, wifiOk, bleOk)
    }

    private companion object {
        const val BURST_MS = 8_000L
    }
}
