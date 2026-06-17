package dev.mascwa.pulse.feature.jarvis

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mascwa.pulse.feature.common.NeonPanel
import dev.mascwa.pulse.feature.common.PulseScaffold
import dev.mascwa.pulse.jarvis.matrix.ActiveMatrixService
import dev.mascwa.pulse.jarvis.vitals.VitalsTrackingService
import dev.mascwa.pulse.jarvis.inference.ChatFormat
import dev.mascwa.pulse.jarvis.inference.EngineState
import dev.mascwa.pulse.jarvis.inference.ModelDownloadState
import dev.mascwa.pulse.ui.theme.JetBrainsMono
import dev.mascwa.pulse.ui.theme.Pulse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
fun JarvisSetupScreen(vm: JarvisSetupViewModel, onBack: () -> Unit) {
    val c = Pulse.colors
    val url by vm.url.collectAsState()
    val token by vm.token.collectAsState()
    val download by vm.downloadState.collectAsState()
    val engine by vm.engineState.collectAsState()
    val resident by vm.resident.collectAsState()
    val vitals by vm.vitals.collectAsState()
    val voiceReplies by vm.voiceReplies.collectAsState()
    val wakeWord by vm.wakeWord.collectAsState()
    val agentTools by vm.agentTools.collectAsState()
    val selfEditEnabled by vm.selfEditEnabled.collectAsState()
    val chatFormat by vm.chatFormat.collectAsState()
    val backend by vm.inferenceBackend.collectAsState()
    val charter by vm.charter.collectAsState()
    val githubToken by vm.githubToken.collectAsState()
    val knowledgeChunks by vm.knowledgeChunks.collectAsState()
    val knowledgeDocs by vm.knowledgeDocs.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val vitalsPermissions = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { VitalsTrackingService.start(context) }

    // Import a text/markdown file into the knowledge library (read off the main thread).
    val docPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch(Dispatchers.IO) {
            val name = uri.lastPathSegment?.substringAfterLast('/')?.ifBlank { null } ?: "Imported document"
            val text = runCatching {
                context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            }.getOrNull().orEmpty()
            if (text.isNotBlank()) vm.addKnowledge(name, text)
        }
    }

    var kbTitle by remember { mutableStateOf("") }
    var kbBody by remember { mutableStateOf("") }

    // Enabling the wake word needs the microphone; only commit it once granted.
    fun enableWakeWord() {
        vm.setWakeWord(true)
        vm.setResident(true) // the wake word lives in the resident service
        ActiveMatrixService.stop(context)
        ActiveMatrixService.start(context, wakeWord = true)
    }
    val micPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) enableWakeWord() }

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

            FieldLabel("PRESETS  ·  free, no account  ·  tap to fill the URL, then DOWNLOAD")
            ModelPresetRow(onPick = vm::onUrlChange)

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

            FieldLabel("CHAT TEMPLATE  ·  prompt format for the model")
            ChatFormatSelector(selected = chatFormat, onSelect = vm::setChatFormat)
            Text(
                "AUTO picks ChatML (Qwen) or Gemma turns from the model URL. If replies come out " +
                    "garbled or repeat control tokens, switch to PLAIN.",
                fontFamily = JetBrainsMono, fontSize = 9.sp, color = c.muted,
            )

            FieldLabel("INFERENCE BACKEND  ·  CPU is slower but more compatible")
            BackendSelector(selected = backend, onSelect = vm::setInferenceBackend)
            Text(
                "AUTO lets MediaPipe choose and falls back to CPU automatically if a GPU run crashes " +
                    "the model. If chat shows \"inference fault\", try CPU.",
                fontFamily = JetBrainsMono, fontSize = 9.sp, color = c.muted,
            )

            FieldLabel("CHARTER  ·  J.A.R.V.I.S.'s personality, prepended to every prompt")
            MonoFieldArea(
                charter, vm::onCharterChange,
                "Leave blank for the built-in persona, or describe the character you want: tone, how it " +
                    "refers to itself, how it addresses you, its quirks…",
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                NeonButton(text = "SAVE CHARTER", enabled = true, color = c.accent, onClick = vm::saveCharter)
            }
            Text(
                "Saved on-device. A built-in safety rule is always appended in code and can't be " +
                    "overridden by the charter.",
                fontFamily = JetBrainsMono, fontSize = 9.sp, color = c.muted,
            )

            SettingToggle(
                title = "VOICE REPLIES",
                subtitle = "Speak J.A.R.V.I.S. replies aloud with the device's on-device " +
                    "text-to-speech. No cloud voices. Honest no-op if no TTS engine is installed.",
                enabled = voiceReplies,
                onToggle = vm::setVoiceReplies,
            )

            SettingToggle(
                title = "ACTIVE-MATRIX",
                subtitle = "Keep J.A.R.V.I.S. resident in the background and surface proactive, " +
                    "on-device remarks in an ongoing notification.",
                enabled = resident,
                onToggle = { on ->
                    vm.setResident(on)
                    if (on) ActiveMatrixService.start(context, wakeWord = wakeWord) else ActiveMatrixService.stop(context)
                },
            )

            SettingToggle(
                title = "WAKE WORD · \"J.A.R.V.I.S.\"",
                subtitle = "Listen for the wake word while resident, then take a spoken command — " +
                    "all on-device, nothing recorded or sent. Uses the mic and more battery.",
                enabled = wakeWord,
                onToggle = { on ->
                    if (on) {
                        val granted = ContextCompat.checkSelfPermission(
                            context, android.Manifest.permission.RECORD_AUDIO,
                        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                        if (granted) enableWakeWord() else micPermission.launch(android.Manifest.permission.RECORD_AUDIO)
                    } else {
                        vm.setWakeWord(false)
                        // Restart the resident service without the mic (if it's running).
                        if (resident) {
                            ActiveMatrixService.stop(context)
                            ActiveMatrixService.start(context, wakeWord = false)
                        }
                    }
                },
            )

            SettingToggle(
                title = "VITALS · BLE HEART-RATE",
                subtitle = "Pair a Bluetooth heart-rate strap. J.A.R.V.I.S. checks in if your heart " +
                    "rate spikes without movement. Honest no-op when no strap is connected.",
                enabled = vitals,
                onToggle = { on ->
                    vm.setVitals(on)
                    if (on) {
                        vitalsPermissions.launch(
                            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT),
                        )
                    } else {
                        VitalsTrackingService.stop(context)
                    }
                },
            )

            SettingToggle(
                title = "AGENT TOOLS",
                subtitle = "Let J.A.R.V.I.S. use tools — web search/fetch, read-only GitHub repos, " +
                    "device state, and durable memory — in a short reasoning loop. Slower, and " +
                    "best-effort on the small on-device model.",
                enabled = agentTools,
                onToggle = vm::setAgentTools,
            )

            SettingToggle(
                title = "SELF-EDIT (PROPOSE-ONLY)",
                subtitle = "Let J.A.R.V.I.S. PROPOSE changes to its own persona, knowledge and tools, " +
                    "plus research. Nothing is applied until you tap APPROVE in the Approvals screen — " +
                    "even web/repo content can never change anything on its own. Requires Agent Tools.",
                enabled = selfEditEnabled,
                onToggle = vm::setSelfEdit,
            )

            FieldLabel("GITHUB TOKEN  ·  optional, for private repos")
            MonoField(githubToken, vm::onGithubTokenChange, "ghp_…  (read-only repo access)")

            FieldLabel("KNOWLEDGE BASE  ·  docs J.A.R.V.I.S. can search (on-device RAG)")
            NeonPanel {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "$knowledgeDocs docs · $knowledgeChunks chunks indexed. Load reference docs " +
                            "(e.g. language notes, API docs) and J.A.R.V.I.S. retrieves the relevant " +
                            "bits into its answers. This is retrieval, not training — stays on-device.",
                        fontFamily = JetBrainsMono, fontSize = 9.sp, color = c.muted,
                    )
                    MonoField(kbTitle, { kbTitle = it }, "Title (e.g. Kotlin coroutines)")
                    MonoFieldArea(kbBody, { kbBody = it }, "Paste document text…")
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        NeonButton(
                            text = "ADD DOC",
                            enabled = kbBody.isNotBlank(),
                            color = c.accent,
                            onClick = {
                                vm.addKnowledge(kbTitle, kbBody)
                                kbTitle = ""
                                kbBody = ""
                            },
                        )
                        NeonButton(
                            text = "IMPORT FILE",
                            enabled = true,
                            color = c.accent,
                            onClick = { docPicker.launch("text/*") },
                        )
                        if (knowledgeChunks > 0) {
                            NeonButton(
                                text = "CLEAR",
                                enabled = true,
                                color = c.magenta,
                                onClick = vm::clearKnowledge,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MonoFieldArea(value: String, onValueChange: (String) -> Unit, placeholder: String) {
    val c = Pulse.colors
    TextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth().height(120.dp),
        singleLine = false,
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
private fun SettingToggle(title: String, subtitle: String, enabled: Boolean, onToggle: (Boolean) -> Unit) {
    val c = Pulse.colors
    NeonPanel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    fontFamily = JetBrainsMono, fontSize = 10.sp, letterSpacing = 1.sp, color = c.ink,
                )
                Text(
                    subtitle,
                    fontFamily = JetBrainsMono, fontSize = 9.sp, color = c.muted,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = c.accent,
                    checkedTrackColor = c.accent.copy(alpha = 0.3f),
                    uncheckedThumbColor = c.muted,
                    uncheckedTrackColor = c.panel,
                ),
            )
        }
    }
}

