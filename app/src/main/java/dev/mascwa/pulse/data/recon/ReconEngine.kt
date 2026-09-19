package dev.mascwa.pulse.data.recon

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Build
import dev.mascwa.pulse.core.device.GrapheneOs
import dev.mascwa.pulse.core.telemetry.Recon
import dev.mascwa.pulse.core.telemetry.Recon.Provenance.INFERRED
import dev.mascwa.pulse.core.telemetry.Recon.Provenance.MEASURED
import dev.mascwa.pulse.core.telemetry.Recon.Provenance.REPORTED
import dev.mascwa.pulse.core.telemetry.Recon.Provenance.RESOLVED
import dev.mascwa.pulse.core.telemetry.Recon.ReconCollection
import dev.mascwa.pulse.core.telemetry.Recon.ReconEntry
import dev.mascwa.pulse.di.AppContainer

/**
 * TROVE's engine — the whole trove of what this device can see, gathered from every subsystem it has.
 *
 * Shaped after `OracleEngine.snapshot` and `DeviceSearchIndex.records`: each collection is gathered
 * independently and defensively, so a store that has not loaded, a permission that is not granted, or a
 * feed that is down costs its own section and nothing else — the surface always has structure to draw,
 * and a section that could not be read says so in its [ReconCollection.caveat] rather than vanishing.
 *
 * The two invasive collections ([network], [radios]) are slow (a subnet sweep, a BLE burst) and the
 * around-you collection hits the network, so each is exposed on its own; the ViewModel drives them and
 * shows progress. Describe-on-demand: nothing here is persisted — the dossier is rebuilt live and keeps
 * no log of who was on the Wi-Fi, which is the privacy-cleaner choice for a feature that enumerates
 * other people's devices.
 */
class ReconEngine(private val c: AppContainer) {

    private val ctx: Context get() = c.applicationContext
    private val oui = OuiTable(ctx)
    private val networkScanner = NetworkScanner(ctx, oui)
    private val radioScanner = RadioScanner(ctx, oui)

    // ---- 1. the network + who's on it ---------------------------------------------------------

    suspend fun network(): ReconCollection = runCatching {
        val s = networkScanner.sweep()
        val info = s.info
        val head = ArrayList<ReconEntry>()
        info.ssid?.let { head += ReconEntry("Network", it, REPORTED) }
        info.bssid?.let { head += ReconEntry("Access point", it, REPORTED) }
        info.ipv4?.let { ip ->
            head += ReconEntry("Your address", ip + (info.prefixLen?.let { "/$it" } ?: ""), REPORTED)
        }
        info.gateway?.let { head += ReconEntry("Gateway", it, REPORTED) }
        if (info.dns.isNotEmpty()) head += ReconEntry("DNS", info.dns.joinToString(", "), REPORTED)
        info.linkSpeedMbps?.let { head += ReconEntry("Link speed", "$it Mbps", REPORTED) }
        info.channel?.let { head += ReconEntry("Channel", "$it" + (info.frequencyMhz?.let { f -> " · $f MHz" } ?: ""), REPORTED) }
        info.rssi?.let { head += ReconEntry("Signal", "$it dBm · ${Recon.rssiBand(it)}", MEASURED) }
        info.vpnActive?.let { if (it) head += ReconEntry("VPN", "active", REPORTED) }
        info.metered?.let { head += ReconEntry("Metered", if (it) "yes" else "no", REPORTED) }

        val hosts = s.hosts.map { h ->
            val label = h.hostname ?: h.ip
            val value = buildString {
                append(h.vendor)
                if (h.isSelf) append(" · this device")
            }
            ReconEntry(
                label = label,
                value = value,
                provenance = if (h.mac != null) RESOLVED else MEASURED,
                detail = listOfNotNull(h.ip.takeIf { it != label }, h.mac).joinToString(" · ").ifBlank { null },
            )
        }

        val caveat = buildString {
            if (info.ipv4 == null) {
                append("Not on a Wi-Fi network the sweep can read.")
            } else {
                append("Saw ${s.hosts.size} of ${s.probed} addresses answer — silent devices are invisible.")
                if (s.clamped) append(" The subnet is wider than a /24, so the sweep was limited to this block.")
                if (!s.arpReadable && s.hosts.isNotEmpty()) append(" This OS did not expose MAC addresses, so vendors are unavailable.")
            }
            if (info.identityHidden) append(" Grant location to read the network name.")
        }
        ReconCollection(
            key = "network",
            title = "The network, and who is on it",
            summary = info.ssid?.let { "You are on $it." } ?: "Your connection and the devices sharing it.",
            entries = head + hosts,
            caveat = caveat.ifBlank { null },
        )
    }.getOrElse { failed("network", "The network, and who is on it") }

