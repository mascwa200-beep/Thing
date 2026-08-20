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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mascwa.pulse.feature.common.LcarsChip
import dev.mascwa.pulse.feature.common.LcarsCorner
import dev.mascwa.pulse.feature.common.LcarsField
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
 * The directory, as a console rather than a list.
 *
 * ⚠️ **THE POINT OF THIS SCREEN IS THAT IT DOES NOT SCROLL.** It used to be twenty-nine full-width
 * rows in one vertical run under seven headers, which meant finding anything below the fold was a
 * scroll-and-read — on the one screen whose entire job is getting you somewhere fast. Every other
 * complaint about it followed from that: the rows were identical, the headers went past, and nothing
 * stayed in the same place from one visit to the next.
 *
 * So it is two panes now, which is the oldest and least clever answer there is — the Explorer /
 * Control Panel arrangement, a column of categories beside the contents of the one you picked. Seven
 * groups fit down the left with no scrolling; the largest group is seven entries, which fit as a grid
 * on the right with no scrolling. **Everything in the app is now two taps and zero scrolls away**, and
 * each of those taps is in a fixed position that does not move between visits.
 *
 * ⚠️ The left column takes the FRAME'S OWN RAIL SLOT (`rail = false` on the scaffold, and this draws
 * its own). On every other screen that column is a decorative stack of LCARS blocks; here it is the
 * same stack of blocks doing work. That is deliberate: it costs no width the console was not already
 * spending, and it is the difference between chrome and an instrument.
 *
 * Search still comes first, because typing beats navigating when you already know the word. Results
 * replace the grid; the group column stays put so the shape of the app never leaves the screen.
 *
 * The data lives in `navigation/Directory.kt` — the console header, the deep-link whitelist and the
 * recommendation catalogue read the same list, so none of them can disagree about what exists.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MenuScreen(vm: MenuViewModel, onOpen: (String) -> Unit) {
    val c = Pulse.colors
    val recents by vm.recents.collectAsState()
    // Re-fires on every composition ENTRY (return from a pushed entry, tab-restore) — the VM survives
    // both, so an init-only load froze the strip at the first composition's snapshot.
    LaunchedEffect(Unit) { vm.refresh() }

    var query by remember { mutableStateOf("") }
    var groupIndex by remember { mutableIntStateOf(0) }
    val results = remember(query) {
        val q = query.trim()
        if (q.isBlank()) emptyList()
        else GROUPS.flatMap { g -> g.entries.filter { matches(it, q) }.map { it to g.accent } }
    }
    val searching = query.isNotBlank()

    // System back while searching clears the search rather than leaving the screen — the same rule
    // News and Guides follow: back unwinds the sub-state first.
    BackHandler(enabled = searching) { query = "" }

    // ⚠️ `rail = false` AND `railWidth` together: the frame skips drawing its decorative column so
    // this screen can draw a working one in the same slot, and the header's corner block takes the
    // same width so the console's L still closes at the corner.
    PulseScaffold(title = "MENU", rail = false, railWidth = GROUP_COLUMN_WIDTH) { innerPadding ->
        Row(
            Modifier.padding(innerPadding).fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(GUTTER),
        ) {
            GroupColumn(
                selected = groupIndex,
                // ⚠️ Dimmed but NOT disabled while searching. Tapping a group is the natural way to
                // say "no, not that — show me this instead", and a column that stopped responding
                // mid-search would strand you in a result set with nothing but the keyboard to leave
                // it. So it also clears the query, which is what the tap means.
                dimmed = searching,
                onSelect = { groupIndex = it; query = "" },
                modifier = Modifier.width(GROUP_COLUMN_WIDTH).fillMaxHeight(),
            )

            Column(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    // ⚠️ A scroll modifier, on the screen whose selling point is not scrolling. It is
                    // here for the two cases the grid genuinely cannot bound: a search that matches
                    // most of the directory at once, and a very short window (a foldable's cover
                    // screen, split-screen). In the ordinary case there is nothing to scroll and the
                    // modifier costs nothing.
                    .verticalScroll(rememberScrollState())
                    .padding(end = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                LcarsField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.padding(top = 10.dp),
                    placeholder = "Find anything — try \"planes\"",
                    leadingIcon = LcarsIcons.Search,
                    imeAction = ImeAction.Go,
                    onImeAction = { results.firstOrNull()?.let { onOpen(it.first.route) } },
                )

                if (searching) {
                    if (results.isEmpty()) {
                        Text(
                            "Nothing matches \"${query.trim()}\". Try another word.",
                            fontFamily = JetBrainsMono, fontSize = 11.sp, color = c.muted,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    } else {
                        EntryGrid(results.map { it.first }, accentByRoute(), onOpen)
                    }
                } else {
                    // The RECENT strip stays above the grid: going back to what you were just doing
                    // is the commonest thing anyone does here, and it should not require choosing a
                    // group first. Recency-ordered, unlike Home's most-used row — the two answer
                    // different questions and are deliberately not the same list.
                    if (recents.isNotEmpty()) {
                        val accentOf = accentByRoute()
                        FlowRow(
                            Modifier.padding(top = 2.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
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
                    val group = GROUPS[groupIndex.coerceIn(GROUPS.indices)]
                    EntryGrid(group.entries, accentByRoute(), onOpen)
                }
                Box(Modifier.height(16.dp))
            }
        }
    }
}

/**
 * The group column: the app's shape, always on screen, never scrolling.
 *
 * Seven blocks sharing the height by weight, exactly as the decorative rail does — same gutters, same
 * swept foot on the last one — so this reads as the console's own rail rather than as a widget that
 * has been dropped into the rail's place.
 *
 * The selected group takes its own accent; the rest sit in the ground colour with the accent as a
 * hairline stub, so which one is live is unmistakable without any of them shouting.
 */
@Composable
private fun GroupColumn(
    selected: Int,
    dimmed: Boolean,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Pulse.colors
    Column(modifier, verticalArrangement = Arrangement.spacedBy(GUTTER)) {
        GROUPS.forEachIndexed { i, group ->
            val on = i == selected && !dimmed
            val accent = group.accent(c)
            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(
                        if (i == GROUPS.lastIndex) lcarsBlockShape(18.dp, LcarsCorner.BottomStart)
                        else if (i == 0) lcarsBlockShape(18.dp, LcarsCorner.TopStart)
                        else lcarsBlockShape(0.dp, LcarsCorner.TopStart),
                    )
                    .background(if (on) accent else c.raise)
                    .clickable { onSelect(i) }
                    .padding(horizontal = 8.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(
                    group.label,
                    fontFamily = ChakraPetch,
                    fontWeight = FontWeight.Bold,
                    // 10sp measured against the longest label in the directory: "YOUR THINGS" is
                    // eleven characters, which at this size and tracking sits inside the column's
                    // usable width with room to spare. Two lines are allowed anyway, because a group
                    // added later should wrap rather than be cut.
                    fontSize = 10.sp,
                    letterSpacing = 0.6.sp,
                    // Black on the lit block: the group accents are all light, and LCARS letters a
                    // filled block in the ground colour.
                    color = if (on) c.void else c.ink2,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * The selected group's destinations, as a two-column grid of blocks.
 *
 * Two columns rather than full-width rows so the largest group still fits without scrolling, and so
 * the eye scans a shape instead of reading a list top to bottom. `FlowRow` with a weight per tile
 * rather than a lazy grid: there are at most seven of them and they are all on screen, so laziness
 * would buy nothing and would reintroduce a scroll container.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EntryGrid(
    entries: List<MenuEntry>,
    accentOf: Map<String, (NightwirePalette) -> Color>,
    onOpen: (String) -> Unit,
) {
    val c = Pulse.colors
    Column(verticalArrangement = Arrangement.spacedBy(GUTTER)) {
        entries.chunked(2).forEach { pair ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(GUTTER),
            ) {
                pair.forEach { entry ->
                    EntryTile(
                        entry = entry,
                        accent = (accentOf[entry.route] ?: { it.accent })(c),
                        onOpen = onOpen,
                        modifier = Modifier.weight(1f),
                    )
                }
                // ⚠️ An odd last row gets a spacer rather than a stretched tile. Letting the single
                // tile take the full width would make it read as more important than the others,
                // which is a claim the directory is not making.
                if (pair.size == 1) Box(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun EntryTile(
    entry: MenuEntry,
    accent: Color,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Pulse.colors
    Column(
        modifier
            .height(TILE_HEIGHT)
            .clip(lcarsBlockShape(16.dp, LcarsCorner.TopEnd))
            .background(c.raise)
            .clickable { onOpen(entry.route) },
    ) {
        // The accent is a bar across the top of the tile rather than a sliver down its side: at this
        // width a vertical stripe is a few pixels and reads as an artefact.
        Box(Modifier.fillMaxWidth().height(5.dp).background(accent))
        Column(Modifier.padding(horizontal = 9.dp, vertical = 7.dp)) {
            Text(
                entry.label.uppercase(),
                fontFamily = ChakraPetch, fontWeight = FontWeight.Bold,
                fontSize = 12.sp, letterSpacing = 0.8.sp, color = c.ink,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            Text(
                entry.description,
                fontFamily = JetBrainsMono, fontSize = 9.sp, lineHeight = 12.sp, color = c.muted,
                maxLines = 3, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 3.dp),
            )
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

/** Matches the frame's own block gutter, so the two columns read as one piece of chrome. */
private val GUTTER = 3.dp

/**
 * ⚠️ Wide enough for "YOUR THINGS", the longest group label in the directory, and no wider.
 *
 * It comes off the content, and the tiles beside it are the thing people are actually reading. This
 * is the same trade the frame's own rail makes, at the same width, which is why the two look like one
 * console rather than a rail plus a menu.
 */
private val GROUP_COLUMN_WIDTH = 88.dp

/**
 * Sized so the largest group — seven entries, four rows — clears the fold on a phone.
 *
 * Four rows at this height plus gutters is a little over 300dp, against roughly 600dp of content
 * area, which leaves the search field and the recents strip their room with margin to spare.
 */
private val TILE_HEIGHT = 78.dp