/** A curated model the user can provision with one tap (premade MediaPipe .task on Hugging Face). */
private data class ModelPreset(val label: String, val note: String, val url: String)

private val MODEL_PRESETS = listOf(
    ModelPreset(
        "QWEN 1.5B", "fast · free",
        "https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct/resolve/main/" +
            "Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv1280.task",
    ),
    ModelPreset(
        "PHI-4 MINI", "smart · free · recommended",
        "https://huggingface.co/litert-community/Phi-4-mini-instruct/resolve/main/" +
            "Phi-4-mini-instruct_multi-prefill-seq_q8_ekv1280.task",
    ),
)

@Composable
private fun ModelPresetRow(onPick: (String) -> Unit) {
    val c = Pulse.colors
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        MODEL_PRESETS.forEach { preset ->
            Column(
                Modifier
                    .border(1.dp, c.lineSoft, RoundedCornerShape(6.dp))
                    .background(c.muted.copy(alpha = 0.05f), RoundedCornerShape(6.dp))
                    .clickable { onPick(preset.url) }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Text(preset.label, fontFamily = JetBrainsMono, fontSize = 10.sp, letterSpacing = 1.sp, color = c.ink)
                Text(preset.note, fontFamily = JetBrainsMono, fontSize = 8.sp, color = c.muted)
            }
        }
    }
}

