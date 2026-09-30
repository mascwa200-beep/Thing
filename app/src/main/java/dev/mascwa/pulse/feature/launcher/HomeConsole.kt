package dev.mascwa.pulse.feature.launcher

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mascwa.pulse.feature.common.LcarsDialog
import dev.mascwa.pulse.feature.common.LcarsField
import dev.mascwa.pulse.feature.common.LcarsIcons
import dev.mascwa.pulse.feature.controls.ControlActions
import dev.mascwa.pulse.feature.controls.ControlsPanel
import dev.mascwa.pulse.feature.controls.ControlsState
import dev.mascwa.pulse.feature.controls.PhoneControls
import dev.mascwa.pulse.feature.lcarsboard.BoardTap
import dev.mascwa.pulse.feature.lcarsboard.ConsoleButton
import dev.mascwa.pulse.feature.lcarsboard.ConsolePanel
import dev.mascwa.pulse.feature.lcarsboard.ELEMENT_RAIL
import dev.mascwa.pulse.feature.lcarsboard.LcarsBoardPanels
import dev.mascwa.pulse.feature.lcarsboard.LcarsConsole
import dev.mascwa.pulse.feature.lcarsboard.PanelCapGap
import dev.mascwa.pulse.feature.lcarsboard.PanelCapWidth
import dev.mascwa.pulse.feature.lcarsboard.PanelTab
import dev.mascwa.pulse.feature.lcarsboard.arrive
import dev.mascwa.pulse.feature.lcarsboard.legibleOn
import dev.mascwa.pulse.feature.lcarsboard.textOnBlock
import dev.mascwa.pulse.feature.shade.NoticeActions
import dev.mascwa.pulse.feature.shade.NoticesPanel
import dev.mascwa.pulse.feature.shade.ShadeDigest
import dev.mascwa.pulse.ui.effects.HapticCue
import dev.mascwa.pulse.ui.effects.SoundCue
import dev.mascwa.pulse.ui.effects.rememberLcarsCue
import dev.mascwa.pulse.ui.theme.ChakraPetch
import dev.mascwa.pulse.ui.theme.JetBrainsMono
import dev.mascwa.pulse.ui.theme.LocalConsoleBlocks
import dev.mascwa.pulse.ui.theme.Pulse
import dev.mascwa.pulse.widget.WidgetBoard

/** What the home screen can be asked to do. One value, so the screen's signature stays readable. */
internal class HomeActions(
    val launch: (HomeApp) -> Unit,
    val togglePin: (HomeApp) -> Unit,
    val info: (HomeApp) -> Unit,
    val tap: (BoardTap) -> Unit,
    val openLcars: () -> Unit,
    val systemSettings: () -> Unit,
    val notices: NoticeActions,
    val controls: ControlActions,
    /** Android's Usage access page, which the recent-apps strip needs. */
    val grantUsage: () -> Unit,
)

/**
 * The home screen: the same LCARS console as the lock screen, with the phone's apps in it.
 *
 * From the top: the dock of pinned apps, the apps used most recently, a search box, the notification
 * centre, the quick controls, the widget's board, and every app A to Z under its letter. The header, rail and control bar stay put; everything else scrolls, so a long
 * agenda or a long directory is scrolled to and never cut off. Typing narrows the directory to what
 * matches and hides the board, because somebody searching wants the answer, not the weather.
 *
 * Black ground throughout; every word is either lettered for the solid block it sits on or lifted to
 * legibility against black — see `LcarsContrast`. App labels are other people's text in other
 * people's colours nowhere: they are drawn in the console's own ink.
 */
