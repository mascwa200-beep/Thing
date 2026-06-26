package dev.mascwa.pulse.feature.spaceballs

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mascwa.pulse.data.settings.ThemeMode
import dev.mascwa.pulse.feature.settings.SettingsViewModel
import dev.mascwa.pulse.ui.theme.JetBrainsMono
import kotlinx.coroutines.delay
import kotlin.math.sin
import kotlin.random.Random

/* -------------------------------------------------------------------------------------------------
 * The BRIDGE CONSOLE: a Spaceballs (1987) tribute. Pure UI theater — gloriously over-engineered,
 * bureaucratically absurd, and almost entirely non-functional. The only buttons that do anything are
 * hidden in the Manual Override panel, where mundane settings wear catastrophic warning labels.
 * Self-contained grey/silver/black look, deliberately separate from the rest of the app. (Warrning:
 * this comment is the most useful thing in the file.)
 * ------------------------------------------------------------------------------------------------- */

private object Sb {
    val base = Color(0xFF343434)
    val panel = Color(0xFF2A2A2A)
    val well = Color(0xFF0A0A0A)
    val silver = Color(0xFFE8E8E8)
    val edgeLight = Color(0xFF6E6E6E)
    val edgeDark = Color(0xFF101010)
    val green = Color(0xFF39FF14)
    val amber = Color(0xFFFFB81C)
    val red = Color(0xFFDC143C)
    val dim = Color(0xFF8C8C8C)
}

/** Hard, two-tone bevel — light top/left, dark bottom/right (or inverted for a recessed look). */
private fun Modifier.beveled(raised: Boolean = true): Modifier = drawBehind {
    val w = 2.dp.toPx()
    val tl = if (raised) Sb.edgeLight else Sb.edgeDark
    val br = if (raised) Sb.edgeDark else Sb.edgeLight
    drawRect(tl, Offset.Zero, Size(size.width, w))
    drawRect(tl, Offset.Zero, Size(w, size.height))
    drawRect(br, Offset(0f, size.height - w), Size(size.width, w))
    drawRect(br, Offset(size.width - w, 0f), Size(w, size.height))
}

/** A faint CRT scanline texture drawn over the content. */
private fun Modifier.scanlines(): Modifier = drawWithContent {
    drawContent()
    val gap = 3.dp.toPx()
    var y = 0f
    while (y < size.height) {
        drawRect(Color.Black.copy(alpha = 0.13f), Offset(0f, y), Size(size.width, 1f))
        y += gap
    }
}

@Composable
private fun mono(
    text: String,
    color: Color = Sb.silver,
    size: Float = 11f,
    weight: FontWeight = FontWeight.Normal,
    align: TextAlign? = null,
    spacing: Float = 0.5f,
    modifier: Modifier = Modifier,
) = Text(
    text, modifier = modifier, color = color, fontFamily = JetBrainsMono, fontSize = size.sp,
    fontWeight = weight, textAlign = align, letterSpacing = spacing.sp,
)

@Composable
fun SpaceballsScreen(vm: SettingsViewModel) {
    val s by vm.settings.collectAsStateWithLifecycle()

    // Meaningless live state — increments forever, reflects nothing.
    var tick by remember { mutableStateOf(0) }
    var uptime by remember { mutableStateOf(8_675_309L) }
    LaunchedEffect(Unit) {
        while (true) { delay(420); tick += 1; uptime += 7 }
    }

    var activeCmd by remember { mutableStateOf<String?>(null) }
    var cmdPhase by remember { mutableStateOf(0) } // 0 = processing, 1 = complete
    LaunchedEffect(activeCmd) {
        if (activeCmd != null) { cmdPhase = 0; delay(2600); cmdPhase = 1 }
    }

    Box(Modifier.fillMaxSize().background(Sb.base).scanlines()) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            BridgeHeader(uptime, tick)
            AlertTicker()
            MockWarning()
            StatusPanel(tick)
            CommandConsole(onCommand = { activeCmd = it })
            AccessLog(tick)
            ManualOverride(
                themeDark = s.theme == ThemeMode.DARK,
                amoled = s.amoledBlack,
                scanlines = s.scanlines,
                onTheme = { vm.update { st -> st.copy(theme = if (st.theme == ThemeMode.DARK) ThemeMode.LIGHT else ThemeMode.DARK) } },
                onAmoled = { vm.update { st -> st.copy(amoledBlack = !st.amoledBlack) } },
                onScanlines = { vm.update { st -> st.copy(scanlines = !st.scanlines) } },
            )
            BridgeFooter()
            Spacer(Modifier.height(8.dp))
        }

        if (activeCmd != null) {
            ProcessingOverlay(
                command = activeCmd!!,
                phase = cmdPhase,
                spinner = SPINNER[tick % SPINNER.size],
                onDismiss = { activeCmd = null },
            )
        }
    }
}

