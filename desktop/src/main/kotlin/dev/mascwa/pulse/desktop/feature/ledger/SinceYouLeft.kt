package dev.mascwa.pulse.desktop.feature.ledger

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mascwa.pulse.core.telemetry.Novelty
import dev.mascwa.pulse.desktop.Screen
import dev.mascwa.pulse.desktop.feature.world.here
import dev.mascwa.pulse.desktop.ledger.MetricRegistry
import dev.mascwa.pulse.desktop.ledger.WorldLedger
import dev.mascwa.pulse.desktop.settings.DesktopSettingsStore
import dev.mascwa.pulse.desktop.theme.ChakraPetch
import dev.mascwa.pulse.desktop.theme.JetBrainsMono
import dev.mascwa.pulse.desktop.theme.LcarsFrame
import dev.mascwa.pulse.desktop.theme.LcarsHeaderBar
import dev.mascwa.pulse.desktop.theme.LcarsSparkline
import dev.mascwa.pulse.desktop.theme.Pulse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/** One metric that moved while nobody was watching. */
data class Move(
    val spec: MetricRegistry.Spec,
    /** Signed change across the absence. */
    val change: Double,
    val from: Double,
    val to: Double,
    val reading: Novelty.Reading,
    /** [Novelty.spanSentence] — never [Novelty.Reading.sentence], which describes a level. */
    val sentence: String,
    /** The level readings taken during the absence, for the trace. */
    val trace: List<Double>,
) {
    val id: String get() = spec.id
}

data class SinceState(
    /** The span actually measured. Zero means there is nothing to show and the card does not render. */
    val lagMs: Long = 0L,
    /** How long you were really away, which is longer than [lagMs] when [capped]. */
    val awayMs: Long = 0L,
    val capped: Boolean = false,
    val movers: List<Move> = emptyList(),
    /** Metrics that could be judged over this window, and metrics there are in total. */
    val judged: Int = 0,
    val total: Int = 0,
    val computedAtMs: Long = 0L,
    val loading: Boolean = false,
)

/**
 * What changed while you were away.
 *
 * ⚠️ Not a lesser copy of the wall, and the difference is the whole reason it exists. ANOMALIES asks
 * *"what is strange right now, against everything on record"*. This asks *"what moved in the six hours
 * I was gone"* — which catches readings that are entirely ordinary where they now sit and remarkable
 * only in **how far they travelled over the particular interval of the absence**. A pressure that walks
 * from one perfectly normal value to another perfectly normal value, quickly, is invisible to the wall
 * and is exactly what somebody returning to the machine wants told.
 *
 * That comparison has to be like for like, which is what [Novelty.spanSeries] is for: the move is
 * scored against this metric's own history of moves over the **same** span. Judging a six-hour move
 * against a distribution of quarter-hour moves would report every absence as extraordinary.
 */
