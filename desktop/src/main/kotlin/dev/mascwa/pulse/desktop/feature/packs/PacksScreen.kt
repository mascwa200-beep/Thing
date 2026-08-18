package dev.mascwa.pulse.desktop.feature.packs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mascwa.pulse.desktop.library.PackOffer
import dev.mascwa.pulse.desktop.telemetry.ContentPack
import dev.mascwa.pulse.desktop.theme.ChakraPetch
import dev.mascwa.pulse.desktop.theme.JetBrainsMono
import dev.mascwa.pulse.desktop.theme.LcarsBusyBar
import dev.mascwa.pulse.desktop.theme.LcarsButton
import dev.mascwa.pulse.desktop.theme.LcarsFillRow
import dev.mascwa.pulse.desktop.theme.LcarsFrame
import dev.mascwa.pulse.desktop.theme.LcarsHeaderBar
import dev.mascwa.pulse.desktop.theme.Pulse

/**
 * PACKS — adding to the library without adding to the download.
 *
 * The app installs lean; a pack is fetched once and is then part of the library like anything
 * bundled, with no network in the reading path ever again. That last part is the whole point and the
 * screen says it outright, because "downloaded content" usually means the opposite.
 */
@Composable
fun PacksScreen(vm: PacksViewModel, modifier: Modifier = Modifier) {
    val state by vm.state.collectAsState()
    val c = Pulse.colors

    Column(modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        LcarsHeaderBar(
            "Packs",
            trailing = state.offers.count { it.state != ContentPack.State.AVAILABLE }
                .takeIf { it > 0 }?.let { "$it INSTALLED" },
        )
        LcarsBusyBar(active = state.loading, modifier = Modifier.fillMaxWidth())

        LazyColumn(
            Modifier.fillMaxWidth().weight(1f),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 12.dp, bottom = 24.dp),
        ) {
            item {
                Text(
                    "Subject packs add guides to the library. Each one downloads once and then works " +
                        "offline for good — nothing here is fetched again when you read it.",
                    fontFamily = JetBrainsMono, fontSize = 12.sp, color = c.muted,
                    modifier = Modifier.widthIn(max = 760.dp),
                )
            }

            state.error?.let { err ->
                item {
                    LcarsFrame(Modifier.fillMaxWidth(), accent = c.negative) {
                        Text(err, fontFamily = JetBrainsMono, fontSize = 12.sp, color = c.ink)
                    }
                }
            }
            state.notice?.let { note ->
                item {
                    LcarsFrame(Modifier.fillMaxWidth(), accent = c.positive) {
                        Text(note, fontFamily = JetBrainsMono, fontSize = 12.sp, color = c.ink)
                    }
                }
            }

            if (state.offers.isEmpty() && !state.loading && state.error == null) {
                item {
                    Text(
                        "No packs have been published yet.",
                        fontFamily = JetBrainsMono, fontSize = 12.sp, color = c.faint,
                    )
                }
            }

            items(state.offers) { offer ->
                PackCard(
                    offer = offer,
                    busy = state.busyId == offer.pack.id,
                    progressPct = state.progressPct,
                    anyBusy = state.busyId != null,
                    onInstall = { vm.install(offer.pack) },
                    onRemove = { vm.remove(offer.pack) },
                )
            }

            item {
                Spacer(Modifier.height(8.dp))
                LcarsButton("CHECK AGAIN", onClick = { vm.refresh() }, accent = c.sky)
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.items(
    offers: List<PackOffer>,
    content: @Composable (PackOffer) -> Unit,
) = items(offers.size) { content(offers[it]) }

@Composable
private fun PackCard(
    offer: PackOffer,
    busy: Boolean,
    progressPct: Int,
    anyBusy: Boolean,
    onInstall: () -> Unit,
    onRemove: () -> Unit,
) {
    val c = Pulse.colors
    val accent = when (offer.state) {
        ContentPack.State.INSTALLED -> c.positive
        ContentPack.State.UPDATABLE -> c.amber
        ContentPack.State.AVAILABLE -> c.sky
    }
    LcarsFrame(Modifier.fillMaxWidth(), accent = accent) {
        Column {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    offer.pack.title,
                    fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 17.sp, color = c.ink,
                )
                Text(
                    when (offer.state) {
                        ContentPack.State.INSTALLED -> "INSTALLED"
                        ContentPack.State.UPDATABLE -> "UPDATE READY"
                        ContentPack.State.AVAILABLE -> "AVAILABLE"
                    },
                    fontFamily = JetBrainsMono, fontSize = 10.sp, letterSpacing = 1.sp, color = accent,
                )
            }
            if (offer.pack.summary.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    offer.pack.summary,
                    fontFamily = JetBrainsMono, fontSize = 12.sp, color = c.muted,
                    modifier = Modifier.widthIn(max = 760.dp),
                )
            }
            Spacer(Modifier.height(6.dp))
            // What it is and what it costs, before anyone agrees to fetch it.
            Text(
                buildString {
                    append(offer.pack.describe())
                    offer.installedVersion?.let { append(" · you have v").append(it) }
                    if (offer.state != ContentPack.State.INSTALLED) append(" · offers v").append(offer.pack.version)
                },
                fontFamily = JetBrainsMono, fontSize = 11.sp, color = c.faint,
            )

            if (busy) {
                Spacer(Modifier.height(12.dp))
                LcarsFillRow(
                    segments = listOf(
                        progressPct.toFloat() to c.accent,
                        (100 - progressPct).toFloat() to c.raise,
                    ),
                    modifier = Modifier.fillMaxWidth().height(7.dp),
                    gap = 1.5.dp,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    // A server that sends no length makes a percentage a fiction, so say the honest thing.
                    if (progressPct > 0) "Downloading — $progressPct%" else "Downloading…",
                    fontFamily = JetBrainsMono, fontSize = 11.sp, color = c.accent,
                )
            } else {
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    when (offer.state) {
                        ContentPack.State.AVAILABLE ->
                            if (!anyBusy) LcarsButton("ADD TO LIBRARY", onClick = onInstall)
                        ContentPack.State.UPDATABLE ->
                            if (!anyBusy) LcarsButton("UPDATE", onClick = onInstall, accent = c.amber)
                        ContentPack.State.INSTALLED -> Unit
                    }
                    if (offer.state != ContentPack.State.AVAILABLE && !anyBusy) {
                        LcarsButton("REMOVE", onClick = onRemove, accent = c.muted)
                    }
                }
            }
        }
    }
}
