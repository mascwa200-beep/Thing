package dev.mascwa.pulse.desktop.feature.world

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mascwa.pulse.core.telemetry.EmergencyNews
import dev.mascwa.pulse.core.telemetry.Insight
import dev.mascwa.pulse.core.telemetry.Oracle
import dev.mascwa.pulse.core.telemetry.OracleMover
import dev.mascwa.pulse.core.telemetry.OracleSignals
import dev.mascwa.pulse.core.telemetry.Urgency
import dev.mascwa.pulse.data.markets.MarketsRepository
import dev.mascwa.pulse.data.space.SpaceWeatherRepository
import dev.mascwa.pulse.data.weather.WeatherRepository
import dev.mascwa.pulse.desktop.Screen
import dev.mascwa.pulse.desktop.news.NewsCategory
import dev.mascwa.pulse.desktop.news.NewsRepository
import dev.mascwa.pulse.desktop.screenForRoute
import dev.mascwa.pulse.desktop.settings.DesktopSettingsStore
import dev.mascwa.pulse.desktop.study.StudyStore
import dev.mascwa.pulse.desktop.study.localDayIndex
import dev.mascwa.pulse.desktop.theme.ChakraPetch
import dev.mascwa.pulse.desktop.theme.JetBrainsMono
import dev.mascwa.pulse.desktop.theme.LcarsBusyBar
import dev.mascwa.pulse.desktop.theme.LcarsCorner
import dev.mascwa.pulse.desktop.theme.LcarsFrame
import dev.mascwa.pulse.desktop.theme.LcarsGhostButton
import dev.mascwa.pulse.desktop.theme.LcarsHeaderBar
import dev.mascwa.pulse.desktop.theme.Pulse
import dev.mascwa.pulse.desktop.theme.lcarsBlockShape
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.ZoneId

/** What the page has to show: the ranked read, the briefing, and whether it is still working. */
data class AdvisoriesState(
    val insights: List<Insight> = emptyList(),
    val briefing: String = "",
    val loading: Boolean = false,
    val located: Boolean = true,
)

/**
 * The Oracle, on a machine that senses far less than a phone — and says so.
 *
 * `Oracle.divine` fires 23 cross-signal rules over one [OracleSignals] snapshot, and every field of
 * that snapshot is optional precisely so that a missing signal mutes its own rules rather than
 * breaking anything. That is what makes this port honest instead of a stub: this machine genuinely
 * fills in the clock, where it is, the weather, the watch list, the Sun's behaviour, a breaking
 * emergency and the study deck. It has no calendar, no battery, no motion and no ambient sensors, so
 * the rules that need those never fire, and [MUTED] names them out loud at the foot of the page
 * rather than leaving someone to wonder why they never see a departure reminder.
 *
 * ⚠️ **No learning layer, deliberately.** The phone re-ranks this stream by which advisories it has
 * actually acted on, which it can do because it timestamps every screen visit. Nothing here does, and
 * inventing an attribution signal to fill the gap would teach the ranking something untrue. The
 * ordering is the core's own prior, which is what the phone had before that arc too.
 */
