package dev.mascwa.pulse.desktop.feature.world

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mascwa.pulse.core.telemetry.Freshness
import dev.mascwa.pulse.core.telemetry.MarketMood
import dev.mascwa.pulse.core.telemetry.MarketSession
import dev.mascwa.pulse.core.util.Async
import dev.mascwa.pulse.core.util.Formatters
import dev.mascwa.pulse.core.util.load
import dev.mascwa.pulse.data.markets.MarketsRepository
import dev.mascwa.pulse.data.markets.Quote
import dev.mascwa.pulse.desktop.theme.ChakraPetch
import dev.mascwa.pulse.desktop.theme.JetBrainsMono
import dev.mascwa.pulse.desktop.theme.LcarsBusyBar
import dev.mascwa.pulse.desktop.theme.LcarsFrame
import dev.mascwa.pulse.desktop.theme.LcarsGhostButton
import dev.mascwa.pulse.desktop.theme.LcarsHeaderBar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import dev.mascwa.pulse.desktop.theme.Pulse

/**
 * ⚠️ Not a [WorldFeed]: this is the one screen here that does not want a coordinate. What it prices is
 * a watch list, which is a preference rather than a place.
 */
class MarketsViewModel(
    private val scope: CoroutineScope,
    private val repository: MarketsRepository,
) {
    private val _state = MutableStateFlow(Async<List<Quote>>())
    val state: StateFlow<Async<List<Quote>>> = _state.asStateFlow()

    private var job: Job? = null

    fun ensureLoaded() {
        if (_state.value.hasData || job?.isActive == true) return
        refresh(force = false)
    }

    fun refresh(force: Boolean = true) {
        job?.cancel()
        job = scope.launch { _state.load(force) { f -> repository.fetchAll(f) } }
    }
}

/**
 * The watch list, and whether the market it belongs to is even open.
 *
 * A desktop window is wide enough to give every row its session state and its position in the year's
 * range without crowding, which the phone has to ration.
 */
