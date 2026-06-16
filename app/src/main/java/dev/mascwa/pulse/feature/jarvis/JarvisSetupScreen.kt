package dev.mascwa.pulse.feature.jarvis

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mascwa.pulse.feature.common.NeonPanel
import dev.mascwa.pulse.feature.common.PulseScaffold
import dev.mascwa.pulse.jarvis.inference.EngineState
import dev.mascwa.pulse.jarvis.inference.ModelDownloadState
import dev.mascwa.pulse.ui.theme.JetBrainsMono
import dev.mascwa.pulse.ui.theme.Pulse
import java.util.Locale

@Composable
fun JarvisSetupScreen(vm: JarvisSetupViewModel, onBack: () -> Unit) {
    val c = Pulse.colors
    val url by vm.url.collectAsState()
    val token by vm.token.collectAsState()
    val download by vm.downloadState.collectAsState()
    val engine by vm.engineState.collectAsState()

    PulseScaffold(
        title = "J.A.R.V.I.S. SETUP",
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = c.ink)
            }
        },
    ) { innerPadding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            StatusPanel(engine, download, vm.modelSizeBytes())

            Text(
                "Provision a local reasoning model. The file streams straight to this " +
                    "device's private storage and never leaves it — no account, no Google " +
                    "services. Point me at a MediaPipe-compatible .task LLM (e.g. a Gemma " +
                    ".task hosted on Hugging Face).",
                fontFamily = JetBrainsMono, fontSize = 11.sp, color = c.muted,
            )

            FieldLabel("MODEL URL")
            MonoField(url, vm::onUrlChange, "https://…/model.task")

            FieldLabel("ACCESS TOKEN  ·  optional, for gated hosts")
            MonoField(token, vm::onTokenChange, "hf_…  (sent as Bearer)")

            (download as? ModelDownloadState.Running)?.let { running ->
                ProgressBar(running.pct, running.downloadedBytes, running.totalBytes)
            }

            val isRunning = download is ModelDownloadState.Running
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                NeonButton(
                    text = if (isRunning) "DOWNLOADING…" else "DOWNLOAD MODEL",
                    enabled = !isRunning && url.isNotBlank(),
                    color = c.accent,
                    onClick = vm::download,
                )
                if (download is ModelDownloadState.Done) {
                    NeonButton(
                        text = "DELETE",
                        enabled = !isRunning,
                        color = c.magenta,
                        onClick = vm::deleteModel,
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusPanel(engine: EngineState, download: ModelDownloadState, sizeBytes: Long) {
    val c = Pulse.colors
    val (engineLabel, engineColor) = when (engine) {
        is EngineState.Ready -> "LLM LOADED · REASONING ONLINE" to c.positive
        is EngineState.Preparing -> "LOADING MODEL…" to c.amber
        is EngineState.Downloading -> "DOWNLOADING ${engine.pct}%" to c.amber
        is EngineState.Unavailable -> "PERSONA CORE (no model)" to c.muted
        is EngineState.Error -> "ERROR · ${engine.message}" to c.magenta
    }
    val (modelLabel, modelColor) = when (download) {
        is ModelDownloadState.Done -> "PRESENT · ${formatMb(sizeBytes)}" to c.positive
        is ModelDownloadState.Running -> "DOWNLOADING ${download.pct}%" to c.amber
        is ModelDownloadState.Failed -> "FAILED · ${download.message}" to c.magenta
        is ModelDownloadState.Idle -> "NOT PROVISIONED" to c.muted
    }
    NeonPanel {
        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
            StatusRow("ENGINE", engineLabel, engineColor)
            StatusRow("MODEL", modelLabel, modelColor)
        }
    }
}

@Composable
private fun StatusRow(key: String, value: String, valueColor: Color) {
    val c = Pulse.colors
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            key,
            fontFamily = JetBrainsMono, fontSize = 9.sp, letterSpacing = 1.sp, color = c.muted,
            modifier = Modifier.width(64.dp),
        )
        Text(value, fontFamily = JetBrainsMono, fontSize = 9.sp, letterSpacing = 1.sp, color = valueColor)
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(
        text,
        fontFamily = JetBrainsMono, fontSize = 8.sp, letterSpacing = 1.sp, color = Pulse.colors.muted,
        modifier = Modifier.padding(bottom = 2.dp),
    )
}

@Composable
private fun MonoField(value: String, onValueChange: (String) -> Unit, placeholder: String) {
    val c = Pulse.colors
    TextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        placeholder = {
            Text(placeholder, fontFamily = JetBrainsMono, fontSize = 11.sp, color = c.muted)
        },
        colors = TextFieldDefaults.colors(
            focusedContainerColor = c.panel,
            unfocusedContainerColor = c.panel,
            focusedIndicatorColor = c.accent,
            unfocusedIndicatorColor = c.lineSoft,
            cursorColor = c.accent,
            focusedTextColor = c.ink,
            unfocusedTextColor = c.ink,
        ),
    )
}

@Composable
private fun ProgressBar(pct: Int, downloadedBytes: Long, totalBytes: Long) {
    val c = Pulse.colors
    val fraction = if (totalBytes > 0L) (pct / 100f).coerceIn(0f, 1f) else 0f
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(6.dp)
                .background(c.lineSoft, RoundedCornerShape(3.dp)),
        ) {
            if (fraction > 0f) {
                Box(
                    Modifier
                        .fillMaxWidth(fraction)
                        .height(6.dp)
                        .background(c.accent, RoundedCornerShape(3.dp)),
                )
            }
        }
        val label = if (totalBytes > 0L) {
            "$pct%  ·  ${formatMb(downloadedBytes)} / ${formatMb(totalBytes)}"
        } else {
            "${formatMb(downloadedBytes)} downloaded"
        }
        Text(label, fontFamily = JetBrainsMono, fontSize = 9.sp, color = c.muted)
    }
}

@Composable
private fun NeonButton(text: String, enabled: Boolean, color: Color, onClick: () -> Unit) {
    val tint = if (enabled) color else Pulse.colors.muted
    Box(
        Modifier
            .border(1.dp, tint.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
            .background(tint.copy(alpha = 0.08f), RoundedCornerShape(6.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(text, fontFamily = JetBrainsMono, fontSize = 11.sp, letterSpacing = 1.sp, color = tint)
    }
}

private fun formatMb(bytes: Long): String =
    if (bytes <= 0L) "?" else String.format(Locale.US, "%.1f MB", bytes / 1_048_576.0)
