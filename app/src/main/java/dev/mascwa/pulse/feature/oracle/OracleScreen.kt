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
import dev.mascwa.pulse.core.telemetry.Insight
import dev.mascwa.pulse.core.telemetry.Urgency
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
        navigationIcon = {
            if (onBack != null) IconButton(onClick = onBack) { Icon(LcarsIcons.ArrowBack, "Back") }
        },
    ) { innerPadding ->
        Box(Modifier.padding(innerPadding).fillMaxSize()) {
            when {
                state.loading && state.insights.isEmpty() -> LoadingState()
                else -> LazyColumn(
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 28.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    item { BriefingCard(state.briefing, onRefresh = { vm.refresh() }) }
                    val list = state.insights
                    if (list.isEmpty()) {
                        item {
                            Text(
                                "All quiet — nothing needs you right now. J.A.R.V.I.S. is watching.",
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

private fun urgencyColor(u: Urgency): Color = when (u) {
    Urgency.CRITICAL -> Color(0xFFE0331A)
    Urgency.URGENT -> Color(0xFFE0661A)
    Urgency.IMPORTANT -> Color(0xFFE0A21A)
    Urgency.NOTABLE -> Color(0xFF35C46A)
    Urgency.AMBIENT -> Color(0xFF7C8894)
}
