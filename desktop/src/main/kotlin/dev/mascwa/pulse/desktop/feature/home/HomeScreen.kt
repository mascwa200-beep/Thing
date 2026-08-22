package dev.mascwa.pulse.desktop.feature.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mascwa.pulse.core.telemetry.DailyLesson
import dev.mascwa.pulse.core.telemetry.Freshness
import dev.mascwa.pulse.core.telemetry.Insight
import dev.mascwa.pulse.core.telemetry.MarketMood
import dev.mascwa.pulse.core.telemetry.Oracle
import dev.mascwa.pulse.core.telemetry.WeatherComfort
import dev.mascwa.pulse.core.util.Formatters
import dev.mascwa.pulse.data.markets.MarketsRepository
import dev.mascwa.pulse.data.markets.Quote
import dev.mascwa.pulse.data.weather.WeatherCode
import dev.mascwa.pulse.data.weather.WeatherData
import dev.mascwa.pulse.data.weather.WeatherRepository
import dev.mascwa.pulse.desktop.Screen
import dev.mascwa.pulse.desktop.feature.ledger.SinceYouLeftCard
import dev.mascwa.pulse.desktop.feature.ledger.SinceYouLeftViewModel
import dev.mascwa.pulse.desktop.feature.world.AdvisoriesViewModel
import dev.mascwa.pulse.desktop.feature.world.here
import dev.mascwa.pulse.desktop.news.Article
import dev.mascwa.pulse.desktop.news.NewsCategory
import dev.mascwa.pulse.desktop.news.NewsRepository
import dev.mascwa.pulse.desktop.screenForRoute
import dev.mascwa.pulse.desktop.settings.DesktopSettingsStore
import dev.mascwa.pulse.desktop.study.StudyStore
import dev.mascwa.pulse.desktop.theme.ChakraPetch
import dev.mascwa.pulse.desktop.theme.JetBrainsMono
import dev.mascwa.pulse.desktop.theme.LcarsBusyBar
import dev.mascwa.pulse.desktop.theme.LcarsDataRow
import dev.mascwa.pulse.desktop.theme.LcarsFrame
import dev.mascwa.pulse.desktop.theme.LcarsGhostButton
import dev.mascwa.pulse.desktop.theme.LcarsHeaderBar
import dev.mascwa.pulse.desktop.theme.LcarsStatBlock
import dev.mascwa.pulse.desktop.theme.Pulse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalTime

data class HomeState(
    val weather: WeatherData? = null,
    val lead: Article? = null,
    val quotes: List<Quote> = emptyList(),
    val lesson: DailyLesson.Lesson? = null,
    val loading: Boolean = false,
    /** The oldest of the feeds on the page — it is only as current as its stalest card. */
    val oldestFetchMs: Long = 0L,
)

/**
 * The overview: one screen that answers "is there anything I need to know" without opening five.
 *
 * ⚠️ Every fetch here is **unforced**, and that is the design rather than an optimisation. Home reads
 * the same repositories the full screens do, so a warm cache costs nothing and a cold one is filled
 * once for both. A dashboard that forced six live requests on open would rate-limit the screens that
 * actually present the data, which is the failure the shared per-host gate exists to prevent.
 */
class HomeViewModel(
    private val scope: CoroutineScope,
    private val settings: DesktopSettingsStore,
    private val weather: WeatherRepository,
    private val markets: MarketsRepository,
    private val news: NewsRepository,
    private val study: StudyStore,
) {
    private val _state = MutableStateFlow(HomeState())
    val state: StateFlow<HomeState> = _state.asStateFlow()

    private var job: Job? = null

    fun ensureLoaded() {
        if (_state.value.oldestFetchMs > 0L || job?.isActive == true) return
        refresh(force = false)
    }

    fun refresh(force: Boolean = true) {
        job?.cancel()
        job = scope.launch {
            _state.value = _state.value.copy(loading = true)

            val here = settings.here()
            val label = runCatching { settings.current().placeLabel }.getOrNull().orEmpty()
            // Each of the four is independent, and each failure costs only its own card. Collected as
            // nullable rather than thrown, so one dead feed never blanks the page.
            var oldest = Long.MAX_VALUE
            val wx = here?.let { (lat, lon) ->
                runCatching { weather.fetch(lat, lon, label.ifBlank { "Here" }, force) }
                    .getOrNull()
                    ?.also { oldest = minOf(oldest, it.timestampEpochMs) }
                    ?.data
            }
            val quotes = runCatching { markets.fetchAll(force) }
                .getOrNull()
                ?.also { oldest = minOf(oldest, it.timestampEpochMs) }
                ?.data
                .orEmpty()
            val lead = runCatching { news.headlines(NewsCategory.TOP, force).getOrNull() }
                .getOrNull()
                ?.also { oldest = minOf(oldest, it.timestampEpochMs) }
                ?.data
                ?.firstOrNull()
            val lesson = runCatching { study.today() }.getOrNull()

            _state.value = HomeState(
                weather = wx,
                lead = lead,
                quotes = quotes,
                lesson = lesson,
                loading = false,
                oldestFetchMs = if (oldest == Long.MAX_VALUE) 0L else oldest,
            )
        }
    }
}

