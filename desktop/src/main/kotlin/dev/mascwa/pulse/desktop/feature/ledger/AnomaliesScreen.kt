package dev.mascwa.pulse.desktop.feature.ledger

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
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mascwa.pulse.core.telemetry.ElapsedPhrase
import dev.mascwa.pulse.core.telemetry.Novelty
import dev.mascwa.pulse.desktop.Screen
import dev.mascwa.pulse.desktop.ledger.MetricRegistry
import dev.mascwa.pulse.desktop.theme.ChakraPetch
import dev.mascwa.pulse.desktop.theme.JetBrainsMono
import dev.mascwa.pulse.desktop.theme.LcarsBusyBar
import dev.mascwa.pulse.desktop.theme.LcarsFrame
import dev.mascwa.pulse.desktop.theme.LcarsGhostButton
import dev.mascwa.pulse.desktop.theme.LcarsHeaderBar
import dev.mascwa.pulse.desktop.theme.LcarsSparkline
import dev.mascwa.pulse.desktop.theme.Pulse
import java.util.Locale
import kotlin.math.roundToLong

/**
 * The long watch, read back: every domain at once, ranked by how strange it is.
 *
 * Everything on this page is judged against what **this machine** has watched that metric do, not
 * against a published guideline or a threshold somebody chose. A Kp of 4 means nothing to a person;
 * "Kp 4 — highest in 23 days" means something instantly, and the second is the only one a recorder
 * can say.
 */
@Composable
fun AnomaliesScreen(
    vm: AnomaliesViewModel,
    onOpenScreen: (Screen) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state: AnomaliesState by vm.state.collectAsState()
    val c = Pulse.colors

    LaunchedEffect(Unit) { vm.ensureLoaded() }

    Column(modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LcarsHeaderBar(
                "Anomalies",
                Modifier.weight(1f),
                trailing = if (state.tested == 0) null else "${state.anomalies.size} OF ${state.tested}",
            )
            LcarsGhostButton("REFRESH", { vm.refresh() })
        }
        LcarsBusyBar(active = state.loading, modifier = Modifier.fillMaxWidth())

        LazyColumn(
            Modifier.fillMaxSize().padding(top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item { Preamble(state) }

            if (state.newestMs > 0L && state.oldestMs < state.newestMs) {
                item { Scrubber(state, vm) }
            }

            if (state.anomalies.isEmpty() && !state.loading) {
                item {
                    LcarsFrame(Modifier.fillMaxWidth()) {
                        Text(
                            if (state.tested == 0) {
                                "Nothing has been recorded here yet. The long watch fills this page; " +
                                    "it collects every few minutes while the console is open, and on its " +
                                    "own schedule while it is not."
                            } else {
                                "Everything is sitting in its usual range."
                            },
                            fontFamily = JetBrainsMono, fontSize = 12.sp, lineHeight = 17.sp, color = c.muted,
                        )
                    }
                }
            }

            items(state.anomalies, key = { it.id }) { AnomalyRow(it, onOpenScreen) }

            if (state.quiet.isNotEmpty()) {
                item { SectionLabel("IN ITS USUAL RANGE", c.muted) }
                items(state.quiet, key = { "q-${it.id}" }) { QuietRow(it) }
            }

            if (state.notYet.isNotEmpty()) {
                item { SectionLabel("NOT ENOUGH HISTORY YET", c.faint) }
                items(state.notYet, key = { "n-${it.spec.id}" }) { NotYetRow(it) }
            }

            item { Box(Modifier.height(24.dp)) }
        }
    }
}

/**
 * ⚠️ The false-alarm line is not decoration; it is what separates this from a horoscope.
 *
 * Score ninety metrics and a one-in-sixteen reading turns up several times over by chance alone. A
 * wall that never says so is training its reader to believe in noise, so the arithmetic
 * ([Novelty.expectedFalseAlarms]) is printed where the count is.
 */
