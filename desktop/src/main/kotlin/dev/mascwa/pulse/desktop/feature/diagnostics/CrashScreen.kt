package dev.mascwa.pulse.desktop.feature.diagnostics

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mascwa.pulse.core.telemetry.ElapsedPhrase
import dev.mascwa.pulse.desktop.diagnostics.CrashEntry
import dev.mascwa.pulse.desktop.diagnostics.CrashReporter
import dev.mascwa.pulse.desktop.diagnostics.CrashUploader
import dev.mascwa.pulse.desktop.theme.ChakraPetch
import dev.mascwa.pulse.desktop.theme.JetBrainsMono
import dev.mascwa.pulse.desktop.theme.LcarsFrame
import dev.mascwa.pulse.desktop.theme.LcarsGhostButton
import dev.mascwa.pulse.desktop.theme.LcarsHeaderBar
import dev.mascwa.pulse.desktop.theme.Pulse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CrashViewModel(
    private val scope: CoroutineScope,
    private val reporter: CrashReporter,
    private val uploader: CrashUploader? = null,
) {
    /** Whether sending is even possible here, so the button is absent rather than inert. */
    val canSend: Boolean get() = uploader != null

    private val _sendState = MutableStateFlow<String?>(null)
    val sendState: StateFlow<String?> = _sendState.asStateFlow()

    fun send() {
        val up = uploader ?: return
        scope.launch {
            _sendState.value = "Sending…"
            _sendState.value = up.send()
        }
    }

    private val _entries = MutableStateFlow<List<CrashEntry>>(emptyList())
    val entries: StateFlow<List<CrashEntry>> = _entries.asStateFlow()

    private val _open = MutableStateFlow<Pair<CrashEntry, String>?>(null)
    val open: StateFlow<Pair<CrashEntry, String>?> = _open.asStateFlow()

    fun refresh() {
        scope.launch {
            // Listing a directory and reading a first line each is disk work, small but still disk
            // work, and this runs from the composition.
            val list = withContext(Dispatchers.IO) { reporter.entries() }
            _entries.value = list
            // A report that has been cleared out from under the pane should not stay on screen.
            _open.value = _open.value?.takeIf { o -> list.any { it.file == o.first.file } }
        }
    }

    fun select(entry: CrashEntry) {
        scope.launch {
            val text = withContext(Dispatchers.IO) { reporter.read(entry) }
            _open.value = entry to text
        }
    }

    fun clear() {
        scope.launch {
            withContext(Dispatchers.IO) { reporter.clear() }
            withContext(Dispatchers.IO) { uploader?.forgetSent() }
            _open.value = null
            refresh()
        }
    }
}

/**
 * What went wrong, and when.
 *
 * ⚠️ The reason this earns a screen on a desktop program rather than being a developer's concern:
 * an exception thrown while drawing does not end this process. AWT logs it to a stream nobody is
 * watching and keeps pumping events, so a panel can fail and the window stays open looking fine.
 * Without somewhere to read the fault back, the honest description of that failure is "it just
 * stopped working", which is not something anybody can act on.
 *
 * List on the left, the whole report on the right, and COPY — because on this machine the useful
 * thing to do with a stack trace is paste it somewhere, which is a thing a desktop can actually do.
 */
@Composable
fun CrashScreen(vm: CrashViewModel, modifier: Modifier = Modifier) {
    val entries by vm.entries.collectAsState()
    val open by vm.open.collectAsState()
    val sendState by vm.sendState.collectAsState()
    val clipboard = LocalClipboardManager.current
    val c = Pulse.colors
    var copied by remember { mutableStateOf(false) }

    // Re-read on every entry to this screen: a fault that happened while you were on another page is
    // exactly the case this exists for, and nothing else would tell it to look again.
    LaunchedEffect(Unit) { vm.refresh() }

    Column(modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LcarsHeaderBar(
                "Crash console",
                Modifier.weight(1f),
                trailing = if (entries.isEmpty()) null else "${entries.size} OF ${CrashReporter.MAX}",
            )
            LcarsGhostButton("REFRESH", { vm.refresh() })
            if (vm.canSend && entries.isNotEmpty()) LcarsGhostButton("SEND", { vm.send() })
            if (entries.isNotEmpty()) LcarsGhostButton("CLEAR", { vm.clear() })
        }

        // What the upload actually did, in a sentence. Silent until something is attempted, so the
        // screen is unchanged for anyone who never presses it.
        sendState?.let { note ->
            Text(
                note,
                fontFamily = JetBrainsMono, fontSize = 11.sp, color = c.muted,
                modifier = Modifier.padding(top = 6.dp),
            )
        }

        if (entries.isEmpty()) {
            LcarsFrame(Modifier.fillMaxWidth().padding(top = 10.dp)) {
                Column {
                    Text(
                        "Nothing has gone wrong since this was last cleared.",
                        fontFamily = ChakraPetch, fontSize = 14.sp, color = c.ink,
                    )
                    Text(
                        "Faults are written here as they happen. A failure while drawing does " +
                            "not close the window, so if a page ever stops working without saying " +
                            "why, this is where the reason will be — and it can be sent to the " +
                            "repository so it can actually be read and fixed.",
                        fontFamily = JetBrainsMono, fontSize = 11.sp, lineHeight = 16.sp,
                        color = c.muted, modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
            return@Column
        }

        Row(
            Modifier.fillMaxWidth().weight(1f).padding(top = 10.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            LazyColumn(Modifier.width(340.dp).fillMaxHeight()) {
                items(entries, key = { it.file.path }) { e ->
                    val selected = open?.first?.file == e.file
                    LcarsFrame(
                        Modifier.fillMaxWidth().padding(bottom = 6.dp)
                            .clickable { copied = false; vm.select(e) },
                        accent = if (selected) c.accent else c.negative,
                    ) {
                        Column {
                            Text(
                                ElapsedPhrase.describe(System.currentTimeMillis() - e.timeMs)
                                    .uppercase(),
                                fontFamily = JetBrainsMono, fontSize = 9.sp,
                                color = if (selected) c.accent else c.faint,
                            )
                            Text(
                                e.summary,
                                fontFamily = ChakraPetch, fontSize = 13.sp,
                                fontWeight = FontWeight.Medium, color = c.ink,
                                modifier = Modifier.padding(top = 3.dp),
                            )
                        }
                    }
                }
            }

            LcarsFrame(Modifier.weight(1f).fillMaxHeight()) {
                val report = open?.second
                if (report == null) {
                    Text(
                        "Pick a fault to read it.",
                        fontFamily = ChakraPetch, fontSize = 13.sp, color = c.muted,
                    )
                } else {
                    Column(Modifier.fillMaxSize()) {
                        Row(
                            Modifier.fillMaxWidth().padding(bottom = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                if (copied) "COPIED" else "",
                                fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.positive,
                                modifier = Modifier.weight(1f),
                            )
                            LcarsGhostButton("COPY", {
                                clipboard.setText(AnnotatedString(report))
                                copied = true
                            })
                        }
                        // ⚠️ Horizontal scrolling as well as vertical, and no wrapping: a stack frame
                        // wrapped across two lines is materially harder to read, and the interesting
                        // part of a long package name is at the end.
                        Text(
                            report,
                            fontFamily = JetBrainsMono, fontSize = 11.sp, lineHeight = 15.sp,
                            color = c.ink, softWrap = false,
                            modifier = Modifier.fillMaxSize()
                                .background(c.void)
                                .verticalScroll(rememberScrollState())
                                .horizontalScroll(rememberScrollState())
                                .padding(4.dp),
                        )
                    }
                }
            }
        }
    }
}