/**
 * Home.
 *
 * ⚠️ The advisories come from the SAME view model the Advisories screen uses, not a second read of
 * the same signals. Two independent Oracle snapshots taken seconds apart would disagree about which
 * insight is first — the ranking is a function of the moment — and the two pages would then be
 * telling you different things about one machine.
 *
 * ⚠️ [since] is its own view model rather than a field on [HomeState], and not for tidiness:
 * [HomeViewModel.refresh] assigns a whole new `HomeState`, so a separately-computed field would be
 * silently wiped by any refresh. Its trigger is different too — window focus and the local ledger,
 * against Home's network fetch — so folding them together would mean refetching the world on every
 * alt-tab or splitting the method anyway.
 */
@Composable
fun HomeScreen(
    vm: HomeViewModel,
    advisories: AdvisoriesViewModel,
    since: SinceYouLeftViewModel,
    onOpenScreen: (Screen) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state: HomeState by vm.state.collectAsState()
    val advice by advisories.state.collectAsState()
    val away by since.state.collectAsState()
    val c = Pulse.colors

    LaunchedEffect(Unit) {
        vm.ensureLoaded()
        advisories.ensureLoaded()
    }

    Column(modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LcarsHeaderBar(greeting(), Modifier.weight(1f), trailing = state.weather?.locationName?.uppercase())
            LcarsGhostButton("REFRESH", {
                vm.refresh()
                advisories.refresh()
            })
        }
        // The ledger read belongs here too: it is slower than it looks after a long absence, and a
        // `loading` written by the view model and read by nobody is this project's most-corrected defect.
        LcarsBusyBar(
            active = state.loading || advice.loading || away.loading,
            modifier = Modifier.fillMaxWidth(),
        )

        val freshness = Freshness.assess(
            lastUpdatedMs = state.oldestFetchMs,
            nowMs = System.currentTimeMillis(),
            online = true,
            servingStored = false,
            refreshFailed = false,
        )
        if (freshness.worthShowing) {
            Text(
                freshness.label,
                fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.amber,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        LazyColumn(
            Modifier.fillMaxSize().padding(top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // What the computer thinks matters, first — the same order the phone's Home settled on,
            // and for the same reason: an advisory that arrives under four other cards is one nobody
            // reads.
            item {
                Column {
                    Text(
                        "THE COMPUTER'S READ",
                        fontFamily = JetBrainsMono, fontSize = 9.sp, letterSpacing = 1.2.sp,
                        fontWeight = FontWeight.Bold, color = c.accent,
                    )
                    if (advice.insights.isEmpty()) {
                        Text(
                            advice.briefing.ifBlank { "Reading the signals…" },
                            fontFamily = JetBrainsMono, fontSize = 12.sp, color = c.muted,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    } else {
                        // Three, then a way to the rest. Home is a summary; the Advisories page is the
                        // list, and putting all eight here would make it a second copy of that page.
                        advice.insights.take(3).forEach { AdviceRow(it, onOpenScreen) }
                        if (advice.insights.size > 3) {
                            Text(
                                "ALL ${advice.insights.size} ADVISORIES ▸",
                                fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 11.sp,
                                letterSpacing = 1.sp, color = c.accent,
                                modifier = Modifier
                                    .clickable { onOpenScreen(Screen.ADVISORIES) }
                                    .padding(top = 8.dp, bottom = 2.dp),
                            )
                        }
                    }
                }
            }

            // ⚠️ Below the read rather than above it. This card arrives from the ledger a moment after
            // the page draws, and putting it first would shove the advisories down just as they are
            // being read — the one thing on Home whose position is already argued for above.
            item { SinceYouLeftCard(away, onOpenScreen) }

            state.weather?.current?.let { now ->
                item {
                    Column(Modifier.padding(top = 10.dp)) {
                        LcarsHeaderBar("Outside", trailing = "WEATHER ▸")
                        Row(
                            Modifier.fillMaxWidth().clickable { onOpenScreen(Screen.WEATHER) },
                            horizontalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            LcarsStatBlock(
                                "NOW",
                                now.temperature?.let { "${it.toInt()}${state.weather?.tempUnitSymbol.orEmpty()}" } ?: "—",
                                Modifier.weight(1f),
                            )
                            LcarsStatBlock(
                                "CONDITIONS",
                                WeatherCode.describe(now.weatherCode),
                                Modifier.weight(1f),
                            )
                            LcarsStatBlock(
                                "WIND",
                                now.windSpeed?.let { "${it.toInt()} ${state.weather?.windUnitSymbol.orEmpty()}" } ?: "—",
                                Modifier.weight(1f),
                            )
                        }
                        // The comfort read is null on an ordinary day by design — the indices are only
                        // defined where they were fitted, so silence here means "nothing unusual".
                        WeatherComfort.compactFeelsLike(
                            now.temperatureC,
                            now.humidity,
                            now.windKmh,
                            state.weather?.tempUnitSymbol.orEmpty(),
                        )?.let {
                            Text(
                                it,
                                fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                                color = c.amber,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                    }
                }
            }

            state.lead?.let { lead ->
                item {
                    Column(Modifier.padding(top = 10.dp)) {
                        LcarsHeaderBar("Leading", trailing = "NEWS ▸")
                        LcarsFrame(
                            Modifier.fillMaxWidth().clickable { onOpenScreen(Screen.NEWS) },
                            accent = c.sky,
                        ) {
                            Column {
                                Text(
                                    lead.title,
                                    fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 16.sp,
                                    lineHeight = 21.sp, color = c.ink,
                                )
                                Text(
                                    listOfNotNull(
                                        lead.source.takeIf { it.isNotBlank() },
                                        lead.publishedEpochMs.takeIf { it > 0 }
                                            ?.let { Formatters.relativeTime(it) },
                                    ).joinToString(" · "),
                                    fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.faint,
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                            }
                        }
                    }
                }
            }

            if (state.quotes.isNotEmpty()) {
                item {
                    val mood = MarketMood.summarize(state.quotes.mapNotNull { it.changePercent })
                    Column(Modifier.padding(top = 10.dp)) {
                        LcarsHeaderBar("Markets", trailing = "MARKETS ▸")
                        LcarsFrame(
                            Modifier.fillMaxWidth().clickable { onOpenScreen(Screen.MARKETS) },
                        ) {
                            Column {
                                if (mood != null) {
                                    Text(
                                        mood.headline.uppercase(),
                                        fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 15.sp,
                                        color = when {
                                            mood.upShare >= 0.55 -> c.positive
                                            mood.upShare <= 0.45 -> c.negative
                                            else -> c.ink
                                        },
                                        modifier = Modifier.padding(bottom = 6.dp),
                                    )
                                }
                                // The three that moved most, either way — a watch list ordered by
                                // absolute change is the only ordering that answers "did anything
                                // happen" in three rows.
                                state.quotes
                                    .filter { it.changePercent != null }
                                    .sortedByDescending { kotlin.math.abs(it.changePercent ?: 0.0) }
                                    .take(3)
                                    .forEach { q ->
                                        LcarsDataRow(
                                            q.label,
                                            Formatters.signedPercent(q.changePercent),
                                            valueColor = if ((q.changePercent ?: 0.0) >= 0) c.positive else c.negative,
                                        )
                                    }
                            }
                        }
                    }
                }
            }

            state.lesson?.let { lesson ->
                item {
                    Column(Modifier.padding(top = 10.dp, bottom = 20.dp)) {
                        LcarsHeaderBar("Today's lesson", trailing = "STUDY ▸")
                        LcarsFrame(
                            Modifier.fillMaxWidth().clickable { onOpenScreen(Screen.STUDY) },
                            accent = c.violet,
                        ) {
                            Column {
                                Text(
                                    lesson.headline,
                                    fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 16.sp,
                                    color = c.ink,
                                )
                                // Why this one — the deck picks for a reason and saying it is what
                                // makes the suggestion trustworthy rather than arbitrary.
                                Text(
                                    lesson.reason,
                                    fontFamily = JetBrainsMono, fontSize = 11.sp, lineHeight = 16.sp,
                                    color = c.ink2,
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AdviceRow(ins: Insight, onOpenScreen: (Screen) -> Unit) {
    val c = Pulse.colors
    val col = Color(Oracle.urgencyArgb(ins.urgency))
    val target = ins.actionRoute?.let(::screenForRoute)
    LcarsFrame(
        Modifier.fillMaxWidth().padding(top = 6.dp)
            .clickable(enabled = target != null) { target?.let(onOpenScreen) },
        accent = col,
    ) {
        Column {
            Text(
                ins.title,
                fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = c.ink,
            )
            Text(
                ins.detail,
                fontFamily = JetBrainsMono, fontSize = 11.sp, lineHeight = 16.sp, color = c.ink2,
                modifier = Modifier.padding(top = 3.dp),
            )
        }
    }
}

/** Local wall clock, because a greeting that reads "good evening" at breakfast is worse than none. */
private fun greeting(): String = when (LocalTime.now().hour) {
    in 0..4 -> "Still up"
    in 5..11 -> "Good morning"
    in 12..17 -> "Good afternoon"
    else -> "Good evening"
}