@Composable
internal fun HomeConsole(
    board: WidgetBoard.Board?,
    apps: List<HomeApp>?,
    pins: List<String>,
    query: String,
    onQuery: (String) -> Unit,
    notice: String?,
    noticeStatus: ShadeDigest.Status,
    notices: List<ShadeDigest.Group>,
    recent: List<HomeApp>,
    usageGranted: Boolean?,
    controls: ControlsState.Reading?,
    volumes: List<PhoneControls.Volume>,
    progress: () -> Float,
    list: LazyListState,
    icon: suspend (HomeApp) -> ImageBitmap?,
    actions: HomeActions,
) {
    val c = Pulse.colors
    val blocks = LocalConsoleBlocks.current
    fun block(i: Int): Color = if (blocks.isEmpty()) c.accent else blocks[i % blocks.size]
    var held by remember { mutableStateOf<HomeApp?>(null) }

    LcarsConsole(
        board = board,
        progress = progress,
        onTap = actions.tap,
        railSeed = RAIL_SEED,
        waitingLine = if (board == null) WAITING_LINE else null,
        bottom = {
            ConsoleButton(LCARS_LABEL, c.accent) { actions.openLcars() }
            ConsoleButton(SETTINGS_LABEL, block(2)) { actions.systemSettings() }
        },
        body = { modifier ->
            val all = apps
            val found = remember(all, query) {
                if (all == null || query.isBlank()) emptyList()
                else HomeDirectory.grouped(all.filter { HomeDirectory.matches(it.label, query) }) { it.label }
                    .flatMap { it.second }
            }
            val groups = remember(all) { all?.let { HomeDirectory.grouped(it) { app -> app.label } }.orEmpty() }
            val docked = remember(all, pins) { all?.let { HomeDirectory.docked(pins, it) { app -> app.key } }.orEmpty() }

            LazyColumn(
                modifier,
                state = list,
                contentPadding = PaddingValues(end = 4.dp, bottom = 16.dp),
            ) {
                item(key = "home:dock") {
                    Dock(docked, block(0), progress, icon, actions.launch) { held = it }
                }
                // Below the dock rather than above it: this strip comes and goes as apps are used,
                // and the dock is what a thumb reaches for without looking.
                item(key = "home:recent") {
                    RecentStrip(recent, pins, usageGranted, block(5), progress, icon, actions.launch, actions.grantUsage) { held = it }
                }
                item(key = "home:search") {
                    LcarsField(
                        value = query,
                        onValueChange = onQuery,
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp),
                        placeholder = SEARCH_HINT,
                        leadingIcon = LcarsIcons.Search,
                        imeAction = ImeAction.Go,
                        // The first match, which is what an enter key on a search box is for.
                        onImeAction = { found.firstOrNull()?.let(actions.launch) },
                    )
                }
                if (notice != null) {
                    item(key = "home:notice") { Line(notice, c.amber, Modifier.padding(vertical = 6.dp)) }
                }
                when {
                    all == null -> item(key = "home:loading") {
                        Line(LOADING_LINE, c.muted, Modifier.padding(top = 12.dp))
                    }
                    query.isNotBlank() -> {
                        if (found.isEmpty()) {
                            item(key = "home:nomatch") {
                                Line("No app on this phone matches “${query.trim()}”.", c.muted, Modifier.padding(top = 12.dp))
                            }
                        } else {
                            item(key = "home:found") { LetterTab("Found · ${found.size}", c.accent) }
                            items(found, key = { "found:${it.key}" }) { app ->
                                AppRow(app, c.accent, icon, actions.launch) { held = it }
                            }
                        }
                    }
                    else -> {
                        item(key = "home:notices") {
                            NoticesPanel(
                                status = noticeStatus,
                                groups = notices,
                                tab = block(1),
                                progress = progress,
                                index = ELEMENT_RAIL + 1,
                                actions = actions.notices,
                                modifier = Modifier.padding(top = 12.dp),
                            )
                        }
                        item(key = "home:controls") {
                            ControlsPanel(
                                reading = controls,
                                volumes = volumes,
                                tab = block(4),
                                progress = progress,
                                index = ELEMENT_RAIL + 1,
                                actions = actions.controls,
                                modifier = Modifier.padding(top = 12.dp),
                            )
                        }
                        if (board != null) {
                            item(key = "home:board") {
                                LcarsBoardPanels(
                                    board,
                                    progress,
                                    actions.tap,
                                    Modifier.fillMaxWidth().padding(top = 12.dp),
                                    agendaLimit = HOME_AGENDA_ROWS,
                                )
                            }
                        }
                        groups.forEachIndexed { i, (letter, members) ->
                            val tab = block(i + 3)
                            item(key = "home:letter:$letter") { LetterTab(letter.toString(), tab) }
                            items(members, key = { "app:${it.key}" }) { app ->
                                AppRow(app, tab, icon, actions.launch) { held = it }
                            }
                        }
                    }
                }
            }
        },
    )

    held?.let { app ->
        val pinned = app.key in pins
        LcarsDialog(
            title = app.label,
            onDismiss = { held = null },
            confirmText = if (pinned) "UNPIN" else "PIN TO DOCK",
            onConfirm = { actions.togglePin(app); held = null },
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Line(
                    if (app.work) "${app.component.packageName} · work profile" else app.component.packageName,
                    c.muted,
                )
                Row(Modifier.fillMaxWidth().height(40.dp)) {
                    ConsoleButton("App info", block(1)) { actions.info(app); held = null }
                }
            }
        }
    }
}

