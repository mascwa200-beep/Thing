package dev.mascwa.pulse.feature.security

import android.app.AppOpsManager
import android.content.Context
import android.net.Uri
import android.os.Process
import android.text.format.DateUtils
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.mascwa.pulse.core.telemetry.SecurityAudit
import dev.mascwa.pulse.data.security.SecurityAuditStore
import dev.mascwa.pulse.data.security.SecurityAuditor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Backs the security-auditor dashboard. Runs scans on demand (the worker runs them periodically), exposes
 * the local findings view, drives the whitelist, and exports a timestamped audit log. All on-device.
 */
class SecurityAuditViewModel(
    private val auditor: SecurityAuditor,
    private val store: SecurityAuditStore,
    private val appContext: Context,
) : ViewModel() {

    val view: StateFlow<SecurityAuditStore.AuditView> = store.auditFlow

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning

    private val _status = MutableStateFlow("")
    val status: StateFlow<String> = _status

    init {
        viewModelScope.launch { runCatching { store.load() } }
    }

    /** Run a fresh audit and persist it. No-op while one is already running. */
    fun scan() {
        if (_scanning.value) return
        _scanning.value = true
        _status.value = "Scanning…"
        viewModelScope.launch {
            runCatching {
                val result = auditor.runAudit(store.snapshot())
                store.saveResult(result)
                result
            }.onSuccess {
                _status.value = "Scanned ${it.appsScanned} apps" +
                    (if (!it.usageAvailable) " · grant Usage Access for data-drain checks" else "")
            }.onFailure { _status.value = "Scan failed: ${it.message}" }
            _scanning.value = false
        }
    }

    fun whitelist(id: String) = viewModelScope.launch { runCatching { store.addToWhitelist(id) } }
    fun clearWhitelist(id: String) = viewModelScope.launch { runCatching { store.removeFromWhitelist(id) } }
    fun clearAll() = viewModelScope.launch { runCatching { store.clear() } }

    /** Whether the app currently holds Usage Access (gates per-app data usage). */
    fun hasUsageAccess(): Boolean = runCatching {
        val appOps = appContext.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), appContext.packageName,
        ) == AppOpsManager.MODE_ALLOWED
    }.getOrDefault(false)

    /** Export the current findings as a timestamped plain-text log to [uri]. */
    fun export(context: Context, uri: Uri) {
        viewModelScope.launch {
            _status.value = runCatching {
                val text = buildExport(view.value)
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                        ?: error("couldn't open the file")
                }
                "Audit log exported."
            }.getOrElse { "Export failed: ${it.message}" }
        }
    }

    private fun buildExport(v: SecurityAuditStore.AuditView): String {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.US).format(Date())
        val last = if (v.lastScanMs > 0) DateUtils.getRelativeTimeSpanString(v.lastScanMs).toString() else "never"
        val sb = StringBuilder()
        sb.appendLine("LCARS SECURITY AUDIT — exported $stamp")
        sb.appendLine("Last scan: $last · apps: ${v.appsScanned} · user CAs: ${v.userCaCount} · " +
            "usage access: ${if (v.usageAvailable) "yes" else "no"}")
        sb.appendLine("Findings: ${v.summary.critical} critical · ${v.summary.warning} warning · ${v.summary.info} info")
        sb.appendLine("(Local-only audit. Note: process/socket inspection, per-app battery, and other apps' " +
            "files require root/system and are not included.)")
        sb.appendLine()
        for (sev in listOf(SecurityAudit.Severity.CRITICAL, SecurityAudit.Severity.WARNING, SecurityAudit.Severity.INFO)) {
            val group = v.findings.filter { it.severity == sev }
            if (group.isEmpty()) continue
            sb.appendLine("== ${sev.name} (${group.size}) ==")
            for (f in group) {
                sb.appendLine("- [${f.category.name}] ${f.title}")
                if (f.detail.isNotBlank()) sb.appendLine("    ${f.detail}")
            }
            sb.appendLine()
        }
        return sb.toString()
    }
}
