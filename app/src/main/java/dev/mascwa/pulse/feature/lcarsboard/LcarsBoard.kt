package dev.mascwa.pulse.feature.lcarsboard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.IntentSender
import android.os.BatteryManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mascwa.pulse.feature.common.LcarsCorner
import dev.mascwa.pulse.feature.common.LcarsFillRow
import dev.mascwa.pulse.feature.common.lcarsBlockShape
import dev.mascwa.pulse.navigation.Routes
import dev.mascwa.pulse.ui.effects.HapticCue
import dev.mascwa.pulse.ui.effects.SoundCue
import dev.mascwa.pulse.ui.effects.rememberLcarsCue
import dev.mascwa.pulse.ui.theme.Antonio
import dev.mascwa.pulse.ui.theme.ChakraPetch
import dev.mascwa.pulse.ui.theme.JetBrainsMono
import dev.mascwa.pulse.ui.theme.LocalConsoleBlocks
import dev.mascwa.pulse.ui.theme.Pulse
import dev.mascwa.pulse.widget.CalendarIntents
import dev.mascwa.pulse.widget.WidgetBoard
import kotlinx.coroutines.delay
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * What a tap on the board asks for. The screen decides how to honour it — the lock console unlocks
 * first, the home screen simply goes.
 *
 * ⚠️ **A route is carried as a route, never as a PendingIntent built here.** The widget's own
 * `widgetOpenIntent` uses `FLAG_UPDATE_CURRENT` under fixed request codes, and `filterEquals` ignores
 * the route extra, so minting one of those from this screen would silently retarget the tap on the
 * placed widget that shares the code. The screen opens a route with an explicit intent instead.
 */
sealed interface BoardTap {
    data class Route(val route: String) : BoardTap

    /** Something the board was handed ready-made — an event to open, the calendar's add screen. */
    data class Sender(val sender: IntentSender, val fillIn: Intent? = null) : BoardTap
}

/**
 * The console's timeline has this many elements: the corner, the header bar, the rail, then panels
 * in order. Panels past the end share the last slot, so a long board still lands on time.
 */
const val CONSOLE_ELEMENTS = 12
const val ELEMENT_CORNER = 0
const val ELEMENT_HEADER = 1
const val ELEMENT_RAIL = 2
private const val FIRST_PANEL = 3

/**
 * An element arriving: fading up and sliding in from the rail side as its window of the timeline
 * plays. [progress] is read INSIDE the layer, so the sweep redraws without recomposing anything.
 */
fun Modifier.arrive(progress: () -> Float, index: Int): Modifier = graphicsLayer {
    val e = ConsoleSequence.element(progress(), index.coerceAtMost(CONSOLE_ELEMENTS - 1), CONSOLE_ELEMENTS)
    alpha = e
    translationX = (1f - e) * -40.dp.toPx()
}

/** A block of chrome growing out from its root: the corner and header sweep right, the rail drops. */
fun Modifier.sweep(progress: () -> Float, index: Int, vertical: Boolean = false): Modifier = graphicsLayer {
    val e = ConsoleSequence.element(progress(), index, CONSOLE_ELEMENTS)
    transformOrigin = if (vertical) TransformOrigin(0.5f, 0f) else TransformOrigin(0f, 0.5f)
    if (vertical) scaleY = e else scaleX = e
    alpha = if (e > 0f) 1f else 0f
}

/** [fg] as drawn on [bg], lifted to legibility if it would not read — see [LcarsContrast]. */
fun legibleOn(fg: Color, bg: Color): Color = Color(LcarsContrast.legible(fg.toArgb(), bg.toArgb()))

/** The text colour for a solid block of [block]. */
fun textOnBlock(block: Color): Color = Color(LcarsContrast.onBlock(block.toArgb()))

/**
 * The console's header bar: the time, large, and the board's own header and subhead beside it.
 *
 * Drawn on a solid block, so every word on it takes [textOnBlock] — black on the ordinary gold,
 * white on the darkest red-alert blocks. Tapping it opens what the widget's header opens.
 */