/** The pinned apps, four to a row, so every pin is on screen at once rather than behind a scroll. */
@Composable
private fun Dock(
    docked: List<HomeApp>,
    tab: Color,
    progress: () -> Float,
    icon: suspend (HomeApp) -> ImageBitmap?,
    onLaunch: (HomeApp) -> Unit,
    onHold: (HomeApp) -> Unit,
) {
    val c = Pulse.colors
    ConsolePanel(DOCK_TITLE, tab, progress, ELEMENT_RAIL + 1) {
        if (docked.isEmpty()) {
            Line(DOCK_EMPTY, c.muted)
        } else {
            docked.chunked(DOCK_PER_ROW).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEach { app -> DockTile(app, icon, onLaunch, onHold, Modifier.weight(1f)) }
                    // Keep a short last row's tiles the width of the rows above it.
                    repeat(DOCK_PER_ROW - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DockTile(
    app: HomeApp,
    icon: suspend (HomeApp) -> ImageBitmap?,
    onLaunch: (HomeApp) -> Unit,
    onHold: (HomeApp) -> Unit,
    modifier: Modifier,
) {
    val c = Pulse.colors
    val cue = rememberLcarsCue()
    Column(
        modifier
            .combinedClickable(
                onClickLabel = "Open ${app.label}",
                onLongClickLabel = "Pin or unpin ${app.label}",
                onLongClick = { cue(SoundCue.TAP, HapticCue.TAP_CRISP); onHold(app) },
            ) { cue(SoundCue.TAP, HapticCue.TAP_CRISP); onLaunch(app) }
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AppIcon(app, icon, DOCK_ICON)
        Spacer(Modifier.height(4.dp))
        Text(
            app.label,
            fontFamily = ChakraPetch,
            fontSize = 11.sp,
            color = legibleOn(c.ink, c.void),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * The apps used most recently, one row of four, newest first — Android's own recents screen belongs
 * to the system, so this is LCARS's.
 *
 * Nothing is drawn until Usage access has been read; without it, one line says what it needs and a
 * tap opens the page. With it and nothing to show — a fresh phone, or every recent app already
 * pinned — the strip is simply absent rather than an empty panel.
 *
 * Pinned apps are left out: the dock above already holds them, one tap away.
 */
@Composable
private fun RecentStrip(
    recent: List<HomeApp>,
    pins: List<String>,
    granted: Boolean?,
    tab: Color,
    progress: () -> Float,
    icon: suspend (HomeApp) -> ImageBitmap?,
    onLaunch: (HomeApp) -> Unit,
    onGrant: () -> Unit,
    onHold: (HomeApp) -> Unit,
) {
    val c = Pulse.colors
    val cue = rememberLcarsCue()
    when (granted) {
        null -> Unit
        false -> Line(
            RECENT_NEEDS_ACCESS,
            c.muted,
            Modifier
                .fillMaxWidth()
                .padding(top = 10.dp)
                .clickable(onClickLabel = "Open Usage access") { cue(SoundCue.TAP, HapticCue.TAP_CRISP); onGrant() },
        )
        true -> {
            val shown = remember(recent, pins) { recent.filter { it.key !in pins }.take(DOCK_PER_ROW) }
            if (shown.isEmpty()) return
            ConsolePanel(RECENT_TITLE, tab, progress, ELEMENT_RAIL + 1, Modifier.padding(top = 12.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    shown.forEach { app -> DockTile(app, icon, onLaunch, onHold, Modifier.weight(1f)) }
                    repeat(DOCK_PER_ROW - shown.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

/**
 * A letter heading in the directory: the board's own panel tab, so the apps are ruled the way the
 * board's regions are.
 */
@Composable
private fun LetterTab(title: String, tab: Color) {
    Box(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 4.dp)) { PanelTab(title, tab) }
}

/**
 * One app: its icon and name on black, with its section's colour running down the left edge.
 *
 * ⚠️ The cap is DRAWN, not laid out, and the rows carry no gap between them — so consecutive rows'
 * caps meet into one unbroken bar, and a section reads as a panel even though a lazy list cannot
 * put its rows inside one.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppRow(
    app: HomeApp,
    tab: Color,
    icon: suspend (HomeApp) -> ImageBitmap?,
    onLaunch: (HomeApp) -> Unit,
    onHold: (HomeApp) -> Unit,
) {
    val c = Pulse.colors
    val cue = rememberLcarsCue()
    Row(
        Modifier
            .fillMaxWidth()
            .drawBehind { drawRect(tab, size = Size(PanelCapWidth.toPx(), size.height)) }
            .combinedClickable(
                onClickLabel = "Open ${app.label}",
                onLongClickLabel = "Pin or unpin ${app.label}",
                onLongClick = { cue(SoundCue.TAP, HapticCue.TAP_CRISP); onHold(app) },
            ) { cue(SoundCue.TAP, HapticCue.TAP_CRISP); onLaunch(app) }
            .padding(start = PanelCapWidth + PanelCapGap, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(app, icon, ROW_ICON)
        Spacer(Modifier.width(12.dp))
        Text(
            app.label,
            fontFamily = ChakraPetch,
            fontSize = 17.sp,
            color = legibleOn(c.ink, c.void),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (app.work) {
            Spacer(Modifier.width(6.dp))
            WorkTag(c.violet)
        }
    }
}

/** Marks an app in the work profile, lettered for its own block. */
@Composable
private fun WorkTag(block: Color) {
    Box(
        Modifier
            .drawBehind { drawRect(block) }
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            "WORK",
            fontFamily = JetBrainsMono,
            fontWeight = FontWeight.Bold,
            fontSize = 9.sp,
            color = textOnBlock(block),
        )
    }
}

/**
 * An app's icon, loaded off the main thread the first time it is drawn. Until it lands — or if it
 * never can — the space is kept, so the label beside it does not jump sideways.
 */
@Composable
private fun AppIcon(app: HomeApp, icon: suspend (HomeApp) -> ImageBitmap?, size: Dp) {
    val bitmap by produceState<ImageBitmap?>(null, app.key) { value = icon(app) }
    Box(Modifier.size(size)) {
        bitmap?.let { Image(it, contentDescription = null, modifier = Modifier.fillMaxSize()) }
    }
}

@Composable
private fun Line(text: String, color: Color, modifier: Modifier = Modifier) {
    Text(
        text,
        fontFamily = JetBrainsMono,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        color = legibleOn(color, Pulse.colors.void),
        modifier = modifier,
    )
}

/** Icons are cached at this size once and drawn at it; the list draws them smaller. */
internal val DOCK_ICON = 48.dp
private val ROW_ICON = 40.dp

private const val DOCK_PER_ROW = 4
private const val HOME_AGENDA_ROWS = 8
private const val RAIL_SEED = "home-console"
private const val DOCK_TITLE = "Dock"
private const val DOCK_EMPTY = "Hold any app below to pin it here."
private const val RECENT_TITLE = "Recent"
private const val RECENT_NEEDS_ACCESS = "Recent apps need Usage access. Tap to allow it."
private const val SEARCH_HINT = "Find an app"
private const val LCARS_LABEL = "LCARS"
private const val SETTINGS_LABEL = "Settings"
private const val WAITING_LINE = "Reading the board…"
private const val LOADING_LINE = "Reading the apps on this phone…"
