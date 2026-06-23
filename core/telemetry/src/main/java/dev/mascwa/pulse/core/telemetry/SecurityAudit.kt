package dev.mascwa.pulse.core.telemetry

/**
 * Pure, dependency-free analysis core for the on-device **security auditor** (a defender's tool: read-only,
 * local-only, never phones home). The Android collectors gather raw facts — installed apps + granted
 * permissions, the system/user CA trust store, device-encryption status, per-app data usage, proxy/DNS
 * config — and feed them here; this object turns facts into ranked, de-duped, whitelist-filtered
 * [Finding]s. Kept Android-free so the (noise-sensitive) classification rules are unit-tested in CI.
 *
 * Design bias, per the brief: catch real issues, ignore noise, present only actionable intelligence.
 * System apps are not flagged for permission combos (they legitimately hold everything); the rules target
 * user-installed apps and changes over time.
 */
object SecurityAudit {

    enum class Severity { INFO, WARNING, CRITICAL }

    enum class Category { PERMISSIONS, CERTIFICATES, ENCRYPTION, DATA, NETWORK, STORAGE }

    /** One audit result. [id] is a stable key (category:rule:subject) used for dedupe + whitelisting. */
    data class Finding(
        val id: String,
        val category: Category,
        val severity: Severity,
        val title: String,
        val detail: String,
        val subject: String = "",
    )

    /** An installed app + its currently-granted permissions (fully-qualified `android.permission.*`). */
    data class AppPermissions(
        val packageName: String,
        val label: String,
        val isSystem: Boolean,
        val granted: Set<String>,
    )

    /** A CA in the device trust store. [isSystem] = shipped with the OS; user-added CAs are the MITM risk. */
    data class TrustedCa(val subject: String, val isSystem: Boolean)

    /** Per-app data usage over the scan window (bytes). */
    data class AppDataUsage(val packageName: String, val label: String, val rxBytes: Long, val txBytes: Long)

    enum class EncryptionStatus { ENCRYPTED, INACTIVE, ACTIVATING, UNSUPPORTED, UNKNOWN }

    // --- Permission constants ---
    const val INTERNET = "android.permission.INTERNET"
    const val CAMERA = "android.permission.CAMERA"
    const val MIC = "android.permission.RECORD_AUDIO"
    const val FINE_LOCATION = "android.permission.ACCESS_FINE_LOCATION"
    const val BACKGROUND_LOCATION = "android.permission.ACCESS_BACKGROUND_LOCATION"
    const val READ_SMS = "android.permission.READ_SMS"
    const val READ_CONTACTS = "android.permission.READ_CONTACTS"
    const val READ_EXTERNAL = "android.permission.READ_EXTERNAL_STORAGE"
    const val MANAGE_EXTERNAL = "android.permission.MANAGE_EXTERNAL_STORAGE"
    const val INSTALL_PACKAGES = "android.permission.REQUEST_INSTALL_PACKAGES"
    const val SYSTEM_ALERT_WINDOW = "android.permission.SYSTEM_ALERT_WINDOW"
    const val QUERY_ALL_PACKAGES = "android.permission.QUERY_ALL_PACKAGES"

    /** Permissions that meaningfully raise an app's risk surface (used for combos + the exfil correlation). */
    val RISKY_PERMISSIONS: Set<String> = setOf(
        CAMERA, MIC, FINE_LOCATION, BACKGROUND_LOCATION, READ_SMS, READ_CONTACTS,
        MANAGE_EXTERNAL, INSTALL_PACKAGES, SYSTEM_ALERT_WINDOW, QUERY_ALL_PACKAGES,
    )

    private fun has(app: AppPermissions, vararg perms: String) = perms.all { it in app.granted }
    private fun short(perm: String) = perm.substringAfterLast('.')

