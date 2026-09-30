package dev.mascwa.pulse.feature.keyboard

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mascwa.pulse.feature.keyboard.KeyboardGeometry.Placed
import dev.mascwa.pulse.feature.keyboard.KeyboardModel.Key
import dev.mascwa.pulse.feature.keyboard.KeyboardModel.Layer
import dev.mascwa.pulse.feature.keyboard.KeyboardModel.ShiftState
import dev.mascwa.pulse.feature.lcarsboard.textOnBlock
import dev.mascwa.pulse.ui.theme.Antonio
import dev.mascwa.pulse.ui.theme.LocalConsoleBlocks
import dev.mascwa.pulse.ui.theme.Pulse
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * The keyboard itself, the size Gboard is and handling touch the way Gboard does.
 *
 * ## Size
 *
 * A 48 dp strip over four rows of 50 dp keys with 8 dp between rows — [KeyHeight], [RowGap] and the
 * rest are named at the bottom of this file so the whole shape can be tuned from a screenshot. The
 * strip is where suggestions will go; until then it names the layer, sized so nothing in it is cut.
 *
 * ## One surface, not forty buttons
 *
 * The keys are DRAWN at the boxes [KeyboardGeometry] works out, and every touch on the keyboard is
 * handled here, once, against the same numbers. A key having its own tap detector is what made the
 * first version unable to do what everybody's thumbs expect: slide from the wrong letter to the right
 * one before lifting, put the next finger down before lifting the last, see which letter is about to
 * be typed. [KeyTouch] decides all of that; this file only carries it out.
 *
 * Every key is a filled block lettered in whichever of black or white reads on it (`LcarsContrast`),
 * so nothing is an outline and no label can clash with its key. Letters are one quiet colour so the
 * eye finds them; the keys that DO something each have their own.
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
    val density = LocalDensity.current
    val metrics = remember(density) {
        with(density) {
            KeyboardGeometry.Metrics(
                keyHeight = KeyHeight.toPx(),
                rowGap = RowGap.toPx(),
                keyGap = KeyGap.toPx(),
                padTop = PadTop.toPx(),
                padBottom = PadBottom.toPx(),
                padSide = PadSide.toPx(),
            )
        }
    }
    val keysHeight = with(density) { KeyboardGeometry.height(rows.size, metrics).toDp() }

    Column(Modifier.fillMaxWidth().background(c.void).navigationBarsPadding()) {
        Strip(state, block(0), block(4))
        BoxWithConstraints(Modifier.fillMaxWidth().height(keysHeight)) {
            val width = constraints.maxWidth.toFloat()
            val placed = remember(rows, width, metrics) { KeyboardGeometry.place(rows, width, metrics) }
            val fingers = remember { mutableStateMapOf<PointerId, KeyTouch.Finger>() }
            val alternates = remember { mutableStateOf<OpenAlternates?>(null) }

            Keys(
                placed = placed,
                fingers = fingers,
                alternates = alternates,
                state = state,
                enter = enter,
                metrics = metrics,
                width = width,
                colourOf = { key ->
                    when (key) {
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
                },
                onKey = onKey,
                onPicker = onPicker,
            )
            Previews(fingers = fingers, state = state, metrics = metrics, width = width, bubble = block(0))
            alternates.value?.let { AlternateRow(it, state, metrics, lit = block(0), back = c.raise) }
        }
    }
}

/**
 * The LCARS strip above the keys: a gold elbow stub and a bar naming what is showing. It is the height
 * Gboard's suggestion strip is, so the keys sit where a thumb trained on Gboard expects them, and the
 * key pop-ups over the top row have somewhere to go.
 */
