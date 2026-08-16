package dev.mascwa.pulse.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mascwa.pulse.feature.common.LcarsSwitch
import dev.mascwa.pulse.ui.theme.ChakraPetch
import dev.mascwa.pulse.ui.theme.JetBrainsMono
import dev.mascwa.pulse.ui.theme.Pulse

/**
 * The rows Settings is built from.
 *
 * Six helpers back about eighty rows, which is why this file is where the screen gets migrated
 * rather than the sixteen-hundred-line screen itself: change these and nearly all of Settings
 * changes with them, with no call site touched.
 *
 * Everything reads [Pulse.colors] and the app's own faces. Material's `MaterialTheme.typography` and
 * `colorScheme` are gone from here — they were the last thing making the app's densest screen look
 * like a different application from the one wrapped around it.
 */

@Composable
fun PrefSection(title: String, initiallyExpanded: Boolean = true, content: @Composable () -> Unit) {
    // All sections start COLLAPSED by default (declutter); pass initiallyExpanded = true to open one
    // (e.g. Software update). State is saved per section title so manual toggles stick.
    var collapsed by androidx.compose.runtime.saveable.rememberSaveable(title) { androidx.compose.runtime.mutableStateOf(!initiallyExpanded) }
    Column(Modifier.fillMaxWidth()) {
        dev.mascwa.pulse.feature.common.CyberHeader(
            title,
            collapsed = collapsed,
            onToggle = { collapsed = !collapsed },
        )
        androidx.compose.animation.AnimatedVisibility(!collapsed) {
            Column(Modifier.fillMaxWidth()) { content() }
        }
    }
}

/** The row title, in the app's display face. */
@Composable
private fun RowTitle(text: String, enabled: Boolean = true) {
    val c = Pulse.colors
    Text(
        text,
        fontFamily = ChakraPetch,
        fontSize = 14.sp,
        letterSpacing = 0.4.sp,
        color = if (enabled) c.ink else c.muted,
    )
}

/** The explanatory line under it. Monospaced and dim — it is reference, not headline. */
@Composable
private fun RowSubtitle(text: String) {
    Text(
        text,
        fontFamily = JetBrainsMono,
        fontSize = 10.sp,
        lineHeight = 14.sp,
        color = Pulse.colors.muted,
        modifier = Modifier.padding(top = 3.dp),
    )
}

@Composable
fun PrefSwitch(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .clickable(enabled = enabled) { onChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            RowTitle(title, enabled)
            if (subtitle != null) RowSubtitle(subtitle)
        }
        // The whole row is already the tap target, so the control does not need its own — but it
        // keeps one anyway, because a switch that cannot be tapped where it looks tappable is a
        // small betrayal repeated eighty times.
        LcarsSwitch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

/**
 * A read-only row. Same layout as [PrefClickable] without the tap target, for facts the user needs to see
 * but cannot act on — a dead `onClick = { }` invites a tap that does nothing, which reads as broken.
 */
@Composable
fun PrefInfo(
    title: String,
    value: String? = null,
    subtitle: String? = null,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            RowTitle(title)
            if (subtitle != null) RowSubtitle(subtitle)
        }
        if (value != null) RowValue(value)
    }
}

@Composable
fun PrefClickable(
    title: String,
    value: String? = null,
    subtitle: String? = null,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            RowTitle(title)
            if (subtitle != null) RowSubtitle(subtitle)
        }
        if (value != null) RowValue(value)
    }
}

/**
 * The trailing value.
 *
 * Accent-coloured, because on a clickable row it is usually the current state of the thing the tap
 * changes, and it should be findable down a long column without reading every title. Capped at a
 * couple of lines: some of these carry a status sentence rather than a word.
 */
@Composable
private fun RowValue(value: String) {
    Text(
        value,
        fontFamily = JetBrainsMono,
        fontSize = 10.sp,
        lineHeight = 13.sp,
        color = Pulse.colors.accent,
        textAlign = TextAlign.End,
        maxLines = 2,
        modifier = Modifier.padding(start = 4.dp),
    )
}

/**
 * Pick one.
 *
 * A filled block rather than a radio button — a radio is a circle inside a circle, and this
 * vocabulary has no curves. Filled is chosen, hollow is not, and the label carries the meaning, so
 * the state does not rest on colour alone.
 */
@Composable
fun <T> PrefRadioGroup(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    val c = Pulse.colors
    Column {
        options.forEach { (value, label) ->
            val on = value == selected
            Row(
                Modifier.fillMaxWidth()
                    .selectable(selected = on, onClick = { onSelect(value) })
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    Modifier
                        .size(width = 14.dp, height = 12.dp)
                        .background(if (on) c.accent else c.raise),
                )
                Text(
                    label,
                    fontFamily = ChakraPetch,
                    fontSize = 14.sp,
                    letterSpacing = 0.4.sp,
                    color = if (on) c.ink else c.ink2,
                )
            }
        }
    }
}