@Composable
internal fun LcarsBoardHeader(
    board: WidgetBoard.Board?,
    block: Color,
    onTap: (BoardTap) -> Unit,
    modifier: Modifier = Modifier,
    waitingLine: String? = null,
    topInset: Dp = 0.dp,
) {
    val ink = textOnBlock(block)
    val clock = rememberClock(board?.clock24 ?: true)
    val battery = rememberBattery()
    val headerRoute = board?.headerRoute
    Row(
        modifier
            .background(block)
            .then(if (headerRoute != null) Modifier.clickable { onTap(BoardTap.Route(headerRoute)) } else Modifier)
            // The block is drawn from the top edge; what is written on it starts below the camera.
            .padding(top = topInset)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            clock,
            fontFamily = Antonio,
            fontWeight = FontWeight.Bold,
            fontSize = 44.sp,
            color = ink,
            maxLines = 1,
        )
        // What the hidden status bar used to say that still matters. Absent, never "0%", when
        // Android has not reported a level.
        if (battery != null) {
            Spacer(Modifier.width(8.dp))
            Text(
                ConsoleBattery.label(battery),
                fontFamily = JetBrainsMono,
                fontSize = 11.sp,
                letterSpacing = 1.sp,
                color = ink,
                maxLines = 1,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
            Text(
                (board?.header ?: "LCARS").uppercase(),
                fontFamily = Antonio,
                fontSize = 15.sp,
                letterSpacing = 1.5.sp,
                color = ink,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.End,
            )
            val sub = board?.subhead?.takeIf { it.isNotBlank() } ?: waitingLine
            if (sub != null) {
                Text(
                    sub.uppercase(),
                    fontFamily = JetBrainsMono,
                    fontSize = 9.sp,
                    letterSpacing = 1.sp,
                    color = ink,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.End,
                )
            }
        }
    }
}

/**
 * Every region of the widget's board, each in its own black panel under a solid coloured tab.
 *
 * ⚠️ **Nothing the widget shows may go missing here** — `LcarsBoardCoverageTest` fails the build if
 * a field of [WidgetBoard.Board] is not read in this package. A region with nothing to say is
 * absent rather than drawn empty, exactly as on the widget.
 *
 * [agendaLimit] caps the agenda for the home screen, where a fortnight of events would bury the
 * apps; what is left out is COUNTED on a line that opens the calendar, never silently dropped.
 */
