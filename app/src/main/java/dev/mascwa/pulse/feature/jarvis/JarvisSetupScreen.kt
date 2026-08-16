package dev.mascwa.pulse.feature.jarvis

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import dev.mascwa.pulse.feature.common.LcarsIcons
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
import dev.mascwa.pulse.feature.common.LcarsCorner
import dev.mascwa.pulse.feature.common.LcarsButton
import dev.mascwa.pulse.feature.common.NeonPanel
import dev.mascwa.pulse.feature.common.PulseScaffold
import dev.mascwa.pulse.feature.common.lcarsBlockShape
import dev.mascwa.pulse.jarvis.matrix.ActiveMatrixService
import dev.mascwa.pulse.jarvis.vitals.VitalsTrackingService
import dev.mascwa.pulse.core.util.openUrl
import dev.mascwa.pulse.jarvis.inference.ChatFormat
import dev.mascwa.pulse.jarvis.inference.CloudProvider
import dev.mascwa.pulse.jarvis.inference.EngineState
import dev.mascwa.pulse.jarvis.inference.ModelDownloadState
import dev.mascwa.pulse.ui.theme.JetBrainsMono
import dev.mascwa.pulse.ui.theme.Pulse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
fun JarvisSetupScreen(
    vm: JarvisSetupViewModel,
    onBack: () -> Unit,
    onOpenApprovals: () -> Unit = {},
    onOpenMemory: () -> Unit = {},
    onOpenDossier: () -> Unit = {},
) {
    val c = Pulse.colors
    val url by vm.url.collectAsState()
    val token by vm.token.collectAsState()
    val download by vm.downloadState.collectAsState()
    val engine by vm.engineState.collectAsState()
    val resident by vm.resident.collectAsState()
    val vitals by vm.vitals.collectAsState()
    val voiceReplies by vm.voiceReplies.collectAsState()
    val voiceName by vm.voiceName.collectAsState()
    val voices by vm.voices.collectAsState()
    val wakeWord by vm.wakeWord.collectAsState()
    val followUpMode by vm.followUpMode.collectAsState()
    val conversationMode by vm.conversationMode.collectAsState()
    val voiceCloudInterpret by vm.voiceCloudInterpret.collectAsState()
    val speakProactive by vm.speakProactive.collectAsState()
    val glassesHud by vm.glassesHud.collectAsState()
    val agentTools by vm.agentTools.collectAsState()
    val selfEditEnabled by vm.selfEditEnabled.collectAsState()
    val selfCoding by vm.selfCoding.collectAsState()
    val chatFormat by vm.chatFormat.collectAsState()
    val backend by vm.inferenceBackend.collectAsState()
    val cloudEnabled by vm.cloudEnabled.collectAsState()
    val cloudProvider by vm.cloudProvider.collectAsState()
    val cloudApiKey by vm.cloudApiKey.collectAsState()
    val cloudModel by vm.cloudModel.collectAsState()
    val maxTokens by vm.maxTokens.collectAsState()
    val curiosityLevel by vm.curiosityLevel.collectAsState()
    val charter by vm.charter.collectAsState()
    val address by vm.address.collectAsState()
    val feedTopic by vm.feedTopic.collectAsState()
    val autonomousCuriosity by vm.autonomousCuriosity.collectAsState()
    val githubToken by vm.githubToken.collectAsState()
    val knowledgeChunks by vm.knowledgeChunks.collectAsState()
    val knowledgeDocs by vm.knowledgeDocs.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val vitalsPermissions = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { VitalsTrackingService.start(context) }

    // Import a file from device storage / Drive / Google Docs into the knowledge library — you
    // authorize the file via the system picker (no account login or key). Read off the main thread.
    val docPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch(Dispatchers.IO) {
            val (name, text) = readPickedDocument(context, uri)
            if (text.isNotBlank()) {
                vm.addKnowledge(name, text)
                withContext(Dispatchers.Main) { Toast.makeText(context, "Imported \"$name\".", Toast.LENGTH_SHORT).show() }
            } else {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Couldn't read that — try a text file or a Google Doc.", Toast.LENGTH_LONG).show()
                }
            }
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
        title = "COMPUTER SETUP",
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(LcarsIcons.ArrowBack, contentDescription = "Back", tint = c.ink)
            }
        },
    ) { innerPadding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            StatusPanel(
                engine, download, vm.modelSizeBytes(),
                cloudLabel = if (cloudEnabled && cloudApiKey.isNotBlank()) cloudProvider.label else null,
            )

            // ---- Console controls (moved out of the Computer top bar to declutter it) ----
            FieldLabel("CONSOLE  ·  Computer controls")
            NeonPanel {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        LcarsButton(text = "APPROVALS", enabled = true, color = c.amber, onClick = onOpenApprovals)
                        LcarsButton(text = "MEMORY", enabled = true, color = c.positive, onClick = onOpenMemory)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        LcarsButton(text = "LOCKDOWN", enabled = true, color = c.magenta, onClick = vm::runLockdown)
                        LcarsButton(text = "CLEAR CHAT", enabled = true, color = c.muted, onClick = vm::clearChat)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        LcarsButton(text = "DOSSIER", enabled = true, color = c.violet, onClick = onOpenDossier)
                    }
                }
            }

            // ---- Cloud AI brain (recommended): far smarter than the on-device model ----
            SettingToggle(
                title = "CLOUD AI  ·  faster, smarter replies",
                subtitle = "Use a cloud AI for chat instead of the on-device model — much higher quality. " +
                    "Your chat messages are sent to the provider you choose. The wake word and all voice " +
                    "stay on-device. Off = use the local model below.",
                enabled = cloudEnabled,
                onToggle = vm::setCloudEnabled,
            )
            if (cloudEnabled) {
                FieldLabel("PROVIDER")
                CloudProviderSelector(selected = cloudProvider, onSelect = vm::setCloudProvider)
                Text(
                    "Get a free key: ${cloudProvider.keyUrl}",
                    fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.accent,
                    modifier = Modifier.clickable { openUrl(context, cloudProvider.keyUrl) },
                )
                FieldLabel("API KEY")
                MonoField(cloudApiKey, vm::onCloudKeyChange, "paste your ${cloudProvider.label} key")
                FieldLabel("MODEL  ·  optional, blank = ${cloudProvider.defaultModel}")
                MonoField(cloudModel, vm::onCloudModelChange, cloudProvider.defaultModel)
                if (cloudProvider.creditsUrl.isNotBlank()) {
                    Text(
                        "Check your balance: ${cloudProvider.creditsUrl}",
                        fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.accent,
                        modifier = Modifier.clickable { openUrl(context, cloudProvider.creditsUrl) },
                    )
                    Text(
                        "A 402 error means low provider credits, not a bad key — top up a little, or lower " +
                            "Model max tokens below.",
                        fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.muted,
                    )
                }
            }

            FieldLabel("MODEL MAX TOKENS  ·  reply length budget")
            MaxTokensSelector(selected = maxTokens, onSelect = vm::setMaxTokens)
            Text(
                "Caps how long a reply can be. Smaller = cheaper cloud requests and a smaller credit hold " +
                    "(fixes most 402s); larger = longer answers. Applies to cloud and on-device.",
                fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.muted,
            )

            Text(
                "Provision a local reasoning model. The file streams straight to this " +
                    "device's private storage and never leaves it — no account, no Google " +
                    "services. Needs a MediaPipe/LiteRT bundle — a .task or .litertlm file (e.g. a " +
                    "Gemma/Qwen/Phi .task on Hugging Face). Raw PyTorch/safetensors repos (e.g. " +
                    "EleutherAI GPT-Neo) won't load — they aren't .task and the architecture isn't supported.",
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
                LcarsButton(
                    text = if (isRunning) "DOWNLOADING…" else "DOWNLOAD MODEL",
                    enabled = !isRunning && url.isNotBlank(),
                    color = c.accent,
                    onClick = vm::download,
                )
                if (download is ModelDownloadState.Done) {
                    LcarsButton(
                        text = "DELETE",
                        enabled = !isRunning,
                        color = c.magenta,
                        onClick = vm::deleteModel,
                    )
                }
            }

            FieldLabel("CHAT TEMPLATE  ·  auto-detected; only change if replies look wrong")
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

            FieldLabel("ADDRESS  ·  what it calls you")
            MonoField(address, vm::setAddress, "Captain, Commander, your name… (blank = no honorific)")
            Text(
                "Blank is the default, and what a ship's computer actually does: it addresses you " +
                    "directly. Set a term and it will use it — sparingly, not in every line.",
                fontFamily = JetBrainsMono, fontSize = 9.sp, color = c.muted,
            )

            FieldLabel("CHARTER  ·  the Computer's personality, prepended to every prompt")
            MonoFieldArea(
                charter, vm::onCharterChange,
                "Leave blank for the built-in persona, or describe the character you want: tone, how it " +
                    "refers to itself, how it addresses you, its quirks…",
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                LcarsButton(text = "SAVE CHARTER", enabled = true, color = c.accent, onClick = vm::saveCharter)
            }
            Text(
                "Saved on-device. A built-in safety rule is always appended in code and can't be " +
                    "overridden by the charter.",
                fontFamily = JetBrainsMono, fontSize = 9.sp, color = c.muted,
            )

            FieldLabel("STATUS-FEED FOCUS  ·  what the Computer briefs on the home HUD")
            MonoField(feedTopic, vm::onFeedTopicChange, "a project, a topic, \"device health\"…")
            Text(
                "Shown as a quick summary on the home status feed — never your chat. Leave blank for just " +
                    "diagnostics + usage-based suggestions.",
                fontFamily = JetBrainsMono, fontSize = 9.sp, color = c.muted,
            )

            SettingToggle(
                title = "AUTONOMOUS CURIOSITY",
                subtitle = "Let the Computer research your standing interests + its own curiosities in the " +
                    "background (~every 4h) and bring you findings with a notification. Needs cloud chat on — " +
                    "it spends your provider credits — so it's off by default. Set standing orders with " +
                    "\"keep an eye on …\"; it also investigates the device itself.",
                enabled = autonomousCuriosity,
                onToggle = vm::setAutonomousCuriosity,
            )

            FieldLabel("CURIOSITY  ·  Computer attentiveness")
            CuriositySelector(selected = curiosityLevel, onSelect = vm::setCuriosityLevel)
            Text(
                "The Computer always pays close attention and remembers what matters. It asks rarely and " +
                    "only at natural lulls — one courteous, anticipatory question to confirm something it " +
                    "inferred, reflected back before it's saved (manage facts in MEMORY). Higher = deeper " +
                    "attention and better-timed questions, NOT more of them; the rate is hard-capped. " +
                    "OFF disables questions.",
                fontFamily = JetBrainsMono, fontSize = 9.sp, color = c.muted,
            )
            if (curiosityLevel >= 4) {
                Text(
                    "⚠ MAX removes the rate cap — frequent questions that break the Computer's character. " +
                        "Use LOW/MED/HIGH for the intended, restrained feel.",
                    fontFamily = JetBrainsMono, fontSize = 9.sp, color = c.magenta,
                )
            }

            SettingToggle(
                title = "VOICE REPLIES",
                subtitle = "Speak Computer replies aloud with the device's on-device " +
                    "text-to-speech. No cloud voices. Honest no-op if no TTS engine is installed.",
                enabled = voiceReplies,
                onToggle = vm::setVoiceReplies,
            )

            if (voiceReplies) {
                // Only bind the TTS engine and enumerate voices once the user actually wants speech.
                androidx.compose.runtime.LaunchedEffect(Unit) { vm.loadVoices() }
                VoicePicker(
                    voices = voices,
                    selected = voiceName,
                    onPick = vm::setVoiceName,
                )
            }

            SettingToggle(
                title = "ACTIVE-MATRIX",
                subtitle = "Keep the Computer resident in the background and surface proactive, " +
                    "on-device remarks in an ongoing notification.",
                enabled = resident,
                onToggle = { on ->
                    vm.setResident(on)
                    if (on) ActiveMatrixService.start(context, wakeWord = wakeWord) else ActiveMatrixService.stop(context)
                },
            )

            SettingToggle(
                title = "WAKE WORD · \"COMPUTER\"",
                subtitle = "Listen for the wake word, then take a spoken command. Wake detection uses a " +
                    "small offline model (~128 MB, downloaded on first use); the command is transcribed " +
                    "by your phone's on-device speech recognition (private — audio stays on the device), " +
                    "falling back to the offline model if that's unavailable. Uses the mic and more battery.",
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
                title = "FOLLOW-UP MODE",
                subtitle = "After a spoken reply, listen again briefly so you can respond without " +
                    "re-saying \"Computer\" — ends when you go quiet. Requires the wake word.",
                enabled = followUpMode,
                onToggle = vm::setFollowUpMode,
            )

            SettingToggle(
                title = "CONVERSATION MODE",
                subtitle = "Let the Computer decide from the conversation whether to keep talking — it " +
                    "stays listening when it expects a reply and tells you when it's wrapping up. You " +
                    "can stop anytime (\"stop\" / \"that's all\"). Implies follow-up.",
                enabled = conversationMode,
                onToggle = vm::setConversationMode,
            )

            SettingToggle(
                title = "CLOUD SPEECH CLEANUP",
                subtitle = "When a cloud key is set, run each wake-word command through the cloud AI once " +
                    "to fix speech-to-text mishears (\"fire\" → \"file\") before acting — better " +
                    "understanding without a heavier model. Adds a brief pause; no effect without a cloud key.",
                enabled = voiceCloudInterpret,
                onToggle = vm::setVoiceCloudInterpret,
            )

            SettingToggle(
                title = "SPEAK PROACTIVELY",
                subtitle = "Say context remarks aloud while resident — a greeting when it comes online, and " +
                    "reactions to power/network changes. Respects quiet hours and stays silent while the " +
                    "console is open or you're mid-command. Needs the resident service + a TTS engine.",
                enabled = speakProactive,
                onToggle = vm::setSpeakProactive,
            )

            SettingToggle(
                title = "GLASSES HUD",
                subtitle = "Show a glanceable Computer HUD (clock, brief, latest reply) on connected " +
                    "display glasses or an external/wireless screen, while the app is open. Off by default; " +
                    "appears only when an external display is detected.",
                enabled = glassesHud,
                onToggle = vm::setGlassesHud,
            )

            SettingToggle(
                title = "VITALS · BLE HEART-RATE",
                subtitle = "Pair a Bluetooth heart-rate strap. The Computer checks in if your heart " +
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
                subtitle = "Let the Computer use tools — web search/fetch, read-only GitHub repos, " +
                    "device state, and durable memory — in a short reasoning loop. Slower, and " +
                    "best-effort on the small on-device model.",
                enabled = agentTools,
                onToggle = vm::setAgentTools,
            )

            SettingToggle(
                title = "SELF-EDIT (PROPOSE-ONLY)",
                subtitle = "Let the Computer PROPOSE changes to its own persona, knowledge and tools, " +
                    "plus research. Nothing is applied until you tap APPROVE in the Approvals screen — " +
                    "even web/repo content can never change anything on its own. Requires Agent Tools.",
                enabled = selfEditEnabled,
                onToggle = vm::setSelfEdit,
            )

            FieldLabel("GITHUB TOKEN  ·  private-repo updates + self-coding")
            MonoField(githubToken, vm::onGithubTokenChange, "ghp_…  (classic 'repo' scope)")
            Text(
                "Needed to reach this private repo. Read access is enough for software updates; self-coding " +
                    "(opening PRs) needs write — a classic token with the 'repo' scope covers both. Paste " +
                    "with no extra spaces.",
                fontFamily = JetBrainsMono, fontSize = 9.sp, color = c.muted,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            Text(
                "Get a token → https://github.com/settings/tokens",
                fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.accent,
                modifier = Modifier
                    .padding(horizontal = 4.dp)
                    .clickable { openUrl(context, "https://github.com/settings/tokens") },
            )

            SettingToggle(
                title = "AUTONOMOUS SELF-CODING",
                subtitle = "One switch, full loop: tell the Computer to build a real feature (\"add a … " +
                    "feature\", \"read your own code\") and it plans the files, opens a PR, lets CI test it, " +
                    "and — once green — merges it; you just install the update. No per-change approval. " +
                    "Needs the write-scoped GitHub token above. Its safety gate, CI and signing stay " +
                    "off-limits and CI must pass before anything ships. Default off — flip on to hand over " +
                    "the loop, off to halt it.",
                enabled = selfCoding,
                onToggle = vm::setSelfCoding,
            )

            FieldLabel("KNOWLEDGE BASE  ·  on-device docs the Computer searches for answers")
            NeonPanel {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "$knowledgeDocs docs · $knowledgeChunks chunks indexed. Load reference docs " +
                            "(e.g. language notes, API docs) and the Computer retrieves the relevant " +
                            "bits into its answers. This is retrieval, not training — stays on-device.",
                        fontFamily = JetBrainsMono, fontSize = 9.sp, color = c.muted,
                    )
                    MonoField(kbTitle, { kbTitle = it }, "Title (e.g. Kotlin coroutines)")
                    MonoFieldArea(kbBody, { kbBody = it }, "Paste document text…")
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        LcarsButton(
                            text = "ADD DOC",
                            enabled = kbBody.isNotBlank(),
                            color = c.accent,
                            onClick = {
                                vm.addKnowledge(kbTitle, kbBody)
                                kbTitle = ""
                                kbBody = ""
                            },
                        )
                        LcarsButton(
                            text = "IMPORT FILE / DRIVE",
                            enabled = true,
                            color = c.accent,
                            onClick = {
                                docPicker.launch(
                                    arrayOf(
                                        "text/*",
                                        "application/json",
                                        "application/vnd.google-apps.document",
                                    ),
                                )
                            },
                        )
                        if (knowledgeChunks > 0) {
                            LcarsButton(
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
                    .border(1.dp, c.lineSoft, lcarsBlockShape(sweep = 6.dp, corner = LcarsCorner.TopStart))
                    .background(c.muted.copy(alpha = 0.05f), lcarsBlockShape(sweep = 6.dp, corner = LcarsCorner.TopStart))
                    .clickable { onPick(preset.url) }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Text(preset.label, fontFamily = JetBrainsMono, fontSize = 10.sp, letterSpacing = 1.sp, color = c.ink)
                Text(preset.note, fontFamily = JetBrainsMono, fontSize = 8.sp, color = c.muted)
            }
        }
    }
}

