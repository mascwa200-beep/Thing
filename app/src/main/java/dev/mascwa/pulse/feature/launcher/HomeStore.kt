package dev.mascwa.pulse.feature.launcher

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * What the home screen remembers: the apps pinned to its dock, in the order they were pinned.
 *
 * A small preferences file of its own rather than a field on the app's settings, because a home
 * screen is read on every press of the Home button and the app's settings are a Keystore-decrypted
 * blob of some two hundred fields. Nothing here is a secret — it is a list of app names.
 *
 * ⚠️ Every read and write is on the IO dispatcher: the FIRST read of a preferences file parses it
 * on whatever thread asks, and the home screen's thread is the one the whole phone is waiting on.
 */
class HomeStore(context: Context) {

    private val prefs: SharedPreferences by lazy {
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    }

    /** The pinned app keys, oldest pin first. */
    suspend fun pins(): List<String> = withContext(Dispatchers.IO) {
        prefs.getString(KEY_PINS, null).orEmpty().split(SEPARATOR).filter { it.isNotBlank() }
    }

    /**
     * Pin [key] if it is not pinned, unpin it if it is, and return the dock as it now stands. Null
     * when the dock is full and the pin was refused — see [HomeDirectory.togglePin].
     */
    suspend fun togglePin(key: String): List<String>? = withContext(Dispatchers.IO) {
        val current = prefs.getString(KEY_PINS, null).orEmpty().split(SEPARATOR).filter { it.isNotBlank() }
        val next = HomeDirectory.togglePin(current, key) ?: return@withContext null
        // commit(), not apply(): the answer is drawn straight back, and a pin that had not landed
        // when the process was reclaimed would silently come undone.
        prefs.edit().putString(KEY_PINS, next.joinToString(SEPARATOR)).commit()
        next
    }

    // ── the crash-loop guard's record ───────────────────────────────────────────────────────────

    /**
     * The guard's record, read BLOCKING on purpose.
     *
     * ⚠️ The guard exists for a home screen that crashes as it starts, so its record has to be on
     * disk before whatever might crash does — a write handed to a background thread could simply not
     * have happened when the process died, and a crash loop would then never add up to anything.
     * The file is a few dozen bytes, so this costs one small read on the start it protects.
     */
    fun guardState(): HomeGuardPolicy.State = HomeGuardPolicy.State(
        pendingStartMs = prefs.getLong(KEY_PENDING, NONE).takeIf { it != NONE },
        failuresMs = prefs.getString(KEY_FAILURES, null).orEmpty().split(',').mapNotNull { it.toLongOrNull() },
    )

    /** Written with commit(), for the reason [guardState] gives: it must land before a crash does. */
    fun saveGuard(state: HomeGuardPolicy.State) {
        prefs.edit()
            .putLong(KEY_PENDING, state.pendingStartMs ?: NONE)
            .putString(KEY_FAILURES, state.failuresMs.joinToString(","))
            .commit()
    }

    /** When the home screen last stood itself down, for Settings to say so. Null if it never has. */
    fun standDownAtMs(): Long? = prefs.getLong(KEY_STOOD_DOWN, NONE).takeIf { it != NONE }

    fun recordStandDown(atMs: Long) {
        prefs.edit().putLong(KEY_STOOD_DOWN, atMs).commit()
    }

    /** Switched back on by hand: the old record is not evidence against the new start. */
    fun clearGuard() {
        prefs.edit().remove(KEY_PENDING).remove(KEY_FAILURES).remove(KEY_STOOD_DOWN).commit()
    }

    private companion object {
        const val FILE = "lcars_home"
        const val KEY_PINS = "pins"
        const val KEY_PENDING = "guard_pending"
        const val KEY_FAILURES = "guard_failures"
        const val KEY_STOOD_DOWN = "guard_stood_down"
        const val NONE = -1L

        /** A key is a component name, a `#` and a number, so it can never contain a line break. */
        const val SEPARATOR = "\n"
    }
}
