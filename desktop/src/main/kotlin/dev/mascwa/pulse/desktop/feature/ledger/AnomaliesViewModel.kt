package dev.mascwa.pulse.desktop.feature.ledger

import dev.mascwa.pulse.core.telemetry.Novelty
import dev.mascwa.pulse.desktop.feature.world.here
import dev.mascwa.pulse.desktop.ledger.MetricRegistry
import dev.mascwa.pulse.desktop.ledger.WorldLedger
import dev.mascwa.pulse.desktop.settings.DesktopSettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId

/** What a reading is: level as it stands, or how fast it is moving. */
enum class Aspect(val label: String) {
    LEVEL(""),
    CHANGE("rate of change"),
}

/** One scored line on the wall. */
data class Anomaly(
    val spec: MetricRegistry.Spec,
    val aspect: Aspect,
    val value: Double,
    val atMs: Long,
    val reading: Novelty.Reading,
    /** Recent history, for the trace beside the row. Level only — a difference series draws as noise. */
    val trace: List<Double>,
    /** Consecutive collections this has been past the threshold. 1 means it has only just appeared. */
    val persistence: Int,
) {
    val id: String get() = "${spec.id}:${aspect.name}"
}

/** A metric that cannot be judged yet, and how much more it needs. */
data class NotYet(val spec: MetricRegistry.Spec, val have: Int, val need: Int, val sentence: String)

data class AnomaliesState(
    val anomalies: List<Anomaly> = emptyList(),
    val quiet: List<Anomaly> = emptyList(),
    val notYet: List<NotYet> = emptyList(),
    val tested: Int = 0,
    val falseAlarms: Double = 0.0,
    /** The moment the wall is rendered for. Equal to [newestMs] unless the scrubber has been moved. */
    val asOfMs: Long = 0L,
    val oldestMs: Long = 0L,
    val newestMs: Long = 0L,
    val ledgerBytes: Long = 0L,
    val watching: Boolean = false,
    val loading: Boolean = false,
)

/**
 * The wall: every domain at once, ranked by how surprising each reading is against its own history.
 *
 * An earthquake, a solar storm, an air-quality reading and a share price share no unit — but they
 * share how improbable each is against what this machine has watched that metric do. Reduce them all
 * to that and they rank against each other on one axis, which is the whole point of the ledger.
 *
 * ## ⚠️ Three things this deliberately does that a dashboard would not
 *
 * **It states its own expected false-alarm count.** Score a hundred metrics and a "1-in-1000" reading
 * turns up constantly by chance alone; a wall that never says so is training its reader to believe in
 * noise. [Novelty.expectedFalseAlarms] does the arithmetic and the screen prints it.
 *
 * **Metrics it cannot judge get their own section.** On a new install almost everything lands there,
 * and quietly sorting them among the scored ones makes the world look either uniformly calm or
 * uniformly alarming.
 *
 * **It reads the past.** [Novelty.score] takes the reading being judged rather than a clock, so
 * rendering the wall as it stood at any earlier moment costs nothing but choosing a different reading
 * — which is a thing no other program can do here, because no other program kept the data.
 */