/**
 * Choose which installed voice speaks.
 *
 * Voices are listed by the engine's own names, tidied — there is no portable way to get a friendly
 * one, and inventing labels would mean claiming things about a voice the platform never told us.
 * AUTO is first and is the honest default: it picks the best available, and on a device whose engine
 * exposes no gender hint that is all anyone can do.
 */
@Composable
private fun VoicePicker(
    voices: List<dev.mascwa.pulse.jarvis.voice.VoiceOption>,
    selected: String,
    onPick: (String) -> Unit,
) {
    val c = Pulse.colors
    NeonPanel {
        // NeonPanel lays its content out in a Box, so several children would stack on top of each
        // other. The Column is load-bearing.
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "VOICE",
                fontFamily = JetBrainsMono, fontSize = 10.sp, letterSpacing = 1.sp, color = c.ink,
            )
            Text(
                if (voices.isEmpty()) {
                    "No text-to-speech voices found. Install a speech engine or a language pack to choose one."
                } else {
                    "Tap to hear it. AUTO leans female and American — the register the computer is dressed as."
                },
                fontFamily = JetBrainsMono, fontSize = 9.sp, color = c.muted,
            )
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                VoiceChip("AUTO", selected.isBlank()) { onPick("") }
                voices.forEach { v ->
                    VoiceChip(v.label, v.name == selected) { onPick(v.name) }
                }
            }
        }
    }
}

