package dev.mascwa.pulse.feature.common

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
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
 */
@Composable
fun LcarsTabRow(
    tabs: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        tabs.forEachIndexed { i, label ->
            LcarsChip(label, selected = i == selected, onClick = { onSelect(i) })
        }
    }
}
