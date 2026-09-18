package dev.mascwa.pulse.data.health

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.mascwa.pulse.core.telemetry.BodyTrend
import dev.mascwa.pulse.core.telemetry.Expenditure
import dev.mascwa.pulse.core.telemetry.Habits
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
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val Context.bodyDataStore: DataStore<Preferences> by preferencesDataStore(name = "pulse_body")

/**
 * Every weigh-in and body measurement, on this device only.
 *
 * In-memory state (authoritative) + a debounced flush, exactly as the app's ProfileStore
 * does. The store keeps raw readings and nothing derived: the trend, the rate and their uncertainty all
 * come from [BodyTrend], which is pure and tested, so there is nothing here that can disagree with what
 * the screen shows.
 *
 * ⚠️ **Nothing is capped, and that is deliberate.** Every other store in this app evicts to bound its
 * blob, and every one of those holds observed or derived data that can be recomputed. This holds what a
 * person recorded about their own body over years, the whole point of it is the long run, and silently
 * dropping the oldest weigh-in would quietly shorten the very history the trend exists to read. A daily
 * weigh-in for a decade is about 150 kB of JSON, which is not a problem worth creating one for.
 */
class BodyStore(
    private val context: Context,
    private val json: Json,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {

    /** A body measurement other than weight. [cm] because every one of these is a circumference. */
    enum class MeasureKind(val label: String) {
        WAIST("Waist"),
        HIPS("Hips"),
        CHEST("Chest"),
        NECK("Neck"),
        THIGH("Thigh"),
        ARM("Arm"),
    }

    data class Measurement(val atMs: Long, val kind: MeasureKind, val cm: Double)

    @Serializable
    private data class StoredWeighin(val atMs: Long, val kg: Double, val note: String = "")

    @Serializable
    private data class StoredMeasurement(val atMs: Long, val kind: String, val cm: Double)

    /**
     * The pedometer's carried baseline.
     *
     * ⚠️ `TYPE_STEP_COUNTER` counts from the last BOOT, not from midnight, so today's figure is the
     * reading minus whatever it read when the day began — and that has to survive the process. All
     * fields are defaulted, so a save written before this existed still loads.
     */
    @Serializable
    private data class StoredSteps(
        val baseline: Long = 0,
        val dayStartMs: Long = 0,
        val today: Long = 0,
        val partial: Boolean = false,
    )

    /**
     * A day that finished, and what it counted.
     *
     * ⚠️ Only ever a COMPLETED day. Today's figure is still climbing, so putting it in the history
     * would make every mean over the history sag toward whatever time of day it happened to be read.
     */
    @Serializable
    private data class StoredStepDay(val dayStartMs: Long, val steps: Long)

    @Serializable
    private data class Stored(
        val weighins: List<StoredWeighin> = emptyList(),
        val measurements: List<StoredMeasurement> = emptyList(),
        val steps: StoredSteps? = null,
        /** Defaulted, so a save written before this existed still loads. */
        val stepHistory: List<StoredStepDay> = emptyList(),
    )

    /**
     * How many finished days of walking to keep.
     *
     * ⚠️ Comfortably past the 28-day expenditure window, so a shift comparison never runs out of
     * earlier days at the moment the window is full. Forty-five days of three numbers is a few
     * hundred bytes.
     */
    private val stepHistoryDays = 45

    private val prefsKey = stringPreferencesKey("body_json")
    private val mutex = Mutex()
    private var loaded: Stored? = null
    private var flushJob: Job? = null

    private val _weighins = MutableStateFlow<List<BodyTrend.Weighin>>(emptyList())

    /** Oldest first, which is the order [BodyTrend.estimate] and every chart wants. */
    val weighins: StateFlow<List<BodyTrend.Weighin>> = _weighins.asStateFlow()

    private val _steps = MutableStateFlow<Habits.Steps?>(null)

    /** Today's walking, or null until the pedometer has been read at least once. */
    val steps: StateFlow<Habits.Steps?> = _steps.asStateFlow()

    private val _stepHistory = MutableStateFlow<List<Expenditure.StepDay>>(emptyList())

    /**
     * Finished days of walking, oldest first.
     *
     * ⚠️ Feeds [Expenditure.stepShift], which asks whether the older half of the measurement window
     * describes a different way of living from the recent half. It is a proxy that INFORMS the
     * measurement and never replaces it — see the note on [Expenditure.StepDay] about why a step
     * count is admissible where a wearable's calorie figure is not.
     */
    val stepHistory: StateFlow<List<Expenditure.StepDay>> = _stepHistory.asStateFlow()

    private val _measurements = MutableStateFlow<List<Measurement>>(emptyList())
    val measurements: StateFlow<List<Measurement>> = _measurements.asStateFlow()

    private suspend fun ensureLoaded(): Stored = mutex.withLock { loadLocked() }

    /**
     * ⚠️ **`withContext`, not merely `suspend`.** A `suspend` function runs on whatever dispatcher its
     * caller is on, and `HealthViewModel.refresh()` — which runs on every foreground — calls into here
     * from `viewModelScope`, which is `Dispatchers.Main.immediate`. `DataStore` reads the preferences
     * FILE on its own IO scope, and a note in this repository once took that to mean the whole read was
     * off the frame thread; it is not. A flow emission is delivered in the COLLECTOR's context, so
     * `first()` resumes on the caller's dispatcher and the `kotlinx.serialization` decode below it — the
     * expensive half, over a record that grows for as long as somebody uses this — ran between two
     * frames. Invisible on a fast phone, a dropped frame every launch on a slow one.
     */
    private suspend fun loadLocked(): Stored = loaded ?: run {
        val s = withContext(Dispatchers.IO) {
            val raw = context.bodyDataStore.data.first()[prefsKey]
            raw?.let { runCatching { json.decodeFromString(Stored.serializer(), it) }.getOrNull() }
                ?: Stored()
        }
        loaded = s
        publish(s)
        s
    }

    private fun publish(s: Stored) {
        _steps.value = s.steps?.let { Habits.Steps(it.baseline, it.dayStartMs, it.today, it.partial) }
        _stepHistory.value = s.stepHistory.sortedBy { it.dayStartMs }
            .map { Expenditure.StepDay(it.dayStartMs, it.steps.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()) }
        _weighins.value = s.weighins.sortedBy { it.atMs }.map { BodyTrend.Weighin(it.atMs, it.kg) }
        _measurements.value = s.measurements.sortedBy { it.atMs }.mapNotNull { m ->
            val kind = runCatching { MeasureKind.valueOf(m.kind) }.getOrNull() ?: return@mapNotNull null
            Measurement(m.atMs, kind, m.cm)
        }
    }

    // ------------------------------------------------------------------------------------ writing

    /**
     * Fold a raw pedometer reading into today's count.
     *
     * ⚠️ The arithmetic is [Habits.steps] and is not repeated here. The carried baseline, the
     * new-day reset and the reboot case are all rules with a wrong answer that looks perfectly
     * plausible, which is exactly why they live in a tested core rather than in a store.
     *
     * ⚠️ A reading that changes nothing does NOT mark the blob dirty. This is called on every sensor
     * event, and a phone sitting still delivers them for hours — flagging each one would schedule a
     * disk write every couple of seconds for a value that has not moved.
     */
    fun onStepReading(raw: Long, todayStartMs: Long) {
        scope.launch {
            val changed = mutex.withLock {
                val s = loadLocked()
                val previous = _steps.value
                val next = Habits.steps(previous, raw, todayStartMs)
                if (next == previous) return@withLock false

                // ⚠️ **A day is banked when the NEXT one starts, and only then.** The completed
                // total is the value the outgoing day held at the moment it rolled over; reading
                // today's figure would bank a number that was still climbing.
                //
                // ⚠️ A PARTIAL day is never banked. `partial` means the count was truncated — the
                // phone restarted, so the baseline was lost and only part of the day was seen. That
                // is not a measurement of that day, and averaging it in would drag every mean down
                // and could read as a fall in activity that never happened.
                val banked = if (previous != null && next.dayStartMs != previous.dayStartMs && !previous.partial) {
                    (s.stepHistory + StoredStepDay(previous.dayStartMs, previous.today))
                        .distinctBy { it.dayStartMs }
                        .sortedBy { it.dayStartMs }
                        .takeLast(stepHistoryDays)
                } else {
                    s.stepHistory
                }

                loaded = s.copy(
                    steps = StoredSteps(next.baseline, next.dayStartMs, next.today, next.partial),
                    stepHistory = banked,
                )
                _steps.value = next
                _stepHistory.value = banked.map {
                    Expenditure.StepDay(it.dayStartMs, it.steps.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                }
                true
            }
            if (changed) scheduleFlush()
        }
    }

    /**
     * Record a weigh-in.
     *
     * ⚠️ A second reading on the same *day* replaces the first rather than adding to it, keyed on the
     * day the caller says it belongs to. Weighing twice in a morning is a correction, not two data
     * points, and treating it as two lets somebody double the weight of one morning in the trend by
     * fiddling. Two readings genuinely hours apart on different days are both kept.
     */
    fun record(atMs: Long, kg: Double, dayStartMs: Long, note: String = "") {
        if (!kg.isFinite() || kg <= 0.0) return
        scope.launch {
            mutex.withLock {
                val s = loadLocked()
                // ⚠️ Where the day genuinely ends, from a calendar — never `dayStartMs + DAY_MS`. This
                // window DELETES, and a fixed day reaches an hour into the next one after the clocks
                // go forward, so correcting one morning would take the next morning's reading with it.
                val dayEnd = HealthDays.plus(dayStartMs, 1)
                val kept = s.weighins.filterNot { it.atMs in dayStartMs until dayEnd }
                val next = s.copy(weighins = (kept + StoredWeighin(atMs, kg, note)).sortedBy { it.atMs })
                loaded = next
                publish(next)
            }
            scheduleFlush()
        }
    }

    fun removeWeighin(atMs: Long) {
        scope.launch {
            mutex.withLock {
                val s = loadLocked()
                val next = s.copy(weighins = s.weighins.filterNot { it.atMs == atMs })
                loaded = next
                publish(next)
            }
            scheduleFlush()
        }
    }

    fun recordMeasurement(atMs: Long, kind: MeasureKind, cm: Double) {
        if (!cm.isFinite() || cm <= 0.0) return
        scope.launch {
            mutex.withLock {
                val s = loadLocked()
                val next = s.copy(measurements = (s.measurements + StoredMeasurement(atMs, kind.name, cm))
                    .sortedBy { it.atMs })
                loaded = next
                publish(next)
            }
            scheduleFlush()
        }
    }

    fun removeMeasurement(atMs: Long, kind: MeasureKind) {
        scope.launch {
            mutex.withLock {
                val s = loadLocked()
                val next = s.copy(measurements = s.measurements.filterNot { it.atMs == atMs && it.kind == kind.name })
                loaded = next
                publish(next)
            }
            scheduleFlush()
        }
    }

    // ------------------------------------------------------------------------------------ reading

    suspend fun all(): List<BodyTrend.Weighin> {
        ensureLoaded()
        return _weighins.value
    }

    /** The trend over everything recorded. The one place any part of the app should get it from. */
    suspend fun trend(): BodyTrend.Trend = BodyTrend.estimate(all())

    suspend fun allMeasurements(): List<Measurement> {
        ensureLoaded()
        return _measurements.value
    }

    /** The note left with a weigh-in, if any — kept out of [weighins] so the trend sees only numbers. */
    suspend fun noteAt(atMs: Long): String {
        val s = ensureLoaded()
        return s.weighins.firstOrNull { it.atMs == atMs }?.note.orEmpty()
    }

    /** Every note at once, keyed by reading. For the export, which needs them all and cannot suspend. */
    suspend fun notes(): Map<Long, String> {
        val s = ensureLoaded()
        return s.weighins.filter { it.note.isNotBlank() }.associate { it.atMs to it.note }
    }

    // ----------------------------------------------------------------------------------- lifecycle

    suspend fun clear() {
        // Cancel first, or a buffered write can resurrect the record after this returns.
        flushJob?.cancel()
        mutex.withLock {
            loaded = Stored()
            publish(Stored())
        }
        runCatching { context.bodyDataStore.edit { it.remove(prefsKey) } }
    }

    /** Force buffered changes to disk now (app stop). */
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
        flushJob = scope.launch {
            delay(FLUSH_DELAY_MS)
            flush()
        }
    }

    /**
     * ⚠️ On IO for the same reason [loadLocked] is: the debounced path already launches on this store's
     * own IO scope, but [flushNow] is called from `onStop` through the container's flush-everything, and
     * that runs in an activity scope on the main thread. Encoding a whole store to JSON there is the
     * worst moment to do it — the system is timing how quickly the app backgrounds.
     */
    private suspend fun flush(): Unit = withContext(Dispatchers.IO) {
        val snapshot = mutex.withLock { loaded } ?: return@withContext
        lastWrite = runCatching {
            context.bodyDataStore.edit { it[prefsKey] = json.encodeToString(Stored.serializer(), snapshot) }
        }
    }

    private companion object {
        const val FLUSH_DELAY_MS = 2_000L
    }
}