class SinceYouLeftViewModel(
    private val scope: CoroutineScope,
    private val settings: DesktopSettingsStore,
    private val ledger: WorldLedger = WorldLedger(),
) {
    private val _state = MutableStateFlow(SinceState())
    val state: StateFlow<SinceState> = _state.asStateFlow()

    private var job: Job? = null

    /**
     * Somebody is at the machine: work out how long they were not, then mark them present.
     *
     * Called when the console window takes focus, which includes the moment it opens.
     */
    fun onPresent() {
        job?.cancel()
        job = scope.launch { present(System.currentTimeMillis()) }
    }

    /**
     * Still here.
     *
     * ⚠️ The **only** writer of the marker, deliberately. Writing it on focus loss and focus gain
     * instead looks cheaper and is wrong in the case that matters: a process killed while focused
     * leaves the marker at the last *gain*, so the next launch reports an absence that includes however
     * long the session ran. One periodic rule has no transitions to get wrong, and [HEARTBEAT_MS] of
     * error on a quantity measured in hours is nothing.
     */
    fun heartbeat() {
        scope.launch { markPresent(System.currentTimeMillis()) }
    }

    /** The real path, callable without the scope so a test can drive it in `runBlocking`. */
    internal suspend fun present(nowMs: Long) {
        val last = runCatching { settings.current().lastSeenMs }.getOrDefault(0L)
        markPresent(nowMs)

        // ⚠️ Zero is "never seen", not the epoch. Without this a first launch is greeted with a
        // fifty-six-year absence and a card full of records.
        val away = if (last <= 0L) 0L else nowMs - last
        if (away < MIN_ABSENCE_MS) {
            // A short step away leaves an existing card alone — it should not vanish while it is being
            // read. It goes once it is older than the floor, which any later return will notice.
            val shown = _state.value
            if (shown.lagMs > 0L && nowMs - shown.computedAtMs > MIN_ABSENCE_MS) _state.value = SinceState()
            return
        }

        val lag = away.coerceAtMost(MAX_ABSENCE_MS)
        _state.value = _state.value.copy(loading = true)

        val place = runCatching { settings.here() }.getOrNull()
        val scan = scanSince(ledger, place?.let { MetricRegistry.placeKey(it.first, it.second) }, lag, nowMs)

        _state.value = SinceState(
            lagMs = lag,
            awayMs = away,
            capped = away > MAX_ABSENCE_MS,
            movers = scan.movers,
            judged = scan.judged,
            total = scan.total,
            computedAtMs = nowMs,
            loading = false,
        )
    }

    private suspend fun markPresent(nowMs: Long) {
        runCatching { settings.update { it.copy(lastSeenMs = nowMs) } }
    }

    companion object {
        /**
         * How often presence is written down while the window has focus.
         *
         * Five minutes rather than one: the marker is only ever read to measure an absence of hours, so
         * the accuracy bought by beating faster is worth nothing, and each beat rewrites the settings
         * file.
         */
        const val HEARTBEAT_MS = 5L * 60L * 1000L

        /**
         * Shortest absence worth a card.
         *
         * ⚠️ Measured against the collector rather than chosen: the slowest domain cadence is sixty
         * minutes, so anything shorter gives those domains a single collection — which is precisely what
         * the wall's rate-of-change reading already covers. Two hours guarantees every domain
         * contributes a genuine multi-step move, and means the card does not appear after a coffee.
         */
        const val MIN_ABSENCE_MS = 2L * 60L * 60L * 1000L

        /**
         * Longest span the question is asked over, however long the absence really was.
         *
         * ⚠️ Also arithmetic rather than taste. Spans do not overlap, so a year of full-resolution
         * history holds about `365 / days` of them; at seven days that is fifty-two, which clears
         * [Novelty.MIN_SAMPLES] with margin, and at a month it is twelve, which does not. Asking a
         * question the record cannot answer would just produce a card of refusals.
         */
        const val MAX_ABSENCE_MS = 7L * 24L * 60L * 60L * 1000L
    }
}

/** Everything one pass over the ledger found for a given span. */
data class SinceScan(val movers: List<Move>, val judged: Int, val total: Int)

/**
 * Score every metric's move across [lagMs] and return the ones worth mentioning.
 *
 * ⚠️ Reads a **bounded slice** of the ledger, unlike [scanLedger], which reads every metric whole. The
 * question only needs enough history for [HISTORY_SPANS] non-overlapping spans, so a six-hour absence
 * reads about fifty days rather than a year — cheaper than the wall and statistically sufficient, since
 * anything beyond that is sample the refusal floor was already satisfied without.
 */