@Composable
fun MarketsScreen(vm: MarketsViewModel, modifier: Modifier = Modifier) {
    val state: Async<List<Quote>> by vm.state.collectAsState()
    val c = Pulse.colors

    LaunchedEffect(Unit) { vm.ensureLoaded() }

    Column(modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        val quotes = state.data.orEmpty()
        val mood = remember(quotes) { MarketMood.summarize(quotes.mapNotNull { it.changePercent }) }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LcarsHeaderBar(
                "Markets",
                Modifier.weight(1f),
                trailing = if (quotes.isEmpty()) null else "${quotes.size} INSTRUMENTS",
            )
            LcarsGhostButton("REFRESH", { vm.refresh() })
        }
        LcarsBusyBar(active = state.loading, modifier = Modifier.fillMaxWidth())

        state.error?.let { err ->
            LcarsFrame(Modifier.fillMaxWidth().padding(top = 10.dp), accent = c.negative) {
                Column {
                    Text(err, fontFamily = JetBrainsMono, fontSize = 12.sp, color = c.ink)
                    LcarsGhostButton("TRY AGAIN", { vm.refresh() }, Modifier.padding(top = 8.dp))
                }
            }
        }

        val freshness = Freshness.assess(
            lastUpdatedMs = state.lastUpdatedEpochMs,
            nowMs = System.currentTimeMillis(),
            online = true,
            servingStored = state.stale,
            refreshFailed = state.stale && state.error != null,
        )
        if (freshness.worthShowing) {
            Text(
                freshness.label,
                fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.amber,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        if (mood != null) {
            // The breadth read, from the same tested core the phone uses — one definition of what
            // "mostly up today" means, so the two never disagree about the same list.
            LcarsFrame(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Column {
                    Text(
                        mood.headline.uppercase(),
                        fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 15.sp,
                        color = when {
                            mood.upShare >= 0.55 -> c.positive
                            mood.upShare <= 0.45 -> c.negative
                            else -> c.ink
                        },
                    )
                    Text(
                        mood.plain,
                        fontFamily = JetBrainsMono, fontSize = 11.sp, lineHeight = 16.sp, color = c.ink2,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    Text(
                        "${mood.up} up · ${mood.down} down · ${mood.flat} flat · " +
                            "net ${Formatters.signedPercent(mood.netChangePct)}",
                        fontFamily = JetBrainsMono, fontSize = 10.sp,
                        color = if (mood.netChangePct >= 0) c.positive else c.negative,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }

        LazyColumn(
            Modifier.fillMaxSize().padding(top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            items(quotes, key = { it.id }) { QuoteRow(it) }
        }
    }
}

@Composable
private fun QuoteRow(quote: Quote) {
    val c = Pulse.colors
    val pct = quote.changePercent
    val tone = when {
        pct == null -> c.muted
        pct > 0 -> c.positive
        pct < 0 -> c.negative
        else -> c.ink
    }
    LcarsFrame(Modifier.fillMaxWidth(), accent = tone) {
        Column {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(
                        quote.label,
                        fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                        color = c.ink,
                    )
                    // The venue's own name for the instrument, where it gave one — it is often more
                    // informative than the label somebody typed into a watch list.
                    quote.name?.takeIf { it.isNotBlank() && !it.equals(quote.label, true) }?.let {
                        Text(it, fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.faint)
                    }
                }
                Column {
                    Text(
                        formatPrice(quote),
                        fontFamily = JetBrainsMono, fontSize = 14.sp, color = c.ink,
                    )
                    Text(
                        Formatters.signedPercent(pct),
                        fontFamily = JetBrainsMono, fontSize = 11.sp, color = tone,
                    )
                }
            }

            val facts = buildList {
                // ⚠️ Whether the venue is open. A closed market's price looks exactly like a live one,
                // which is the thing this row exists to stop implying.
                sessionLine(quote)?.let { add(it) }
                quote.exchange?.takeIf { it.isNotBlank() }?.let { add(it) }
                rangeLine(quote)?.let { add(it) }
            }
            if (facts.isNotEmpty()) {
                Text(
                    facts.joinToString(" · "),
                    fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.muted,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

/**
 * Whether the venue is open, in the core's own words.
 *
 * ⚠️ Not a `when` over the phase written here. `MarketSession.describe` already says "Open · 2h 14m to
 * the bell" and "Closed · last traded 3h ago", which is worth more than a one-word state, and writing a
 * second version of it is how two screens end up describing the same market differently.
 *
 * `Phase.UNKNOWN` renders as the empty string, which is deliberate on its side too: "Closed" is a claim
 * about a venue and not knowing is a fact about us, and the two must not read the same.
 */
private fun sessionLine(quote: Quote): String? {
    val hours = quote.hours ?: return null
    return MarketSession.describe(hours.toWindows(), System.currentTimeMillis(), quote.marketTimeMs)
        .takeIf { it.isNotBlank() }
}

/**
 * The year's range, but only when it says something.
 *
 * The core's bands are deliberately uneven — the ends of a yearly range are news and the wide middle is
 * not — so the broad "mid-range for the year" is dropped here rather than printed on almost every row.
 */
private fun rangeLine(quote: Quote): String? =
    MarketSession.describeRange(
        MarketSession.rangePosition(quote.price, quote.fiftyTwoWeekLow, quote.fiftyTwoWeekHigh),
    )?.takeIf { it != "mid-range for the year" }

/**
 * ⚠️ Precision belongs to the INSTRUMENT, not the value. Two decimals on an FX pair rounds away exactly
 * the digits that move — the phone's day-range line had that defect and it is not repeated here.
 */
private fun formatPrice(quote: Quote): String {
    val price = quote.price ?: return "—"
    val digits = quote.priceHint ?: if (price < 10) 4 else 2
    return String.format(java.util.Locale.US, "%,.${digits.coerceIn(0, 8)}f", price)
}
