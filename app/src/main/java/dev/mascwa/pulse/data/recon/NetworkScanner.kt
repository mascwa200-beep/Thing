package dev.mascwa.pulse.data.recon

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import androidx.core.content.ContextCompat
import dev.mascwa.pulse.core.telemetry.Recon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.net.Inet4Address
import java.net.InetAddress

/**
 * The invasive half of TROVE: your connection laid bare, and a live sweep of who else is on the Wi-Fi.
 *
 * ## What it can and cannot see (stated, because a surveillance readout that overstates is worse than one
 * that admits its limits)
 *
 * - **The connection** comes from [LinkProperties] (IP, prefix, gateway, DNS) and [WifiManager]'s
 *   association info (SSID, BSSID, signal, link speed, band). SSID and BSSID need location permission on
 *   Android 10+; without it those two fields are null and everything else still reads.
 * - **The sweep** pings every ordinary host in the subnet ([Recon.subnetHosts], clamped to this device's
 *   own /24 for a wider network) and reports the ones that **answer**. A device that stays silent —
 *   firewalled, asleep — is invisible, so the collection built from this says "responders", never "everyone".
 * - **Each responder's MAC** is read from the kernel ARP cache (`/proc/net/arp`), populated by the sweep
 *   itself. ⚠️ That file is restricted on newer Android and may come back empty; when it does, the host is
 *   still listed with its IP and hostname and its vendor reads "MAC unavailable", never a fabricated one.
 * - **The vendor** is resolved from the OUI table ([OuiTable]); a randomized (privacy) MAC is named
 *   private and never resolved — the honesty rule lives in [Recon.resolveMac].
 *
 * No new permission is needed for the sweep — Android has no local-network gate. All work is on IO.
 */
class NetworkScanner(context: Context, private val oui: OuiTable) {
    private val appContext = context.applicationContext

    /** Your connection, laid bare. Cheap — no sweep. */
    data class NetworkInfo(
        val ssid: String?,
        val bssid: String?,
        val security: String?,
        val ipv4: String?,
        val prefixLen: Int?,
        val gateway: String?,
        val dns: List<String>,
        val linkSpeedMbps: Int?,
        val frequencyMhz: Int?,
        val channel: Int?,
        val rssi: Int?,
        val metered: Boolean?,
        val vpnActive: Boolean?,
        /** SSID/BSSID were withheld because location permission is not granted. */
        val identityHidden: Boolean,
    )

    /** One device found answering on the local subnet. */
    data class LanHost(
        val ip: String,
        val mac: String?,
        val hostname: String?,
        val vendor: String,
        val isSelf: Boolean,
    )

    data class Sweep(
        val info: NetworkInfo,
        val hosts: List<LanHost>,
        /** How many addresses were probed. */
        val probed: Int,
        /** The real subnet was wider than a /24, so the sweep was clamped to this device's own block. */
        val clamped: Boolean,
        /** The kernel ARP table could be read (so hosts carry MACs); false on an OS that restricts it. */
        val arpReadable: Boolean,
    )

    private fun hasLocation(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    @Suppress("DEPRECATION")
    suspend fun connection(): NetworkInfo = withContext(Dispatchers.IO) {
        val cm = appContext.getSystemService(ConnectivityManager::class.java)
        val wifi = appContext.getSystemService(WifiManager::class.java)
        val net = cm?.activeNetwork
        val lp: LinkProperties? = net?.let { runCatching { cm.getLinkProperties(it) }.getOrNull() }
        val caps: NetworkCapabilities? = net?.let { runCatching { cm.getNetworkCapabilities(it) }.getOrNull() }

        // IP + prefix from the first IPv4 link address.
        val v4 = lp?.linkAddresses?.firstOrNull { it.address is Inet4Address }
        val ipv4 = v4?.address?.hostAddress
        val prefix = v4?.prefixLength

        // Gateway from the default route.
        val gateway = lp?.routes
            ?.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }
            ?.gateway?.hostAddress

        val dns = lp?.dnsServers?.mapNotNull { it.hostAddress }.orEmpty()

        val loc = hasLocation()
        val info = wifi?.connectionInfo
        val ssidRaw = info?.ssid?.takeIf { it.isNotBlank() && it != "<unknown ssid>" }
        val ssid = if (loc) ssidRaw?.trim('"') else null
        val bssid = if (loc) info?.bssid?.takeIf { it.isNotBlank() && it != "02:00:00:00:00:00" } else null
        val freq = info?.frequency?.takeIf { it > 0 }
        val vpn = caps?.let { !it.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) }

