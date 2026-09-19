package dev.mascwa.pulse.feature.recon

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mascwa.pulse.core.telemetry.Recon
import dev.mascwa.pulse.core.telemetry.Recon.Provenance
import dev.mascwa.pulse.core.telemetry.Recon.ReconCollection
import dev.mascwa.pulse.core.telemetry.Recon.ReconEntry
import dev.mascwa.pulse.feature.common.LcarsButton
import dev.mascwa.pulse.feature.common.LcarsCorner
import dev.mascwa.pulse.feature.common.PulseScaffold
import dev.mascwa.pulse.ui.theme.ChakraPetch
import dev.mascwa.pulse.ui.theme.JetBrainsMono
import dev.mascwa.pulse.ui.theme.Pulse

/**
 * TROVE — the reconnaissance dossier. The device's own front page: the network and who is on it, the
 * radios in range, the phone laid bare, and the file the app has gathered on its owner, each section a
 * collection in the "here is everything we hold" style. Every line carries where it came from, and
 * every collection carries its own honest ceiling.
 */
@Composable
fun ReconScreen(vm: ReconViewModel, onExport: (() -> Unit)? = null, onBack: (() -> Unit)? = null) {
    val state by vm.state.collectAsStateWithLifecycle()
    val c = Pulse.colors

    PulseScaffold(title = "Trove", onBack = onBack) { innerPadding ->
        LazyColumn(
            modifier = Modifier.padding(innerPadding),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Column {
                    Text(
                        "EVERYTHING THIS DEVICE CAN SEE",
                        fontFamily = JetBrainsMono, fontSize = 10.sp, letterSpacing = 1.4.sp,
                        fontWeight = FontWeight.Bold, color = c.accent,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "Read live, kept nowhere. Each line says where it came from.",
                        fontFamily = JetBrainsMono, fontSize = 9.sp, color = c.muted,
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LcarsButton(
                            text = if (state.scanning) "SCANNING…" else "RESCAN",
                            onClick = { vm.scan() },
                            enabled = !state.scanning,
                            color = c.accent,
                            corner = LcarsCorner.TopStart,
                        )
                        if (onExport != null && state.done.isNotEmpty()) {
                            LcarsButton(
                                text = "EXPORT",
                                onClick = onExport,
                                color = c.sky,
                                corner = LcarsCorner.TopStart,
                            )
                        }
                    }
                }
            }

            for (key in ReconViewModel.ORDER) {
                val col = state.done[key]
                if (col != null) {
                    collectionSection(col, c)
                } else {
                    item(key = "pending:$key") {
                        PendingSection(ReconViewModel.PENDING_TITLE[key] ?: key, scanning = state.scanning, c = c)
                    }
                }
            }
        }
    }
}

/** A whole collection: header, summary, caveat, then every entry. Emitted as separate LazyColumn items
 *  so a long section (the host list, the radio list) scrolls with the page rather than nesting a scroll. */
private fun androidx.compose.foundation.lazy.LazyListScope.collectionSection(
    col: ReconCollection,
    c: dev.mascwa.pulse.ui.theme.NightwirePalette,
) {
    item(key = "head:${col.key}") {
        Column(Modifier.padding(top = 4.dp)) {
            Text(
                "◢ ${col.title.uppercase()}  ·  ${col.count}",
                fontFamily = JetBrainsMono, fontSize = 11.sp, letterSpacing = 0.8.sp,
                fontWeight = FontWeight.Bold, color = c.accent,
            )
            if (col.summary.isNotBlank()) {
                Text(col.summary, fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.ink,
                    modifier = Modifier.padding(top = 2.dp))
            }
            col.caveat?.let {
                Text("⚠ $it", fontFamily = JetBrainsMono, fontSize = 9.sp, color = c.amber,
                    modifier = Modifier.padding(top = 3.dp))
            }
            if (col.entries.isEmpty() && col.caveat == null) {
                Text("nothing found", fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.muted,
                    modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
    items(col.entries.size, key = { "${col.key}:$it" }) { i ->
        EntryRow(col.entries[i], c)
    }
}

@Composable
private fun EntryRow(e: ReconEntry, c: dev.mascwa.pulse.ui.theme.NightwirePalette) {
    Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.Top) {
            Text(
                e.label,
                fontFamily = ChakraPetch, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = c.ink,
                modifier = Modifier.weight(1f, fill = false).width(140.dp),
            )
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(e.value, fontFamily = JetBrainsMono, fontSize = 12.sp, color = c.ink)
                Text(
                    e.provenance.label,
                    fontFamily = JetBrainsMono, fontSize = 8.sp, color = provenanceColor(e.provenance, c),
                )
            }
        }
        e.detail?.let {
            Text(it, fontFamily = JetBrainsMono, fontSize = 9.sp, color = c.muted,
                modifier = Modifier.padding(start = 148.dp, top = 1.dp))
        }
    }
}

@Composable
private fun PendingSection(title: String, scanning: Boolean, c: dev.mascwa.pulse.ui.theme.NightwirePalette) {
    Column(Modifier.padding(top = 4.dp)) {
        Text(
            "◢ ${title.uppercase()}",
            fontFamily = JetBrainsMono, fontSize = 11.sp, letterSpacing = 0.8.sp,
            fontWeight = FontWeight.Bold, color = c.muted,
        )
        Text(
            if (scanning) "scanning…" else "not read",
            fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.faint,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

/** How much to trust a line, in colour — the DDoSecrets ethos of showing provenance beside the fact. */
private fun provenanceColor(p: Provenance, c: dev.mascwa.pulse.ui.theme.NightwirePalette): Color = when (p) {
    Provenance.MEASURED -> c.sky
    Provenance.REPORTED -> c.muted
    Provenance.RESOLVED -> c.positive
    Provenance.INFERRED -> c.amber
}
