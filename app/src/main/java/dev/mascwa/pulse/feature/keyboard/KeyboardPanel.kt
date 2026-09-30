package dev.mascwa.pulse.feature.keyboard

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mascwa.pulse.feature.keyboard.KeyboardModel.Key
import dev.mascwa.pulse.feature.keyboard.KeyboardModel.Layer
import dev.mascwa.pulse.feature.keyboard.KeyboardModel.ShiftState
import dev.mascwa.pulse.feature.lcarsboard.textOnBlock
import dev.mascwa.pulse.ui.theme.Antonio
import dev.mascwa.pulse.ui.theme.LocalConsoleBlocks
import dev.mascwa.pulse.ui.theme.Pulse
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The keyboard itself: a thin LCARS header bar and four rows of solid capsules on black.
 *
 * Every key is a filled block lettered in whichever of black or white reads on it (`LcarsContrast`),
 * so nothing is an outline and no label can clash with its key. Letters are one quiet colour so the
 * eye finds them; the keys that DO something — shift, delete, the layer keys, enter — each have their
 * own, so they are found without reading.
 *
 * A key types when it is released, not when it is touched, the way every phone keyboard behaves: a
 * finger that lands on the wrong key can slide off it. Delete is the exception — it fires on the touch
 * and repeats while held, because that is what somebody holding delete is asking for.
 */
@Composable
internal fun KeyboardPanel(
    state: KeyboardModel.State,
    enter: EnterKey.Spec,
    punctuation: String,
    offerSwitch: Boolean,
    onKey: (Key) -> Unit,
    onPicker: () -> Unit,
) {
    val c = Pulse.colors
    val blocks = LocalConsoleBlocks.current
    fun block(i: Int): Color = if (blocks.isEmpty()) c.accent else blocks[i % blocks.size]
    val rows = remember(state.layer, punctuation, offerSwitch) {
        KeyboardModel.rows(state.layer, punctuation, offerSwitch)
    }

    Column(
        Modifier
            .fillMaxWidth()
            .background(c.void)
            .navigationBarsPadding()
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(KeyGap),
    ) {
        Header(state, block(0), block(4))
        rows.forEach { row ->
            Row(
                Modifier.fillMaxWidth().height(KeyHeight),
                horizontalArrangement = Arrangement.spacedBy(KeyGap),
            ) {
                row.forEach { slot ->
                    val key = slot.key
                    if (key == Key.Gap) {
                        Spacer(Modifier.weight(slot.weight))
                    } else {
                        val colour = when (key) {
                            is Key.Text -> c.muted
                            Key.Shift -> when (state.shift) {
                                ShiftState.OFF -> block(1)
                                ShiftState.ONCE -> block(0)
                                ShiftState.LOCKED -> block(6)
                            }
                            Key.Delete -> block(2)
                            Key.Space -> block(1)
                            Key.Enter -> block(0)
                            is Key.ToLayer -> block(5)
                            Key.SwitchIme -> block(4)
                            Key.Gap -> c.void
                        }
                        val label = if (key == Key.Enter) enter.label else KeyboardModel.label(key, state)
                        KeyCap(
                            label = label,
                            colour = colour,
                            weight = slot.weight,
                            large = key is Key.Text,
                            repeats = key == Key.Delete,
                            onPress = { onKey(key) },
                            onLongPress = if (key == Key.SwitchIme) onPicker else null,
                        )
                    }
                }
            }
        }
    }
}

/** The LCARS strip above the keys: a gold elbow stub and a bar naming what is showing. */
@Composable
private fun Header(state: KeyboardModel.State, stub: Color, bar: Color) {
    val name = when (state.layer) {
        Layer.LETTERS -> if (state.shift == ShiftState.LOCKED) "INPUT · CAPS" else "INPUT"
        Layer.SYMBOLS -> "INPUT · 123"
        Layer.MORE -> "INPUT · SYMBOLS"
    }
    Row(Modifier.fillMaxWidth().height(HeaderHeight), horizontalArrangement = Arrangement.spacedBy(KeyGap)) {
        Box(
            Modifier
                .width(HeaderStub)
                .fillMaxHeight()
                .clip(RoundedCornerShape(topStart = HeaderHeight, bottomStart = HeaderHeight))
                .background(stub),
        )
        Box(
            Modifier.weight(1f).fillMaxHeight().background(bar).padding(horizontal = 8.dp),
            contentAlignment = Alignment.CenterEnd,
        ) {
            Text(
                name,
                fontFamily = Antonio,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
                letterSpacing = 2.sp,
                color = textOnBlock(bar),
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun RowScope.KeyCap(
    label: String,
    colour: Color,
    weight: Float,
    large: Boolean,
    repeats: Boolean,
    onPress: () -> Unit,
    onLongPress: (() -> Unit)?,
) {
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    var pressed by remember { mutableStateOf(false) }
    // ⚠️ The gesture must NOT be keyed on these callbacks. They are new lambdas on every recomposition,
    // and a keyed `pointerInput` restarts when its key changes — cancelling a press in progress. Holding
    // delete across the start of a sentence flips shift, which recomposes the panel, which would stop
    // the repeat dead. Read the latest callbacks instead, and key only on what changes the gesture.
    val press by rememberUpdatedState(onPress)
    val longPress by rememberUpdatedState(onLongPress)
    // Brighter while held: the only feedback a key under a thumb can give that the thumb does not hide
    // is the colour round its edge.
    val face = if (pressed) lerp(colour, Color.White, 0.35f) else colour
    Box(
        Modifier
            .weight(weight)
            .fillMaxHeight()
            .clip(RoundedCornerShape(percent = 50))
            .background(face)
            .pointerInput(repeats, onLongPress != null) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        try {
                            if (repeats) {
                                press()
                                val held = scope.launch {
                                    delay(KeyboardModel.REPEAT_DELAY_MS)
                                    while (true) {
                                        press()
                                        delay(KeyboardModel.REPEAT_INTERVAL_MS)
                                    }
                                }
                                try {
                                    tryAwaitRelease()
                                } finally {
                                    held.cancel()
                                }
                            } else {
                                tryAwaitRelease()
                            }
                        } finally {
                            pressed = false
                        }
                    },
                    onTap = if (repeats) null else { _ -> press() },
                    onLongPress = if (onLongPress == null) null else { _ -> longPress?.invoke() },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            fontFamily = Antonio,
            fontWeight = FontWeight.Bold,
            fontSize = if (large) 20.sp else 13.sp,
            letterSpacing = if (large) 0.sp else 1.sp,
            color = textOnBlock(face),
            maxLines = 1,
            overflow = TextOverflow.Clip,
        )
    }
}

private val KeyHeight = 46.dp
private val KeyGap = 5.dp
private val HeaderHeight = 16.dp
private val HeaderStub = 44.dp