@Composable
internal fun LcarsBoardPanels(
    board: WidgetBoard.Board,
    progress: () -> Float,
    onTap: (BoardTap) -> Unit,
    modifier: Modifier = Modifier,
    agendaLimit: Int? = null,
) {
    val c = Pulse.colors
    val blocks = LocalConsoleBlocks.current
    val context = LocalContext.current
    var slot = FIRST_PANEL
    fun next() = slot++
    fun tab(i: Int): Color = if (blocks.isEmpty()) c.accent else blocks[i % blocks.size]

    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        board.upNext?.takeIf { it.isNotBlank() }?.let { line ->
            val open = board.upNextOpen
            ConsolePanel("Up next", tab(0), progress, next()) {
                BoardLine(
                    line,
                    color = board.upNextArgb?.let { Color(it) } ?: c.ink,
                    size = 16.sp,
                    onClick = open?.let { { onTap(BoardTap.Sender(it.intentSender)) } },
                )
            }
        }

        board.alert?.takeIf { it.isNotBlank() }?.let { alert ->
            ConsolePanel("Alert", c.negative, progress, next()) {
                BoardLine(alert, color = c.negative, size = 15.sp, onClick = board.alertRoute?.route(onTap))
            }
        }

        val lead = board.lead?.takeIf { it.isNotBlank() }
        val detail = board.leadDetail?.takeIf { it.isNotBlank() }
        val sources = board.sources.filter { it.isNotBlank() }
        if (lead != null || detail != null || sources.isNotEmpty()) {
            ConsolePanel("News", tab(1), progress, next()) {
                lead?.let {
                    BoardLine(
                        it,
                        color = board.leadArgb?.let { argb -> Color(argb) } ?: c.ink2,
                        size = 16.sp,
                        bold = true,
                        onClick = board.leadRoute?.route(onTap),
                    )
                }
                detail?.let { BoardLine(it, color = c.muted, size = 13.sp) }
                sources.forEach { BoardLine("▸ $it", color = c.ink, size = 13.sp, onClick = board.sourcesRoute?.route(onTap)) }
            }
        }

        val breadth = board.breadth?.takeIf { it.isNotBlank() }
        val pct = board.breadthPct
        if (board.indices.isNotEmpty() || board.stocks.isNotEmpty() || breadth != null || pct != null) {
            val markets = Routes.MARKETS.route(onTap)
            ConsolePanel("Markets", tab(2), progress, next(), onTitle = markets) {
                if (board.indices.isNotEmpty()) Cells(board.indicesLabel, board.indices, markets)
                if (board.stocks.isNotEmpty()) Cells(board.stocksLabel, board.stocks, markets)
                breadth?.let { BoardLine(it, color = c.ink, size = 13.sp, mono = true, onClick = markets) }
                pct?.let { p ->
                    val up = p.coerceIn(0, 100) / 100f
                    LcarsFillRow(
                        listOf(up to c.positive, (1f - up) to c.negative),
                        Modifier.fillMaxWidth().height(8.dp),
                        gap = 2.dp,
                    )
                }
            }
        }

        val readouts = board.readouts.filter { it.text.isNotBlank() }
        if (readouts.isNotEmpty()) {
            ConsolePanel("Readings", tab(3), progress, next()) {
                readouts.forEach { r -> BoardLine(r.text, color = c.ink, size = 13.sp, mono = true, onClick = r.route?.route(onTap)) }
            }
        }

        board.pairs.forEach { (left, right) ->
            val index = next()
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf(left, right).forEachIndexed { side, col ->
                    val shown = col.label.isNotBlank() || col.lines.any { it.isNotBlank() }
                    if (shown) {
                        ConsolePanel(col.label.ifBlank { "·" }, tab(4 + side), progress, index, Modifier.weight(1f), onTitle = col.route?.route(onTap)) {
                            col.lines.filter { it.isNotBlank() }.forEach {
                                BoardLine(it, color = c.ink, size = 12.sp, mono = true, onClick = col.route?.route(onTap))
                            }
                        }
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        }

        board.agenda?.let { block ->
            val day = BoardTap.Sender(CalendarIntents.day(context, block.todayMs).intentSender)
            ConsolePanel(
                block.heading,
                tab(6),
                progress,
                next(),
                onTitle = { onTap(day) },
                trailing = {
                    BlockButton("+ ADD", tab(6)) { onTap(BoardTap.Sender(CalendarIntents.addEvent(context).intentSender)) }
                },
            ) {
                if (block.rows.isEmpty()) {
                    block.empty?.takeIf { it.isNotBlank() }?.let { empty ->
                        val target = if (block.emptyOpensSettings) CalendarIntents.openAppDetails(context) else CalendarIntents.day(context, block.todayMs)
                        BoardLine(empty, color = c.muted, size = 13.sp, onClick = { onTap(BoardTap.Sender(target.intentSender)) })
                    }
                } else {
                    val template = CalendarIntents.template(context).intentSender
                    val shown = agendaLimit?.let { block.rows.take(it) } ?: block.rows
                    shown.forEach { row -> AgendaLine(row) { onTap(BoardTap.Sender(template, row.fillIn)) } }
                    val rest = block.rows.size - shown.size
                    if (rest > 0) {
                        BoardLine(
                            "+ $rest more in your calendar",
                            color = c.muted,
                            size = 12.sp,
                            mono = true,
                            onClick = { onTap(day) },
                        )
                    }
                }
            }
        }

        board.foot?.takeIf { it.isNotBlank() }?.let { foot ->
            ConsolePanel("System", tab(7), progress, next()) {
                BoardLine(foot, color = c.muted, size = 12.sp, mono = true, onClick = board.footRoute?.route(onTap))
            }
        }

        board.degraded?.takeIf { it.isNotBlank() }?.let { degraded ->
            ConsolePanel("Feeds down", c.amber, progress, next()) {
                BoardLine(degraded, color = c.amber, size = 13.sp, onClick = board.degradedRoute?.route(onTap))
            }
        }

        board.needsYou?.takeIf { it.isNotBlank() }?.let { needs ->
            ConsolePanel("Needs you", c.accent, progress, next()) {
                BoardLine(needs, color = c.accent, size = 13.sp, onClick = board.needsYouRoute?.route(onTap))
            }
        }
    }
}

// ── pieces ──────────────────────────────────────────────────────────────────────────────────────

private fun String.route(onTap: (BoardTap) -> Unit): () -> Unit = { onTap(BoardTap.Route(this)) }

/** Width of the coloured cap down a panel's left edge, and the gap between it and the words. */
internal val PanelCapWidth = 8.dp
internal val PanelCapGap = 10.dp

/**
 * One region: a solid cap down its left edge, a solid tab carrying its name, and its lines on black.
 *
 * The cap is DRAWN rather than laid out, so the panel needs no intrinsic measurement to know how
 * tall its cap should be — it is simply as tall as the panel turned out.
 *
 * Shared with the home screen, so its dock is a panel of the same console rather than a lookalike.
 */
@Composable
internal fun ConsolePanel(
    title: String,
    tab: Color,
    progress: () -> Float,
    index: Int,
    modifier: Modifier = Modifier,
    onTitle: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .arrive(progress, index)
            .drawBehind {
                drawRoundRect(
                    color = tab,
                    size = Size(PanelCapWidth.toPx(), size.height),
                    cornerRadius = CornerRadius(PanelCapWidth.toPx() / 2f),
                )
            }
            .padding(start = PanelCapWidth + PanelCapGap),
    ) {
        PanelTab(title, tab, onTitle, trailing)
        Column(
            Modifier.padding(top = 6.dp, bottom = 2.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            content = content,
        )
    }
}

/**
 * A panel's title: a solid tab carrying the name, lettered for its own colour, and a bar of the same
 * colour running out to the edge. Tapping the tab opens what [onTitle] names.
 *
 * Also the home screen's letter headings, so the directory is ruled the way the board is.
 */
@Composable
internal fun PanelTab(
    title: String,
    tab: Color,
    onTitle: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val cue = rememberLcarsCue()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .clip(lcarsBlockShape(8.dp, LcarsCorner.TopEnd))
                .background(tab)
                .then(
                    if (onTitle != null) {
                        Modifier.clickable { cue(SoundCue.TAP, HapticCue.TAP_CRISP); onTitle() }
                    } else {
                        Modifier
                    },
                )
                .padding(horizontal = 10.dp, vertical = 3.dp),
        ) {
            Text(
                title.uppercase(),
                fontFamily = Antonio,
                fontSize = 14.sp,
                letterSpacing = 1.5.sp,
                color = textOnBlock(tab),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(4.dp))
        Box(Modifier.weight(1f).height(6.dp).background(tab))
        if (trailing != null) {
            Spacer(Modifier.width(4.dp))
            trailing()
        }
    }
}