private val SPINNER = listOf("|", "/", "—", "\\")

@Composable
private fun BridgeHeader(uptime: Long, tick: Int) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        mono("ADVISORY–ADMINISTRATIVE", Sb.amber, 8f, spacing = 2f, align = TextAlign.Center)
        mono("ARGUS DYNAMICS // BRIDGE CONSOLE", Sb.silver, 17f, FontWeight.Bold, TextAlign.Center, 1f,
            Modifier.fillMaxWidth())
        mono("SPACEBALL ONE · DECK 1 · PANEL IS PURELY DECORATIVE", Sb.dim, 8f, align = TextAlign.Center,
            modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Readout("UPTIME", "${uptime}s", Sb.green, Modifier.weight(1f))
            Readout("LOAD", "87%", Sb.green, Modifier.weight(1f))
            Readout("SCHWARTZ", "STABLE", Sb.amber, Modifier.weight(1f))
        }
    }
}

@Composable
private fun Readout(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Column(modifier.beveled(false).background(Sb.well).padding(horizontal = 8.dp, vertical = 6.dp)) {
        mono("SYS: $label", Sb.dim, 7f, spacing = 1f)
        mono(value, color, 13f, FontWeight.Bold)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AlertTicker() {
    val text = listOf(
        "WARNING: AUXILIARY POWER CONDUIT 7-B HUMMING AT UNUSUAL FREQUENCY",
        "ADVISORY: TUESDAY IS LIKELY TO OCCUR",
        "CRITICAL: PRINTER OUT OF TONER (AGAIN)",
        "SYS: COMBOLINGUS LEVELS NOMINAL",
        "ADVISORY–ADMINISTRATIVE: FORM 27-B/6 REMAINS UNFILED",
        "WARRNING: SPELL-CHECK SUBSYSTEM OFFLINE",
        "SYS: SCHWARTZ FIELD STABLE — DO NOT INVERT",
    ).joinToString("   ////   ")
    Box(
        Modifier.fillMaxWidth().beveled(false).background(Sb.well).padding(vertical = 5.dp, horizontal = 8.dp),
    ) {
        mono(text, Sb.amber, 10f, spacing = 1f, modifier = Modifier.fillMaxWidth().basicMarquee())
    }
}

@Composable
private fun MockWarning() {
    var ack by remember { mutableStateOf(false) }
    if (ack) return
    Row(
        Modifier.fillMaxWidth().beveled().background(Sb.panel).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            mono("WARN: BRIDGE CONSOLE INITIALIZED", Sb.amber, 11f, FontWeight.Bold)
            mono("All systems nominal. No action is required, possible, or advisable.", Sb.dim, 9f)
        }
        SbButton("ACKNOWLEDGE", Sb.amber) { ack = true }
    }
}

@Composable
private fun StatusPanel(tick: Int) {
    Panel("STATUS DISPLAY — VERIFIED (PROBABLY)") {
        val animated = listOf(
            Triple("SHIP INTEGRITY", 0, Sb.green),
            Triple("LIFE SUPPORT", 11, Sb.amber),
            Triple("CABIN PRESSURE", 23, Sb.green),
            Triple("PRINTER TONER", 37, Sb.red),
        )
        animated.forEach { (label, seed, color) ->
            val pct = (62 + (sin((tick + seed) / 4.0) * 22).toInt()).coerceIn(2, 99)
            MetricRow(label, "$pct%", color, pct)
        }
        // Permanently impossible / bureaucratic readouts.
        MetricRow("CPU LOAD", "87%", Sb.green, 87)
        MetricRow("FUEL RESERVES", "0% — SHIP IN MOTION", Sb.red, 0)
        MetricRow("NAVIGATION", "OFFLINE — AT FTL SPEED", Sb.red, null)
        MetricRow("DIPLOMATIC RELATIONS", "UNKNOWN", Sb.dim, null)
        MetricRow("MEMO DISTRIBUTION", "412 PENDING", Sb.amber, null)
    }
}

@Composable
private fun MetricRow(label: String, value: String, color: Color, pct: Int?) {
    Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            mono(label, Sb.silver, 10f, spacing = 1f)
            mono(value, color, 10f, FontWeight.Bold)
        }
        if (pct != null) {
            Spacer(Modifier.height(3.dp))
            PixelBar(pct, color)
        }
    }
}

