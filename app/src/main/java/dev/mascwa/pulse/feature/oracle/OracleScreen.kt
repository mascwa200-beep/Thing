package dev.mascwa.pulse.feature.oracle

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import dev.mascwa.pulse.feature.common.LcarsIcons
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mascwa.pulse.core.telemetry.DayAhead
import dev.mascwa.pulse.core.telemetry.Insight
import dev.mascwa.pulse.core.telemetry.Oracle
import dev.mascwa.pulse.core.telemetry.Urgency
import dev.mascwa.pulse.data.oracle.DayAheadEngine
import dev.mascwa.pulse.feature.common.LcarsCorner
import dev.mascwa.pulse.feature.common.LoadingState
import dev.mascwa.pulse.feature.common.PulseScaffold
import dev.mascwa.pulse.feature.common.lcarsBlockShape
import dev.mascwa.pulse.ui.theme.ChakraPetch
import dev.mascwa.pulse.ui.theme.JetBrainsMono
import dev.mascwa.pulse.ui.theme.Pulse

/**
 * The ORACLE HUD — J.A.R.V.I.S.'s foresight, made browsable: a one-line briefing, the single FOCUS insight
 * writ large, then the full ranked stream. Each card shows which signal domains combined to fire it (for
 * transparency) and, where it can, deep-links you straight to acting on it.
 */
@Composable
fun OracleScreen(vm: OracleViewModel, onOpenRoute: (String) -> Unit, onBack: (() -> Unit)? = null) {
    val state by vm.state.collectAsStateWithLifecycle()
    val c = Pulse.colors

    PulseScaffold(
        title = "Oracle",
        onBack = onBack,
    ) { innerPadding ->
        Box(Modifier.padding(innerPadding).fillMaxSize()) {
            when {
                state.loading && state.insights.isEmpty() -> LoadingState()
                else -> LazyColumn(
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 28.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    item { BriefingCard(state.briefing, onRefresh = { vm.refresh() }) }
                    if (state.learned.isNotBlank()) {
                        item {
                            Text(
                                "⌁ " + state.learned,
                                fontFamily = JetBrainsMono, fontSize = 9.sp, color = c.muted,
                                modifier = Modifier.padding(horizontal = 4.dp),
                            )
                        }
                    }
                    val list = state.insights
                    if (list.isEmpty()) {
                        item {
                            Text(
                                // "Nothing needs you" would contradict the timeline below it when the
                                // day has a departure in it. With one, this line is only claiming there
                                // is nothing to act on *now*.
                                if (state.dayAhead.isEmpty())
                                    "All quiet — nothing needs you right now. The computer is watching."
                                else
                                    "Nothing needs you this minute. The rest of the day is below.",
                                fontFamily = JetBrainsMono, fontSize = 12.sp, color = c.muted,
                                modifier = Modifier.padding(12.dp),
                            )
                        }
                    } else {
                        item { FocusCard(list.first(), onOpenRoute) }
                        if (list.size > 1) {
                            item {
                                Text(
                                    "ALSO ON THE RADAR",
                                    fontFamily = JetBrainsMono, fontSize = 9.sp, letterSpacing = 1.2.sp,
                                    fontWeight = FontWeight.Bold, color = c.accent,
                                    modifier = Modifier.padding(top = 6.dp, start = 2.dp),
                                )
                            }
                            items(list.drop(1), key = { it.id }) { ins -> InsightCard(ins, onOpenRoute) }
                        }
                    }

                    // The rest of the day. A different question from everything above it — those are
                    // about now, this is about what is coming — so it gets its own heading rather
                    // than being mixed into the ranked stream.
                    if (state.dayAhead.isNotEmpty()) {
                        item {
                            Text(
                                "DAY AHEAD",
                                fontFamily = JetBrainsMono, fontSize = 9.sp, letterSpacing = 1.2.sp,
                                fontWeight = FontWeight.Bold, color = c.accent,
                                modifier = Modifier.padding(top = 12.dp, start = 2.dp),
                            )
                        }
                        // Keyed on position as well as content: two calendar accounts syncing the
                        // same entry produce two identical beats, and a repeated key is a crash.
                        itemsIndexed(
                            state.dayAhead,
                            key = { i, b -> "$i-${b.atMs}-${b.kind}" },
                        ) { _, beat -> BeatRow(beat) }
                    }
                }
            }
        }
    }
}