        NetworkInfo(
            ssid = ssid,
            bssid = bssid,
            security = null, // security is a per-AP capability string, surfaced by the radio scan
            ipv4 = ipv4,
            prefixLen = prefix,
            gateway = gateway,
            dns = dns,
            linkSpeedMbps = info?.linkSpeed?.takeIf { it > 0 },
            frequencyMhz = freq,
            channel = freq?.let { Recon.channelFor(it) },
            rssi = info?.rssi?.takeIf { it in -127..0 }, // a valid associated RSSI; sentinel otherwise
            metered = cm?.isActiveNetworkMetered,
            vpnActive = vpn,
            identityHidden = !loc && ipv4 != null,
        )
    }

    /**
     * Connection details plus a live subnet sweep. Bounded parallel ICMP/TCP reachability probes; the
     * responders are then correlated against the ARP cache the sweep just populated.
     */
    suspend fun sweep(): Sweep = withContext(Dispatchers.IO) {
        val info = connection()
        val ip = info.ipv4
        val prefix = info.prefixLen
        if (ip == null || prefix == null) {
            return@withContext Sweep(info, emptyList(), 0, false, false)
        }
        val targets = Recon.subnetHosts(ip, prefix)
        val clamped = Recon.sweepWasClamped(prefix)

        val gate = Semaphore(CONCURRENCY)
        val responders: List<String> = coroutineScope {
            targets.map { host ->
                async {
                    gate.withPermit {
                        val reachable = runCatching {
                            InetAddress.getByName(host).isReachable(PROBE_TIMEOUT_MS)
                        }.getOrDefault(false)
                        if (reachable) host else null
                    }
                }
            }.awaitAll().filterNotNull()
        }

        // Read the ARP cache once, after the sweep has populated it for the responders.
        val arp = readArp()
        val oui = oui.map()

        val hosts = responders.map { host ->
            val mac = arp[host]
            val hostname = runCatching {
                InetAddress.getByName(host).canonicalHostName.takeIf { it != host }
            }.getOrNull()
            LanHost(
                ip = host,
                mac = mac,
                hostname = hostname,
                vendor = mac?.let { Recon.resolveMac(it, oui).describe() } ?: "MAC unavailable",
                isSelf = host == ip,
            )
        }.sortedWith(compareByDescending<LanHost> { it.isSelf }.thenBy { sortKey(it.ip) })

        Sweep(info, hosts, targets.size, clamped, arpReadable = arp.isNotEmpty())
    }

    /**
     * The kernel ARP table: IP → MAC for hosts recently talked to. Best-effort — restricted on newer
     * Android, where the file reads empty and every host's MAC is honestly absent.
     *
     * Format: `IP HWtype Flags HWaddr Mask Device`, one per line after a header. A `00:00:00:00:00:00`
     * entry is an incomplete cache slot and is dropped.
     */
    private fun readArp(): Map<String, String> {
        val f = File("/proc/net/arp")
        return runCatching {
            f.readLines().drop(1).mapNotNull { line ->
                val cols = line.trim().split(Regex("\\s+"))
                if (cols.size < 4) return@mapNotNull null
                val ip = cols[0]
                val mac = cols[3].uppercase()
                if (mac == "00:00:00:00:00:00") return@mapNotNull null
                ip to mac
            }.toMap()
        }.getOrDefault(emptyMap())
    }

    /** Sort dotted IPs numerically, not lexically, so .10 follows .9. */
    private fun sortKey(ip: String): Long = Recon.parseIpv4(ip) ?: Long.MAX_VALUE

    private companion object {
        const val CONCURRENCY = 32
        const val PROBE_TIMEOUT_MS = 400
    }
}
