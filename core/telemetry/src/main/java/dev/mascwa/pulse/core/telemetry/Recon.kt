package dev.mascwa.pulse.core.telemetry

/**
 * The pure, CI-tested core of TROVE — the device's reconnaissance dossier.
 *
 * ## Why it exists
 *
 * The ask was for the front page of a leak site pointed inward: one screen that lays out the whole
 * trove of what this device can see — the network and who else is on it, the radios advertising in
 * range, the phone itself laid bare, and the file the app has gathered on its owner — in the style of
 * "here is everything we hold". The invasiveness is the point; the owner asked for it on their own
 * device, their own network, as the sole authorized user.
 *
 * This file holds only what a test can pin: the dossier's data model, the honesty that keeps it from
 * lying, and the pure logic the two invasive collections need — which hosts a subnet sweep should
 * probe, whether a MAC address is a real hardware identifier or a privacy-randomized one, the vendor
 * behind a real one, how far a radio signal roughly is, and how to read a Wi-Fi capability string. The
 * sockets, the radio scans and the system reads live in the app module, because they are the parts a
 * test cannot hold still.
 *
 * ## The honesty this core enforces (the app's signature, and here it matters most)
 *
 * A surveillance readout that overstates what it knows is worse than one that admits its limits, so
 * the model carries limits as data rather than leaving them to prose:
 *
 * - **A randomized MAC is named, never resolved to a false vendor.** Since Android 10 a phone
 *   advertises a per-network locally-administered address; treating its first three octets as an OUI
 *   would attribute a neighbour's iPhone to whatever registrant happens to own that random prefix.
 *   [macKind] reads the U/L bit and says "randomized" instead. See [resolveMac].
 * - **A subnet sweep sees only devices that answer.** [subnetHosts] enumerates addresses to probe; a
 *   silent host is invisible, and the collection built from it must say "responders", not "everyone".
 * - **Distance from signal is an estimate and reads as one.** [rssiBand] gives a band, not a false
 *   metre figure; [rssiMeters] is offered for a numeric readout but the band is what the UI leads with.
 * - **Every [ReconEntry] carries where it came from.** [Provenance] separates what the device measured,
 *   what the system reported, what was looked up, and what was merely inferred.
 */
object Recon {

    /** Where a fact in the dossier came from — so the reader can weigh it. */
    enum class Provenance(val label: String) {
        /** This device measured it directly (a sensor reading, a host that answered a probe, an RSSI). */
        MEASURED("measured"),

        /** The operating system reported it (your IP, an installed package, the hardware model). */
        REPORTED("reported by the OS"),

        /** Looked up from an identifier (OUI → vendor, reverse-DNS, reverse-geocode). */
        RESOLVED("resolved"),

        /** Estimated, not observed (distance from signal strength, "normal for this hour"). */
        INFERRED("estimated"),
    }

    /** One line of the dossier. [detail] is optional supporting text shown on expand. */
    data class ReconEntry(
        val label: String,
        val value: String,
        val provenance: Provenance,
        val detail: String? = null,
    )

    /**
     * One section of the trove.
     *
     * @param caveat the honest ceiling for this whole collection — what it could not see, or could not
     *   be sure of. Null when there is nothing to qualify. This is the field that keeps the dossier
     *   from reading as omniscient.
     */
    data class ReconCollection(
        val key: String,
        val title: String,
        val summary: String,
        val entries: List<ReconEntry>,
        val caveat: String? = null,
    ) {
        val count: Int get() = entries.size
    }

    // ---- subnet maths -------------------------------------------------------------------------

