package dev.mascwa.pulse.feature.tacnet

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mascwa.pulse.feature.common.PipFrame
import dev.mascwa.pulse.feature.common.PipHeader
import dev.mascwa.pulse.ui.theme.ChakraPetch
import dev.mascwa.pulse.ui.theme.JetBrainsMono
import dev.mascwa.pulse.ui.theme.Pulse
import kotlin.math.sin

/** The RADIO feed — a Fallout-style tuner: a now-playing readout + a list of stations you tune to. */
@Composable
fun RadioBody(vm: RadioViewModel, modifier: Modifier = Modifier) {
    val state by vm.state.collectAsStateWithLifecycle()
    val c = Pulse.colors
    val tuned = state.index?.let { vm.stations.getOrNull(it) }

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
    ) {
        PipHeader("Tuner")
        PipFrame(Modifier.fillMaxWidth()) {
            Column {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    val label = when (state.status) {
                        RadioViewModel.Status.ON_AIR -> "▸ ON AIR"
                        RadioViewModel.Status.TUNING -> "··· TUNING"
                        RadioViewModel.Status.ERROR -> "✕ NO SIGNAL"
                        RadioViewModel.Status.IDLE -> "○ STANDBY"
                    }
                    val labelColor = when (state.status) {
                        RadioViewModel.Status.ERROR -> c.amber
                        RadioViewModel.Status.ON_AIR -> c.accent
                        else -> c.muted
                    }
                    Text(label, fontFamily = JetBrainsMono, fontSize = 11.sp, letterSpacing = 1.5.sp, color = labelColor)
                    if (state.status == RadioViewModel.Status.ON_AIR) {
                        SignalBars(c.accent, Modifier.padding(start = 10.dp).size(width = 26.dp, height = 14.dp))
                    }
                }
                Text(
                    tuned?.name ?: "— SELECT A STATION —",
                    fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 24.sp, color = c.ink,
                    modifier = Modifier.padding(top = 6.dp),
                )
                Text(
                    tuned?.band ?: "FREE LISTENER-SUPPORTED STREAMS",
                    fontFamily = JetBrainsMono, fontSize = 10.sp, letterSpacing = 1.sp, color = c.accent,
                )
            }
        }

        PipHeader("Stations")
        vm.stations.forEachIndexed { i, st ->
            val active = state.index == i
            val onAir = active && state.status == RadioViewModel.Status.ON_AIR
            val tuningThis = active && state.status == RadioViewModel.Status.TUNING
            PipFrame(
                Modifier.fillMaxWidth().padding(vertical = 3.dp).clickable { vm.toggle(i) },
                accent = if (active) c.accent else c.line,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (onAir || tuningThis) "❚❚" else "▶",
                        fontFamily = JetBrainsMono, fontSize = 14.sp,
                        color = if (active) c.accent else c.muted,
                    )
                    Column(Modifier.padding(start = 12.dp)) {
                        Text(st.name, fontFamily = ChakraPetch, fontWeight = FontWeight.SemiBold, fontSize = 16.sp,
                            color = if (active) c.ink else c.ink2)
                        Text(st.band, fontFamily = JetBrainsMono, fontSize = 9.sp, letterSpacing = 0.8.sp, color = c.muted)
                    }
                }
            }
        }

        Text(
            "Streams: SomaFM — free & listener-supported. Audio plays while the PIP-BOY is open " +
                "(background play is coming). Needs a connection.",
            fontFamily = JetBrainsMono, fontSize = 9.sp, color = c.muted,
            modifier = Modifier.padding(top = 12.dp, bottom = 24.dp),
        )
    }
}

/** A small animated equaliser, drawn analytically (no retained buffers) while a station is on air. */
@Composable
private fun SignalBars(color: androidx.compose.ui.graphics.Color, modifier: Modifier) {
    val t by rememberInfiniteTransition(label = "eq").animateFloat(
        0f, 1f, infiniteRepeatable(tween(900), RepeatMode.Restart), label = "eqp",
    )
    Canvas(modifier.height(14.dp)) {
        val bars = 4
        val gap = size.width * 0.12f
        val bw = (size.width - gap * (bars - 1)) / bars
        for (i in 0 until bars) {
            val phase = t * 6.2831855f + i * 1.3f
            val h = (0.35f + 0.65f * (0.5f + 0.5f * sin(phase))) * size.height
            val x = i * (bw + gap)
            drawRect(color, topLeft = Offset(x, size.height - h), size = androidx.compose.ui.geometry.Size(bw, h))
        }
    }
}
