package dev.mascwa.pulse.feature.interrogator

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mascwa.pulse.core.telemetry.Rebuttal
import dev.mascwa.pulse.data.interrogator.InterrogatorCascade
import dev.mascwa.pulse.data.interrogator.TranscriptStore
import dev.mascwa.pulse.feature.common.LcarsButton
import dev.mascwa.pulse.feature.common.LcarsCorner
import dev.mascwa.pulse.feature.common.LcarsIcons
import dev.mascwa.pulse.feature.common.PulseScaffold
import dev.mascwa.pulse.feature.common.lcarsBlockShape
import dev.mascwa.pulse.ui.theme.ChakraPetch
import dev.mascwa.pulse.ui.theme.JetBrainsMono
import dev.mascwa.pulse.ui.theme.Pulse
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The acoustic interrogator.
 *
 * Three things, in the order they matter: whether it is listening and what that costs; the last
 * finding, with the one question worth asking and where it came from; and the transcript, with the
 * control that erases it.
 *
 * ⚠️ The provenance is on the finding, not buried in a tooltip. "WORDING ONLY" and "READ AND JUDGED"
 * are very different claims about how much thought went into a sentence that is about to be pointed
 * at something somebody said, and the reader is entitled to know which one they are looking at.
 */
@Composable
fun InterrogatorScreen(vm: InterrogatorViewModel, onBack: (() -> Unit)? = null) {
    val c = Pulse.colors
    val ctx = LocalContext.current
    val listening by vm.listening.collectAsStateWithLifecycle()
    val lines by vm.lines.collectAsStateWithLifecycle()
    val kept by vm.kept.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val trace by vm.lastTrace.collectAsStateWithLifecycle()
    val log by vm.findingLog.collectAsStateWithLifecycle()
    var finding by remember { mutableStateOf<Rebuttal.Response?>(null) }
    var confirmPurge by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { vm.refresh() }
    // findings replays its last value, so opening the screen after one was made still shows it.
    LaunchedEffect(Unit) { vm.findings.collect { finding = it } }

    val permission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) vm.start(ctx) }

    PulseScaffold(
        title = "Interrogator",
        onBack = onBack,
    ) { innerPadding ->
        Box(Modifier.padding(innerPadding).fillMaxSize()) {
            LazyColumn(
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    StateCard(
                        listening = listening,
                        modelReady = vm.modelReady,
                        adjudicator = when {
                            vm.adjudicatorReady -> "Loaded"
                            vm.adjudicatorPresent -> "On disk, not loaded"
                            else -> "Not downloaded"
                        },
                        busy = busy,
                        onStart = {
                            if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) ==
                                PackageManager.PERMISSION_GRANTED
                            ) {
                                vm.start(ctx)
                            } else {
                                permission.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        },
                        onStop = { vm.stop(ctx) },
                        onFetch = { vm.fetchAdjudicator() },
                    )
                }
                item { FindingCard(finding, InterrogatorViewModel.quietLine(trace), trace?.heard) }
                if (log.size > 1) {
                    item {
                        Text(
                            "EARLIER · ${log.size - 1}",
                            fontFamily = JetBrainsMono, fontSize = 9.sp, letterSpacing = 1.2.sp,
                            fontWeight = FontWeight.Bold, color = c.accent,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    // Everything but the newest, which the card above is already showing.
                    items(log.drop(1), key = { it.atMs }) { EarlierFinding(it) }
                }
                item {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "TRANSCRIPT · $kept KEPT",
                            fontFamily = JetBrainsMono, fontSize = 9.sp, letterSpacing = 1.2.sp,
                            fontWeight = FontWeight.Bold, color = c.accent,
                        )
                        LcarsButton(
                            text = if (confirmPurge) "REALLY ERASE" else "ERASE",
                            onClick = {
                                if (confirmPurge) {
                                    vm.purge()
                                    confirmPurge = false
                                } else {
                                    confirmPurge = true
                                }
                            },
                        )
                    }
                }
                if (lines.isEmpty()) {
                    item {
                        Text(
                            "Nothing recorded.",
                            fontFamily = ChakraPetch, fontSize = 13.sp, color = c.muted,
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                    }
                } else {
                    items(lines, key = { it.atMs }) { line -> TranscriptRow(line) }
                }
            }
        }
    }
}

@Composable
private fun StateCard(
    listening: Boolean,
    modelReady: Boolean,
    adjudicator: String,
    busy: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onFetch: () -> Unit,
) {
    val c = Pulse.colors
    Column(
        Modifier.fillMaxWidth().clip(lcarsBlockShape(sweep = 8.dp, corner = LcarsCorner.TopStart)).background(c.raise).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            if (listening) "◉ LISTENING" else "○ NOT LISTENING",
            fontFamily = JetBrainsMono, fontSize = 12.sp, letterSpacing = 1.4.sp,
            fontWeight = FontWeight.Bold, color = if (listening) c.accent else c.muted,
        )
        Text(
            // ⚠️ Said plainly and up front, because both consequences are surprising if discovered
            // later: the wake word cannot run at the same time, and speech is written down.
            "Speech is transcribed on this device and kept, encrypted, for a day. The wake word " +
                "stands down while this runs.",
            fontFamily = ChakraPetch, fontSize = 12.sp, color = c.muted,
        )
        Text(
            "Transcriber: " + (if (modelReady) "ready" else "not loaded") +
                "   ·   Adjudicator: " + adjudicator,
            fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.muted,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LcarsButton(text = if (listening) "STOP" else "LISTEN", onClick = { if (listening) onStop() else onStart() })
            // The weights are about a gigabyte, so the download is a deliberate tap and says so.
            if (adjudicator == "Not downloaded" && !busy) {
                LcarsButton(text = "GET ADJUDICATOR (~1 GB)", onClick = onFetch)
            }
        }
    }
}