/** A line of text on the panel's black ground, lifted to legibility whatever colour it was handed. */
@Composable
private fun BoardLine(
    text: String,
    color: Color,
    size: TextUnit,
    mono: Boolean = false,
    bold: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val cue = rememberLcarsCue()
    Text(
        text,
        fontFamily = if (mono) JetBrainsMono else ChakraPetch,
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        fontSize = size,
        lineHeight = size * 1.3f,
        color = legibleOn(color, Pulse.colors.void),
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable { cue(SoundCue.TAP, HapticCue.TAP_CRISP); onClick() } else Modifier),
    )
}

/** A small solid capsule control, lettered for its own colour. */
@Composable
private fun BlockButton(label: String, color: Color, onClick: () -> Unit) {
    val cue = rememberLcarsCue()
    Box(
        Modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(color)
            .clickable { cue(SoundCue.TAP, HapticCue.TAP_CRISP); onClick() }
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Text(label, fontFamily = JetBrainsMono, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = textOnBlock(color))
    }
}

/** A strip of market instruments, three to a row, each with its own sparkline when one was drawn. */
@Composable
private fun Cells(label: String, cells: List<WidgetBoard.Cell>, onClick: () -> Unit) {
    val c = Pulse.colors
    if (label.isNotBlank()) BoardLine(label.uppercase(), color = c.muted, size = 10.sp, mono = true)
    cells.chunked(CELLS_PER_ROW).forEach { row ->
        Row(Modifier.fillMaxWidth().clickable(onClick = onClick), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            row.forEach { cell ->
                Column(Modifier.weight(1f)) {
                    Text(cell.label, fontFamily = JetBrainsMono, fontSize = 10.sp, color = legibleOn(c.muted, c.void), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        cell.value,
                        fontFamily = JetBrainsMono,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = legibleOn(colorResource(cell.colorRes), c.void),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    cell.chart?.let { chart ->
                        Image(
                            chart.asImageBitmap(),
                            contentDescription = null,
                            contentScale = ContentScale.FillBounds,
                            modifier = Modifier.fillMaxWidth().height(22.dp),
                        )
                    }
                }
            }
            repeat(CELLS_PER_ROW - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

/** One agenda row: a day heading, or an event with its calendar's colour down the side. */
@Composable
private fun AgendaLine(row: WidgetBoard.AgendaRow, onClick: () -> Unit) {
    val c = Pulse.colors
    val cue = rememberLcarsCue()
    when (row.kind) {
        WidgetBoard.AgendaKind.DAY -> Text(
            row.text.uppercase(),
            fontFamily = JetBrainsMono,
            fontSize = 11.sp,
            letterSpacing = 1.sp,
            color = legibleOn(row.textArgb?.let { Color(it) } ?: c.muted, c.void),
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp).clickable { cue(SoundCue.TAP, HapticCue.TAP_CRISP); onClick() },
        )
        WidgetBoard.AgendaKind.EVENT -> Row(
            Modifier.fillMaxWidth().clickable { cue(SoundCue.TAP, HapticCue.TAP_CRISP); onClick() },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .width(4.dp)
                    .height(28.dp)
                    .background(legibleOn(row.barArgb?.let { Color(it) } ?: c.muted, c.void)),
            )
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    row.text,
                    fontFamily = ChakraPetch,
                    fontSize = 14.sp,
                    color = legibleOn(row.textArgb?.let { Color(it) } ?: c.ink, c.void),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                row.detail?.takeIf { it.isNotBlank() }?.let {
                    Text(it, fontFamily = JetBrainsMono, fontSize = 10.sp, color = legibleOn(c.muted, c.void), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/**
 * The time, ticking on the minute, in the app's own 12- or 24-hour choice.
 *
 * `delay` schedules nothing with the platform, so this cannot wake the phone; a minute slept through
 * simply arrives late and the next pass recomputes the boundary from the clock as it then is.
 */
@Composable
private fun rememberClock(clock24: Boolean): String {
    val pattern = if (clock24) "HH:mm" else "h:mm a"
    val text by produceState(initialValue = clockNow(pattern), pattern) {
        while (true) {
            val now = System.currentTimeMillis()
            delay(MINUTE_MS - now % MINUTE_MS + 50L)
            value = clockNow(pattern)
        }
    }
    return text
}

/**
 * The battery, as Android reports it. `ACTION_BATTERY_CHANGED` is sticky, so registering answers at
 * once with the current reading and every change after that arrives on its own — nothing polls.
 *
 * ⚠️ No export flag, as for every protected system broadcast in this app: this is one only the
 * system can send.
 */
@Composable
private fun rememberBattery(): ConsoleBattery.Reading? {
    val context = LocalContext.current
    var reading by remember { mutableStateOf<ConsoleBattery.Reading?>(null) }
    DisposableEffect(context) {
        fun read(intent: Intent?) {
            if (intent == null) return
            reading = ConsoleBattery.reading(
                level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1),
                scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1),
                plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0),
            )
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) = read(intent)
        }
        val registered = runCatching {
            read(context.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED)))
        }.isSuccess
        onDispose { if (registered) runCatching { context.unregisterReceiver(receiver) } }
    }
    return reading
}

private fun clockNow(pattern: String): String =
    LocalTime.now().format(DateTimeFormatter.ofPattern(pattern, Locale.getDefault()))

private const val CELLS_PER_ROW = 3
private const val MINUTE_MS = 60_000L