class AnomaliesViewModel(
    private val scope: CoroutineScope,
    private val settings: DesktopSettingsStore,
    private val ledger: WorldLedger = WorldLedger(),
) {
    private val _state = MutableStateFlow(AnomaliesState())
    val state: StateFlow<AnomaliesState> = _state.asStateFlow()

    private var job: Job? = null
    private var loaded = false

    fun ensureLoaded() {
        if (loaded) return
        loaded = true
        refresh()
    }

    fun refresh(asOfMs: Long? = null) {
        job?.cancel()
        _state.value = _state.value.copy(loading = true)
        job = scope.launch { rebuild(asOfMs) }
    }

    /** Move the wall to a past moment. Null puts it back to now. */
    fun scrubTo(atMs: Long?) = refresh(atMs)

    /**
     * The whole page, built and published, without going through the scope.
     *
     * Exists so a test can drive the real path in a plain `runBlocking` — the module has no
     * coroutines-test dependency and every other test here uses that idiom. ⚠️ It is the *same*
     * function [refresh] launches, not a parallel one written for tests: a second implementation is
     * how a page ends up passing its tests and rendering something else.
     */
    internal suspend fun rebuildNow(asOfMs: Long? = null) = rebuild(asOfMs)

    private suspend fun rebuild(asOfMs: Long?) {
        val place = settings.here()
        val placeKey = place?.let { MetricRegistry.placeKey(it.first, it.second) }
        val offset = ZoneId.systemDefault().rules.getOffset(Instant.now()).totalSeconds

        val scored = mutableListOf<Anomaly>()
        val notYet = mutableListOf<NotYet>()
        var oldest = Long.MAX_VALUE
        var newest = 0L
        var tested = 0

        for (spec in MetricRegistry.ALL.filter { it.scored }) {
            val series = runCatching { ledger.read(spec.key(placeKey)) }.getOrDefault(emptyList())
            if (series.isEmpty()) continue
            series.firstOrNull()?.let { if (it.atMs < oldest) oldest = it.atMs }
            series.lastOrNull()?.let { if (it.atMs > newest) newest = it.atMs }

            // ⚠️ The scrubber works by choosing a different reading to judge, not by filtering the
            // history: `score` already excludes everything at or after the reading it is given, so
            // handing it an older one renders the wall exactly as it stood then.
            val ordered = series.sortedBy { it.atMs }
            val visible = if (asOfMs == null) ordered else ordered.filter { it.atMs <= asOfMs }
            val latest = visible.lastOrNull() ?: continue

            tested++
            when (val s = Novelty.score(visible, latest, spec.diurnal, offset)) {
                is Novelty.Score.TooLittleHistory -> notYet += NotYet(spec, s.have, s.need, s.sentence)
                is Novelty.Score.Scored -> scored += Anomaly(
                    spec = spec,
                    aspect = Aspect.LEVEL,
                    value = latest.value,
                    atMs = latest.atMs,
                    reading = s.reading,
                    trace = visible.takeLast(TRACE_POINTS).map { it.value },
                    persistence = persistenceOf(visible, spec, offset),
                )
            }

            // How fast it is moving, judged the same way. A value can be unremarkable in level and
            // remarkable in speed — a barometric pressure drop being the case everyone knows.
            val changes = Novelty.changeSeries(visible)
            val newestChange = changes.lastOrNull()
            if (newestChange != null) {
                tested++
                val s = Novelty.score(changes, newestChange, diurnal = false, utcOffsetSeconds = offset)
                if (s is Novelty.Score.Scored) {
                    scored += Anomaly(
                        spec = spec,
                        aspect = Aspect.CHANGE,
                        value = newestChange.value,
                        atMs = newestChange.atMs,
                        reading = s.reading,
                        trace = visible.takeLast(TRACE_POINTS).map { it.value },
                        persistence = 1,
                    )
                }
                // ⚠️ A rate that cannot be judged is NOT listed under "not enough history yet". The
                // reader would see the same metric twice in that section for one reason, and a
                // difference series is thin whenever its level series is.
            }
        }

        val ranked = Novelty.rank(
            scored.map { it.id to it.reading },
            scored.associate { it.id to it.persistence },
        )
        val byId = scored.associateBy { it.id }
        val order = ranked.mapNotNull { byId[it] }

        _state.value = AnomaliesState(
            anomalies = order.filter { it.reading.bits >= THRESHOLD_BITS },
            quiet = order.filter { it.reading.bits < THRESHOLD_BITS },
            notYet = notYet,
            tested = tested,
            falseAlarms = Novelty.expectedFalseAlarms(tested, THRESHOLD_BITS),
            asOfMs = asOfMs ?: newest,
            oldestMs = if (oldest == Long.MAX_VALUE) 0L else oldest,
            newestMs = newest,
            ledgerBytes = runCatching { ledger.sizeBytes() }.getOrDefault(0L),
            watching = settings.current().longWatch,
            loading = false,
        )
    }

    /**
     * How many collections in a row this metric has been past the threshold.
     *
     * ⚠️ Walked backwards only as far as the credit can reach, because that is all the answer is used
     * for and a full walk would mean re-scoring every reading of every metric on every render. An
     * anomaly present in one sample and gone the next is usually noise; one that has held is usually
     * not, and [Novelty.PERSISTENCE_BITS] deliberately credits that by very little.
     */
    private fun persistenceOf(series: List<Novelty.Observation>, spec: MetricRegistry.Spec, offset: Int): Int {
        var held = 0
        for (i in 0..Novelty.PERSISTENCE_CAP) {
            val at = series.getOrNull(series.size - 1 - i) ?: break
            val s = Novelty.score(series.take(series.size - i), at, spec.diurnal, offset)
            val bits = (s as? Novelty.Score.Scored)?.reading?.bits ?: break
            if (bits < THRESHOLD_BITS) break
            held++
        }
        return held.coerceAtLeast(1)
    }

    companion object {
        /**
         * How surprising a reading has to be to reach the wall rather than the quiet list.
         *
         * 4 bits is about a 1-in-16 reading. ⚠️ Chosen with the false-alarm line in view rather than
         * for its own sake: at roughly ninety tested readings it means about six of them are expected
         * to be chance, which the screen states outright. A higher bar would make the wall emptier and
         * the arithmetic less honest, not more.
         */
        const val THRESHOLD_BITS = 4.0

        /** Readings behind the trace on each row. */
        const val TRACE_POINTS = 96
    }
}