@Composable
private fun BriefingCard(briefing: String, onRefresh: () -> Unit) {
    val c = Pulse.colors
    Column(
        Modifier.fillMaxWidth().clip(lcarsBlockShape(sweep = 8.dp, corner = LcarsCorner.TopStart)).background(c.accent.copy(alpha = 0.08f))
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("◉ ORACLE", fontFamily = ChakraPetch, fontWeight = FontWeight.Black, fontSize = 14.sp,
                letterSpacing = 2.sp, color = c.accent)
            Spacer(Modifier.weight(1f))
            Text("↻ REFRESH", fontFamily = JetBrainsMono, fontSize = 9.sp, color = c.muted,
                modifier = Modifier.clip(lcarsBlockShape(sweep = 4.dp, corner = LcarsCorner.TopStart)).clickable(onClick = onRefresh)
                    .padding(horizontal = 8.dp, vertical = 4.dp))
        }
        Text(
            briefing.ifBlank { "Reading the signals…" },
            fontFamily = JetBrainsMono, fontSize = 12.sp, color = c.ink,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
private fun FocusCard(ins: Insight, onOpenRoute: (String) -> Unit) {
    val c = Pulse.colors
    val col = urgencyColor(ins.urgency)
    Column(
        Modifier.fillMaxWidth().clip(lcarsBlockShape(sweep = 8.dp, corner = LcarsCorner.TopStart))
            .background(col.copy(alpha = 0.12f))
            .clickable(enabled = ins.actionRoute != null) { ins.actionRoute?.let(onOpenRoute) }
            .padding(14.dp),
    ) {
        Text("FOCUS · ${ins.kind.name} · ${ins.urgency.name}", fontFamily = JetBrainsMono, fontSize = 8.sp,
            letterSpacing = 1.sp, fontWeight = FontWeight.Bold, color = col)
        Text(ins.title, fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 19.sp, color = c.ink,
            modifier = Modifier.padding(top = 4.dp))
        Text(ins.detail, fontFamily = JetBrainsMono, fontSize = 12.sp, color = c.ink2,
            modifier = Modifier.padding(top = 5.dp))
        SourceRow(ins)
        if (ins.actionRoute != null) {
            Text("▸ ACT", fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 12.sp,
                letterSpacing = 1.sp, color = col, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

@Composable
private fun InsightCard(ins: Insight, onOpenRoute: (String) -> Unit) {
    val c = Pulse.colors
    val col = urgencyColor(ins.urgency)
    Row(
        Modifier.fillMaxWidth().clip(lcarsBlockShape(sweep = 6.dp, corner = LcarsCorner.TopStart)).background(c.raise.copy(alpha = 0.5f))
            .clickable(enabled = ins.actionRoute != null) { ins.actionRoute?.let(onOpenRoute) }
            .padding(10.dp),
    ) {
        Box(Modifier.width(3.dp).height(38.dp).clip(lcarsBlockShape(sweep = 2.dp, corner = LcarsCorner.TopStart)).background(col))
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Text("${ins.kind.name} · ${ins.urgency.name}", fontFamily = JetBrainsMono, fontSize = 8.sp,
                letterSpacing = 0.8.sp, color = col)
            Text(ins.title, fontFamily = ChakraPetch, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = c.ink,
                modifier = Modifier.padding(top = 2.dp))
            Text(ins.detail, fontFamily = JetBrainsMono, fontSize = 11.sp, color = c.ink2,
                modifier = Modifier.padding(top = 2.dp))
            SourceRow(ins)
        }
    }
}

@Composable
private fun SourceRow(ins: Insight) {
    val c = Pulse.colors
    if (ins.sources.isEmpty()) return
    Text(
        "⌁ " + ins.sources.joinToString(" · "),
        fontFamily = JetBrainsMono, fontSize = 8.sp, color = c.muted,
        modifier = Modifier.padding(top = 5.dp),
    )
}

/**
 * How urgent an insight looks.
 *
 * `internal` rather than private because Home shows the same insights and must colour them the same
 * way. The values themselves now live beside the rules that produce the urgency — the desktop draws
 * this same stream, and a third copy of five hex values is how a palette drifts.
 */
internal fun urgencyColor(u: Urgency): Color = Color(Oracle.urgencyArgb(u))

/**
 * One entry on the projected day.
 *
 * A spine down the left with a coloured node, so the column reads as a sequence rather than a list of
 * cards — the ordering is the information here. Departures and conflicts are the reason anyone reads
 * this, so they carry weight; the commitments themselves stay quiet and act as the ruler between them.
 */
@Composable
private fun BeatRow(beat: DayAhead.Beat) {
    val c = Pulse.colors
    val tint = when (beat.kind) {
        DayAhead.BeatKind.CONFLICT -> c.negative
        DayAhead.BeatKind.DEPART -> c.amber
        DayAhead.BeatKind.FOCUS -> c.positive
        else -> c.muted
    }
    val loud = beat.kind == DayAhead.BeatKind.DEPART || beat.kind == DayAhead.BeatKind.CONFLICT

    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        // The spine: a fixed-width clock column, then the node.
        Text(
            DayAheadEngine.clock(beat.atMs),
            fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.muted,
            modifier = Modifier.width(40.dp).padding(top = 3.dp),
        )
        Box(
            Modifier.width(3.dp).height(if (loud) 34.dp else 22.dp)
                .clip(lcarsBlockShape(sweep = 2.dp, corner = LcarsCorner.TopStart))
                .background(tint),
        )
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Text(
                beat.title,
                fontFamily = ChakraPetch,
                fontWeight = if (loud) FontWeight.Bold else FontWeight.Normal,
                fontSize = if (loud) 14.sp else 13.sp,
                color = if (loud) c.ink else c.ink2,
            )
            if (beat.detail.isNotBlank()) {
                Text(
                    beat.detail,
                    fontFamily = JetBrainsMono, fontSize = 10.sp, lineHeight = 14.sp, color = c.muted,
                    modifier = Modifier.padding(top = 1.dp),
                )
            }
            // What it rests on. A departure from a straight-line guess is a weaker claim than one
            // from a road route, and the screen has to carry that difference rather than flatten it.
            if (beat.confidence == DayAhead.Confidence.ROUGH) {
                Text(
                    "⌁ rough estimate",
                    fontFamily = JetBrainsMono, fontSize = 8.sp, letterSpacing = 0.6.sp, color = c.muted,
                    modifier = Modifier.padding(top = 1.dp),
                )
            }
        }
    }
}
