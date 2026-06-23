package dev.mascwa.pulse.data.security

import android.app.admin.DevicePolicyManager
import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Build
import dev.mascwa.pulse.core.telemetry.SecurityAudit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.KeyStore
import java.security.cert.X509Certificate

/**
 * Collects the read-only facts the on-device security auditor can legitimately reach **without root or a
 * system signature**, and feeds them through the pure [SecurityAudit] core into findings.
 *
 * Reachable here (the honest, available subset): installed apps + granted permissions
 * ([PackageManager]), the device trust store ([KeyStore] `AndroidCAStore`), storage-encryption status
 * ([DevicePolicyManager]), per-app data usage ([NetworkStatsManager], needs Usage Access), and the system
 * HTTP proxy. NOT reachable for a sideloaded app, and therefore deliberately absent: other apps'
 * processes/services/sockets, per-app battery mAh, and other apps' private files. Every collector is
 * guarded so a denied permission degrades to "skipped", never a crash.
 */
class SecurityAuditor(context: Context) {

    private val appContext = context.applicationContext

    data class AuditResult(
        val findings: List<SecurityAudit.Finding>,
        /** pkg → granted permissions, persisted as the baseline for the next scan's "newly granted" diff. */
        val permissionSnapshot: Map<String, Set<String>>,
        val timestamp: Long,
        /** Whether per-app data usage was readable (Usage Access granted) — drives a UI hint. */
        val usageAvailable: Boolean,
        val appsScanned: Int,
        val userCaCount: Int,
    )

    private data class AppInfoLite(
        val packageName: String,
        val label: String,
        val isSystem: Boolean,
        val uid: Int,
        val granted: Set<String>,
    )

    suspend fun runAudit(
        previousSnapshot: Map<String, Set<String>>,
    ): AuditResult = withContext(Dispatchers.IO) {
        val apps = installedApps()
        val appPerms = apps.map { SecurityAudit.AppPermissions(it.packageName, it.label, it.isSystem, it.granted) }
        val cas = trustedCas()
        val enc = encryptionStatus()
        val proxy = httpProxyHost()
        val usage = dataUsage(apps)

        val riskyApps = appPerms
            .filter { !it.isSystem && SecurityAudit.INTERNET in it.granted &&
                it.granted.any { p -> p in SecurityAudit.RISKY_PERMISSIONS } }
            .map { it.packageName }
            .toSet()

        val findings = buildList {
            addAll(SecurityAudit.permissionFindings(appPerms))
            addAll(SecurityAudit.newlyGrantedRisky(previousSnapshot, appPerms))
            addAll(SecurityAudit.caFindings(cas))
            addAll(SecurityAudit.proxyFindings(proxy, cas.count { !it.isSystem }))
            add(SecurityAudit.encryptionFinding(enc))
            addAll(SecurityAudit.dataExfilFindings(usage, riskyApps, EXFIL_TX_THRESHOLD))
        }

        // Dedupe + sort here; the whitelist is applied live in the store's view so toggling it is instant.
        AuditResult(
            findings = SecurityAudit.finalize(findings, emptySet()),
            permissionSnapshot = appPerms.associate { it.packageName to it.granted },
            timestamp = System.currentTimeMillis(),
            usageAvailable = usage.isNotEmpty(),
            appsScanned = apps.size,
            userCaCount = cas.count { !it.isSystem },
        )
    }

