package dev.mascwa.pulse.desktop.feature.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.LaunchedEffect
import dev.mascwa.pulse.core.telemetry.DeviceSearch
import dev.mascwa.pulse.core.telemetry.EmergencyTriage
import dev.mascwa.pulse.core.telemetry.GuideIndexEntry
import dev.mascwa.pulse.desktop.DeepAnalysis
import dev.mascwa.pulse.desktop.theme.ChakraPetch
import dev.mascwa.pulse.desktop.theme.JetBrainsMono
import dev.mascwa.pulse.desktop.theme.LcarsButton
import dev.mascwa.pulse.desktop.theme.LcarsFrame
import dev.mascwa.pulse.desktop.theme.LcarsHeaderBar
import dev.mascwa.pulse.desktop.theme.LcarsSwitch
import dev.mascwa.pulse.desktop.theme.LcarsTextField
import dev.mascwa.pulse.desktop.theme.Pulse

/**
 * SEARCH — one box over everything this machine holds.
 *
 * Results are grouped by kind and open in the reader. An emergency recognised in the query is answered
 * above them all, from the same curated table the phone uses, so the first action is on screen before
 * any ranking happens.
 */
@Composable
fun SearchScreen(
    vm: SearchViewModel,
    onOpenGuide: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by vm.state.collectAsState()
    val c = Pulse.colors

    Column(modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        LcarsHeaderBar("Search")

        LcarsTextField(
            label = "FIND",
            value = state.query,
            onValueChange = { vm.onQueryChanged(it) },
            placeholder = "a subject, a phrase, or what has gone wrong",
            modifier = Modifier.fillMaxWidth().widthIn(max = 720.dp),
        )
        Spacer(Modifier.height(6.dp))
        // ⚠️ Says what it actually searched. The phone's stores are not reachable from here, and
        // implying otherwise would be the difference between "not found" and "not looked at".
        Text(
            corpusLine(state.corpus),
            fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.faint,
        )
        Spacer(Modifier.height(8.dp))

        // ⚠️ The deep switch, in the panel it applies to — never a mode, never a setting somewhere
        // else. Default off, and nothing here starts until it is turned on.
        LcarsSwitch(
            label = "Read every page, not just the catalogue",
            checked = state.deepOn,
            onCheckedChange = { vm.setDeep(it) },
            modifier = Modifier.fillMaxWidth().widthIn(max = 720.dp),
        )
        Text(
            deepLine(state),
            fontFamily = JetBrainsMono, fontSize = 10.sp,
            color = if (state.deep.failedFor != null) c.magenta else c.faint,
            modifier = Modifier.padding(top = 3.dp),
        )
        // ⚠️ The ONLY thing that starts a deep scan: the switch going on, or the question changing
        // while it is already on. No clock is involved, and with the switch off `runDeep` returns
        // immediately — which is what makes opening this screen cost nothing.
        LaunchedEffect(state.query, state.deepOn) { vm.runDeep() }
        Spacer(Modifier.height(10.dp))

        LazyColumn(
            Modifier.fillMaxWidth().weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            state.emergency?.let { e ->
                item { EmergencyCard(e, onOpenGuide) }
            }

            // ⚠️ Before the early return below, or a deep scan that found something in a corpus the
            // index could not see would be silently dropped whenever the ordinary search found
            // nothing — which is exactly the case the deep switch exists for.
            if (state.deepHits.isNotEmpty()) {
                item {
                    Text(
                        "FOUND IN THE TEXT",
                        fontFamily = ChakraPetch, fontWeight = FontWeight.Bold,
                        fontSize = 11.sp, letterSpacing = 2.sp, color = c.sky,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
                items(state.deepHits, key = { "deep:" + it.id }) { g -> DeepRow(g, onOpenGuide) }
            }

            if (state.results.isEmpty()) {
                if (state.searched) {
                    item {
                        Text(
                            "Nothing here matches that.",
                            fontFamily = JetBrainsMono, fontSize = 12.sp, color = c.muted,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
                return@LazyColumn
            }

            DeviceSearch.byKind(state.results).forEach { (kind, results) ->
                item {
                    Text(
                        kind.label.uppercase(),
                        fontFamily = ChakraPetch, fontWeight = FontWeight.Bold,
                        fontSize = 11.sp, letterSpacing = 2.sp, color = c.faint,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
                items(results, key = { it.id + it.kind.name }) { r -> ResultRow(r, onOpenGuide) }
            }
        }
    }
}

/**
 * What the deep switch is doing, in words.
 *
 * ⚠️ Every state says something. A switch that is on and silent is indistinguishable from one that is
 * on and broken, and the whole reason a scan of every shard is behind a switch is that it takes long
 * enough to wonder about.
 */
private fun deepLine(state: SearchUiState): String = when {
    !state.deepOn -> DeepAnalysis.OFF_HINT
    state.query.isBlank() -> "On. Type something to read the library for it."
    state.deep.running -> "Reading every page for “${state.query.trim()}”…"
    state.deep.failedFor == state.query.trim() -> "That scan failed. Switch off and on to try again."
    state.deepHits.isEmpty() && state.deep.readyFor == state.query.trim() ->
        "Read the whole library. Those words are not in any page."
    state.deepHits.isNotEmpty() ->
        "Found in the text of ${state.deepHits.size} page${if (state.deepHits.size == 1) "" else "s"}."
    else -> "On."
}

/** A guide the ordinary search could not see, because the words are in its body. */
@Composable
private fun DeepRow(entry: GuideIndexEntry, onOpenGuide: (String) -> Unit) {
    val c = Pulse.colors
    LcarsFrame(Modifier.fillMaxWidth().clickable { onOpenGuide(entry.id) }, accent = c.sky) {
        Column {
            Text(
                entry.title,
                fontFamily = ChakraPetch, fontWeight = FontWeight.Bold,
                fontSize = 13.sp, color = c.ink,
            )
            Text(
                entry.category,
                fontFamily = JetBrainsMono, fontSize = 9.sp, letterSpacing = 1.sp, color = c.faint,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

@Composable
private fun EmergencyCard(e: EmergencyTriage.Emergency, onOpenGuide: (String) -> Unit) {
    val c = Pulse.colors
    LcarsFrame(Modifier.fillMaxWidth(), accent = c.magenta) {
        Column {
            Text(
                e.label.uppercase(),
                fontFamily = ChakraPetch, fontWeight = FontWeight.Bold,
                fontSize = 13.sp, letterSpacing = 2.sp, color = c.magenta,
            )
            Spacer(Modifier.height(6.dp))
            // The action first, in full. Reading is never the first thing to do in an emergency.
            Text(
                e.firstAction,
                fontFamily = JetBrainsMono, fontSize = 13.sp, color = c.ink, lineHeight = 19.sp,
                modifier = Modifier.widthIn(max = 760.dp),
            )
            val guide = e.guideId
            if (guide != null) {
                Spacer(Modifier.height(10.dp))
                LcarsButton("OPEN THE PROTOCOL", onClick = { onOpenGuide(guide) }, accent = c.magenta)
            }
        }
    }
}

@Composable
private fun ResultRow(r: DeviceSearch.Result, onOpenGuide: (String) -> Unit) {
    val c = Pulse.colors
    val guideId = when (r.kind) {
        DeviceSearch.RecordKind.GUIDE -> r.id
        // A study card's id is the question's, not a guide's — its title carries the guide, and the
        // reader is reached from STUDY instead. Opening the wrong page would be worse than not opening.
        else -> null
    }
    LcarsFrame(
        Modifier.fillMaxWidth().let { if (guideId != null) it.clickable { onOpenGuide(guideId) } else it },
    ) {
        Column {
            Text(r.title, fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = c.ink)
            val summary = r.record.entry.summary
            if (summary.isNotBlank()) {
                Spacer(Modifier.height(3.dp))
                Text(
                    summary,
                    fontFamily = JetBrainsMono, fontSize = 11.sp, color = c.muted, lineHeight = 16.sp,
                    modifier = Modifier.widthIn(max = 900.dp),
                )
            }
            if (r.record.entry.category.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(r.record.entry.category, fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.faint)
            }
        }
    }
}

private fun corpusLine(corpus: List<Pair<DeviceSearch.RecordKind, Int>>): String =
    if (corpus.isEmpty()) {
        "Nothing indexed yet."
    } else {
        "Searching " + corpus.joinToString(" · ") { (kind, n) ->
            "$n ${kind.label.lowercase()}${if (n == 1) "" else "s"}"
        } + " on this machine."
    }