suspend fun scanSince(
    ledger: WorldLedger,
    placeKey: String?,
    lagMs: Long,
    nowMs: Long,
): SinceScan {
    if (lagMs <= 0L) return SinceScan(emptyList(), 0, 0)
    val offset = ZoneId.systemDefault().rules.getOffset(Instant.now()).totalSeconds
    val specs = MetricRegistry.ALL.filter { it.scored }
    val since = nowMs - lagMs * HISTORY_SPANS
    // ⚠️ How stale the newest span may be. A reading a week old cannot answer "what changed while I was
    // away" however good the history behind it is — that would be a move that finished before the
    // absence began. Proportional, so a two-hour question tolerates half an hour and a seven-day one
    // does not tolerate a week, but capped so a long absence does not accept a stale answer either.
    val freshEnough = nowMs - minOf(lagMs / STALE_FRACTION, STALE_CAP_MS)

    val moves = mutableListOf<Move>()
    var judged = 0

    for (spec in specs) {
        val series = runCatching { ledger.read(spec.key(placeKey), since) }.getOrDefault(emptyList())
        if (series.size < 2) continue

        val spans = Novelty.spanSeries(series, lagMs)
        val newest = spans.lastOrNull() ?: continue
        if (newest.atMs < freshEnough) continue

        val scored = Novelty.score(spans, newest, spec.diurnal, offset) as? Novelty.Score.Scored ?: continue
        judged++

        if (scored.reading.bits < CARD_BITS) continue
        val sentence = Novelty.spanSentence(scored.reading, lagMs, newest.value) ?: continue

        val to = series.last { it.atMs <= newest.atMs }.value
        moves += Move(
            spec = spec,
            change = newest.value,
            from = to - newest.value,
            to = to,
            reading = scored.reading,
            sentence = sentence,
            trace = series.filter { it.atMs >= newest.atMs - lagMs }.map { it.value },
        )
    }

    // Ranked by the core, with no persistence credit: a span has no consecutive-collections meaning.
    val byId = moves.associateBy { it.id }
    val ranked = Novelty.rank(moves.map { it.id to it.reading }).mapNotNull { byId[it] }
    return SinceScan(ranked, judged, specs.size)
}

/** Non-overlapping spans to read history for. Well past [Novelty.MIN_SAMPLES], even after diurnal thinning. */
private const val HISTORY_SPANS = 200L

/**
 * How surprising a move has to be to reach the card.
 *
 * ⚠️ **Higher than the wall's four bits, and the plan said to share that constant.** The measurement
 * overruled it: running the shipped scorer over a real year of London hourly weather, a six-hour move
 * clears four bits on **9.8%** of hours. Across the couple of dozen scored metrics that puts something
 * on the card nearly every time somebody comes back, which is how a card stops being read. Five bits —
 * about a one-in-thirty move — cuts that to 4.7% per metric, so the card is often empty and worth
 * looking at when it is not.
 *
 * ⚠️ Not higher still, and this is the constraint that decides it. Surprisal is capped at what the
 * sample can resolve, so a bar of `b` bits is **unreachable** below `2^b − 1` non-overlapping spans: 63
 * at five bits, 127 at six. Spans do not overlap, so at a six-hour lag that is sixteen days of history
 * against thirty-two, and at a seven-day lag six bits could never be reached inside the year the ledger
 * keeps at full resolution — a bar the record cannot clear is a feature that never fires.
 *
 * The wall keeps four bits on purpose: it lists everything with its expected false-alarm count beside
 * it, on a page somebody chose to open. This card arrives unasked with three rows.
 */
private const val CARD_BITS = 5.0

private const val STALE_FRACTION = 4L
private const val STALE_CAP_MS = 3L * 60L * 60L * 1000L

/** How many movers Home shows. The wall is the list; this is the headline. */
private const val SHOWN = 3

/**
 * The card. Renders nothing at all unless there was a real absence, so its presence already means
 * something happened.
 */
