package dev.mascwa.pulse.feature.launcher

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * What the home screen remembers: the apps pinned to its dock, in the order they were pinned, and
 * the widgets placed on it — as Android's integer ids, never anything a widget shows.
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

    // ── the one-time default ────────────────────────────────────────────────────────────────────

    /** Whether LCARS has already been made the Home by default once — see [HomeDefaultPolicy]. */
    fun defaultApplied(): Boolean = prefs.getBoolean(KEY_DEFAULT_APPLIED, false)

    /**
     * Written with commit() for the same reason as the guard. If the record were lost, a later start
     * would apply the default again over a Home somebody had deliberately switched off.
     */
    fun recordDefaultApplied() {
        prefs.edit().putBoolean(KEY_DEFAULT_APPLIED, true).commit()
    }

    // ── widgets ─────────────────────────────────────────────────────────────────────────────────

    /** The widget ids on the console, in the order they were added — see [HomeWidgets]. */
    suspend fun widgets(): List<Int> = withContext(Dispatchers.IO) { readWidgets() }

    /**
     * Add [id] and return the list as it now stands, or null when the console is full and it was
     * refused. commit() like the pins: a widget that had not landed when the process was reclaimed
     * would be an orphan the next reconcile deletes.
     */
    suspend fun addWidget(id: Int): List<Int>? = withContext(Dispatchers.IO) {
        val next = HomeWidgets.add(readWidgets(), id) ?: return@withContext null
        writeWidgets(next)
        next
    }

    suspend fun removeWidget(id: Int): List<Int> = withContext(Dispatchers.IO) {
        HomeWidgets.remove(readWidgets(), id).also(::writeWidgets)
    }

    /** Replace the list outright, after a reconcile dropped widgets the host no longer holds. */
    suspend fun saveWidgets(ids: List<Int>) = withContext(Dispatchers.IO) { writeWidgets(ids) }

    /**
     * The widget id being added right now, between allocating it and it landing on the console —
     * the one [HomeWidgets.reconcile] must not delete. On disk, not in memory, because Android may
     * reclaim the home screen while its bind prompt or the widget's configuration screen is up.
     */
    suspend fun pendingWidget(): Int? = withContext(Dispatchers.IO) {
        prefs.getInt(KEY_WIDGET_PENDING, NO_WIDGET).takeIf { it != NO_WIDGET }
    }

    suspend fun setPendingWidget(id: Int?) = withContext(Dispatchers.IO) {
        prefs.edit().putInt(KEY_WIDGET_PENDING, id ?: NO_WIDGET).commit()
    }

    private fun readWidgets(): List<Int> =
        prefs.getString(KEY_WIDGETS, null).orEmpty().split(',').mapNotNull { it.trim().toIntOrNull() }

    private fun writeWidgets(ids: List<Int>) {
        prefs.edit().putString(KEY_WIDGETS, ids.joinToString(",")).commit()
    }

    private companion object {
        const val FILE = "lcars_home"
        const val KEY_PINS = "pins"
        const val KEY_WIDGETS = "widgets"
        const val KEY_WIDGET_PENDING = "widget_pending"

        /** `AppWidgetManager.INVALID_APPWIDGET_ID`, which Android never hands out as a real id. */
        const val NO_WIDGET = 0
        const val KEY_PENDING = "guard_pending"
        const val KEY_FAILURES = "guard_failures"
        const val KEY_STOOD_DOWN = "guard_stood_down"
        const val KEY_DEFAULT_APPLIED = "default_applied"
        const val NONE = -1L

        /** A key is a component name, a `#` and a number, so it can never contain a line break. */
        const val SEPARATOR = "\n"
    }
}