    @Suppress("DEPRECATION")
    private fun installedApps(): List<AppInfoLite> {
        val pm = appContext.packageManager
        val pkgs = runCatching {
            if (Build.VERSION.SDK_INT >= 33)
                pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()))
            else pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)
        }.getOrDefault(emptyList())
        return pkgs.mapNotNull { pi ->
            val ai = pi.applicationInfo ?: return@mapNotNull null
            val granted = HashSet<String>()
            val req = pi.requestedPermissions
            val flags = pi.requestedPermissionsFlags
            if (req != null && flags != null) {
                for (i in req.indices) {
                    if (i < flags.size && (flags[i] and PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0) granted += req[i]
                }
            }
            AppInfoLite(
                packageName = pi.packageName,
                label = runCatching { pm.getApplicationLabel(ai).toString() }.getOrDefault(pi.packageName),
                isSystem = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                uid = ai.uid,
                granted = granted,
            )
        }
    }

    /** Enumerate the device trust store; aliases are prefixed `system:` / `user:`. */
    private fun trustedCas(): List<SecurityAudit.TrustedCa> = runCatching {
        val ks = KeyStore.getInstance("AndroidCAStore").apply { load(null) }
        ks.aliases().toList().mapNotNull { alias ->
            val cert = ks.getCertificate(alias) as? X509Certificate ?: return@mapNotNull null
            SecurityAudit.TrustedCa(
                subject = shortSubject(cert.subjectX500Principal.name),
                isSystem = alias.startsWith("system:"),
            )
        }
    }.getOrDefault(emptyList())

    private fun encryptionStatus(): SecurityAudit.EncryptionStatus {
        val dpm = appContext.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            ?: return SecurityAudit.EncryptionStatus.UNKNOWN
        return when (runCatching { dpm.storageEncryptionStatus }.getOrNull()) {
            DevicePolicyManager.ENCRYPTION_STATUS_ACTIVE,
            DevicePolicyManager.ENCRYPTION_STATUS_ACTIVE_DEFAULT_KEY,
            DevicePolicyManager.ENCRYPTION_STATUS_ACTIVE_PER_USER -> SecurityAudit.EncryptionStatus.ENCRYPTED
            DevicePolicyManager.ENCRYPTION_STATUS_INACTIVE -> SecurityAudit.EncryptionStatus.INACTIVE
            DevicePolicyManager.ENCRYPTION_STATUS_ACTIVATING -> SecurityAudit.EncryptionStatus.ACTIVATING
            DevicePolicyManager.ENCRYPTION_STATUS_UNSUPPORTED -> SecurityAudit.EncryptionStatus.UNSUPPORTED
            else -> SecurityAudit.EncryptionStatus.UNKNOWN
        }
    }

    private fun httpProxyHost(): String? {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null
        val proxy = runCatching { cm.defaultProxy }.getOrNull() ?: return null
        val host = proxy.host?.takeIf { it.isNotBlank() } ?: return null
        return "$host:${proxy.port}"
    }

    /** Per-app data usage over the window. Needs Usage Access; any failure → empty (feature degrades). */
    @Suppress("DEPRECATION")
    private fun dataUsage(apps: List<AppInfoLite>): List<SecurityAudit.AppDataUsage> {
        val nsm = appContext.getSystemService(Context.NETWORK_STATS_SERVICE) as? NetworkStatsManager ?: return emptyList()
        val end = System.currentTimeMillis()
        val start = end - WINDOW_MS
        val perUid = HashMap<Int, LongArray>()
        for (type in intArrayOf(ConnectivityManager.TYPE_WIFI, ConnectivityManager.TYPE_MOBILE)) {
            runCatching {
                val stats = nsm.querySummary(type, null, start, end)
                val bucket = NetworkStats.Bucket()
                while (stats.hasNextBucket()) {
                    stats.getNextBucket(bucket)
                    val arr = perUid.getOrPut(bucket.uid) { LongArray(2) }
                    arr[0] += bucket.rxBytes
                    arr[1] += bucket.txBytes
                }
                stats.close()
            }
        }
        if (perUid.isEmpty()) return emptyList()
        return apps.mapNotNull { app ->
            val u = perUid[app.uid] ?: return@mapNotNull null
            SecurityAudit.AppDataUsage(app.packageName, app.label, u[0], u[1])
        }
    }

    private fun shortSubject(dn: String): String {
        // Prefer the CN, else the O, else the raw DN — keeps CA findings readable.
        val parts = dn.split(',').map { it.trim() }
        return parts.firstOrNull { it.startsWith("CN=") }?.removePrefix("CN=")
            ?: parts.firstOrNull { it.startsWith("O=") }?.removePrefix("O=")
            ?: dn
    }

    private companion object {
        val WINDOW_MS = 24L * 60 * 60 * 1000          // per-app data over the last 24h
        const val EXFIL_TX_THRESHOLD = 20L * 1_048_576 // 20 MB upload by a risky app → flag
    }
}