@Composable
private fun VoiceChip(label: String, on: Boolean, onClick: () -> Unit) {
    val c = Pulse.colors
    val tint = if (on) c.accent else c.muted
    Box(
        Modifier
            .border(1.dp, tint.copy(alpha = if (on) 0.6f else 0.3f), lcarsBlockShape(sweep = 6.dp, corner = LcarsCorner.TopStart))
            .background(tint.copy(alpha = if (on) 0.12f else 0.04f), lcarsBlockShape(sweep = 6.dp, corner = LcarsCorner.TopStart))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(label, fontFamily = JetBrainsMono, fontSize = 10.sp, letterSpacing = 1.sp, color = tint)
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
                    .border(1.dp, tint.copy(alpha = if (on) 0.6f else 0.3f), lcarsBlockShape(sweep = 6.dp, corner = LcarsCorner.TopStart))
                    .background(tint.copy(alpha = if (on) 0.12f else 0.04f), lcarsBlockShape(sweep = 6.dp, corner = LcarsCorner.TopStart))
                    .clickable { onSelect(value) }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Text(label, fontFamily = JetBrainsMono, fontSize = 10.sp, letterSpacing = 1.sp, color = tint)
            }
        }
    }
}

private const val MAX_IMPORT_CHARS = 200_000

/** Read a picked document into (title, text): text-like files & JSON directly, Google Docs exported to
 *  plain text. Returns (name, "") for types we can't read as text. Call off the main thread. */