@Composable
fun SinceYouLeftCard(state: SinceState, onOpenScreen: (Screen) -> Unit) {
    if (state.lagMs <= 0L) return
    val c = Pulse.colors

    Column(Modifier.padding(top = 10.dp)) {
        LcarsHeaderBar("Since you last looked", trailing = spanLabel(state.lagMs).uppercase(Locale.US))

        if (state.capped) {
            Text(
                "Away ${spanLabel(state.awayMs)} — showing the last ${spanLabel(state.lagMs)}, " +
                    "which is as long a move as the record can judge.",
                fontFamily = JetBrainsMono, fontSize = 10.sp, lineHeight = 15.sp, color = c.faint,
                modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
            )
        }

        if (state.movers.isEmpty()) {
            Text(
                if (state.judged == 0) {
                    // ⚠️ "recent" is doing real work: nought judged can mean a short history OR that
                    // collection stopped, and a flat "not watched long enough" would be misleading in
                    // the second case.
                    "Nothing has enough recent history to judge a ${spanLabel(state.lagMs)} move yet."
                } else {
                    "Nothing moved unusually in the last ${spanLabel(state.lagMs)}."
                },
                fontFamily = JetBrainsMono, fontSize = 11.sp, color = c.muted,
                modifier = Modifier.padding(top = 6.dp),
            )
        } else {
            state.movers.take(SHOWN).forEach { MoveRow(it, onOpenScreen) }
        }

        // ⚠️ Both of these are the wall's instinct, and they answer different questions. Two movers out
        // of forty-odd metrics looks like a quiet world when it really means a short history; and any
        // bar tested against enough metrics is crossed by chance sometimes. Saying so is the difference
        // between an instrument and a horoscope.
        val chance = Novelty.expectedFalseAlarms(state.judged, CARD_BITS)
        val notes = listOfNotNull(
            "${state.total - state.judged} of ${state.total} have too little recent history for a " +
                "${spanLabel(state.lagMs)} window yet".takeIf { state.judged < state.total },
            // Below half a metric it rounds to "none of these", which is a claim rather than a caveat.
            "about ${"%.1f".format(Locale.US, chance)} of ${state.judged} judged would cross this bar by chance"
                .takeIf { state.movers.isNotEmpty() && chance >= 0.5 },
        )
        if (notes.isNotEmpty()) {
            Text(
                notes.joinToString(" · ") + ".",
                fontFamily = JetBrainsMono, fontSize = 9.sp, lineHeight = 13.sp, color = c.faint,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

@Composable
private fun MoveRow(m: Move, onOpenScreen: (Screen) -> Unit) {
    val c = Pulse.colors
    val tint = tintFor(m.reading.bits)
    val goes = m.spec.domain.screen
    val unit = m.spec.unit.takeIf { it.isNotBlank() }?.let { " $it" }.orEmpty()
    val arrow = if (m.change >= 0) "▲" else "▼"

    LcarsFrame(
        Modifier.fillMaxWidth().padding(top = 6.dp)
            .let { if (goes == null) it else it.clickable { onOpenScreen(goes) } },
        accent = tint,
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    m.spec.label.uppercase(Locale.US),
                    fontFamily = ChakraPetch, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                    color = c.ink, modifier = Modifier.weight(1f),
                )
                Text(
                    "$arrow ${fmt(kotlin.math.abs(m.change), m.spec.decimals)}$unit",
                    fontFamily = JetBrainsMono, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = tint,
                )
            }
            Text(
                m.sentence.replaceFirstChar { it.uppercase() },
                fontFamily = JetBrainsMono, fontSize = 11.sp, lineHeight = 16.sp, color = c.ink2,
                modifier = Modifier.padding(top = 4.dp),
            )
            Row(
                Modifier.padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                LcarsSparkline(m.trace, tint, Modifier.weight(1f).height(18.dp), c.raise)
                Text(
                    "${fmt(m.from, m.spec.decimals)} → ${fmt(m.to, m.spec.decimals)}$unit",
                    fontFamily = JetBrainsMono, fontSize = 9.sp, color = c.faint,
                )
            }
        }
    }
}

/** A bare duration the way somebody says it: "6 hours", "2 days". */
private fun spanLabel(ms: Long): String {
    val hours = ms / (60L * 60L * 1000L)
    return when {
        hours < 48L -> "$hours hour" + if (hours == 1L) "" else "s"
        else -> "${hours / 24L} days"
    }
}
