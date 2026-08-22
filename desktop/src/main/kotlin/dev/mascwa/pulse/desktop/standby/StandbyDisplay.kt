package dev.mascwa.pulse.desktop.standby

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import dev.mascwa.pulse.core.telemetry.Oracle
import dev.mascwa.pulse.desktop.theme.ChakraPetch
import dev.mascwa.pulse.desktop.theme.JetBrainsMono
import dev.mascwa.pulse.desktop.theme.Orbitron
import dev.mascwa.pulse.desktop.theme.Pulse
import java.util.Locale

/**
 * The standby display — what the console shows when nobody is at it.
 *
 * ⚠️ **This composable is a pure function of [state] and [scale]. No effects, no flows, no
 * suspending reads, no `remember` that matters.** That is the load-bearing rule of the whole
 * feature, not a style preference: the lock-screen wallpaper is produced by rendering this exactly
 * once, off-screen, through `renderComposeScene`, where a `LaunchedEffect` never gets a frame to
 * run in. Anything this fetched for itself would be present in the live HUD and blank on the lock
 * screen — three surfaces silently disagreeing about one machine.
 *
 * [scale] is the only difference between a 420 dp HUD panel and a 3840 px wallpaper. Everything is
 * weighted rather than pinned, so nothing has a size it was designed at and clips outside of.
 */
@Composable
fun StandbyDisplay(
    state: StandbyState,
    layout: StandbyLayout,
    modifier: Modifier = Modifier,
) {
    val c = Pulse.colors
    val scale = layout.scale
    val pad = (18 * scale).dp

    Box(modifier.fillMaxSize().background(c.void)) {
        Row(Modifier.fillMaxSize()) {
            // The rail. Pure LCARS chrome and the one thing that says at a glance whether the
            // console is calm — it takes the lead insight's urgency colour.
            StandbyRail(state, scale)

            Column(Modifier.fillMaxSize().padding(pad), verticalArrangement = Arrangement.spacedBy(pad * 0.7f)) {
                StandbyHeader(state, scale)

                // ⚠️ `weight(1f)` on the body and NOT on the panels. A Column clips whatever
                // overflows it, silently — which is how the first cut lost two whole panels off the
                // bottom of a 1280×800 render with every test still green. The layout decides what
                // is emitted at all; nothing here is left to be cropped.
                if (layout.twoColumn) {
                    Row(
                        Modifier.fillMaxWidth().weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(pad * 0.8f),
                    ) {
                        Column(
                            Modifier.weight(1.5f).fillMaxHeight(),
                            verticalArrangement = Arrangement.spacedBy(pad * 0.6f),
                        ) {
                            OraclePanel(state, layout, Modifier.fillMaxWidth())
                            // The wire takes whatever the Oracle left. Without the weight the
                            // column ends where its content does and the rest is dead black.
                            if (layout.showWire) HeadlinePanel(state, layout, Modifier.fillMaxWidth().weight(1f))
                        }
                        Column(
                            Modifier.weight(1f).fillMaxHeight(),
                            verticalArrangement = Arrangement.spacedBy(pad * 0.6f),
                        ) {
                            WeatherPanel(state, layout, Modifier.fillMaxWidth())
                            if (layout.showMarkets) MarketPanel(state, layout, Modifier.fillMaxWidth())
                            if (layout.showStanding) SkyPanel(state, layout, Modifier.fillMaxWidth())
                        }
                    }
                } else {
                    Column(
                        Modifier.fillMaxWidth().weight(1f),
                        verticalArrangement = Arrangement.spacedBy(pad * 0.6f),
                    ) {
                        OraclePanel(state, layout, Modifier.fillMaxWidth())
                        WeatherPanel(state, layout, Modifier.fillMaxWidth())
                        if (layout.showMarkets) MarketPanel(state, layout, Modifier.fillMaxWidth())
                        if (layout.showStanding) SkyPanel(state, layout, Modifier.fillMaxWidth())
                        if (layout.showWire) HeadlinePanel(state, layout, Modifier.fillMaxWidth())
                    }
                }

                StandbyFooter(state, scale)
            }
        }
    }
}

/* ── chrome ──────────────────────────────────────────────────────────────────────────────────── */