private fun readPickedDocument(context: Context, uri: Uri): Pair<String, String> {
    val resolver = context.contentResolver
    val name = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cur ->
            if (cur.moveToFirst()) cur.getString(0) else null
        }
    }.getOrNull()?.ifBlank { null }
        ?: uri.lastPathSegment?.substringAfterLast('/')?.ifBlank { null }
        ?: "Imported document"
    val mime = runCatching { resolver.getType(uri) }.getOrNull().orEmpty()
    val text = runCatching {
        when {
            mime == "application/vnd.google-apps.document" ->
                resolver.openTypedAssetFileDescriptor(uri, "text/plain", null)
                    ?.createInputStream()?.bufferedReader()?.use { it.readText() }
            mime.startsWith("text/") || mime == "application/json" || mime == "application/xml" ->
                resolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            else -> null
        }
    }.getOrNull().orEmpty()
    return name to text.take(MAX_IMPORT_CHARS)
}

@Composable
private fun CuriositySelector(selected: Int, onSelect: (Int) -> Unit) {
    val c = Pulse.colors
    val options = listOf(0 to "OFF", 1 to "LOW", 2 to "MED", 3 to "HIGH", 4 to "MAX")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (value, label) ->
            val on = value == selected
            val tint = if (on) c.accent else c.muted
            Box(
                Modifier
                    .border(1.dp, tint.copy(alpha = if (on) 0.6f else 0.3f), lcarsBlockShape(sweep = 6.dp, corner = LcarsCorner.TopStart))
                    .background(tint.copy(alpha = if (on) 0.12f else 0.04f), lcarsBlockShape(sweep = 6.dp, corner = LcarsCorner.TopStart))
                    .clickable { onSelect(value) }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Text(label, fontFamily = JetBrainsMono, fontSize = 10.sp, letterSpacing = 1.sp, color = tint)
            }
        }
    }
}

