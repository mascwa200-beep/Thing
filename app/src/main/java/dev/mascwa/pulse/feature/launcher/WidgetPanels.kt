package dev.mascwa.pulse.feature.launcher

import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import dev.mascwa.pulse.feature.common.LcarsDialog
import dev.mascwa.pulse.feature.lcarsboard.ConsolePanel
import dev.mascwa.pulse.feature.lcarsboard.ELEMENT_RAIL
import dev.mascwa.pulse.feature.lcarsboard.legibleOn
import dev.mascwa.pulse.feature.lcarsboard.textOnBlock
import dev.mascwa.pulse.ui.effects.HapticCue
import dev.mascwa.pulse.ui.effects.SoundCue
import dev.mascwa.pulse.ui.effects.rememberLcarsCue
import dev.mascwa.pulse.ui.theme.ChakraPetch
import dev.mascwa.pulse.ui.theme.JetBrainsMono
import dev.mascwa.pulse.ui.theme.Pulse

/** A widget on the console: Android's id for it, what to call it, and how tall to draw it. */
internal data class PlacedWidget(val id: Int, val label: String, val heightDp: Int)

/** One widget in the picker. [key] names the provider; [app] is the app that offers it. */
internal data class WidgetOffer(val key: String, val label: String, val app: String)

/** What the widget panels can ask the home screen to do. */
internal class WidgetActions(
    /**
     * The widget's own view, made once and kept by the home screen for as long as it lives, or null
     * when Android could not make it. Never made here: a lazy list drops an item as it scrolls away,
     * and a widget rebuilt on every scroll would flicker and lose whatever it was showing.
     */
    val view: (Int) -> View?,
    /** The size the widget is actually drawn at, in dp, so it can lay itself out for it. */
    val sized: (id: Int, widthDp: Float, heightDp: Float) -> Unit,
    /** Every widget this phone offers, worth offering, read when the picker opens. */
    val offers: suspend () -> List<WidgetOffer>,
    val add: (WidgetOffer) -> Unit,
    val remove: (Int) -> Unit,
)

/**
 * The WIDGETS heading, carrying + WIDGET — except when the console is full, when it says so instead
 * of offering a button that could only be refused.
 *
 * [count] is null until the home screen has read what is placed, and nothing is said until then:
 * "No widgets yet" on a console that has six would be a flash of something untrue.
 */
@Composable
internal fun WidgetsHeader(
    count: Int?,
    tab: Color,
    progress: () -> Float,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Pulse.colors
    val full = count != null && count >= HomeWidgets.MAX_WIDGETS
    ConsolePanel(
        title = if (count != null && count > 0) "$WIDGETS_TITLE · $count" else WIDGETS_TITLE,
        tab = tab,
        progress = progress,
        index = ELEMENT_RAIL + 1,
        modifier = modifier,
        trailing = if (count == null || full) null else {
            { TabButton(ADD_LABEL, c.accent, "Add a widget to the console", onAdd) }
        },
    ) {
        when {
            count == null -> Unit
            count == 0 -> WidgetLine(WIDGETS_EMPTY, c.muted)
            full -> WidgetLine(WIDGETS_FULL, c.muted)
        }
    }
}

/**
 * One widget, in a panel of its own under a tab carrying its name, with REMOVE beside it.
 *
 * ⚠️ REMOVE is on the TAB, not on a long press. A widget is another app's view, and it takes its own
 * touches — a long press on it reaches the widget and never the console.
 */
@Composable
internal fun WidgetPanel(
    widget: PlacedWidget,
    tab: Color,
    progress: () -> Float,
    actions: WidgetActions,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Pulse.colors
    ConsolePanel(
        title = HomeWidgets.title(widget.label),
        tab = tab,
        progress = progress,
        index = ELEMENT_RAIL + 1,
        modifier = modifier,
        trailing = { TabButton(REMOVE_LABEL, c.negative, "Remove ${widget.label}", onRemove) },
    ) {
        val view = remember(widget.id) { actions.view(widget.id) }
        if (view == null) {
            WidgetLine(WIDGET_UNAVAILABLE, c.muted)
        } else {
            val density = LocalDensity.current
            AndroidView(
                // The same view every time this item comes back into view — which means it may still
                // be the child of the holder Compose made for it last time, and a view can only
                // have one parent.
                factory = { _ -> (view.parent as? ViewGroup)?.removeView(view); view },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(widget.heightDp.dp)
                    .onSizeChanged { size ->
                        with(density) { actions.sized(widget.id, size.width.toDp().value, size.height.toDp().value) }
                    },
            )
        }
    }
}

