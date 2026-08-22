package dev.mascwa.pulse.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import dev.mascwa.pulse.desktop.theme.ChakraPetch
import dev.mascwa.pulse.desktop.theme.JetBrainsMono
import dev.mascwa.pulse.desktop.theme.LcarsCorner
import dev.mascwa.pulse.desktop.theme.LcarsTextField
import dev.mascwa.pulse.desktop.theme.Pulse
import dev.mascwa.pulse.desktop.theme.lcarsBlockShape

/**
 * What a key press asks the console to do.
 *
 * ⚠️ **A pure function over a key event, kept out of the composable so it can be reasoned about and
 * tested.** Shortcut handling is the kind of code that quietly stops working — a modifier changes, an
 * event type is missed, a key is consumed somewhere higher up — and none of that is visible by reading
 * the composable it is buried in.
 */
enum class ConsoleCommand { OPEN_COMMAND_BAR, TOGGLE_OPS_WALL, CLOSE_OVERLAY, POP_OUT_CURRENT }

/**
 * Which key combination means what, or null for "not ours, leave it alone".
 *
 * ⚠️ **Takes the three facts rather than the event, and that is because of a real constraint, not for
 * neatness.** Compose Desktop lets nothing outside its own module build a `KeyEvent`: the converter
 * from a platform event is internal and the value-class factory is marked as unstable-between-modules
 * API. So a rule stated over a `KeyEvent` could not be tested at all, and shortcut handling is exactly
 * the code that stops working silently. Decomposed, the decision is ordinary values and the part that
 * cannot be tested shrinks to three property reads with nothing to get wrong — see [ConsoleKeys.handle].
 *
 * ⚠️ **KeyDown only.** Every press produces a down and an up, so acting on both fires everything twice
 * — and the toggles are the ones where that is invisible, because a thing opened on the down and shut
 * on the up simply never appears.
 */
fun consoleCommandFor(
    key: Key,
    type: KeyEventType,
    ctrl: Boolean,
    overlayOpen: Boolean,
): ConsoleCommand? {
    if (type != KeyEventType.KeyDown) return null
    // Escape closes whatever is over the page, and means nothing when there is nothing over it. Left
    // unclaimed in that case rather than swallowed, because a screen may want it.
    if (key == Key.Escape) return if (overlayOpen) ConsoleCommand.CLOSE_OVERLAY else null
    // F11 is what a Windows program means by "fill the screen". No modifier, because that is the
    // convention and a modified F11 is nobody's muscle memory.
    if (key == Key.F11) return ConsoleCommand.TOGGLE_OPS_WALL
    if (!ctrl) return null
    return when (key) {
        // Ctrl+K and Ctrl+P are the two conventions for "jump to anything"; accepting both costs one
        // line and means whichever one someone has in their fingers works.
        Key.K, Key.P -> ConsoleCommand.OPEN_COMMAND_BAR
        // Ctrl+O for "open in its own window" — O for out, and it does not collide with Ctrl+W, which
        // Windows reserves for closing the window you are in.
        Key.O -> ConsoleCommand.POP_OUT_CURRENT
        else -> null
    }
}

/**
 * The bridge between the window's key handler and the shell's state.
 *
 * ⚠️ It exists because the two live in different places and neither can reach the other: the handler
 * is a parameter of `Window`, which is constructed in `main` before any composition exists, while what
 * a shortcut should DO depends on state the shell owns. Rather than hoist the shell's state out into
 * `main` — where a window that has not opened yet would be reasoning about which screen is showing —
 * the shell publishes a callback into this, once per composition.
 *
 * ⚠️ **Both sides run on the AWT event thread**: Compose Desktop composes there, and key events are
 * delivered there. So plain fields are correct and a lock would be theatre. If either side ever moves
 * off it, this needs revisiting rather than a `@Volatile` sprinkled on top.
 */
class ConsoleKeys {
    /** Whether something is over the page, so Escape knows if it has anything to close. */
    var overlayOpen: Boolean = false

    /** What the shell does about a command. Null before the first composition, which is a real state. */
    var onCommand: ((ConsoleCommand) -> Unit)? = null

    /**
     * True when the console took the key, so the window does not pass it on as well.
     *
     * ⚠️ Deliberately nothing but three property reads and a call to [dispatch]. This is the one part
     * of the path that cannot be tested — a `KeyEvent` is not constructible from outside compose-ui —
     * so everything that could be got wrong lives on the other side of it.
     */
    fun handle(event: KeyEvent): Boolean = dispatch(event.key, event.type, event.isCtrlPressed)

