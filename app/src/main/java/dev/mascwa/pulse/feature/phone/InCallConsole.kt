package dev.mascwa.pulse.feature.phone

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mascwa.pulse.feature.common.LcarsDialog
import dev.mascwa.pulse.feature.lcarsboard.ConsoleButton
import dev.mascwa.pulse.feature.lcarsboard.LcarsConsole
import dev.mascwa.pulse.feature.lcarsboard.legibleOn
import dev.mascwa.pulse.feature.lcarsboard.textOnBlock
import dev.mascwa.pulse.ui.theme.Antonio
import dev.mascwa.pulse.ui.theme.JetBrainsMono
import dev.mascwa.pulse.ui.theme.LocalConsoleBlocks
import dev.mascwa.pulse.ui.theme.Pulse

/** What the call screen can ask for. Every one acts through [CallRegistry], never on a `Call`. */
internal class InCallActions(
    val answer: (String) -> Unit,
    val decline: (String) -> Unit,
    val end: (String) -> Unit,
    val hold: (String) -> Unit,
    val resume: (String) -> Unit,
    val swap: (String) -> Unit,
    val merge: (String) -> Unit,
    val addCall: () -> Unit,
    val mute: (Boolean) -> Unit,
    val route: (CallDisplay.Route) -> Unit,
    val dtmfDown: (String, Char) -> Unit,
    val dtmfUp: (String) -> Unit,
)

/**
 * The call screen: who it is, how long it has been, and only the buttons this call can use
 * ([CallDisplay.controls]).
 *
 * ⚠️ **ANSWER and DECLINE replace END only while the call rings** — on the bottom bar, where a thumb
 * lands. Anywhere else a "decline" would hang up on somebody mid-sentence.
 */
@Composable
internal fun InCallConsole(
    calls: List<CallRegistry.LiveCall>,
    audio: CallRegistry.Audio,
    now: Long,
    format: (String) -> String,
    progress: () -> Float,
    actions: InCallActions,
) {
    val c = Pulse.colors
    val blocks = LocalConsoleBlocks.current
    fun block(i: Int): Color = if (blocks.isEmpty()) c.accent else blocks[i % blocks.size]
    val focus = CallRegistry.focus(calls)
    val others = calls.filter { it.id != focus?.id && it.phase != CallDisplay.Phase.ENDED }
    val phase = focus?.phase ?: CallDisplay.Phase.ENDED
    val controls = CallDisplay.controls(
        phase,
        focus?.capabilities ?: 0,
        otherCalls = others.size,
        otherHeld = others.any { it.phase == CallDisplay.Phase.HOLDING },
    )
    var keypad by remember { mutableStateOf(false) }
    var routes by remember { mutableStateOf(false) }

    LcarsConsole(
        board = null,
        progress = progress,
        onTap = {},
        railSeed = RAIL_SEED,
        title = TITLE,
        waitingLine = CallDisplay.label(phase, focus?.connectMs ?: 0L, now),
        bottom = {
            val id = focus?.id
            when {
                id == null -> ConsoleButton(ENDED_LABEL, c.raise) {}
                controls.answer -> {
                    ConsoleButton(DECLINE_LABEL, c.negative) { actions.decline(id) }
                    ConsoleButton(ANSWER_LABEL, c.positive) { actions.answer(id) }
                }
                controls.end -> ConsoleButton(END_LABEL, c.negative) { actions.end(id) }
                else -> ConsoleButton(ENDED_LABEL, c.raise) {}
            }
        },
        body = { modifier ->
            Column(
                modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(12.dp))
                val title = focus?.let { titleOf(it, format) } ?: ENDED_LABEL
                Text(
                    title,
                    fontFamily = Antonio,
                    fontWeight = FontWeight.Bold,
                    fontSize = if (title.length <= 16) 38.sp else 28.sp,
                    color = legibleOn(c.ink, c.void),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
                if (focus?.name != null && focus.number.isNotBlank()) {
                    Line(format(focus.number), c.muted)
                }
                Text(
                    CallDisplay.label(phase, focus?.connectMs ?: 0L, now),
                    fontFamily = JetBrainsMono,
                    fontSize = 15.sp,
                    letterSpacing = 2.sp,
                    color = legibleOn(if (phase == CallDisplay.Phase.RINGING) c.positive else c.accent, c.void),
                )
                if (focus?.video == true) Line(VIDEO_NOTE, c.amber)
                others.forEach { o ->
                    Line("${o.phase.name.replace('_', ' ')} · ${titleOf(o, format)}", c.muted)
                }
                Spacer(Modifier.height(8.dp))
                val id = focus?.id
                if (id != null && keypad && controls.keypad) {
                    Keypad(id, actions)
                    Row(Modifier.fillMaxWidth().height(BUTTON_HEIGHT)) {
                        ConsoleButton(HIDE_KEYPAD_LABEL, block(2)) { keypad = false }
                    }
                } else if (id != null) {
                    ControlGrid(id, controls, audio, block = ::block, actions, onKeypad = { keypad = true }, onRoutes = { routes = true })
                }
            }
        },
    )

    if (routes) {
        RouteDialog(audio, actions) { routes = false }
    }
}