    /**
     * High-signal permission combinations for a single (user-installed) app. System apps are skipped —
     * they hold everything by design and would only add noise.
     */
    fun permissionCombos(app: AppPermissions): List<Finding> {
        if (app.isSystem) return emptyList()
        val out = ArrayList<Finding>()
        fun add(rule: String, sev: Severity, title: String, detail: String) {
            out += Finding("PERMISSIONS:$rule:${app.packageName}", Category.PERMISSIONS, sev, title, detail, app.packageName)
        }
        val net = has(app, INTERNET)
        if (net && has(app, CAMERA)) add("CAM_NET", Severity.WARNING, "${app.label}: camera + network", "Can capture images/video and send them off-device.")
        if (net && has(app, MIC)) add("MIC_NET", Severity.WARNING, "${app.label}: microphone + network", "Can record audio and send it off-device.")
        if (has(app, MIC) && (has(app, READ_EXTERNAL) || has(app, MANAGE_EXTERNAL))) add("MIC_STORAGE", Severity.WARNING, "${app.label}: microphone + storage", "Can record audio and read shared files.")
        if (net && has(app, READ_SMS)) add("SMS_NET", Severity.WARNING, "${app.label}: read SMS + network", "Can read messages (incl. 2FA codes) and send them off-device.")
        if (net && has(app, READ_CONTACTS)) add("CONTACTS_NET", Severity.INFO, "${app.label}: contacts + network", "Can read contacts and send them off-device.")
        if (has(app, MANAGE_EXTERNAL)) add("MANAGE_STORAGE", Severity.WARNING, "${app.label}: all-files access", "Holds MANAGE_EXTERNAL_STORAGE — broad access to shared storage.")
        if (net && has(app, INSTALL_PACKAGES)) add("INSTALL_NET", Severity.WARNING, "${app.label}: install apps + network", "Can download and prompt to install other apps.")
        if (has(app, SYSTEM_ALERT_WINDOW) && has(app, BACKGROUND_LOCATION)) add("OVERLAY_BGLOC", Severity.WARNING, "${app.label}: overlay + background location", "Can draw over other apps and track location in the background.")
        if (has(app, QUERY_ALL_PACKAGES) && !app.isSystem) add("QUERY_ALL", Severity.INFO, "${app.label}: enumerates all apps", "Holds QUERY_ALL_PACKAGES — can see every installed app.")
        return out
    }

    /** Combos across the whole install set. */
    fun permissionFindings(apps: List<AppPermissions>): List<Finding> = apps.flatMap { permissionCombos(it) }

    /**
     * Newly-granted risky permissions since the [previous] snapshot (pkg → granted set). This is the
     * honest, available substitute for "grants outside normal workflow": we can't hook the grant event,
     * but we can diff snapshots between scans. New installs (absent from [previous]) are NOT flagged here.
     */
    fun newlyGrantedRisky(previous: Map<String, Set<String>>, current: List<AppPermissions>): List<Finding> {
        val out = ArrayList<Finding>()
        for (app in current) {
            val before = previous[app.packageName] ?: continue // unknown app = fresh install, not a "change"
            val added = app.granted - before
            for (perm in added) {
                if (perm in RISKY_PERMISSIONS) {
                    out += Finding(
                        "PERMISSIONS:NEWGRANT:${app.packageName}:${short(perm)}",
                        Category.PERMISSIONS, Severity.WARNING,
                        "${app.label}: newly granted ${short(perm)}",
                        "This risky permission was granted since the last scan — confirm you did this.",
                        app.packageName,
                    )
                }
            }
        }
        return out
    }

    /** User-added CAs are the main TLS-interception vector; system CAs are reported only as a count. */
    fun caFindings(cas: List<TrustedCa>): List<Finding> {
        val user = cas.filter { !it.isSystem }
        return user.map {
            Finding(
                "CERTIFICATES:USERCA:${it.subject}", Category.CERTIFICATES, Severity.WARNING,
                "User-added CA in trust store", "\"${it.subject}\" can intercept TLS for apps that trust user CAs. Remove it if unexpected.",
                it.subject,
            )
        }
    }

