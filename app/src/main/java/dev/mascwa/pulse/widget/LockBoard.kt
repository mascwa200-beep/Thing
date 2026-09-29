package dev.mascwa.pulse.widget

import android.content.Context
import dev.mascwa.pulse.feature.lockboard.LockBoardPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The board the lock screen shows: the last one the widget drew, kept in this process.
 *
 * ⚠️ **It is the widget's own pass, not a second one.** Every widget render publishes here, so the
 * lock screen and the widget read the same feeds through the same budgets and cannot disagree about
 * what is going on. When no render is recent — no widget is placed, or the phone has been in a
 * pocket for an hour — the lock screen asks for one with [refreshIfStale] and shows what it has
 * while it waits, then redraws in place when the fresh board lands.
 *
 * ⚠️ **In memory only, deliberately.** The board carries bitmaps and PendingIntents, neither of which
 * belongs on disk, and the process that shows the lock-screen board is the same one a live service
 * keeps running. A cold process simply has nothing yet and loads.
 */
internal object LockBoard {

    data class Snapshot(val board: WidgetBoard.Board, val atMs: Long)

    private val _latest = MutableStateFlow<Snapshot?>(null)

    /** The newest board, or null before anything has been drawn in this process. */
    val latest: StateFlow<Snapshot?> = _latest

    private val loading = AtomicBoolean(false)

    fun publish(board: WidgetBoard.Board, nowMs: Long) {
        _latest.value = Snapshot(board, nowMs)
    }

    /**
     * Load a fresh board when the one held is missing or old, and at most one at a time.
     *
     * ⚠️ Single-flight on purpose: the lock screen asks every time the screen goes off and every time
     * it comes on, and a phone woken three times in a minute must not start three full passes over
     * every feed. [scope] should outlive the caller — the load finishes and publishes even if the
     * screen that asked has already gone.
     */
    fun refreshIfStale(context: Context, scope: CoroutineScope, nowMs: Long) {
        if (!LockBoardPolicy.isStale(latest.value?.atMs, nowMs)) return
        if (!loading.compareAndSet(false, true)) return
        val app = context.applicationContext
        scope.launch {
            try {
                LockWidgetProvider().loadBoard(app)?.let { publish(it, System.currentTimeMillis()) }
            } finally {
                loading.set(false)
            }
        }
    }
}
