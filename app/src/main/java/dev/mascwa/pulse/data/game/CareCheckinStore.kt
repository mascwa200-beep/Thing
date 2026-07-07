package dev.mascwa.pulse.data.game

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.mascwa.pulse.core.telemetry.CareCheckin
import dev.mascwa.pulse.core.telemetry.CareContext
import dev.mascwa.pulse.core.telemetry.CareSchedule
import dev.mascwa.pulse.core.telemetry.NeedKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Calendar

private val Context.careCheckinDataStore: DataStore<Preferences> by preferencesDataStore(name = "pulse_carecheckin")

/**
 * Tracks the state the smart self-care scheduler ([CareSchedule]) needs — when each check-in was last DONE
 * and last ASKED, and when you last ate (which drives the post-meal brush) — and picks the next check-in to
 * raise. Completions come from two places: you answering a full-screen prompt ([complete]) AND the camera/mic
 * catching you do it ([start] observes the auto-care `sensed` flow), so the schedule stays honest either way.
 * Completing a check-in also tops up the matching game need via [SpecialGameStore].
 */
class CareCheckinStore(
    private val context: Context,
    private val json: Json,
    private val game: SpecialGameStore,
) {
    @Serializable
    private data class Stored(
        val confirmed: Map<String, Long> = emptyMap(),
        val asked: Map<String, Long> = emptyMap(),
        val lastMealMs: Long = 0L,
    )

    private val key = stringPreferencesKey("care_checkin")
    private val mutex = Mutex()
    private val confirmed = mutableMapOf<CareCheckin, Long>()
    private val asked = mutableMapOf<CareCheckin, Long>()
    private var lastMealMs = 0L
    private var loaded = false

    /** The top check-in to raise now (priority + rhythm + context), or null when nothing's due. */
    suspend fun schedule(): CareCheckin? = mutex.withLock {
        ensureLoaded()
        val now = System.currentTimeMillis()
        val needs = runCatching { game.lifeSnapshot() }.getOrNull() ?: return@withLock null
        val ctx = CareContext(now, hourOfDay(now), confirmed.toMap(), lastMealMs, needs)
        CareSchedule.next(ctx, asked.toMap())
    }

    /** Stamp a check-in as ASKED (a full-screen prompt fired) so it isn't re-raised until the ask-gap. */
    suspend fun markAsked(c: CareCheckin) = mutex.withLock {
        ensureLoaded()
        asked[c] = System.currentTimeMillis()
        flush()
    }

    /** Record a check-in COMPLETED (you answered DONE): stamp it, tend the matching need, note a meal. */
    suspend fun complete(c: CareCheckin) = mutex.withLock {
        ensureLoaded()
        stampDone(c, System.currentTimeMillis())
        restore(c)
        flush()
    }

    /** Observe the auto-care sensed stream — when the camera/mic catch you tending a need, treat it as a
     *  completed check-in so the scheduler doesn't then nag you to do what you just did. */
    fun start(scope: CoroutineScope, sensed: SharedFlow<NeedKind>) {
        scope.launch {
            sensed.collect { need ->
                val c = CareCheckin.entries.firstOrNull { it.need == need } ?: return@collect
                mutex.withLock {
                    ensureLoaded()
                    stampDone(c, System.currentTimeMillis())
                    flush()
                }
            }
        }
    }

    private fun stampDone(c: CareCheckin, now: Long) {
        confirmed[c] = now
        if (c == CareCheckin.EAT) lastMealMs = now
    }

    private fun restore(c: CareCheckin) = when (c.need) {
        NeedKind.HYDRATION -> game.drink()
        NeedKind.NOURISHMENT -> game.eat()
        NeedKind.ENERGY -> game.rest()
        NeedKind.HYGIENE -> game.wash()
        NeedKind.BRUSHING -> game.brushTeeth()
        NeedKind.FLOSSING -> game.floss()
    }

    private fun hourOfDay(now: Long): Int =
        Calendar.getInstance().apply { timeInMillis = now }.get(Calendar.HOUR_OF_DAY)

    private suspend fun ensureLoaded() {
        if (loaded) return
        val stored = context.careCheckinDataStore.data.first()[key]
            ?.let { runCatching { json.decodeFromString(Stored.serializer(), it) }.getOrNull() }
        if (stored != null) {
            stored.confirmed.forEach { (k, v) -> runCatching { CareCheckin.valueOf(k) }.getOrNull()?.let { confirmed[it] = v } }
            stored.asked.forEach { (k, v) -> runCatching { CareCheckin.valueOf(k) }.getOrNull()?.let { asked[it] = v } }
            lastMealMs = stored.lastMealMs
        }
        loaded = true
    }

    private suspend fun flush() {
        val blob = json.encodeToString(
            Stored.serializer(),
            Stored(confirmed.mapKeys { it.key.name }, asked.mapKeys { it.key.name }, lastMealMs),
        )
        runCatching { context.careCheckinDataStore.edit { it[key] = blob } }
    }
}
