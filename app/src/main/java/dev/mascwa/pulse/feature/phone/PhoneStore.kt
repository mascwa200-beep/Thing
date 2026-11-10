package dev.mascwa.pulse.feature.phone

import android.content.Context
import dev.mascwa.pulse.core.util.CrashLoopGuard

/**
 * What the phone takeover keeps between runs: the crash-loop guard's record for the call screen, the
 * dialler that held the role before LCARS (so turning the switch off can hand it back), and when the
 * guard last stood the screen down.
 *
 * ⚠️ **There is no "is LCARS the phone app" flag here, deliberately** — that is `RoleManager`'s to
 * answer, and a second copy could disagree with it (see [PhoneSwitch.isOn]). This store holds only
 * what Android does not already remember for us.
 *
 * Its own prefs file, and every write is `commit()`, not `apply()`: the guard's pending-start must
 * land on disk BEFORE a crash it is meant to catch, exactly as `HomeStore` argues.
 */
class PhoneStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("lcars_phone", Context.MODE_PRIVATE)

    // ── the call screen's crash-loop guard ──────────────────────────────────────────────────────

    fun guardState(): CrashLoopGuard.State = CrashLoopGuard.State(
        pendingStartMs = prefs.getLong(KEY_PENDING, NONE).takeIf { it != NONE },
        failuresMs = prefs.getString(KEY_FAILURES, "").orEmpty()
            .split(",").mapNotNull { it.trim().toLongOrNull() },
    )

    fun saveGuard(state: CrashLoopGuard.State) {
        prefs.edit()
            .putLong(KEY_PENDING, state.pendingStartMs ?: NONE)
            .putString(KEY_FAILURES, state.failuresMs.joinToString(","))
            .commit()
    }

    fun clearGuard() {
        prefs.edit().remove(KEY_PENDING).remove(KEY_FAILURES).remove(KEY_STOOD_DOWN).commit()
    }

    fun standDownAtMs(): Long? = prefs.getLong(KEY_STOOD_DOWN, NONE).takeIf { it != NONE }

    fun recordStandDown(atMs: Long) {
        prefs.edit().putLong(KEY_STOOD_DOWN, atMs).commit()
    }

    // ── the dialler to hand back to ─────────────────────────────────────────────────────────────

    /**
     * The package that held the dialler role before LCARS took it, or null if none was remembered.
     * Set once, when the switch goes on, and never overwritten with LCARS's own package.
     */
    fun previousDialer(): String? = prefs.getString(KEY_PREVIOUS, null)?.takeIf { it.isNotBlank() }

    fun rememberPreviousDialer(pkg: String?) {
        if (pkg.isNullOrBlank()) return
        prefs.edit().putString(KEY_PREVIOUS, pkg).commit()
    }

    private companion object {
        const val NONE = Long.MIN_VALUE
        const val KEY_PENDING = "guard_pending"
        const val KEY_FAILURES = "guard_failures"
        const val KEY_STOOD_DOWN = "guard_stood_down"
        const val KEY_PREVIOUS = "previous_dialer"
    }
}