@Composable
private fun Preamble(state: AnomaliesState) {
    val c = Pulse.colors
    LcarsFrame(Modifier.fillMaxWidth(), accent = c.accent) {
        Column {
            Text(
                if (state.tested == 0) {
                    "Watching nothing yet."
                } else {
                    "Judging ${state.tested} readings against their own history. " +
                        "At this threshold about ${fmt(state.falseAlarms, 1)} of what is listed is chance."
                },
                fontFamily = JetBrainsMono, fontSize = 12.sp, lineHeight = 17.sp, color = c.ink,
            )
            Text(
                buildString {
                    append(if (state.watching) "Watch running" else "⚠ The long watch is switched off")
                    if (state.oldestMs > 0L) {
                        append(" · recording since ")
                        append(ElapsedPhrase.describe(state.newestMs - state.oldestMs))
                        append(" ago")
                    }
                    if (state.ledgerBytes > 0L) append(" · ${bytes(state.ledgerBytes)} on disk")
                },
                fontFamily = JetBrainsMono, fontSize = 10.sp, color = if (state.watching) c.faint else c.amber,
                modifier = Modifier.padding(top = 5.dp),
            )
        }
    }
}

/**
 * A world time machine, and it costs one slider.
 *
 * [Novelty.score] judges a reading against the history *before it*, so rendering the wall as it stood
 * at any past moment is a matter of handing it an older reading. Nothing else in the app can do this,
 * because nothing else kept the data.
 */
@Composable
private fun Scrubber(state: AnomaliesState, vm: AnomaliesViewModel) {
    val c = Pulse.colors
    val span = (state.newestMs - state.oldestMs).coerceAtLeast(1L)
    // ⚠️ Held locally so dragging is smooth, and only committed on release: rebuilding the whole wall
    // on every pixel of drag would score every metric a hundred times a second.
    var position by remember(state.newestMs, state.oldestMs) {
        mutableStateOf(((state.asOfMs - state.oldestMs).toDouble() / span).toFloat().coerceIn(0f, 1f))
    }
    val at = state.oldestMs + (span * position.toDouble()).roundToLong()

    LcarsFrame(Modifier.fillMaxWidth(), accent = c.sky) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "AS IT STOOD",
                    fontFamily = JetBrainsMono, fontSize = 9.sp, letterSpacing = 1.2.sp,
                    fontWeight = FontWeight.Bold, color = c.sky,
                )
                Text(
                    if (position >= 0.999f) "  now" else "  ${ElapsedPhrase.describe(state.newestMs - at)} ago",
                    fontFamily = JetBrainsMono, fontSize = 11.sp, color = c.ink,
                )
            }
            Slider(
                value = position,
                onValueChange = { position = it },
                onValueChangeFinished = { vm.scrubTo(if (position >= 0.999f) null else at) },
                colors = SliderDefaults.colors(
                    thumbColor = c.sky,
                    activeTrackColor = c.sky,
                    inactiveTrackColor = c.raise,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun AnomalyRow(a: Anomaly, onOpenScreen: (Screen) -> Unit) {
    val c = Pulse.colors
    val tint = tintFor(a.reading.bits)
    // ⚠️ A domain does not necessarily own a screen — SAFETY and ORBITAL fold into others — so the
    // row is only clickable where there is somewhere for it to go.
    val goes = a.spec.domain.screen
    LcarsFrame(
        Modifier.fillMaxWidth().let { if (goes == null) it else it.clickable { onOpenScreen(goes) } },
        accent = tint,
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    a.spec.label.uppercase(Locale.US) + if (a.aspect == Aspect.LEVEL) "" else " · RATE",
                    fontFamily = ChakraPetch, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                    color = c.ink, modifier = Modifier.weight(1f),
                )
                Text(
                    reading(a),
                    fontFamily = JetBrainsMono, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = tint,
                )
            }
            Text(
                a.reading.sentence,
                fontFamily = JetBrainsMono, fontSize = 11.sp, lineHeight = 16.sp, color = c.ink2,
                modifier = Modifier.padding(top = 4.dp),
            )
            Row(
                Modifier.padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                LcarsSparkline(a.trace, tint, Modifier.weight(1f).height(20.dp), c.raise)
                Text(footnote(a), fontFamily = JetBrainsMono, fontSize = 9.sp, color = c.faint)
            }
        }
    }
}