class AdvisoriesViewModel(
    private val scope: CoroutineScope,
    private val settings: DesktopSettingsStore,
    private val weather: WeatherRepository,
    private val markets: MarketsRepository,
    private val space: SpaceWeatherRepository,
    private val news: NewsRepository,
    private val study: StudyStore,
) {
    private val _state = MutableStateFlow(AdvisoriesState())
    val state: StateFlow<AdvisoriesState> = _state.asStateFlow()

    private var job: Job? = null

    fun ensureLoaded() {
        if (_state.value.insights.isNotEmpty() || job?.isActive == true) return
        refresh()
    }

    fun refresh() {
        job?.cancel()
        job = scope.launch {
            _state.value = _state.value.copy(loading = true)
            val signals = snapshot()
            val insights = Oracle.divine(signals)
            _state.value = AdvisoriesState(
                insights = insights,
                briefing = Oracle.briefing(insights),
                loading = false,
                located = signals.lat != null,
            )
        }
    }

    /**
     * Gather what this machine can, defensively.
     *
     * Shaped after the phone's `OracleEngine.snapshot`: every read is wrapped, every failure leaves
     * its field at the neutral default, and **nothing is forced**. These are warm reads of caches the
     * other screens have already filled — an advisory page that made six live requests every time you
     * opened it would cost more than it is worth, and would rate-limit the screens that actually
     * present the data.
     */
    private suspend fun snapshot(): OracleSignals {
        val now = System.currentTimeMillis()
        val local = LocalDateTime.now()
        val prefs = runCatching { settings.current() }.getOrNull()
        val lat = prefs?.latitude
        val lon = prefs?.longitude

        val wx = if (lat != null && lon != null) {
            runCatching {
                weather.fetch(lat, lon, prefs.placeLabel.ifBlank { "Here" }, force = false).data
            }.getOrNull()
        } else {
            null
        }

        // Tonight's low, taken as the minimum of the next twelve hourly readings rather than from a
        // daily figure. A daily minimum belongs to a calendar day, so by the evening today's is
        // already behind you and tomorrow's covers a night that has not started.
        //
        // ⚠️ The forecast's own zone, not this machine's. Open-Meteo returns hourly stamps in the
        // requested location's timezone, and comparing them against a local clock would be an hour
        // or ten out for anyone reading a forecast somewhere they are not.
        val overnightLow = wx?.let { data ->
            runCatching {
                val zone = ZoneId.of(data.timezone)
                val here = LocalDateTime.now(zone)
                data.hourly
                    .dropWhile { LocalDateTime.parse(it.timeIso) < here }
                    .take(12)
                    .mapNotNull { it.temperatureC }
                    .minOrNull()
            }.getOrNull()
        }

        val movers = runCatching {
            markets.fetchAll(force = false).data.mapNotNull { q ->
                q.changePercent?.let { OracleMover(name = q.label, changePct = it, onWatchlist = true) }
            }
        }.getOrDefault(emptyList())

        val kp = runCatching { space.fetch(force = false).data.kp }.getOrNull()

        val emergency = runCatching {
            news.headlines(NewsCategory.TOP, force = false).getOrNull()?.data
                ?.maxByOrNull { EmergencyNews.severity(it.title, it.summary) }
                ?.takeIf { EmergencyNews.isMajor(it.title, it.summary) }?.title
        }.getOrNull()

        // The study deck. Warm reads on a store that is already loaded, and every failure leaves the
        // field at its neutral default — so someone who has never studied hears nothing from it.
        val progress = runCatching { study.progress() }.getOrNull()
        val due = runCatching { study.dueCount() }.getOrDefault(0)
        val today = localDayIndex(now)
        val studiedToday = progress != null && progress.lastStudiedAtMs > 0L &&
            localDayIndex(progress.lastStudiedAtMs) == today
        val shaky = runCatching { study.weakestGuide() }.getOrNull()

        return OracleSignals(
            nowMs = now,
            hourOfDay = local.hour,
            minuteOfDay = local.hour * 60 + local.minute,
            // java.time counts Monday as 1, which is the convention the core states.
            dayOfWeek = local.dayOfWeek.value,
            lat = lat,
            lon = lon,
            placeName = prefs?.placeLabel?.ifBlank { null },
            // ⚠️ Left at rest, and that is a measurement rather than a default: a tower PC is not
            // going anywhere. The rules that ask whether you are settled are therefore correct here,
            // which is why the study advisories can fire at all.
            tempC = wx?.current?.temperatureC,
            precipChancePct = wx?.daily?.firstOrNull()?.precipProbabilityMax,
            uvIndex = wx?.daily?.firstOrNull()?.uvIndexMax,
            windKmh = wx?.current?.windKmh,
            humidityPct = wx?.current?.humidity,
            dewPointC = wx?.current?.dewPointC,
            gustKmh = wx?.current?.gustKmh,
            overnightLowC = overnightLow,
            movers = movers,
            emergencyHeadline = emergency,
            kpIndex = kp,
            reviewsDue = due,
            studyStreakDays = progress?.streakDays ?: 0,
            studiedToday = studiedToday,
            shakyGuideTitle = shaky?.first,
            shakyGuideDetail = shaky?.second,
        )
    }
}

/**
 * What this machine deliberately cannot advise on, and why.
 *
 * Written out because the alternative is silence that reads as a bug. Someone who uses the phone will
 * notice the departure reminders and the low-battery nudge are missing, and the honest answer is that
 * a tower PC has no calendar of yours, no battery, no accelerometer and no microphone.
 */
private val MUTED = listOf(
    "when to leave for an appointment — no calendar on this machine",
    "battery and storage — nothing here runs on a battery",
    "a good moment to start a task — no task board on this machine",
    "the room around you — no ambient sensors",
)