    /** The testable half: decide, then act. */
    internal fun dispatch(key: Key, type: KeyEventType, ctrl: Boolean): Boolean {
        val command = consoleCommandFor(key, type, ctrl, overlayOpen) ?: return false
        val run = onCommand ?: return false
        run(command)
        return true
    }
}

/**
 * Jump to any screen by typing.
 *
 * ⚠️ **The same search the MENU on the phone does, over the same [deskMatches] rule** — not a new
 * idiom. What a desktop adds is that it is reachable without moving your hand, which is the half of
 * "drivable from the keyboard" that a console with twenty-six screens actually needs.
 *
 * ⚠️ **The arrow keys are read on the FIELD, in preview, and that scoping is deliberate.** This repo
 * has already shipped the bug where a root-level preview handler swallowed every digit before a text
 * field could have it. Preview at the field is the opposite case and the correct one: the field is
 * where the keys are going, and the list has to see Up/Down before the caret does.
 */
@Composable
fun CommandBar(
    onPick: (Screen) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Pulse.colors
    var query by remember { mutableStateOf("") }
    var cursor by remember { mutableStateOf(0) }
    val focus = remember { FocusRequester() }

    val hits = remember(query) {
        val all = DESK_GROUPS.flatMap { g -> g.entries.map { g to it } }
        if (query.isBlank()) all else all.filter { (_, e) -> deskMatches(e, query.trim()) }
    }
    // ⚠️ Clamped rather than reset: typing a letter that narrows the list must not silently move the
    // selection to something else, and an unclamped cursor would point past the end and pick nothing.
    val selected = cursor.coerceIn(0, (hits.size - 1).coerceAtLeast(0))

    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    Column(
        modifier
            .width(COMMAND_WIDTH)
            .clip(lcarsBlockShape(18.dp, LcarsCorner.TopStart))
            .background(c.raise)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        LcarsTextField(
            label = "Go to",
            value = query,
            onValueChange = { query = it; cursor = 0 },
            placeholder = "type a screen name…",
            modifier = Modifier.fillMaxWidth(),
            fieldModifier = Modifier
                .focusRequester(focus)
                .onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (e.key) {
                        Key.DirectionDown -> { cursor = selected + 1; true }
                        Key.DirectionUp -> { cursor = selected - 1; true }
                        Key.Enter, Key.NumPadEnter -> {
                            hits.getOrNull(selected)?.let { (_, entry) -> onPick(entry.screen) }
                            true
                        }
                        // Handled here as well as at the window, because a focused field is exactly
                        // where someone will press it and relying on it bubbling out is a guess.
                        Key.Escape -> { onDismiss(); true }
                        else -> false
                    }
                },
        )
        if (hits.isEmpty()) {
            Text(
                "Nothing by that name.",
                fontFamily = JetBrainsMono, fontSize = 11.sp, color = c.muted,
            )
        } else {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                hits.take(MAX_HITS).forEachIndexed { i, (group, entry) ->
                    CommandRow(
                        label = entry.label,
                        group = group.label,
                        description = entry.description,
                        selected = i == selected,
                        onClick = { onPick(entry.screen) },
                    )
                }
                if (hits.size > MAX_HITS) {
                    Text(
                        // Said rather than silently truncated — a list that stops without saying so
                        // reads as "that is everything", which it is not.
                        "…and ${hits.size - MAX_HITS} more. Keep typing.",
                        fontFamily = JetBrainsMono, fontSize = 9.sp, color = c.faint,
                        modifier = Modifier.padding(top = 4.dp, start = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun CommandRow(
    label: String,
    group: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val c = Pulse.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clip(lcarsBlockShape(10.dp, LcarsCorner.TopStart))
            .background(if (selected) c.accent else c.void)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            label.uppercase(),
            fontFamily = ChakraPetch, fontWeight = FontWeight.Bold,
            fontSize = 11.sp, letterSpacing = 1.sp,
            color = if (selected) c.void else c.ink,
        )
        Text(
            description,
            fontFamily = JetBrainsMono, fontSize = 9.sp,
            color = if (selected) c.void.copy(alpha = 0.72f) else c.muted,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            group,
            fontFamily = JetBrainsMono, fontSize = 8.sp, letterSpacing = 1.sp,
            color = if (selected) c.void.copy(alpha = 0.6f) else c.faint,
        )
    }
}

/** A scrim that closes the bar when the page behind it is clicked, the way every such overlay does. */
@Composable
fun CommandScrim(onDismiss: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Pulse.colors.void.copy(alpha = 0.72f))
            .clickable(onClick = onDismiss),
    )
}

/** Wide enough for a name, a description and its group on one line at 9sp. */
private val COMMAND_WIDTH = 560.dp

/** Past this the list is longer than the eye scans, and the answer is to type another letter. */
private const val MAX_HITS = 9