    /**
     * Hosts to probe for the "who's on the Wi-Fi" sweep.
     *
     * A home network is almost always a /24 (254 hosts), which a ping-sweep handles in a second or two.
     * A prefix wider than that describes thousands of addresses, and probing all of them is both slow
     * and a poor guest on a network you may not own — so when the real subnet is larger than [cap] hosts
     * the sweep is clamped to **this device's own /24**, the block its neighbours actually live in. The
     * caller states the clamp; here it is simply the honest set of addresses worth trying.
     *
     * Returns dotted-quad host addresses, network and broadcast excluded. Empty for a point-to-point
     * link (/31, /32), a malformed address, or anything that is not IPv4 — a sweep is a v4 operation and
     * pretending otherwise would be the dishonesty this core exists to prevent.
     */
    fun subnetHosts(ip: String, prefixLen: Int, cap: Int = 254): List<String> {
        val base = parseIpv4(ip) ?: return emptyList()
        if (prefixLen !in 1..30) return emptyList() // /31,/32 have no ordinary hosts; <1 is nonsense
        val hostBits = 32 - prefixLen
        val hostCount = (1L shl hostBits) - 2L
        if (hostCount <= 0L) return emptyList()
        // Clamp a wide subnet to this device's own /24 — where the neighbours are, and a set a sweep
        // can finish. A /24 has exactly 254 ordinary hosts, so this is bounded whatever the input.
        val effectivePrefix = if (hostCount > cap) 24 else prefixLen
        val mask = maskFor(effectivePrefix)
        val network = base and mask
        // ⚠️ mask.inv() on a Long flips the high 32 bits too, so `network or mask.inv()` would overflow
        // into a huge (negative) value and the sweep loop below would never run. The host mask must be
        // clamped to 32 bits — network is already ≤ 32 bits, so this one clamp is what keeps it honest.
        val broadcast = network or (mask.inv() and 0xFFFFFFFFL)
        val out = ArrayList<String>(254)
        var addr = network + 1
        while (addr < broadcast) {
            out += ipv4String(addr)
            addr++
        }
        return out
    }

    /** True when [ip]'s real subnet is wider than [cap] hosts, so [subnetHosts] clamped it to a /24. */
    fun sweepWasClamped(prefixLen: Int, cap: Int = 254): Boolean =
        prefixLen in 1..30 && ((1L shl (32 - prefixLen)) - 2L) > cap

    private fun maskFor(prefixLen: Int): Long =
        if (prefixLen == 0) 0L else (0xFFFFFFFFL shl (32 - prefixLen)) and 0xFFFFFFFFL

    /** Parse a dotted IPv4 to a 32-bit value in a Long, or null if it is not a well-formed v4 address. */
    fun parseIpv4(ip: String): Long? {
        val parts = ip.trim().split('.')
        if (parts.size != 4) return null
        var v = 0L
        for (p in parts) {
            val n = p.toIntOrNull() ?: return null
            if (n !in 0..255) return null
            v = (v shl 8) or n.toLong()
        }
        return v
    }

    fun ipv4String(addr: Long): String {
        val a = addr and 0xFFFFFFFFL
        return "${(a ushr 24) and 0xFF}.${(a ushr 16) and 0xFF}.${(a ushr 8) and 0xFF}.${a and 0xFF}"
    }

    // ---- MAC identity -------------------------------------------------------------------------

    enum class MacKind {
        /** A valid, globally-unique hardware address — worth resolving to a vendor. */
        HARDWARE,

        /** A locally-administered (privacy-randomized) address — resolving it is meaningless. */
        RANDOMIZED,

        /** Blank, malformed, or not readable on this OS — nothing to say. */
        UNKNOWN,
    }

    /**
     * What kind of address this is.
     *
     * The **U/L bit** — bit 1 (value 2) of the first octet — is set on a locally-administered address,
     * which is what a modern phone advertises to defeat exactly the kind of tracking this dossier does.
     * The **multicast bit** — bit 0 — is never set on a real unicast device address; a scan result
     * carrying one is not a host. Both are read here so a randomized address is called what it is.
     */
    fun macKind(mac: String): MacKind {
        val first = firstOctet(mac) ?: return MacKind.UNKNOWN
        if (first and 0x01 != 0) return MacKind.UNKNOWN // multicast/broadcast — not a device
        return if (first and 0x02 != 0) MacKind.RANDOMIZED else MacKind.HARDWARE
    }

    /** The 24-bit OUI (first three octets, uppercase hex, no separators), or null if unresolvable. */
    fun ouiKey(mac: String): String? {
        val hex = mac.filter { it.isLetterOrDigit() }.uppercase()
        if (hex.length < 6) return null
        val oui = hex.take(6)
        if (!oui.all { it in "0123456789ABCDEF" }) return null
        return oui
    }