/**
 * Every widget this phone offers, to pick one to add.
 *
 * ⚠️ **A scrolling Column, not a LazyColumn.** `LcarsDialog` measures itself with
 * `height(IntrinsicSize.Min)`, and a lazy list is a `SubcomposeLayout`, which refuses an intrinsic
 * query outright — it compiles perfectly and throws the moment the picker opens. A phone offers a few
 * dozen widgets, which is nothing to compose at once. Bounded, because a list taller than the screen
 * would take the dialog's own CLOSE button with it.
 */
@Composable
internal fun WidgetPicker(actions: WidgetActions, onDismiss: () -> Unit) {
    val c = Pulse.colors
    val cue = rememberLcarsCue()
    val offers by produceState<List<WidgetOffer>?>(null) { value = actions.offers() }
    LcarsDialog(title = PICKER_TITLE, onDismiss = onDismiss) {
        val list = offers
        when {
            list == null -> WidgetLine(PICKER_LOADING, c.muted, c.panel)
            list.isEmpty() -> WidgetLine(PICKER_EMPTY, c.muted, c.panel)
            else -> Column(
                Modifier
                    .heightIn(max = PICKER_MAX_HEIGHT)
                    .verticalScroll(rememberScrollState()),
            ) {
                list.forEach { offer ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clickable(onClickLabel = "Add ${offer.label}") {
                                cue(SoundCue.TAP, HapticCue.TAP_CRISP)
                                actions.add(offer)
                                onDismiss()
                            }
                            .padding(vertical = 7.dp),
                    ) {
                        Text(
                            offer.label,
                            fontFamily = ChakraPetch,
                            fontSize = 15.sp,
                            color = legibleOn(c.ink, c.panel),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            offer.app,
                            fontFamily = JetBrainsMono,
                            fontSize = 11.sp,
                            color = legibleOn(c.muted, c.panel),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/** Asks before a widget comes off the console: a mis-tap on REMOVE would lose its setup. */
@Composable
internal fun RemoveWidgetDialog(widget: PlacedWidget, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val c = Pulse.colors
    LcarsDialog(
        title = "Remove ${widget.label}",
        onDismiss = onDismiss,
        confirmText = REMOVE_LABEL,
        onConfirm = onConfirm,
    ) {
        WidgetLine(REMOVE_EXPLAINED, c.muted, c.panel)
    }
}

/** A solid block in a panel's tab row, lettered for its own colour — NOTICES' CLEAR, reused. */
@Composable
private fun TabButton(label: String, block: Color, clickLabel: String, onClick: () -> Unit) {
    val cue = rememberLcarsCue()
    Box(
        Modifier
            .background(block)
            .clickable(onClickLabel = clickLabel) { cue(SoundCue.TAP, HapticCue.TAP_CRISP); onClick() }
            .padding(horizontal = 10.dp, vertical = 3.dp),
    ) {
        Text(
            label,
            fontFamily = JetBrainsMono,
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp,
            color = textOnBlock(block),
        )
    }
}

@Composable
private fun WidgetLine(text: String, color: Color, ground: Color = Pulse.colors.void) {
    Text(
        text,
        fontFamily = JetBrainsMono,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        color = legibleOn(color, ground),
    )
}

private val PICKER_MAX_HEIGHT = 360.dp

private const val WIDGETS_TITLE = "Widgets"
private const val ADD_LABEL = "+ WIDGET"
private const val REMOVE_LABEL = "REMOVE"
private const val WIDGETS_EMPTY = "No widgets yet — + WIDGET adds one from any app on this phone."
/** Also the host's refusal when a full console is asked for another — one sentence, one place. */
internal val WIDGETS_FULL = "The console holds ${HomeWidgets.MAX_WIDGETS} widgets at most — REMOVE one to add another."
private const val WIDGET_UNAVAILABLE = "Android could not draw this widget. REMOVE takes it off."
private const val PICKER_TITLE = "Add a widget"
private const val PICKER_LOADING = "Reading the widgets on this phone…"
private const val PICKER_EMPTY = "No app on this phone offers a widget."
private const val REMOVE_EXPLAINED = "It comes off the console. The app that made it is not touched."