@Composable
private fun StandbyRail(state: StandbyState, scale: Float) {
    val c = Pulse.colors
    // ⚠️ The rail's colour IS the console's condition. It comes from the lead insight's urgency
    // through the shared `Oracle.urgencyArgb`, so the display and every other surface that draws
    // this stream cannot disagree about what counts as urgent — the duplicated-palette mistake this
    // project has corrected five times.
    val accent = state.lead?.let { Color(Oracle.urgencyArgb(it.urgency)) } ?: c.accent
    Column(Modifier.fillMaxHeight().width((26 * scale).dp)) {
        Box(Modifier.fillMaxWidth().weight(2f).background(accent))
        Spacer(Modifier.height((4 * scale).dp))
        Box(Modifier.fillMaxWidth().weight(1f).background(c.magenta))
        Spacer(Modifier.height((4 * scale).dp))
        Box(Modifier.fillMaxWidth().weight(3f).background(c.violet))
        Spacer(Modifier.height((4 * scale).dp))
        Box(Modifier.fillMaxWidth().weight(1.4f).background(c.sky))
    }
}

@Composable
private fun StandbyHeader(state: StandbyState, scale: Float) {
    val c = Pulse.colors
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        Column(Modifier.weight(1f)) {
            Text(
                state.clock,
                fontFamily = Orbitron,
                fontWeight = FontWeight.Bold,
                fontSize = (44 * scale).sp,
                lineHeight = (52 * scale).sp,
                color = c.ink,
                maxLines = 1,
            )
            Text(
                listOf(state.dateLine, state.placeName).filter { it.isNotBlank() }.joinToString("  ·  "),
                fontFamily = ChakraPetch,
                fontSize = (13 * scale).sp,
                letterSpacing = (1.4 * scale).sp,
                color = c.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            state.stardate,
            fontFamily = JetBrainsMono,
            fontSize = (13 * scale).sp,
            letterSpacing = (1.4 * scale).sp,
            color = c.accent,
            maxLines = 1,
        )
    }
}

@Composable
private fun StandbyFooter(state: StandbyState, scale: Float) {
    val c = Pulse.colors
    val m = state.machine
    val bits = buildList {
        if (m.cpuLoadPct >= 0) add("CPU ${m.cpuLoadPct}%")
        if (m.memoryUsedPct >= 0) add("MEM ${m.memoryUsedPct}%")
        if (m.diskUsedPct >= 0) add("DISK ${m.diskUsedPct}%")
        if (m.uptime.isNotBlank()) add("UP ${m.uptime}")
        if (m.build.isNotBlank()) add("BUILD ${m.build}")
        if (state.freshness.isNotBlank()) add(state.freshness.uppercase())
    }
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height((2 * scale).dp).background(c.lineSoft))
        Spacer(Modifier.height((6 * scale).dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                bits.joinToString("   ·   "),
                fontFamily = JetBrainsMono,
                fontSize = (10 * scale).sp,
                letterSpacing = (1.1 * scale).sp,
                color = c.faint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            // ⚠️ Degradation is a row, never a silence. A feed that could not answer and a feed
            // with nothing to say produce the same blank panel, and only one of them is our fault.
            if (state.degraded.isNotBlank()) {
                Text(
                    state.degraded,
                    fontFamily = JetBrainsMono,
                    fontSize = (10 * scale).sp,
                    letterSpacing = (1.1 * scale).sp,
                    color = c.negative,
                    maxLines = 1,
                )
            }
        }
    }
}

/* ── panels ──────────────────────────────────────────────────────────────────────────────────── */

@Composable
private fun StandbyPanel(
    title: String,
    scale: Float,
    modifier: Modifier = Modifier,
    accent: Color? = null,
    content: @Composable () -> Unit,
) {
    val c = Pulse.colors
    Column(modifier.background(c.panel).padding((12 * scale).dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width((16 * scale).dp).height((7 * scale).dp).background(accent ?: c.accent))
            Spacer(Modifier.width((7 * scale).dp))
            Text(
                title,
                fontFamily = ChakraPetch,
                fontWeight = FontWeight.Bold,
                fontSize = (11 * scale).sp,
                letterSpacing = (2.0 * scale).sp,
                color = accent ?: c.accent,
                maxLines = 1,
            )
        }
        Spacer(Modifier.height((8 * scale).dp))
        content()
    }
}