/** The buttons a live call offers, three to a row, each only when [CallDisplay.controls] allows it. */
@Composable
private fun ControlGrid(
    id: String,
    controls: CallDisplay.Controls,
    audio: CallRegistry.Audio,
    block: (Int) -> Color,
    actions: InCallActions,
    onKeypad: () -> Unit,
    onRoutes: () -> Unit,
) {
    val c = Pulse.colors
    val buttons = buildList<Pair<String, Pair<Color, () -> Unit>>> {
        if (controls.mute) {
            add((if (audio.muted) "Unmute" else "Mute") to ((if (audio.muted) c.amber else block(0)) to { actions.mute(!audio.muted) }))
        }
        if (controls.audioRoute) {
            val speaker = audio.route == CallDisplay.Route.SPEAKER
            add("Speaker" to ((if (speaker) c.amber else block(1)) to {
                actions.route(CallDisplay.speakerToggle(audio.route, audio.available))
            }))
            // A choice only exists when there is somewhere other than the earpiece and the speaker.
            if (audio.available.any { it == CallDisplay.Route.BLUETOOTH || it == CallDisplay.Route.WIRED }) {
                add("Audio" to (block(2) to onRoutes))
            }
        }
        if (controls.keypad) add("Keypad" to (block(3) to onKeypad))
        if (controls.hold) add("Hold" to (block(4) to { actions.hold(id) }))
        if (controls.resume) add("Resume" to (c.amber to { actions.resume(id) }))
        if (controls.swap) add("Swap" to (block(5) to { actions.swap(id) }))
        if (controls.merge) add("Merge" to (block(6) to { actions.merge(id) }))
        if (controls.addCall) add("Add call" to (block(7) to actions.addCall))
    }
    buttons.chunked(3).forEach { row ->
        Row(Modifier.fillMaxWidth().height(BUTTON_HEIGHT), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            row.forEach { (label, colorAndAction) ->
                ConsoleButton(label, colorAndAction.first) { colorAndAction.second() }
            }
            // Keep a short last row's buttons the width of the rows above it.
            repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

/**
 * Tones sent down the call while a key is held: a menu option, a PIN, an extension. The tone plays
 * for exactly as long as the key is pressed, as on every phone — so this is a press, not a click.
 */
@Composable
private fun Keypad(id: String, actions: InCallActions) {
    DialPad.KEYS.chunked(3).forEach { row ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            row.forEach { key -> ToneKey(key.digit, id, actions, Modifier.weight(1f)) }
        }
    }
}

@Composable
private fun ToneKey(digit: Char, id: String, actions: InCallActions, modifier: Modifier) {
    val c = Pulse.colors
    // ⚠️ Keyed on nothing, read through rememberUpdatedState: a keyed pointerInput restarts when its
    // key changes, which would cut a held tone off mid-press.
    val current by rememberUpdatedState(id)
    Text(
        digit.toString(),
        fontFamily = Antonio,
        fontWeight = FontWeight.Bold,
        fontSize = 26.sp,
        color = textOnBlock(c.raise),
        textAlign = TextAlign.Center,
        modifier = modifier
            .height(TONE_KEY_HEIGHT)
            .clip(RoundedCornerShape(18.dp))
            .background(c.raise)
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        actions.dtmfDown(current, digit)
                        try {
                            tryAwaitRelease()
                        } finally {
                            actions.dtmfUp(current)
                        }
                    },
                )
            }
            .padding(top = 12.dp),
    )
}

@Composable
private fun RouteDialog(audio: CallRegistry.Audio, actions: InCallActions, onDismiss: () -> Unit) {
    val c = Pulse.colors
    LcarsDialog(title = ROUTE_TITLE, onDismiss = onDismiss) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ROUTE_ORDER.filter { it in audio.available }.forEach { r ->
                Row(Modifier.fillMaxWidth().height(BUTTON_HEIGHT)) {
                    ConsoleButton(routeName(r), if (r == audio.route) c.amber else c.raise) {
                        actions.route(r)
                        onDismiss()
                    }
                }
            }
        }
    }
}

private fun titleOf(call: CallRegistry.LiveCall, format: (String) -> String): String =
    call.name ?: call.number.takeIf { it.isNotBlank() }?.let(format) ?: CallLogDigest.withheldLabel(call.presentation)

private fun routeName(r: CallDisplay.Route): String = when (r) {
    CallDisplay.Route.EARPIECE -> "Phone"
    CallDisplay.Route.SPEAKER -> "Speaker"
    CallDisplay.Route.BLUETOOTH -> "Bluetooth"
    CallDisplay.Route.WIRED -> "Headset"
    CallDisplay.Route.STREAMING -> "Streaming"
    CallDisplay.Route.UNKNOWN -> "Other"
}

@Composable
private fun Line(text: String, color: Color) {
    Text(
        text,
        fontFamily = JetBrainsMono,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        color = legibleOn(color, Pulse.colors.void),
        textAlign = TextAlign.Center,
    )
}

private val ROUTE_ORDER = listOf(
    CallDisplay.Route.EARPIECE,
    CallDisplay.Route.SPEAKER,
    CallDisplay.Route.BLUETOOTH,
    CallDisplay.Route.WIRED,
    CallDisplay.Route.STREAMING,
)

private val BUTTON_HEIGHT = 52.dp
private val TONE_KEY_HEIGHT = 56.dp

private const val RAIL_SEED = "call"
private const val TITLE = "Call"
private const val ANSWER_LABEL = "Answer"
private const val DECLINE_LABEL = "Decline"
private const val END_LABEL = "End call"
private const val ENDED_LABEL = "Call ended"
private const val HIDE_KEYPAD_LABEL = "Hide keypad"
private const val ROUTE_TITLE = "Sound to"
private const val VIDEO_NOTE = "A video call — answered with sound only. LCARS has no camera view for calls."
