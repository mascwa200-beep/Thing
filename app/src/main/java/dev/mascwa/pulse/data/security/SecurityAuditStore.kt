package dev.mascwa.pulse.data.security

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.mascwa.pulse.core.telemetry.SecurityAudit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val Context.securityDataStore: DataStore<Preferences> by preferencesDataStore(name = "pulse_security_audit")

/**
 * Local-only persistence for the security auditor: the latest findings, the permission snapshot used as
 * the baseline for the next scan's "newly granted" diff, the user's whitelist of acknowledged findings,
 * and scan metadata. In-memory + Mutex + debounced flush, mirroring the other stores. Never leaves the
 * device. The whitelist is applied **live** in [auditFlow] so toggling it updates the dashboard instantly.
 */
class SecurityAuditStore(
    private val context: Context,
    private val json: Json,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    @Serializable
    private data class StoredFinding(
        val id: String,
        val category: String,
        val severity: String,
        val title: String,
        val detail: String,
        val subject: String = "",
    )

    @Serializable
    private data class Stored(
        val findings: List<StoredFinding> = emptyList(),
        val snapshot: Map<String, List<String>> = emptyMap(),
        val whitelist: List<String> = emptyList(),
        val lastScanMs: Long = 0,
        val usageAvailable: Boolean = false,
        val appsScanned: Int = 0,
        val userCaCount: Int = 0,
    )

    /** The dashboard's view: findings (whitelist-filtered), counts, and scan metadata. */
    data class AuditView(
        val findings: List<SecurityAudit.Finding>,
        val summary: SecurityAudit.Summary,
        val lastScanMs: Long,
        val usageAvailable: Boolean,
        val appsScanned: Int,
        val userCaCount: Int,
        val whitelistSize: Int,
    )

    private val prefsKey = stringPreferencesKey("audit_json")
    private val mutex = Mutex()
    private var stored: Stored? = null
    private var flushJob: Job? = null

    private val _view = MutableStateFlow(EMPTY_VIEW)
    val auditFlow: StateFlow<AuditView> = _view.asStateFlow()

    private suspend fun ensureLoaded(): Stored = mutex.withLock {
        stored ?: run {
            val raw = context.securityDataStore.data.first()[prefsKey]
            val loaded = raw
                ?.let { runCatching { json.decodeFromString(Stored.serializer(), it) }.getOrNull() }
                ?: Stored()
            stored = loaded
            _view.value = loaded.toView()
            loaded
        }
    }

    suspend fun load() { ensureLoaded() }

    /** The permission baseline for the next scan's diff. */
    suspend fun snapshot(): Map<String, Set<String>> =
        ensureLoaded().snapshot.mapValues { it.value.toSet() }

    /** Persist a fresh scan result (raw findings; the whitelist is kept and applied in the view). */
    suspend fun saveResult(result: SecurityAuditor.AuditResult) {
        ensureLoaded()
        mutex.withLock {
            val next = (stored ?: Stored()).copy(
                findings = result.findings.map { it.toStored() },
                snapshot = result.permissionSnapshot.mapValues { it.value.toList() },
                lastScanMs = result.timestamp,
                usageAvailable = result.usageAvailable,
                appsScanned = result.appsScanned,
                userCaCount = result.userCaCount,
            )
            stored = next
            _view.value = next.toView()
        }
        scheduleFlush()
    }

    suspend fun addToWhitelist(id: String) = mutateWhitelist { it + id }
    suspend fun removeFromWhitelist(id: String) = mutateWhitelist { it - id }

    private suspend fun mutateWhitelist(transform: (Set<String>) -> Set<String>) {
        ensureLoaded()
        mutex.withLock {
            val cur = stored ?: Stored()
            val next = cur.copy(whitelist = transform(cur.whitelist.toSet()).toList())
            stored = next
            _view.value = next.toView()
        }
        scheduleFlush()
    }

    suspend fun clear() {
        flushJob?.cancel()
        mutex.withLock { stored = Stored(); _view.value = EMPTY_VIEW }
        runCatching { context.securityDataStore.edit { it.remove(prefsKey) } }
    }

    /**
     * The outcome of the most recent write, so an explicit [flushNow] can report a failure it would
     * otherwise swallow.
     *
     * ⚠️ **Both callers of [flushNow] already wrap it in a reporter that could never fire.** Every
     * store of this shape catches its own DataStore edit and discards the `Result`, so the "the
     * store could not be written to disk; anything recorded since is lost" report in `MainActivity`
     * and `NutritionContainer` was structurally unreachable — a claim in a KDoc that nothing could
     * make true. The debounced background flush still swallows, deliberately: an exception thrown
     * there escapes into a launched coroutine and takes the process with it.
     */
    @Volatile
    private var lastWrite: Result<*>? = null

    suspend fun flushNow() {
        flushJob?.cancel()
        // ⚠️ Cleared first: [flush] returns early when nothing is owed, and a stale failure
        // from an earlier write would then be reported against a write no longer outstanding.
        lastWrite = null
        flush()
        lastWrite?.getOrThrow()
    }

    private fun scheduleFlush() {
        if (flushJob?.isActive == true) return
        flushJob = scope.launch { delay(FLUSH_DELAY_MS); flush() }
    }

    private suspend fun flush() {
        val snapshot = mutex.withLock { stored } ?: return
        lastWrite = runCatching {
            context.securityDataStore.edit { it[prefsKey] = json.encodeToString(Stored.serializer(), snapshot) }
        }
    }

    // --- mapping ---

    private fun Stored.toView(): AuditView {
        val wl = whitelist.toSet()
        val visible = findings.mapNotNull { it.toDomain() }.filter { it.id !in wl }
        return AuditView(
            findings = visible,
            summary = SecurityAudit.summarize(visible),
            lastScanMs = lastScanMs,
            usageAvailable = usageAvailable,
            appsScanned = appsScanned,
            userCaCount = userCaCount,
            whitelistSize = wl.size,
        )
    }

    private fun SecurityAudit.Finding.toStored() =
        StoredFinding(id, category.name, severity.name, title, detail, subject)

    private fun StoredFinding.toDomain(): SecurityAudit.Finding? = runCatching {
        SecurityAudit.Finding(
            id = id,
            category = SecurityAudit.Category.valueOf(category),
            severity = SecurityAudit.Severity.valueOf(severity),
            title = title,
            detail = detail,
            subject = subject,
        )
    }.getOrNull()

    private companion object {
        const val FLUSH_DELAY_MS = 2_000L
        val EMPTY_VIEW = AuditView(emptyList(), SecurityAudit.Summary(0, 0, 0), 0, false, 0, 0, 0)
    }
}