    // ---- 2. radios in range -------------------------------------------------------------------

    suspend fun radios(): ReconCollection = runCatching {
        val r = radioScanner.scan()
        val wifi = r.wifi.map { n ->
            ReconEntry(
                label = n.ssid,
                value = "${n.security} · ${n.band}" + (n.channel?.let { " · ch $it" } ?: ""),
                provenance = MEASURED,
                detail = "${n.bssid} · ${n.rssi} dBm" + if (n.isOpen) " · OPEN" else "",
            )
        }
        val ble = r.ble.map { d ->
            ReconEntry(
                label = d.name ?: d.address,
                value = "${d.vendor} · ${d.band}",
                provenance = if (d.name != null) RESOLVED else MEASURED,
                detail = listOfNotNull(
                    d.address.takeIf { it != d.name },
                    "${d.rssi} dBm",
                    d.services.firstOrNull()?.let { "svc ${it.take(8)}…" },
                ).joinToString(" · "),
            )
        }
        val caveat = buildString {
            if (!r.wifiAvailable) append("Grant location to list Wi-Fi networks. ")
            if (!r.bleAvailable) append("Turn on Bluetooth and grant nearby-devices to list BLE. ")
            if (r.ble.isNotEmpty()) append("Most BLE addresses are privacy-randomized, which is why so many read \"private\".")
        }
        ReconCollection(
            key = "radios",
            title = "Radios in range",
            summary = "${r.wifi.size} Wi-Fi networks · ${r.ble.size} Bluetooth devices advertising.",
            entries = wifi + ble,
            caveat = caveat.trim().ifBlank { null },
        )
    }.getOrElse { failed("radios", "Radios in range") }

    // ---- 3. this device, laid bare ------------------------------------------------------------