    /** A system HTTP proxy and/or user CAs are classic MITM signals. */
    fun proxyFindings(httpProxyHost: String?, userCaCount: Int): List<Finding> {
        val out = ArrayList<Finding>()
        if (!httpProxyHost.isNullOrBlank()) {
            out += Finding(
                "NETWORK:PROXY:$httpProxyHost", Category.NETWORK, Severity.WARNING,
                "System HTTP proxy configured", "Traffic may route through \"$httpProxyHost\". Expected only if you set it.",
                httpProxyHost,
            )
        }
        return out
    }

    fun encryptionFinding(status: EncryptionStatus): Finding = when (status) {
        EncryptionStatus.ENCRYPTED -> Finding("ENCRYPTION:STATUS", Category.ENCRYPTION, Severity.INFO, "Device storage encrypted", "Storage encryption is active.")
        EncryptionStatus.INACTIVE -> Finding("ENCRYPTION:STATUS", Category.ENCRYPTION, Severity.CRITICAL, "Device storage NOT encrypted", "Encryption is supported but inactive — your data is at risk if the device is lost.")
        EncryptionStatus.ACTIVATING -> Finding("ENCRYPTION:STATUS", Category.ENCRYPTION, Severity.INFO, "Encryption activating", "Storage encryption is being enabled.")
        EncryptionStatus.UNSUPPORTED -> Finding("ENCRYPTION:STATUS", Category.ENCRYPTION, Severity.WARNING, "Encryption unsupported", "This device reports no storage-encryption support.")
        EncryptionStatus.UNKNOWN -> Finding("ENCRYPTION:STATUS", Category.ENCRYPTION, Severity.INFO, "Encryption status unknown", "Couldn't read the storage-encryption status.")
    }

    /**
     * Data-exfiltration heuristic: a user app that both holds a risky capability (camera/mic/etc.) AND has
     * transmitted more than [txThresholdBytes] this window is worth a defender's eye. [riskyApps] is the
     * set of package names flagged by [permissionFindings] / [RISKY_PERMISSIONS].
     */
    fun dataExfilFindings(usage: List<AppDataUsage>, riskyApps: Set<String>, txThresholdBytes: Long): List<Finding> =
        usage.filter { it.packageName in riskyApps && it.txBytes >= txThresholdBytes }
            .sortedByDescending { it.txBytes }
            .map {
                Finding(
                    "DATA:EXFIL:${it.packageName}", Category.DATA, Severity.WARNING,
                    "${it.label}: high upload + risky permissions",
                    "Uploaded ${mb(it.txBytes)} this window and holds camera/mic/location-class access. Confirm it's expected.",
                    it.packageName,
                )
            }

    /** Simple per-app upload outlier (no permission correlation) — INFO-level, for the data view. */
    fun dataOutliers(usage: List<AppDataUsage>, txThresholdBytes: Long): List<Finding> =
        usage.filter { it.txBytes >= txThresholdBytes }
            .sortedByDescending { it.txBytes }
            .map {
                Finding(
                    "DATA:UPLOAD:${it.packageName}", Category.DATA, Severity.INFO,
                    "${it.label}: ${mb(it.txBytes)} uploaded", "High outbound data this window.", it.packageName,
                )
            }

    /** Drop whitelisted ids, dedupe by id (keep highest severity), sort critical→info then by category. */
    fun finalize(findings: List<Finding>, whitelist: Set<String>): List<Finding> =
        findings.asSequence()
            .filter { it.id !in whitelist }
            .groupBy { it.id }
            .map { (_, group) -> group.maxByOrNull { it.severity.ordinal }!! }
            .sortedWith(compareByDescending<Finding> { it.severity.ordinal }.thenBy { it.category.ordinal })
            .toList()

    data class Summary(val critical: Int, val warning: Int, val info: Int) {
        val total get() = critical + warning + info
    }

    fun summarize(findings: List<Finding>): Summary = Summary(
        critical = findings.count { it.severity == Severity.CRITICAL },
        warning = findings.count { it.severity == Severity.WARNING },
        info = findings.count { it.severity == Severity.INFO },
    )

    private fun mb(bytes: Long): String = if (bytes < 1_048_576L) "${bytes / 1024}KB" else "%.1fMB".format(bytes / 1_048_576.0)
}