@Composable
private fun Strip(state: KeyboardModel.State, stub: Color, bar: Color) {
    val name = when (state.layer) {
        Layer.LETTERS -> if (state.shift == ShiftState.LOCKED) "INPUT · CAPS" else "INPUT"
        Layer.SYMBOLS -> "INPUT · 123"
        Layer.MORE -> "INPUT · SYMBOLS"
    }
    Row(
        Modifier.fillMaxWidth().height(StripHeight).padding(horizontal = PadSide, vertical = StripInset),
        horizontalArrangement = Arrangement.spacedBy(KeyGap),
    ) {
        Box(
            Modifier
                .width(StripStub)
                .fillMaxHeight()
                .clip(RoundedCornerShape(topStartPercent = 50, bottomStartPercent = 50))
                .background(stub),
        )
        Box(
            Modifier.weight(1f).fillMaxHeight().background(bar).padding(horizontal = 12.dp),
            contentAlignment = Alignment.CenterEnd,
        ) {
            Text(
                name,
                fontFamily = Antonio,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                letterSpacing = 2.sp,
                color = textOnBlock(bar),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * A held key's row of choices while it is open: which finger opened it, on which key, what it offers,
 * where the row sits and which choice the finger is on (null once it has been dragged away to back out).
 */
private data class OpenAlternates(
    val pointer: PointerId,
    val key: Placed,
    val choices: List<String>,
    val row: AlternatePicker.Row,
    val selected: Int?,
)

/**
 * Every key, and the one touch handler for all of them.
 *
 * ⚠️ **The handler is keyed on nothing.** A keyed `pointerInput` restarts when its key changes, which
 * cancels every press in progress — and the layout, the callbacks and the shift state all change while
 * a finger is down (holding delete across the start of a sentence flips shift). Everything the handler
 * needs is read through `rememberUpdatedState` instead.
 */
@Composable
private fun Keys(
    placed: List<Placed>,
    fingers: MutableMap<PointerId, KeyTouch.Finger>,
    alternates: MutableState<OpenAlternates?>,
    state: KeyboardModel.State,
    enter: EnterKey.Spec,
    metrics: KeyboardGeometry.Metrics,
    width: Float,
    colourOf: (Key) -> Color,
    onKey: (Key) -> Unit,
    onPicker: () -> Unit,
) {
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val keys by rememberUpdatedState(placed)
    val press by rememberUpdatedState(onKey)
    val picker by rememberUpdatedState(onPicker)
    val across by rememberUpdatedState(width)
    val m by rememberUpdatedState(metrics)
    // A held key's timers, by finger. Not state: nothing is drawn from them.
    val timers = remember { HashMap<PointerId, Job>() }

    // Brighter while held: the only feedback a key under a thumb can give that the thumb does not hide
    // is the colour round its edge.
    val held = fingers.values.filter { !it.done }.map { it.current }.toSet()
    Box(
        Modifier
            .fillMaxWidth()
            .fillMaxHeight()
            .pointerInput(Unit) {
                /**
                 * Start the timer that turns holding [key] into its long press: Android's keyboard
                 * picker for the switch key and the space bar, a row of choices for a key that offers
                 * them. It only fires if the finger is still on that key and nothing has used it up.
                 */
                fun armLongPress(id: PointerId, key: Placed) {
                    timers.remove(id)?.cancel()
                    val choices = (key.key as? Key.Text)?.let { KeyAlternates.of(it.value) }.orEmpty()
                    val opensPicker = key.key == Key.SwitchIme || key.key == Key.Space
                    if (!opensPicker && choices.isEmpty()) return
                    timers[id] = scope.launch {
                        delay(KeyTouch.LONG_PRESS_MS)
                        val f = fingers[id] ?: return@launch
                        if (f.done || f.current != key) return@launch
                        fingers[id] = f.copy(done = true)
                        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        if (opensPicker) {
                            picker()
                        } else if (alternates.value == null) {
                            val row = AlternatePicker.row(key.box.cx, choices.size, key.cell.width, across, m.padSide)
                            alternates.value = OpenAlternates(id, key, choices, row, selected = 0)
                        }
                    }
                }
                try {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            for (change in event.changes) {
                                val id = change.id
                                val open = alternates.value?.takeIf { it.pointer == id }
                                when {
                                    change.pressed && !change.previousPressed -> {
                                        // A second finger down: the first one's letter is typed NOW, so
                                        // fast typing comes out in the order it was typed.
                                        fingers.keys.toList().forEach { other ->
                                            val (after, typed) = KeyTouch.rollOver(fingers.getValue(other))
                                            fingers[other] = after
                                            typed?.let { press(it.key) }
                                        }
                                        val key = KeyboardGeometry.keyAt(keys, change.position.x, change.position.y)
                                        if (key != null) {
                                            fingers[id] = KeyTouch.down(key)
                                            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                            timers.remove(id)?.cancel()
                                            if (KeyTouch.actsOnDown(key.key)) {
                                                press(key.key)
                                                timers[id] = scope.launch {
                                                    delay(KeyboardModel.REPEAT_DELAY_MS)
                                                    while (true) {
                                                        press(key.key)
                                                        delay(KeyboardModel.REPEAT_INTERVAL_MS)
                                                    }
                                                }
                                            } else {
                                                armLongPress(id, key)
                                            }
                                        }
                                        change.consume()
                                    }
                                    change.pressed && open != null -> {
                                        // Choosing along an open row: the finger's key no longer matters.
                                        val choice = AlternatePicker.pick(
                                            change.position.x,
                                            change.position.y,
                                            open.row,
                                            cancelBelow = open.key.box.bottom + m.keyHeight,
                                        )
                                        if (choice != open.selected) alternates.value = open.copy(selected = choice)
                                        change.consume()
                                    }
                                    change.pressed -> {
                                        val f = fingers[id]
                                        if (f != null) {
                                            val key = KeyboardGeometry.keyAt(keys, change.position.x, change.position.y)
                                            if (key != null && key != f.current) {
                                                fingers[id] = KeyTouch.moveTo(f, key)
                                                // Sliding onto another letter starts its own hold: a
                                                // finger that corrects to é's key and waits gets é's row.
                                                if (!f.done && f.down.key is Key.Text) armLongPress(id, key)
                                            }
                                        }
                                        change.consume()
                                    }
                                    change.previousPressed -> {
                                        timers.remove(id)?.cancel()
                                        val finger = fingers.remove(id)
                                        // ⚠️ A CANCELLED touch arrives looking like a lift, and must type
                                        // nothing: it is what Android sends when the system takes the
                                        // gesture — a back swipe from the edge, over q or p. Compose builds
                                        // that lift already consumed (read out of the 1.7.6
                                        // `SuspendingPointerInputModifierNodeImpl.onCancelPointerInput`
                                        // bytecode: `isInitiallyConsumed` is the old `pressed`), where a
                                        // real lift reaches this handler unconsumed, since nothing else on
                                        // the keyboard handles touch.
                                        val cancelled = change.isConsumed
                                        if (open != null) {
                                            alternates.value = null
                                            val chosen = open.selected?.let { open.choices.getOrNull(it) }
                                            if (!cancelled && chosen != null) press(Key.Text(chosen))
                                        } else if (finger != null && !cancelled) {
                                            KeyTouch.release(finger)?.let { press(it.key) }
                                        }
                                        change.consume()
                                    }
                                }
                            }
                        }
                    }
                } finally {
                    // The handler is being torn down — the keyboard is closing. Nothing still under a
                    // finger is typed, no row stays open, and no timer outlives it.
                    timers.values.forEach { it.cancel() }
                    timers.clear()
                    fingers.clear()
                    alternates.value = null
                }
            },
    ) {
        placed.forEach { p ->
            val colour = colourOf(p.key)
            val face = if (p in held) lerp(colour, Color.White, 0.35f) else colour
            val label = if (p.key == Key.Enter) enter.label else KeyboardModel.label(p.key, state)
            // The digit a held top-row letter types, small in its corner, as Gboard draws it.
            val hint = (p.key as? Key.Text)?.takeIf { state.layer == Layer.LETTERS }?.let { KeyAlternates.hint(it.value) }
            KeyCap(p, label, hint, face, large = p.key is Key.Text, pxToDp = { with(density) { it.toDp() } })
        }
    }
}

@Composable
private fun KeyCap(p: Placed, label: String, hint: String?, face: Color, large: Boolean, pxToDp: (Float) -> Dp) {
    val ink = textOnBlock(face)
    Box(
        Modifier
            .offset { IntOffset(p.box.left.roundToInt(), p.box.top.roundToInt()) }
            .size(pxToDp(p.box.width), pxToDp(p.box.height))
            .clip(RoundedCornerShape(percent = 50))
            .background(face),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            fontFamily = Antonio,
            fontWeight = FontWeight.Bold,
            fontSize = if (large) LetterSize else 14.sp,
            letterSpacing = if (large) 0.sp else 1.sp,
            color = ink,
            maxLines = 1,
            overflow = TextOverflow.Clip,
        )
        if (hint != null) {
            Text(
                hint,
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 3.dp, end = 9.dp),
                fontFamily = Antonio,
                fontSize = HintSize,
                color = ink.copy(alpha = 0.7f),
                maxLines = 1,
            )
        }
    }
}

