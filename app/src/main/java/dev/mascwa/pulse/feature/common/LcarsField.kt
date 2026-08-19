package dev.mascwa.pulse.feature.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mascwa.pulse.ui.theme.JetBrainsMono
import dev.mascwa.pulse.ui.theme.Pulse

/**
 * THE text field — one input idiom for the whole console.
 *
 * Before this existed the app had grown thirteen hand-rolled `BasicTextField` wrappers plus a
 * scatter of stock Material `TextField`/`OutlinedTextField`s, each with its own chrome, its own
 * placeholder colour and its own clear affordance (or none). An input slot is exactly the kind of
 * thing a person should only have to learn once.
 *
 * Chrome matches the kit: a swept-corner block on the panel colour, hairline border that turns
 * accent while focused, JetBrainsMono at 13sp. The clear ✕ appears whenever there is something to
 * clear (and [showClear] is left on), and carries the kit cue like every other kit control.
 *
 * [onImeAction] fires for whichever action key [imeAction] put on the keyboard — the mapping is
 * handled here so a call site never wires `onGo` while asking for `ImeAction.Search`.
 */
@Composable
fun LcarsField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    leadingIcon: ImageVector? = null,
    imeAction: ImeAction = ImeAction.Done,
    onImeAction: (() -> Unit)? = null,
    showClear: Boolean = true,
    singleLine: Boolean = true,
) {
    val c = Pulse.colors
    val cue = rememberLcarsCue()
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = lcarsBlockShape(10.dp, LcarsCorner.TopStart)
    val act: () -> Unit = { onImeAction?.invoke() }
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        textStyle = TextStyle(fontFamily = JetBrainsMono, fontSize = 13.sp, color = c.ink),
        cursorBrush = SolidColor(c.accent),
        singleLine = singleLine,
        interactionSource = interaction,
        keyboardOptions = KeyboardOptions(imeAction = imeAction),
        // Every action key routes to the one callback; asking for Search but wiring only onGo is a
        // silent dead key, and nothing at a call site can get this pairing wrong now.
        keyboardActions = KeyboardActions(
            onDone = { act() }, onGo = { act() }, onSearch = { act() }, onSend = { act() },
        ),
        decorationBox = { inner ->
            Row(
                Modifier
                    .clip(shape)
                    .background(c.panel)
                    .border(1.5.dp, if (focused) c.accent else c.line, shape)
                    .padding(horizontal = 12.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (leadingIcon != null) {
                    Icon(
                        leadingIcon, null,
                        tint = if (focused) c.accent else c.muted,
                        modifier = Modifier.size(16.dp).padding(end = 0.dp),
                    )
                    Box(Modifier.size(8.dp))
                }
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty() && placeholder.isNotEmpty()) {
                        Text(placeholder, fontFamily = JetBrainsMono, fontSize = 13.sp, color = c.muted)
                    }
                    inner()
                }
                if (showClear && value.isNotEmpty()) {
                    Icon(
                        LcarsIcons.Close, "Clear",
                        tint = c.muted,
                        modifier = Modifier
                            .size(16.dp)
                            .clickable { cue(SoundCue.TAP, HapticCue.TAP_LIGHT); onValueChange("") },
                    )
                }
            }
        },
    )
}