@Composable
private fun CloudProviderSelector(selected: CloudProvider, onSelect: (CloudProvider) -> Unit) {
    val c = Pulse.colors
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CloudProvider.entries.forEach { provider ->
            val on = provider == selected
            val tint = if (on) c.accent else c.muted
            Box(
                Modifier
                    .border(1.dp, tint.copy(alpha = if (on) 0.6f else 0.3f), lcarsBlockShape(sweep = 6.dp, corner = LcarsCorner.TopStart))
                    .background(tint.copy(alpha = if (on) 0.12f else 0.04f), lcarsBlockShape(sweep = 6.dp, corner = LcarsCorner.TopStart))
                    .clickable { onSelect(provider) }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Text(provider.label, fontFamily = JetBrainsMono, fontSize = 10.sp, letterSpacing = 1.sp, color = tint)
            }
        }
    }
}

@Composable
private fun MaxTokensSelector(selected: Int, onSelect: (Int) -> Unit) {
    val c = Pulse.colors
    val presets = listOf(1024, 2048, 4096, 8192)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        presets.forEach { value ->
            val on = value == selected
            val tint = if (on) c.accent else c.muted
            Box(
                Modifier
                    .border(1.dp, tint.copy(alpha = if (on) 0.6f else 0.3f), lcarsBlockShape(sweep = 6.dp, corner = LcarsCorner.TopStart))
                    .background(tint.copy(alpha = if (on) 0.12f else 0.04f), lcarsBlockShape(sweep = 6.dp, corner = LcarsCorner.TopStart))
                    .clickable { onSelect(value) }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Text("$value", fontFamily = JetBrainsMono, fontSize = 10.sp, letterSpacing = 1.sp, color = tint)
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
                    .border(1.dp, tint.copy(alpha = if (on) 0.6f else 0.3f), lcarsBlockShape(sweep = 6.dp, corner = LcarsCorner.TopStart))
                    .background(tint.copy(alpha = if (on) 0.12f else 0.04f), lcarsBlockShape(sweep = 6.dp, corner = LcarsCorner.TopStart))
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
private fun StatusPanel(engine: EngineState, download: ModelDownloadState, sizeBytes: Long, cloudLabel: String?) {
    val c = Pulse.colors
    val (engineLabel, engineColor) = when {
        cloudLabel != null -> "CLOUD · ${cloudLabel.uppercase(Locale.US)} · REASONING ONLINE" to c.positive
        engine is EngineState.Ready -> "LLM LOADED · REASONING ONLINE" to c.positive
        engine is EngineState.Preparing -> "LOADING MODEL…" to c.amber
        engine is EngineState.Downloading -> "DOWNLOADING ${engine.pct}%" to c.amber
        engine is EngineState.Unavailable -> "PERSONA CORE (no model)" to c.muted
        engine is EngineState.Error -> "ERROR · ${engine.message}" to c.magenta
        else -> "PERSONA CORE" to c.muted
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

private fun formatMb(bytes: Long): String =
    if (bytes <= 0L) "?" else String.format(Locale.US, "%.1f MB", bytes / 1_048_576.0)