/**
 * The open row of a held key's choices, over the key the finger is on. The choice the finger would type
 * on lifting is lit in the console's own colour; dragged away below the key, none is, and lifting then
 * types nothing.
 */
@Composable
private fun AlternateRow(
    open: OpenAlternates,
    state: KeyboardModel.State,
    metrics: KeyboardGeometry.Metrics,
    lit: Color,
    back: Color,
) {
    val density = LocalDensity.current
    val upper = state.layer == Layer.LETTERS && state.shift != ShiftState.OFF
    val h = metrics.keyHeight * PopupHeight
    val top = open.key.box.top + metrics.keyHeight * PopupOverlap - h
    Box(
        Modifier
            .offset { IntOffset(open.row.left.roundToInt(), top.roundToInt()) }
            .size(with(density) { (open.row.cellWidth * open.row.count).toDp() }, with(density) { h.toDp() })
            .clip(RoundedCornerShape(12.dp))
            .background(back),
    ) {
        open.choices.forEachIndexed { i, choice ->
            val slot = AlternatePicker.slotOf(i, open.row)
            val face = if (i == open.selected) lit else back
            Box(
                Modifier
                    .offset { IntOffset((slot * open.row.cellWidth).roundToInt(), 0) }
                    .size(with(density) { open.row.cellWidth.toDp() }, with(density) { h.toDp() })
                    .clip(RoundedCornerShape(10.dp))
                    .background(face),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    KeyAlternates.cased(choice, upper),
                    fontFamily = Antonio,
                    fontWeight = FontWeight.Bold,
                    fontSize = AlternateSize,
                    color = textOnBlock(face),
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                )
            }
        }
    }
}