@Composable
fun AdvisoriesScreen(
    vm: AdvisoriesViewModel,
    onOpenScreen: (Screen) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state: AdvisoriesState by vm.state.collectAsState()
    val c = Pulse.colors

    LaunchedEffect(Unit) { vm.ensureLoaded() }

    Column(modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LcarsHeaderBar(
                "Advisories",
                Modifier.weight(1f),
                trailing = if (state.insights.isEmpty()) null else "${state.insights.size} STANDING",
            )
            LcarsGhostButton("REFRESH", { vm.refresh() })
        }
        LcarsBusyBar(active = state.loading, modifier = Modifier.fillMaxWidth())

        LazyColumn(
            Modifier.fillMaxSize().padding(top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item {
                LcarsFrame(Modifier.fillMaxWidth(), accent = c.accent) {
                    Text(
                        state.briefing.ifBlank { "Reading the signals…" },
                        fontFamily = JetBrainsMono, fontSize = 12.sp, lineHeight = 17.sp, color = c.ink,
                    )
                }
            }

            if (!state.located) {
                item {
                    LcarsFrame(Modifier.fillMaxWidth(), accent = c.amber) {
                        Text(
                            "This machine does not know where it is, so the weather and daylight " +
                                "advisories cannot run. Open SETTINGS and either let it guess from " +
                                "your connection or type a latitude and longitude.",
                            fontFamily = JetBrainsMono, fontSize = 12.sp, lineHeight = 17.sp, color = c.ink,
                        )
                    }
                }
            }

            val list = state.insights
            if (list.isEmpty()) {
                if (!state.loading) {
                    item {
                        LcarsFrame(Modifier.fillMaxWidth()) {
                            Text(
                                "All quiet — nothing needs you right now.",
                                fontFamily = JetBrainsMono, fontSize = 12.sp, color = c.muted,
                            )
                        }
                    }
                }
            } else {
                item { FocusCard(list.first(), onOpenScreen) }
                if (list.size > 1) {
                    item {
                        Text(
                            "ALSO ON THE RADAR",
                            fontFamily = JetBrainsMono, fontSize = 9.sp, letterSpacing = 1.2.sp,
                            fontWeight = FontWeight.Bold, color = c.accent,
                            modifier = Modifier.padding(top = 8.dp, start = 2.dp),
                        )
                    }
                    items(list.drop(1), key = { it.id }) { InsightCard(it, onOpenScreen) }
                }
            }

            item {
                Column(Modifier.padding(top = 16.dp, bottom = 16.dp)) {
                    Text(
                        "NOT WATCHED FROM HERE",
                        fontFamily = JetBrainsMono, fontSize = 9.sp, letterSpacing = 1.2.sp,
                        fontWeight = FontWeight.Bold, color = c.muted,
                    )
                    MUTED.forEach {
                        Text(
                            "· $it",
                            fontFamily = JetBrainsMono, fontSize = 10.sp, lineHeight = 15.sp, color = c.faint,
                            modifier = Modifier.padding(top = 3.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FocusCard(ins: Insight, onOpenScreen: (Screen) -> Unit) {
    val c = Pulse.colors
    val col = urgencyColor(ins.urgency)
    val target = ins.actionRoute?.let(::screenForRoute)
    Column(
        Modifier.fillMaxWidth()
            .clip(lcarsBlockShape(sweep = 28.dp, corner = LcarsCorner.TopStart))
            .background(col.copy(alpha = 0.12f))
            // ⚠️ `clickable` on the modifier rather than an `onClick` parameter on the frame — the kit
            // has none, and adding one for this caller would be a change to a primitive that thirty
            // screens draw. The same choice the wildlife list makes.
            .clickable(enabled = target != null) { target?.let(onOpenScreen) }
            .padding(14.dp),
    ) {
        Text(
            "FOCUS · ${ins.kind.name} · ${ins.urgency.name}",
            fontFamily = JetBrainsMono, fontSize = 9.sp, letterSpacing = 1.sp,
            fontWeight = FontWeight.Bold, color = col,
        )
        Text(
            ins.title,
            fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 20.sp, color = c.ink,
            modifier = Modifier.padding(top = 4.dp),
        )
        Text(
            ins.detail,
            fontFamily = JetBrainsMono, fontSize = 12.sp, lineHeight = 17.sp, color = c.ink2,
            modifier = Modifier.padding(top = 5.dp),
        )
        SourceRow(ins)
        if (target != null) {
            Text(
                "▸ OPEN",
                fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 12.sp,
                letterSpacing = 1.sp, color = col,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun InsightCard(ins: Insight, onOpenScreen: (Screen) -> Unit) {
    val c = Pulse.colors
    val col = urgencyColor(ins.urgency)
    val target = ins.actionRoute?.let(::screenForRoute)
    Row(
        Modifier.fillMaxWidth()
            .clip(lcarsBlockShape(sweep = 18.dp, corner = LcarsCorner.TopStart))
            .background(c.raise.copy(alpha = 0.5f))
            .clickable(enabled = target != null) { target?.let(onOpenScreen) }
            .padding(11.dp),
    ) {
        Box(Modifier.width(3.dp).height(40.dp).background(col))
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Text(
                "${ins.kind.name} · ${ins.urgency.name}",
                fontFamily = JetBrainsMono, fontSize = 9.sp, letterSpacing = 0.8.sp, color = col,
            )
            Text(
                ins.title,
                fontFamily = ChakraPetch, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = c.ink,
                modifier = Modifier.padding(top = 2.dp),
            )
            Text(
                ins.detail,
                fontFamily = JetBrainsMono, fontSize = 11.sp, lineHeight = 16.sp, color = c.ink2,
                modifier = Modifier.padding(top = 2.dp),
            )
            SourceRow(ins)
        }
    }
}

/** Which signal domains combined to fire it — the transparency the phone's cards carry too. */
@Composable
private fun SourceRow(ins: Insight) {
    if (ins.sources.isEmpty()) return
    Text(
        "⌁ " + ins.sources.joinToString(" · "),
        fontFamily = JetBrainsMono, fontSize = 9.sp, color = Pulse.colors.muted,
        modifier = Modifier.padding(top = 5.dp),
    )
}

/**
 * ⚠️ The five values themselves live in the shared core, beside the rules that produce the urgency.
 * Both applications draw this stream, and five hex values written out twice is exactly how a palette
 * drifts — which this project has had to correct four times.
 */
private fun urgencyColor(u: Urgency): Color = Color(Oracle.urgencyArgb(u))
