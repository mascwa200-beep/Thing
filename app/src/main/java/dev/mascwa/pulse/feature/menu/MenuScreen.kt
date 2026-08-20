package dev.mascwa.pulse.feature.menu

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mascwa.pulse.feature.common.LcarsChip
import dev.mascwa.pulse.feature.common.LcarsCorner
import dev.mascwa.pulse.feature.common.LcarsField
import dev.mascwa.pulse.feature.common.LcarsHeaderBar
import dev.mascwa.pulse.feature.common.LcarsIcons
import dev.mascwa.pulse.feature.common.PulseScaffold
import dev.mascwa.pulse.feature.common.lcarsBlockShape
import dev.mascwa.pulse.navigation.GROUPS
import dev.mascwa.pulse.navigation.MenuEntry
import dev.mascwa.pulse.ui.theme.ChakraPetch
import dev.mascwa.pulse.ui.theme.JetBrainsMono
import dev.mascwa.pulse.ui.theme.NightwirePalette
import dev.mascwa.pulse.ui.theme.Pulse

/**
 * THE flat directory, rendered — the fix for "hidden as sub tabs, hard to find". Every feature is a
 * big, plain-English, always-in-the-same-place block, grouped, ONE tap from here.
 *
 * Search-first: the box at the top filters the directory as you type (label, description, and the
 * synonyms each entry declares — "planes" finds the radar), and the keyboard's GO opens the first
 * hit. Below it, a RECENT strip of the last few menu destinations you actually opened, so the common
 * case — going back to the thing you use — is one tap with zero scrolling. The full grouped
 * directory renders beneath, exactly as before.
 *
 * The data itself lives in `navigation/Directory.kt`, because the console header, the deep-link
 * whitelist and the recommendation catalog read it too.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MenuScreen(vm: MenuViewModel, onOpen: (String) -> Unit) {
    val c = Pulse.colors
    val recents by vm.recents.collectAsState()
    // Re-fires on every composition ENTRY (return from a pushed chip, tab-restore) — the VM
    // survives both, so an init-only load froze the strip at the first composition's snapshot.
    androidx.compose.runtime.LaunchedEffect(Unit) { vm.refresh() }
    var query by remember { mutableStateOf("") }
    val results = remember(query) {
        val q = query.trim()
        if (q.isBlank()) emptyList()
        else GROUPS.flatMap { g -> g.entries.filter { matches(it, q) }.map { it to g.accent } }
    }

    // System back while searching clears the search rather than leaving the screen — the same rule
    // News and Guides follow: back unwinds the sub-state first.
    BackHandler(enabled = query.isNotBlank()) { query = "" }

    PulseScaffold(title = "MENU") { innerPadding ->
        LazyColumn(
            modifier = Modifier.padding(innerPadding).padding(horizontal = 14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item(key = "search") {
                LcarsField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.padding(top = 10.dp),
                    placeholder = "Find a feature — try \"planes\" or \"aurora\"",
                    leadingIcon = LcarsIcons.Search,
                    imeAction = ImeAction.Go,
                    onImeAction = { results.firstOrNull()?.let { onOpen(it.first.route) } },
                )
            }

            if (query.isNotBlank()) {
                if (results.isEmpty()) {
                    item(key = "no_results") {
                        Text(
                            "Nothing matches \"${query.trim()}\". Try another word.",
                            fontFamily = JetBrainsMono, fontSize = 11.sp, color = c.muted,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    }
                } else {
                    items(results, key = { "hit_${it.first.route}" }) { (entry, accent) ->
                        MenuRow(entry, accent(c), onOpen)
                    }
                }
            } else {
                if (recents.isNotEmpty()) {
                    item(key = "recent_hdr") {
                        LcarsHeaderBar("RECENT", Modifier.padding(top = 6.dp, bottom = 2.dp))
                    }
                    item(key = "recent_chips") {
                        val accentOf = accentByRoute()
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            recents.forEach { entry ->
                                LcarsChip(
                                    text = entry.label,
                                    selected = false,
                                    onClick = { onOpen(entry.route) },
                                    accent = (accentOf[entry.route] ?: { it.accent })(c),
                                )
                            }
                        }
                    }
                }
                GROUPS.forEach { group ->
                    item(key = "hdr_${group.label}") {
                        LcarsHeaderBar(group.label, Modifier.padding(top = 12.dp, bottom = 2.dp))
                    }
                    items(group.entries, key = { it.route }) { entry ->
                        MenuRow(entry, group.accent(c), onOpen)
                    }
                }
            }
            item(key = "footer_pad") { Box(Modifier.height(16.dp)) }
        }
    }
}

/** Case-insensitive contains over everything an entry says about itself. */
private fun matches(e: MenuEntry, q: String): Boolean =
    e.label.contains(q, ignoreCase = true) ||
        e.description.contains(q, ignoreCase = true) ||
        e.searchTerms.any { it.contains(q, ignoreCase = true) }

@Composable
private fun accentByRoute(): Map<String, (NightwirePalette) -> Color> =
    remember { GROUPS.flatMap { g -> g.entries.map { it.route to g.accent } }.toMap() }

@Composable
private fun MenuRow(entry: MenuEntry, accent: Color, onOpen: (String) -> Unit) {
    val c = Pulse.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(lcarsBlockShape(14.dp, LcarsCorner.TopStart))
            .background(c.raise)
            .clickable { onOpen(entry.route) }
            .padding(end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(12.dp)
                .height(52.dp)
                .background(accent, lcarsBlockShape(10.dp, LcarsCorner.TopStart)),
        )
        Column(Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp)) {
            Text(
                entry.label.uppercase(),
                fontFamily = ChakraPetch,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                letterSpacing = 1.2.sp,
                color = c.ink,
            )
            Text(
                entry.description,
                fontFamily = JetBrainsMono,
                fontSize = 10.sp,
                color = c.muted,
            )
        }
    }
}