@Composable
private fun PixelBar(pct: Int, color: Color) {
    val cells = 24
    val on = (pct * cells / 100).coerceIn(0, cells)
    Row(Modifier.fillMaxWidth().height(7.dp), horizontalArrangement = Arrangement.spacedBy(1.dp)) {
        repeat(cells) { i ->
            Box(Modifier.weight(1f).height(7.dp).background(if (i < on) color else Sb.edgeDark))
        }
    }
}

@Composable
private fun CommandConsole(onCommand: (String) -> Unit) {
    Panel("COMMAND CONSOLE — AUTHORIZED USE ONLY") {
        val cmds = listOf(
            "INITIALIZE NAVIGATION SUBSYSTEM",
            "QUERY DEEP SPACE PROTOCOLS",
            "RESET COFFEE MAKER",
            "AUDIT THE AUDIT LOG",
            "ENGAGE LUDICROUS SPEED",
            "DISPENSE BEVERAGE MODULE",
            "SYNERGIZE INTERFACE MATRICES",
            "REALIGN PARADIGMATIC VERTICALS",
        )
        // Two-column grid of physical-feeling buttons.
        cmds.chunked(2).forEach { rowCmds ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rowCmds.forEach { cmd ->
                    ConsoleKey(cmd, Modifier.weight(1f)) { onCommand(cmd) }
                }
                if (rowCmds.size == 1) Spacer(Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun ConsoleKey(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var pressed by remember { mutableStateOf(false) }
    LaunchedEffect(pressed) { if (pressed) { delay(140); pressed = false } }
    Box(
        modifier
            .height(58.dp)
            .beveled(raised = !pressed)
            .background(if (pressed) Sb.silver.copy(alpha = 0.20f) else Sb.panel)
            .clickable { pressed = true; onClick() }
            .padding(6.dp),
        contentAlignment = Alignment.Center,
    ) {
        mono(label, if (pressed) Sb.green else Sb.silver, 9f, FontWeight.Bold, TextAlign.Center, 0.6f)
    }
}

@Composable
private fun AccessLog(tick: Int) {
    val lines = remember { mutableStateListOf<String>() }
    var clock by remember { mutableStateOf(52_327L) } // fake HH:MM:SS source
    LaunchedEffect(Unit) {
        while (true) {
            clock += Random.nextInt(1, 4)
            val h = (clock / 3600) % 24; val m = (clock / 60) % 60; val sec = clock % 60
            val stamp = "%02d:%02d:%02d".format(h, m, sec)
            lines.add(0, "$stamp | ${LOG_EVENTS.random()}")
            if (lines.size > 14) lines.removeAt(lines.size - 1)
            delay(1400)
        }
    }
    Panel("ACCESS LOG VIEWER — [SCROLLING]") {
        Column(Modifier.fillMaxWidth().beveled(false).background(Sb.well).padding(8.dp)) {
            if (lines.isEmpty()) mono("…awaiting events…", Sb.dim, 9f)
            lines.forEach { line ->
                val color = when {
                    "DELETED" in line || "ROGUE" in line -> Sb.red
                    "VERIFIED" in line || "SUCCESS" in line -> Sb.green
                    else -> Sb.dim
                }
                mono(line, color, 9f, spacing = 0.4f, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

private val LOG_EVENTS = listOf(
    "USER ACCESSED BRIDGE CONSOLE",
    "AUTHORIZATION VERIFIED (PROBABLY)",
    "ENVIRONMENTAL CONTROLS QUERIED",
    "ROGUE PROCESS DETECTED: [DELETED BY SECURITY]",
    "COFFEE MAKER PINGED — NO REPLY",
    "AUDIT LOG AUDITED SUCCESSFULLY",
    "MEMO 412 ESCALATED TO COMMITTEE",
    "INERTIAL DAMPENERS RECALIBRATED (UNCHANGED)",
    "PERMISSION ELEVATED, THEN REVOKED",
    "SCHWARTZ RING POLISHED",
    "FORM 27-B/6 STAMPED 'PENDING'",
    "SUBSYSTEM 7-B HUMMED OMINOUSLY",
)

@Composable
private fun ManualOverride(
    themeDark: Boolean,
    amoled: Boolean,
    scanlines: Boolean,
    onTheme: () -> Unit,
    onAmoled: () -> Unit,
    onScanlines: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().beveled().background(Sb.panel)) {
        Row(
            Modifier.fillMaxWidth().clickable { open = !open }.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                mono("MANUAL OVERRIDE", Sb.red, 12f, FontWeight.Bold, spacing = 1f)
                mono("AUTHORIZED PERSONNEL ONLY · DO NOT TOUCH", Sb.dim, 8f)
            }
            mono(if (open) "▼ OPEN" else "▶ SEALED", Sb.red, 10f, FontWeight.Bold)
        }
        if (open) {
            Column(Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, bottom = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                mono("If you know what these do, you have too much clearance.", Sb.amber, 8f)
                OverrideSwitch("INITIATE CASCADE PROTOCOL", "actually: dark ↔ light theme", themeDark, onTheme)
                OverrideSwitch("EMERGENCY SYSTEMS SHUTDOWN", "actually: true-black surfaces", amoled, onAmoled)
                OverrideSwitch("RECALIBRATE INERTIAL DAMPENERS", "actually: CRT scanline FX", scanlines, onScanlines)
            }
        }
    }
}

@Composable
private fun OverrideSwitch(label: String, real: String, on: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().beveled(raised = !on)
            .background(if (on) Sb.red.copy(alpha = 0.16f) else Sb.well)
            .clickable { onToggle() }.padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f)) {
            mono("CRITICAL: $label", if (on) Sb.red else Sb.silver, 10f, FontWeight.Bold)
            mono(real, Sb.dim, 8f)
        }
        mono(if (on) "● ARMED" else "○ SAFE", if (on) Sb.red else Sb.green, 10f, FontWeight.Bold)
    }
}

@Composable
private fun BridgeFooter() {
    Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        mono("STATUS: UNDER REVIEW (SINCE 2847)", Sb.dim, 8f, modifier = Modifier.fillMaxWidth(),
            align = TextAlign.Center)
        mono("LAST ACCESSED BY: SYSTEM ADMIN (UNAUTHORIZED)", Sb.dim, 8f,
            modifier = Modifier.fillMaxWidth(), align = TextAlign.Center)
        mono("all systems nominal (citation needed) — we ain't found shit", Sb.edgeLight, 7f,
            modifier = Modifier.fillMaxWidth(), align = TextAlign.Center)
    }
}

@Composable
private fun Panel(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().beveled().background(Sb.panel)) {
        Box(Modifier.fillMaxWidth().background(Sb.edgeDark).padding(horizontal = 8.dp, vertical = 4.dp)) {
            mono(title, Sb.amber, 9f, FontWeight.Bold, spacing = 1.5f)
        }
        Column(Modifier.fillMaxWidth().padding(10.dp)) { content() }
    }
}

@Composable
private fun SbButton(label: String, color: Color, onClick: () -> Unit) {
    Box(
        Modifier.beveled().background(Sb.base).clickable { onClick() }.padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        mono(label, color, 10f, FontWeight.Bold, spacing = 1f)
    }
}

@Composable
private fun ProcessingOverlay(command: String, phase: Int, spinner: String, onDismiss: () -> Unit) {
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.93f)).scanlines()
            .clickable(enabled = phase == 1) { onDismiss() },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.fillMaxWidth(0.86f).beveled().background(Sb.panel).padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (phase == 0) {
                mono("$spinner  PROCESSING  $spinner", Sb.green, 18f, FontWeight.Bold, TextAlign.Center, 2f,
                    Modifier.fillMaxWidth())
                mono("EXECUTING: $command", Sb.silver, 10f, align = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                mono("PLEASE STAND BY · SYSTEM LOAD AT 87%", Sb.amber, 9f, align = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth())
                mono("ESTIMATED TIME REMAINING: CALCULATING…", Sb.dim, 9f, align = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(4.dp))
                PixelBar(87, Sb.green)
            } else {
                mono("OPERATION COMPLETE", Sb.green, 18f, FontWeight.Bold, TextAlign.Center, 2f, Modifier.fillMaxWidth())
                mono(command, Sb.silver, 10f, align = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                mono("STATUS: NOMINAL · NO EFFECT DETECTED · THIS IS FINE", Sb.amber, 9f, align = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(4.dp))
                SbButton("PROCEED", Sb.green, onDismiss)
            }
        }
    }
}
