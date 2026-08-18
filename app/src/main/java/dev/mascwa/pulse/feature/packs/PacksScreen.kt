package dev.mascwa.pulse.feature.packs

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mascwa.pulse.core.telemetry.ContentPack
import dev.mascwa.pulse.data.survival.PackOffer
import dev.mascwa.pulse.feature.common.LcarsButton
import dev.mascwa.pulse.feature.common.LcarsFillRow
import dev.mascwa.pulse.feature.common.LcarsHeaderBar
import dev.mascwa.pulse.feature.common.LcarsIcons
import dev.mascwa.pulse.feature.common.LoadingState
import dev.mascwa.pulse.feature.common.NeonPanel
import dev.mascwa.pulse.feature.common.PulseScaffold
import dev.mascwa.pulse.ui.theme.ChakraPetch
import dev.mascwa.pulse.ui.theme.JetBrainsMono
import dev.mascwa.pulse.ui.theme.Pulse

/**
 * PACKS — adding to the library without adding to the download.
 *
 * The app installs lean; a pack is fetched once and is then part of the library like anything
 * bundled, with no network in the reading path ever again. That last part is the whole point and the
 * screen says it outright, because "downloaded content" usually means the opposite.
 */
@Composable
fun PacksScreen(vm: PacksViewModel, onBack: (() -> Unit)? = null) {
    val state by vm.state.collectAsStateWithLifecycle()
    val c = Pulse.colors

    PulseScaffold(
        title = "Packs",
        navigationIcon = {
            if (onBack != null) IconButton(onClick = onBack) { Icon(LcarsIcons.ArrowBack, "Back") }
        },
    ) { innerPadding ->
        Box(Modifier.padding(innerPadding).fillMaxSize()) {
            if (state.loading && state.offers.isEmpty()) {
                LoadingState()
                return@Box
            }
            LazyColumn(
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    LcarsHeaderBar(
                        "Knowledge packs",
                        trailing = state.offers.count { it.state != ContentPack.State.AVAILABLE }
                            .takeIf { it > 0 }?.let { "$it INSTALLED" },
                    )
                }
                item {
                    Text(
                        "Subject packs add guides to the library. Each one downloads once and then " +
                            "works offline for good — nothing here is fetched again when you read it.",
                        fontFamily = JetBrainsMono, fontSize = 11.sp, color = c.muted,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }

                state.error?.let { err ->
                    item {
                        NeonPanel {
                            Text(err, fontFamily = JetBrainsMono, fontSize = 11.sp, color = c.negative)
                        }
                    }
                }
                state.notice?.let { note ->
                    item {
                        NeonPanel {
                            Text(note, fontFamily = JetBrainsMono, fontSize = 11.sp, color = c.positive)
                        }
                    }
                }

                if (state.offers.isEmpty() && !state.loading && state.error == null) {
                    item {
                        Text(
                            "No packs have been published yet.",
                            fontFamily = JetBrainsMono, fontSize = 11.sp, color = c.muted,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                        )
                    }
                }

                items(state.offers, key = { it.pack.id }) { offer ->
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
                    Spacer(Modifier.height(6.dp))
                    LcarsButton("CHECK AGAIN", onClick = { vm.refresh() }, color = c.sky)
                }
            }
        }
    }
}

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
    NeonPanel(corners = true) {
        Column {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    offer.pack.title,
                    fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = c.ink,
                )
                Text(
                    when (offer.state) {
                        ContentPack.State.INSTALLED -> "INSTALLED"
                        ContentPack.State.UPDATABLE -> "UPDATE READY"
                        ContentPack.State.AVAILABLE -> "AVAILABLE"
                    },
                    fontFamily = JetBrainsMono, fontSize = 9.sp, letterSpacing = 1.sp, color = accent,
                )
            }
            if (offer.pack.summary.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(offer.pack.summary, fontFamily = JetBrainsMono, fontSize = 11.sp, color = c.muted)
            }
            Spacer(Modifier.height(6.dp))
            // What it is and what it costs, before anyone agrees to fetch it.
            Text(
                buildString {
                    append(offer.pack.describe())
                    offer.installedVersion?.let { append(" · you have v").append(it) }
                    if (offer.state != ContentPack.State.INSTALLED) append(" · offers v").append(offer.pack.version)
                },
                fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.muted,
            )

            if (busy) {
                Spacer(Modifier.height(10.dp))
                LcarsFillRow(
                    segments = listOf(
                        progressPct.toFloat() to c.accent,
                        (100 - progressPct).toFloat() to c.raise,
                    ),
                    modifier = Modifier.fillMaxWidth().height(6.dp),
                    gap = 1.5.dp,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    // A server that sends no length makes a percentage a fiction, so say the honest thing.
                    if (progressPct > 0) "Downloading — $progressPct%" else "Downloading…",
                    fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.accent,
                )
            } else {
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    when (offer.state) {
                        ContentPack.State.AVAILABLE ->
                            if (!anyBusy) LcarsButton("ADD TO LIBRARY", onClick = onInstall)
                        ContentPack.State.UPDATABLE ->
                            if (!anyBusy) LcarsButton("UPDATE", onClick = onInstall, color = c.amber)
                        ContentPack.State.INSTALLED -> Unit
                    }
                    if (offer.state != ContentPack.State.AVAILABLE && !anyBusy) {
                        LcarsButton("REMOVE", onClick = onRemove, color = c.muted)
                    }
                }
            }
        }
    }
}