@Composable
private fun FindingCard(finding: Rebuttal.Response?, quiet: String, heard: String?) {
    val c = Pulse.colors
    Column(
        Modifier.fillMaxWidth().clip(lcarsBlockShape(sweep = 8.dp, corner = LcarsCorner.TopStart)).background(c.raise).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            "LAST FINDING",
            fontFamily = JetBrainsMono, fontSize = 9.sp, letterSpacing = 1.2.sp,
            fontWeight = FontWeight.Bold, color = c.accent,
        )
        if (finding == null) {
            Text(quiet, fontFamily = ChakraPetch, fontSize = 13.sp, color = c.muted)
            heard?.let {
                Text("“$it”", fontFamily = ChakraPetch, fontSize = 12.sp, color = c.muted)
            }
            return@Column
        }
        // The question first and largest — it is the whole value of the feature.
        Text(finding.question, fontFamily = ChakraPetch, fontSize = 16.sp, color = c.ink)
        // ⚠️ THE EVIDENCE, and it comes before the label. The sentence is what lets somebody dismiss
        // a silly match at a glance; a label cannot be checked against anything. Absent from older
        // findings made before this was carried through, so it is rendered only when present.
        if (finding.heard.isNotEmpty()) {
            Text(
                "“${finding.heard}”",
                fontFamily = ChakraPetch, fontSize = 14.sp, lineHeight = 19.sp, color = c.ink2,
            )
        }
        Text(
            finding.label + " · " + InterrogatorViewModel.provenanceLine(finding.provenance),
            fontFamily = JetBrainsMono, fontSize = 10.sp, letterSpacing = 1.sp, color = c.accent,
        )
        Text(finding.note, fontFamily = ChakraPetch, fontSize = 12.sp, color = c.muted)
        // ⚠️ Labelled and in the monospace face. It used to render identically to the note above it,
        // which defeated the reason it is on screen at all: it is not another sentence of
        // explanation, it is the literal words the keyword screen matched on.
        Text(
            "matched on: ${finding.quote}",
            fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.faint,
        )
        finding.repeatNote?.let { Text(it, fontFamily = ChakraPetch, fontSize = 12.sp, color = c.muted) }
        finding.citation?.let {
            Text("Source: $it", fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.muted)
        }
        if (finding.provenance == Rebuttal.Provenance.PATTERN_ONLY) {
            Text(
                Rebuttal.UNREASONED_CAVEAT,
                fontFamily = ChakraPetch, fontSize = 12.sp, color = c.muted,
            )
        }
    }
}

/**
 * One finding from earlier in the conversation.
 *
 * Same order as the card above and the same rule about what leads — the question, then the sentence
 * it was about — but smaller, because the reason to look at these is to compare them with each other
 * rather than to read one closely.
 */
@Composable
private fun EarlierFinding(entry: InterrogatorCascade.Finding) {
    val c = Pulse.colors
    val f = entry.response
    val stamp = remember(entry.atMs) {
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(entry.atMs))
    }
    Column(
        Modifier.fillMaxWidth().clip(lcarsBlockShape(sweep = 8.dp, corner = LcarsCorner.TopStart))
            .background(c.raise).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            "$stamp · ${f.label} · ${InterrogatorViewModel.provenanceLine(f.provenance)}",
            fontFamily = JetBrainsMono, fontSize = 9.sp, letterSpacing = 1.sp, color = c.accent,
        )
        Text(f.question, fontFamily = ChakraPetch, fontSize = 13.sp, color = c.ink)
        if (f.heard.isNotEmpty()) {
            Text("“${f.heard}”", fontFamily = ChakraPetch, fontSize = 12.sp, color = c.ink2)
        }
        f.citation?.let {
            Text("Source: $it", fontFamily = JetBrainsMono, fontSize = 9.sp, color = c.muted)
        }
    }
}

@Composable
private fun TranscriptRow(line: TranscriptStore.Line) {
    val c = Pulse.colors
    val stamp = remember(line.atMs) {
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(line.atMs))
    }
    Row(
        Modifier.fillMaxWidth().clip(lcarsBlockShape(sweep = 8.dp, corner = LcarsCorner.TopStart)).background(c.raise).padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(stamp, fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.accent)
        Text(line.text, fontFamily = ChakraPetch, fontSize = 13.sp, color = c.ink)
    }
}