    suspend fun device(): ReconCollection = runCatching {
        val e = ArrayList<ReconEntry>()
        val maker = (Build.MANUFACTURER ?: "").replaceFirstChar { it.uppercase() }.trim()
        val model = (Build.MODEL ?: "").trim()
        e += ReconEntry("Device", listOf(maker, model).filter { it.isNotBlank() }.joinToString(" ").ifBlank { "unknown" }, REPORTED,
            detail = Build.DEVICE?.takeIf { it.isNotBlank() })
        val patch = runCatching { Build.VERSION.SECURITY_PATCH }.getOrNull()?.takeIf { it.isNotBlank() }
        e += ReconEntry("OS", "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})", REPORTED,
            detail = patch?.let { "security patch $it" })
        runCatching { Build.SUPPORTED_ABIS.firstOrNull() }.getOrNull()?.let { e += ReconEntry("Architecture", it, REPORTED) }

        runCatching { GrapheneOs.detect(ctx) }.getOrNull()?.let {
            e += ReconEntry("GrapheneOS", if (it.isGraphene) "detected" else "not detected", INFERRED, detail = it.signals.joinToString(", ").ifBlank { null })
        }

        runCatching { c.deviceAttestation.run() }.getOrNull()?.let { rep ->
            rep.verdict?.let { v ->
                e += ReconEntry("Security posture", v.summary, REPORTED, detail = listOf(
                    "StrongBox ${yn(v.strongBox)}",
                    "verified boot ${yn(v.verifiedBoot)}",
                    "bootloader ${if (v.bootloaderLocked) "locked" else "unlocked"}",
                ).joinToString(" · "))
            } ?: run {
                if (rep.error != null) e += ReconEntry("Security posture", "attestation unavailable", REPORTED, detail = rep.error)
            }
        }

        runCatching { c.deviceContextProvider.snapshot() }.getOrNull()?.let { dc ->
            val p = if (dc.batteryPct >= 0) "${dc.batteryPct}%" + (if (dc.isCharging) " · charging" else "") else "unknown"
            e += ReconEntry("Power", p + (if (dc.isPowerSave) " · power-save" else ""), MEASURED)
            e += ReconEntry("Network type", dc.network.name.lowercase(), REPORTED)
        }
        runCatching { c.deviceContextProvider.storage() }.getOrNull()?.let { st ->
            e += ReconEntry("Storage", "${gb(st.freeBytes)} free of ${gb(st.totalBytes)}", REPORTED)
        }
        runCatching {
            val am = ctx.getSystemService(ActivityManager::class.java)
            val mi = ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) }
            e += ReconEntry("Memory", "${gb(mi.availMem)} free of ${gb(mi.totalMem)}", REPORTED)
        }
        runCatching {
            val pm = ctx.packageManager
            val all = pm.getInstalledApplications(0).size
            val launchable = pm.queryIntentActivities(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0,
            ).size
            e += ReconEntry("Installed apps", "$all apps", REPORTED, detail = "$launchable with a launcher icon")
        }
        runCatching {
            val sm = ctx.getSystemService(SensorManager::class.java)
            val n = sm?.getSensorList(Sensor.TYPE_ALL)?.size ?: 0
            if (n > 0) e += ReconEntry("Sensors", "$n on this device", REPORTED)
        }

        ReconCollection("device", "This device, laid bare", "Everything the phone will admit about itself.", e)
    }.getOrElse { failed("device", "This device, laid bare") }

    // ---- 4. the file the app has on you -------------------------------------------------------

    suspend fun aboutYou(): ReconCollection = runCatching {
        val e = ArrayList<ReconEntry>()
        runCatching { c.profileStore.all() to c.profileStore.digest() }.getOrNull()?.let { (all, digest) ->
            if (all.isNotEmpty()) e += ReconEntry("Profile", "${all.size} facts on file", INFERRED, detail = digest.ifBlank { null })
        }
        runCatching { c.interestStore.all() to c.interestStore.digest() }.getOrNull()?.let { (all, digest) ->
            if (all.isNotEmpty()) e += ReconEntry("Interests", "${all.size} tracked", INFERRED, detail = digest.ifBlank { null })
        }
        runCatching { c.taskStore.all() to c.taskStore.summary() }.getOrNull()?.let { (all, summary) ->
            if (all.isNotEmpty()) e += ReconEntry("Tasks", summary.ifBlank { "${all.size} tracked" }, REPORTED)
        }
        runCatching { c.memoryStream.all() }.getOrNull()?.let { mem ->
            if (mem.isNotEmpty()) {
                val timeline = runCatching { c.memoryStream.timeline(8) }.getOrNull()
                e += ReconEntry("Episodic memory", "${mem.size} moments recorded", INFERRED, detail = timeline?.ifBlank { null })
            }
        }
        runCatching { c.usageRepository.recentActivity(200).size }.getOrNull()?.let { n ->
            if (n > 0) e += ReconEntry("Activity log", "$n recent events logged", MEASURED)
        }
        runCatching { c.bodyStore.all() }.getOrNull()?.lastOrNull()?.let { w ->
            e += ReconEntry("Health", "last weigh-in on record", REPORTED, detail = "${"%.1f".format(java.util.Locale.US, w.kg)} kg")
        }
        val caveat = if (e.isEmpty()) "Nothing gathered yet — the assistant fills this in as you use the app." else
            "Everything here stays on this device; it is not transmitted."
        ReconCollection("you", "The file the app has on you", "What this app has gathered about its owner.", e, caveat)
    }.getOrElse { failed("you", "The file the app has on you") }

    // ---- 5. the environment right now ---------------------------------------------------------

    suspend fun environment(): ReconCollection = runCatching {
        val reading = c.sensoriumEngine.reading.value
        val e = ArrayList<ReconEntry>()
        e += ReconEntry("Setting", reading.setting.name.lowercase(), INFERRED)
        e += ReconEntry("Motion", reading.motion.name.lowercase(), MEASURED)
        e += ReconEntry("Around you", reading.social.name.lowercase(), INFERRED)
        e += ReconEntry("Sound", reading.noise.name.lowercase(), MEASURED)
        e += ReconEntry("Light", reading.light.name.lowercase(), MEASURED)
        val anomaly = runCatching { c.sensoriumEngine.anomalies.value.firstOrNull()?.let { "${it.metric} ${it.text}" } }.getOrNull()
        anomaly?.let { e += ReconEntry("Unusual", it, INFERRED) }
        ReconCollection(
            key = "environment",
            title = "The environment right now",
            summary = runCatching { reading.describe() }.getOrDefault("What the ship's senses read around you."),
            entries = e,
            caveat = "Read from the microphone, camera and motion sensors; raw audio and frames are never kept — only these labels.",
        )
    }.getOrElse { failed("environment", "The environment right now") }

    // ---- 6. where you are, and what is around -------------------------------------------------

    suspend fun aroundYou(): ReconCollection = runCatching {
        val loc = c.locationProvider.current()
            ?: return@runCatching ReconCollection(
                "around", "Where you are, and what is around", "",
                emptyList(), "Location is off or has no fix, so nothing around you can be read.",
            )
        val e = ArrayList<ReconEntry>()
        e += ReconEntry("You are at", loc.name, REPORTED,
            detail = "${"%.5f".format(java.util.Locale.US, loc.latitude)}, ${"%.5f".format(java.util.Locale.US, loc.longitude)}")
        loc.accuracyM?.let { e += ReconEntry("Fix accuracy", "±${it.toInt()} m", MEASURED) }

        val lat = loc.latitude; val lon = loc.longitude
        runCatching { c.radarRepository.fetch(lat, lon, false).data }.getOrNull()?.let { radar ->
            val air = radar.contacts.filter { it.kind == "AIRCRAFT" }
            if (air.isNotEmpty()) e += ReconEntry("Aircraft overhead", "${air.size} tracked", MEASURED,
                detail = air.take(3).joinToString(", ") { it.label })
        }
        runCatching { c.safetyRepository.fetch(lat, lon, false).data }.getOrNull()?.let { safety ->
            if (safety.incidents.isNotEmpty()) {
                val nearest = safety.incidents.minByOrNull { it.distanceMeters }
                e += ReconEntry("Incidents nearby", "${safety.incidents.size} in range", MEASURED,
                    detail = nearest?.let { "${it.title} · ${(it.distanceMeters / 1000).toInt()} km" })
            }
        }
        runCatching { c.overpassRepository.fetch(dev.mascwa.pulse.data.places.PlaceCategory.HOSPITAL, lat, lon, false).data }.getOrNull()
            ?.places?.minByOrNull { it.distanceMeters }?.let { h ->
                e += ReconEntry("Nearest hospital", h.name, RESOLVED, detail = "${(h.distanceMeters / 1000).toInt()} km")
            }
        runCatching { c.spaceWeatherRepository.fetch(force = false, heavy = false).data.kp }.getOrNull()?.let { kp ->
            e += ReconEntry("Space weather", "Kp ${"%.1f".format(java.util.Locale.US, kp)}", MEASURED)
        }
        ReconCollection("around", "Where you are, and what is around", "Your position and everything the feeds place near it.", e)
    }.getOrElse { failed("around", "Where you are, and what is around") }

    // ---- the whole trove ----------------------------------------------------------------------

    /** Every collection, each gathered independently. The order is the dossier's reading order. */
    suspend fun full(): List<ReconCollection> =
        listOf(network(), radios(), device(), aboutYou(), environment(), aroundYou())

    // ---- helpers ------------------------------------------------------------------------------

    private fun failed(key: String, title: String) =
        ReconCollection(key, title, "", emptyList(), "This section could not be read.")

    private fun yn(b: Boolean) = if (b) "yes" else "no"

    private fun gb(bytes: Long): String {
        val g = bytes / 1_000_000_000.0
        return if (g >= 1.0) "${"%.1f".format(java.util.Locale.US, g)} GB"
        else "${(bytes / 1_000_000L)} MB"
    }
}