@Composable
private fun BackendSelector(selected: Int, onSelect: (Int) -> Unit) {
    val c = Pulse.colors
    val options = listOf(0 to "AUTO", 1 to "GPU", 2 to "CPU")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (value, label) ->
            val on = value == selected
            val tint = if (on) c.accent else c.muted
            Box(
                Modifier
                    .border(1.dp, tint.copy(alpha = if (on) 0.6f else 0.3f), RoundedCornerShape(6.dp))
                    .background(tint.copy(alpha = if (on) 0.12f else 0.04f), RoundedCornerShape(6.dp))
                    .clickable { onSelect(value) }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Text(label, fontFamily = JetBrainsMono, fontSize = 10.sp, letterSpacing = 1.sp, color = tint)
            }
        }
    }
}

@Composable
private fun ChatFormatSelector(selected: ChatFormat, onSelect: (ChatFormat) -> Unit) {
    val c = Pulse.colors
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ChatFormat.entries.forEach { format ->
            val on = format == selected
            val tint = if (on) c.accent else c.muted
            Box(
                Modifier
                    .border(1.dp, tint.copy(alpha = if (on) 0.6f else 0.3f), RoundedCornerShape(6.dp))
                    .background(tint.copy(alpha = if (on) 0.12f else 0.04f), RoundedCornerShape(6.dp))
                    .clickable { onSelect(format) }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Text(
                    format.label.uppercase(Locale.US),
                    fontFamily = JetBrainsMono, fontSize = 10.sp, letterSpacing = 1.sp, color = tint,
                )
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