@Composable
private fun OraclePanel(state: StandbyState, layout: StandbyLayout, modifier: Modifier) {
    val c = Pulse.colors
    val scale = layout.scale
    val lead = state.lead
    val accent = lead?.let { Color(Oracle.urgencyArgb(it.urgency)) } ?: c.accent

    StandbyPanel("THE COMPUTER", scale, modifier, accent) {
        if (lead == null) {
            Text(
                state.briefing.ifBlank { "Nothing needs your attention." },
                fontFamily = ChakraPetch,
                fontSize = (16 * scale).sp,
                lineHeight = (21 * scale).sp,
                color = c.muted,
            )
            return@StandbyPanel
        }
        Text(
            lead.title,
            fontFamily = ChakraPetch,
            fontWeight = FontWeight.Bold,
            fontSize = (23 * scale).sp,
            lineHeight = (28 * scale).sp,
            color = accent,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (lead.detail.isNotBlank()) {
            Spacer(Modifier.height((3 * scale).dp))
            Text(
                lead.detail,
                fontFamily = ChakraPetch,
                fontSize = (14 * scale).sp,
                lineHeight = (19 * scale).sp,
                color = c.ink2,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        state.rest.take(layout.insights).forEach { ins ->
            Spacer(Modifier.height((7 * scale).dp))
            Row(verticalAlignment = Alignment.Top) {
                Box(
                    Modifier.padding(top = (5 * scale).dp)
                        .width((6 * scale).dp).height((6 * scale).dp)
                        .background(Color(Oracle.urgencyArgb(ins.urgency))),
                )
                Spacer(Modifier.width((8 * scale).dp))
                Text(
                    ins.title,
                    fontFamily = ChakraPetch,
                    fontSize = (13 * scale).sp,
                    color = c.ink2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun HeadlinePanel(state: StandbyState, layout: StandbyLayout, modifier: Modifier) {
    val c = Pulse.colors
    val scale = layout.scale
    StandbyPanel("WIRE", scale, modifier, c.sky) {
        Column(verticalArrangement = Arrangement.spacedBy((5 * scale).dp)) {
            state.headlines.take(layout.headlines).forEach {
                Text(
                    it,
                    fontFamily = ChakraPetch,
                    fontSize = (12 * scale).sp,
                    color = c.ink2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun WeatherPanel(state: StandbyState, layout: StandbyLayout, modifier: Modifier) {
    val c = Pulse.colors
    val scale = layout.scale
    if (state.temperature.isBlank() && state.condition.isBlank()) return
    StandbyPanel("CONDITIONS", scale, modifier, c.sky) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                state.temperature,
                fontFamily = Orbitron,
                fontWeight = FontWeight.Bold,
                fontSize = (30 * scale).sp,
                lineHeight = (36 * scale).sp,
                color = c.ink,
                maxLines = 1,
            )
            Spacer(Modifier.width((10 * scale).dp))
            Column(Modifier.weight(1f)) {
                Text(
                    state.condition,
                    fontFamily = ChakraPetch,
                    fontSize = (13 * scale).sp,
                    color = c.ink2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (state.feelsLike.isNotBlank()) {
                    Text(
                        state.feelsLike,
                        fontFamily = ChakraPetch,
                        fontSize = (11 * scale).sp,
                        color = c.amber,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        if (state.weatherDetail.isNotBlank()) {
            Spacer(Modifier.height((4 * scale).dp))
            Text(
                state.weatherDetail,
                fontFamily = JetBrainsMono,
                fontSize = (10 * scale).sp,
                letterSpacing = (0.8 * scale).sp,
                color = c.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (layout.showSparkline && state.hourlyTemps.size >= 4) {
            Spacer(Modifier.height((8 * scale).dp))
            Sparkline(state.hourlyTemps, c.sky, Modifier.fillMaxWidth().height((26 * scale).dp))
        }
    }
}

@Composable
private fun MarketPanel(state: StandbyState, layout: StandbyLayout, modifier: Modifier) {
    val c = Pulse.colors
    val scale = layout.scale
    val mood = state.mood ?: return
    StandbyPanel("MARKETS", scale, modifier, c.violet) {
        Text(
            mood.headline.uppercase(),
            fontFamily = ChakraPetch,
            fontWeight = FontWeight.Bold,
            fontSize = (15 * scale).sp,
            color = if (mood.upShare >= 0.55) c.positive else if (mood.upShare <= 0.45) c.negative else c.ink2,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            "NET ${signed(mood.netChangePct)}%   ·   ${mood.up}▲ ${mood.down}▼ ${mood.flat}=",
            fontFamily = JetBrainsMono,
            fontSize = (10 * scale).sp,
            letterSpacing = (1.0 * scale).sp,
            color = c.muted,
            maxLines = 1,
        )
        Spacer(Modifier.height((6 * scale).dp))
        state.movers.take(layout.movers).forEach { (label, pct) ->
            Row(Modifier.fillMaxWidth()) {
                Text(
                    label,
                    fontFamily = ChakraPetch,
                    fontSize = (12 * scale).sp,
                    color = c.ink2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "${signed(pct)}%",
                    fontFamily = JetBrainsMono,
                    fontSize = (12 * scale).sp,
                    color = if (pct >= 0) c.positive else c.negative,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun SkyPanel(state: StandbyState, layout: StandbyLayout, modifier: Modifier) {
    val c = Pulse.colors
    val scale = layout.scale
    val lines = buildList {
        if (state.issLine.isNotBlank()) add(state.issLine to c.accent)
        if (state.spaceWeather.isNotBlank()) add(state.spaceWeather.uppercase() to c.ink2)
        if (state.reviewsDue > 0) {
            add("${state.reviewsDue} REVIEW${if (state.reviewsDue == 1) "" else "S"} DUE" to c.amber)
        }
        if (state.studyStreakDays > 1) add("${state.studyStreakDays}-DAY STUDY STREAK" to c.muted)
        if (state.nowPlaying.isNotBlank()) add("♪ ${state.nowPlaying}" to c.magenta)
    }
    if (lines.isEmpty()) return
    StandbyPanel("STANDING", scale, modifier, c.amber) {
        Column(verticalArrangement = Arrangement.spacedBy((4 * scale).dp)) {
            // ⚠️ Capped by the layout rather than left to overflow. A Column clips silently, and the
            // fifth line disappearing off the bottom of a panel looks exactly like a feed that had
            // nothing to say — the distinction this whole subsystem exists to preserve.
            lines.take(layout.standing).forEach { (text, colour) ->
                Text(
                    text,
                    fontFamily = ChakraPetch,
                    fontSize = (12 * scale).sp,
                    color = colour,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/* ── bits ────────────────────────────────────────────────────────────────────────────────────── */

/**
 * A compact temperature trace.
 *
 * ⚠️ Deliberately **not** the shared `LcarsTimeChart`. That one is built for a full screen and
 * draws a labelled time axis; at HUD scale the axis is most of the ink and none of the information.
 * The trace is normalised to its own range because what a reader wants from a strip this size is
 * the shape of the next day, not the absolute value — which the panel states above it in words.
 */
@Composable
private fun Sparkline(values: List<Double>, colour: Color, modifier: Modifier) {
    val c = Pulse.colors
    Canvas(modifier.background(c.raise)) {
        if (values.size < 2) return@Canvas
        val lo = values.min()
        val hi = values.max()
        val span = (hi - lo).takeIf { it > 0.0001 } ?: 1.0
        val stepX = size.width / (values.size - 1)
        var previous: Offset? = null
        values.forEachIndexed { i, v ->
            val point = Offset(
                x = stepX * i,
                y = (size.height - (((v - lo) / span) * size.height)).toFloat(),
            )
            previous?.let { drawLine(colour, it, point, strokeWidth = size.height * 0.08f) }
            previous = point
        }
    }
}

/** Sign always shown, so a rise and a fall are told apart by more than colour. */
private fun signed(v: Double): String = (if (v >= 0) "+" else "") + String.format(Locale.US, "%.2f", v)


/** Kept so a caller can size a HUD from the same numbers the display lays out at. */
val StandbyHudSize: Pair<Dp, Dp> = 460.dp to 560.dp
