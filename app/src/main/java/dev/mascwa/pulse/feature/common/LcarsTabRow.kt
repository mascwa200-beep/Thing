package dev.mascwa.pulse.feature.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * THE sub-tab rail — one idiom for switching a screen's sub-views.
 *
 * Before this existed, five screens each hand-rolled the same scrolling chip row (Markets and
 * Weather were byte-identical copies of each other), and which chip style a screen used depended
 * on which screen it was. A tab rail is precisely the control a person should only have to learn
 * once: same geometry, same selected state, same cue, everywhere.
 *
 * Takes indices rather than a value type so the caller keeps owning its own tab enum — the rail
 * knows labels and a position, nothing else.
 *
 * A LazyRow rather than a scrollable Row for one behaviour Material's ScrollableTabRow had and a
 * plain Row cannot give: **the selected tab scrolls itself into view**. News carries ~19 tabs;
 * returning to it with tab 15 selected must not show a rail whose indicator is off-screen.
 */
@Composable
fun LcarsTabRow(
    tabs: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(selected, tabs.size) {
        if (selected in tabs.indices) listState.animateScrollToItem(selected)
    }
    LazyRow(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(tabs.size, key = { tabs[it] + it }) { i ->
            LcarsChip(tabs[i], selected = i == selected, onClick = { onSelect(i) })
        }
    }
}