/**
 * The pop-up over every key a finger is about to type, drawn above the key the finger hides. For the
 * top row it reaches up over the strip, which is one reason the strip is there.
 */
@Composable
private fun Previews(
    fingers: Map<PointerId, KeyTouch.Finger>,
    state: KeyboardModel.State,
    metrics: KeyboardGeometry.Metrics,
    width: Float,
    bubble: Color,
) {
    val density = LocalDensity.current
    fingers.values.mapNotNull { KeyTouch.preview(it) }.distinct().forEach { p ->
        val w = (p.box.width * PopupWidth).coerceAtMost(width)
        val h = metrics.keyHeight * PopupHeight
        val left = (p.box.cx - w / 2f).coerceIn(0f, (width - w).coerceAtLeast(0f))
        val top = p.box.top + metrics.keyHeight * PopupOverlap - h
        Box(
            Modifier
                .offset { IntOffset(left.roundToInt(), top.roundToInt()) }
                .size(with(density) { w.toDp() }, with(density) { h.toDp() })
                .clip(RoundedCornerShape(12.dp))
                .background(bubble),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                KeyboardModel.label(p.key, state),
                fontFamily = Antonio,
                fontWeight = FontWeight.Bold,
                fontSize = PopupLetterSize,
                color = textOnBlock(bubble),
                maxLines = 1,
                overflow = TextOverflow.Clip,
            )
        }
    }
}

// Gboard's shape: a 48 dp strip over four rows of 50 dp keys, 8 dp apart — 284 dp above the gesture
// bar. Each is a named constant so the whole keyboard can be matched to a screenshot by changing one.
private val StripHeight = 48.dp
private val StripInset = 6.dp
private val StripStub = 44.dp
private val KeyHeight = 50.dp
private val RowGap = 8.dp
private val KeyGap = 6.dp
private val PadTop = 6.dp
private val PadBottom = 6.dp
private val PadSide = 4.dp
private val LetterSize = 22.sp

// The pop-up: a quarter wider than its key, 1.3 keys tall, overlapping the top quarter of the key so it
// reads as coming out of it. Over the top row it reaches 46 dp up — inside the 48 dp strip.
private const val PopupWidth = 1.25f
private const val PopupHeight = 1.3f
private const val PopupOverlap = 0.25f
private val PopupLetterSize = 30.sp
private val AlternateSize = 24.sp
private val HintSize = 11.sp