    /** The resolved identity of a scan result's address. */
    data class MacIdentity(val kind: MacKind, val vendor: String?) {
        /** One line for the dossier: the vendor, or an honest reason there is none. */
        fun describe(): String = when (kind) {
            MacKind.RANDOMIZED -> "randomized (private)"
            MacKind.UNKNOWN -> "MAC unavailable"
            MacKind.HARDWARE -> vendor ?: "unknown vendor"
        }
    }

    /**
     * Resolve [mac] against an OUI [table] (24-bit prefix → registrant).
     *
     * A randomized address is never looked up — its vendor field stays null and [MacIdentity.describe]
     * says so. A hardware address with no entry in the table is "unknown vendor", distinct from "not a
     * hardware address at all".
     */
    fun resolveMac(mac: String, table: Map<String, String>): MacIdentity {
        val kind = macKind(mac)
        if (kind != MacKind.HARDWARE) return MacIdentity(kind, null)
        val vendor = ouiKey(mac)?.let { table[it] }
        return MacIdentity(MacKind.HARDWARE, vendor)
    }

    private fun firstOctet(mac: String): Int? {
        val hex = mac.filter { it.isLetterOrDigit() }.uppercase()
        if (hex.length < 2) return null
        return hex.take(2).toIntOrNull(16)
    }

    // ---- radio signal -------------------------------------------------------------------------

    /**
     * A rough distance band from signal strength — the honest granularity.
     *
     * RSSI is dominated by walls, bodies and orientation, so a metre figure implies a precision the
     * measurement does not have. A band does not. A value of 0 or above is not a real reading.
     */
    fun rssiBand(rssi: Int): String = when {
        rssi >= 0 -> "signal unknown"
        rssi >= -50 -> "very close"
        rssi >= -67 -> "nearby"
        rssi >= -80 -> "in range"
        else -> "far"
    }

    /**
     * Log-distance path-loss estimate in metres, for a numeric readout that wants one. `n` is the
     * environmental factor (≈2 free space, higher indoors); [txAt1m] is the reference power at one
     * metre. Offered alongside [rssiBand], which the UI leads with, because this number is an estimate.
     */
    fun rssiMeters(rssi: Int, txAt1m: Int = -59, n: Double = 2.5): Double {
        if (rssi >= 0) return -1.0
        return Math.pow(10.0, (txAt1m - rssi) / (10.0 * n))
    }

    // ---- Wi-Fi capability strings -------------------------------------------------------------

    /** Read a scan result's capability string into a short security name. */
    fun wifiSecurity(capabilities: String): String {
        val c = capabilities.uppercase()
        return when {
            c.contains("WPA3") || c.contains("SAE") -> "WPA3"
            c.contains("WPA2") || c.contains("RSN") -> "WPA2"
            c.contains("WPA") -> "WPA"
            c.contains("WEP") -> "WEP"
            c.contains("OWE") -> "Open (encrypted)"
            else -> "Open"
        }
    }

    /** True when a network is genuinely open — no key of any kind. A distinct, worth-flagging fact. */
    fun isOpenNetwork(capabilities: String): Boolean = wifiSecurity(capabilities) == "Open"

    /** The 802.11 channel for a centre frequency in MHz, or null for a frequency outside the bands. */
    fun channelFor(freqMhz: Int): Int? = when (freqMhz) {
        2484 -> 14
        in 2412..2472 -> (freqMhz - 2407) / 5
        in 5160..5885 -> (freqMhz - 5000) / 5
        in 5955..7115 -> (freqMhz - 5950) / 5 // 6 GHz
        else -> null
    }

    // ---- the dossier as text ------------------------------------------------------------------

    /**
     * The whole dossier rendered to plain text — the one definition the on-screen view and the exported
     * report both defer to, so they can never describe the trove differently.
     */
    fun reportText(collections: List<ReconCollection>, header: String): String = buildString {
        append(header).append('\n')
        append("=".repeat(header.length.coerceAtMost(60))).append("\n\n")
        for (c in collections) {
            append("## ").append(c.title).append("  (").append(c.count).append(")\n")
            if (c.summary.isNotBlank()) append(c.summary).append('\n')
            c.caveat?.let { append("⚠ ").append(it).append('\n') }
            append('\n')
            for (e in c.entries) {
                append("  ").append(e.label).append(": ").append(e.value)
                append("  [").append(e.provenance.label).append("]\n")
                e.detail?.let { append("      ").append(it).append('\n') }
            }
            append('\n')
        }
    }
}
