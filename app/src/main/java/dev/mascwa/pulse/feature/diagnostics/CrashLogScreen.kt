package dev.mascwa.pulse.feature.diagnostics

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import dev.mascwa.pulse.feature.common.LcarsIcons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mascwa.pulse.BuildConfig
import dev.mascwa.pulse.crash.CrashEntry
import dev.mascwa.pulse.feature.common.NeonPanel
import dev.mascwa.pulse.feature.common.PulseScaffold
import dev.mascwa.pulse.ui.theme.JetBrainsMono
import dev.mascwa.pulse.ui.theme.Pulse
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun CrashLogScreen(vm: CrashLogViewModel, onBack: () -> Unit) {
    val c = Pulse.colors
    val entries by vm.entries.collectAsState()
    val uploadStatus by vm.uploadStatus.collectAsState()
    val context = LocalContext.current
    var expanded by remember { mutableStateOf<CrashEntry?>(null) }

    PulseScaffold(
        title = "CRASH LOG",
        onBack = onBack,
        actions = {
            if (entries.isNotEmpty()) {
                IconButton(onClick = { vm.clear() }) {
                    Icon(Icons.Filled.DeleteSweep, contentDescription = "Clear", tint = c.magenta)
                }
            }
        },
    ) { innerPadding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Uncaught faults are logged on-device. Native (C++) crashes can't be captured here. " +
                    "Crash reports auto-upload (scrubbed of secrets) to the repo's debug-reports branch " +
                    "on next launch when a GitHub token is set; toggle it off in Settings → Diagnostics.",
                fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.muted,
            )

            NeonPanel(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { vm.sendReport() },
                borderColor = c.accent.copy(alpha = 0.6f),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "▲ SEND DEBUG REPORT TO REPO",
                        fontFamily = JetBrainsMono, fontSize = 10.sp, letterSpacing = 1.sp, color = c.accent,
                    )
                    uploadStatus?.let {
                        Text(it, fontFamily = JetBrainsMono, fontSize = 9.sp, color = c.muted)
                    }
                }
            }

            if (BuildConfig.DEBUG) {
                NeonPanel(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { vm.simulateCrash() },
                    borderColor = c.magenta.copy(alpha = 0.6f),
                ) {
                    Text(
                        "▶ SIMULATE FAULT  (debug)",
                        fontFamily = JetBrainsMono, fontSize = 10.sp, letterSpacing = 1.sp, color = c.magenta,
                    )
                }
            }

            if (entries.isEmpty()) {
                Text(
                    "NO FAULTS LOGGED",
                    fontFamily = JetBrainsMono, fontSize = 11.sp, letterSpacing = 1.sp, color = c.muted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
                )
            } else {
                entries.forEach { entry ->
                    val isOpen = expanded == entry
                    NeonPanel(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { expanded = if (isOpen) null else entry },
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    DATE_FMT.format(Date(entry.timeMs)),
                                    fontFamily = JetBrainsMono, fontSize = 9.sp, letterSpacing = 1.sp,
                                    color = c.sky,
                                )
                                IconButton(onClick = { share(context, vm.read(entry)) }) {
                                    Icon(Icons.Filled.Share, contentDescription = "Share", tint = c.accent)
                                }
                            }
                            Text(
                                entry.summary,
                                fontFamily = JetBrainsMono, fontSize = 11.sp, color = c.ink,
                            )
                            if (isOpen) {
                                // Read once per entry, off the recomposition hot path.
                                val full = remember(entry) { vm.read(entry) }
                                Text(
                                    full,
                                    fontFamily = JetBrainsMono, fontSize = 9.sp, color = c.muted,
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                            } else {
                                Text(
                                    "tap to expand",
                                    fontFamily = JetBrainsMono, fontSize = 8.sp, color = c.muted,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun share(context: android.content.Context, text: String) {
    if (text.isBlank()) return
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "LCARS crash report")
        putExtra(Intent.EXTRA_TEXT, text)
    }
    runCatching { context.startActivity(Intent.createChooser(send, "Share crash report")) }
}

private val DATE_FMT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