@Composable
private fun QuietRow(a: Anomaly) {
    val c = Pulse.colors
    Row(
        Modifier.fillMaxWidth().background(c.raise).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            a.spec.label + if (a.aspect == Aspect.LEVEL) "" else " · rate",
            fontFamily = JetBrainsMono, fontSize = 11.sp, color = c.muted, modifier = Modifier.weight(1f),
        )
        LcarsSparkline(a.trace, c.faint, Modifier.width(70.dp).height(14.dp))
        Text(
            "  " + reading(a),
            fontFamily = JetBrainsMono, fontSize = 11.sp, color = c.ink2,
        )
    }
}

@Composable
private fun NotYetRow(n: NotYet) {
    val c = Pulse.colors
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            n.spec.label,
            fontFamily = JetBrainsMono, fontSize = 11.sp, color = c.muted, modifier = Modifier.weight(1f),
        )
        Text(
            "${n.have} of ${n.need} readings",
            fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.faint,
        )
    }
}

@Composable
private fun SectionLabel(text: String, colour: Color) {
    Text(
        text,
        fontFamily = JetBrainsMono, fontSize = 9.sp, letterSpacing = 1.2.sp,
        fontWeight = FontWeight.Bold, color = colour,
        modifier = Modifier.padding(top = 14.dp, start = 2.dp),
    )
}

// ---------------------------------------------------------------- wording

/**
 * ⚠️ Warmer means stranger, and nothing here is red for being *bad*. A share price at a two-year low
 * and a share price at a two-year high are equally surprising, and the wall ranks strangeness, not
 * misfortune — colouring by direction would quietly turn it into a different instrument.
 */
@Composable
internal fun tintFor(bits: Double): Color {
    val c = Pulse.colors
    return when {
        bits >= 8.0 -> c.negative
        bits >= 6.0 -> c.amber
        bits >= AnomaliesViewModel.THRESHOLD_BITS -> c.accent
        else -> c.muted
    }
}

private fun reading(a: Anomaly): String {
    val v = fmt(a.value, a.spec.decimals)
    val unit = a.spec.unit.takeIf { it.isNotBlank() }?.let { " $it" } ?: ""
    return if (a.aspect == Aspect.CHANGE) (if (a.value >= 0) "+$v$unit" else "$v$unit") else "$v$unit"
}

private fun footnote(a: Anomaly): String = buildString {
    // ⚠️ The percentile earns its place precisely where the sentence is vaguest. "Highest on record"
    // needs no help, but "unusually high" is a shrug without a number, and a reader knows instantly
    // what "higher than 97% of everything recorded" means. Both of these were computed by the core
    // and read by nothing at all until now, which is the defect class this project keeps correcting.
    if (!a.reading.cappedAtCeiling) {
        val pct = (a.reading.percentile * 100).roundToLong()
        append(if (a.reading.direction < 0) "below ${100 - pct}% of the record" else "above $pct% of the record")
        append(" · ")
    }
    // The sample really behind the verdict, which on a metric polled faster than it updates is far
    // smaller than the number of rows — see Novelty.effectiveSampleSize.
    append("${a.reading.effectiveN} readings")
    when (a.reading.basis) {
        Novelty.Basis.RECORDED -> {}
        Novelty.Basis.BACKFILLED -> append(" · fetched history")
        Novelty.Basis.MIXED -> append(" · part fetched")
    }
    if (a.reading.cappedAtCeiling) append(" · at the limit of what they can show")
    if (a.persistence > 1) append(" · held ${a.persistence} collections")
}

internal fun fmt(v: Double, decimals: Int): String = String.format(Locale.US, "%.${decimals}f", v)

private fun bytes(n: Long): String = when {
    n >= 1_048_576L -> String.format(Locale.US, "%.1f MB", n / 1_048_576.0)
    n >= 1024L -> "${n / 1024} kB"
    else -> "$n B"
}
