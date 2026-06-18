package dev.mascwa.pulse.feature.jarvis

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import dev.mascwa.pulse.feature.common.NeonPanel
import dev.mascwa.pulse.feature.common.PulseScaffold
import dev.mascwa.pulse.jarvis.inference.EngineState
import dev.mascwa.pulse.ui.theme.JetBrainsMono
import dev.mascwa.pulse.ui.theme.Pulse

@Composable
fun JarvisScreen(
    vm: JarvisViewModel,
    onBack: () -> Unit,
    onOpenSetup: () -> Unit = {},
    onOpenApprovals: () -> Unit = {},
    onOpenMemory: () -> Unit = {},
) {
    val c = Pulse.colors
    val messages by vm.messages.collectAsState()
    val streaming by vm.streaming.collectAsState()
    val busy by vm.busy.collectAsState()
    val engineState by vm.engineState.collectAsState()
    val cloudStatus by vm.cloudStatus.collectAsState()
    val banter by vm.banterLine.collectAsState()
    val voiceReplies by vm.voiceReplies.collectAsState()
    val voiceInput by vm.voiceInput.collectAsState()
    val listState = rememberLazyListState()

    val context = LocalContext.current
    val micPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) vm.startVoiceInput() }
    val onMic = {
        val active = voiceInput is VoiceInputState.Listening || voiceInput is VoiceInputState.Preparing
        when {
            active -> vm.stopVoiceInput()
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED -> vm.startVoiceInput()
            else -> micPermission.launch(android.Manifest.permission.RECORD_AUDIO)
        }
    }

    // While the console is on-screen it owns the mic (tap-to-talk); the resident wake loop
    // pauses and auto-resumes when we leave — so the two never fight over the recognizer.
    DisposableEffect(Unit) {
        vm.setConsoleActive(true)
        onDispose { vm.setConsoleActive(false) }
    }

    // Keep the latest turn in view as messages arrive / stream.
    LaunchedEffect(messages.size, streaming) {
        val count = messages.size + if (streaming.isNotEmpty()) 1 else 0
        if (count > 0) listState.animateScrollToItem(count - 1)
    }

    PulseScaffold(
        title = "J.A.R.V.I.S.",
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = c.ink)
            }
        },
        actions = {
            IconButton(onClick = { vm.setVoiceReplies(!voiceReplies) }) {
                Icon(
                    if (voiceReplies) Icons.Filled.VolumeUp else Icons.Filled.VolumeOff,
                    contentDescription = if (voiceReplies) "Mute voice" else "Speak replies",
                    tint = if (voiceReplies) c.sky else c.muted,
                )
            }
            IconButton(onClick = { vm.requestBrief() }, enabled = !busy) {
                Icon(Icons.Filled.Campaign, contentDescription = "Brief me", tint = c.positive)
            }
            IconButton(onClick = { vm.runLockdown() }, enabled = !busy) {
                Icon(Icons.Filled.Lock, contentDescription = "Lockdown", tint = c.magenta)
            }
            IconButton(onClick = onOpenApprovals) {
                Icon(Icons.Filled.Checklist, contentDescription = "Approvals", tint = c.amber)
            }
            IconButton(onClick = onOpenMemory) {
                Icon(Icons.Filled.Psychology, contentDescription = "Memory", tint = c.positive)
            }
            IconButton(onClick = onOpenSetup) {
                Icon(Icons.Filled.Tune, contentDescription = "Model setup", tint = c.sky)
            }
            IconButton(onClick = { vm.clearHistory() }) {
                Icon(Icons.Filled.DeleteSweep, contentDescription = "Clear", tint = c.muted)
            }
        },
    ) { innerPadding ->
        // imePadding lifts the input bar above the soft keyboard — the window is edge-to-edge
        // (MainActivity.enableEdgeToEdge), so it doesn't auto-resize for the IME on its own.
        Column(Modifier.fillMaxSize().padding(innerPadding).imePadding()) {
            StatusLine(engineState, cloudStatus)
            if (banter.isNotBlank()) BanterLine(banter)

            if (messages.isEmpty() && streaming.isEmpty()) {
                Column(
                    Modifier.fillMaxWidth().weight(1f).padding(horizontal = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
                ) {
                    Text(
                        "J.A.R.V.I.S. MATRIX",
                        fontFamily = JetBrainsMono, fontSize = 13.sp, letterSpacing = 2.sp, color = c.accent,
                    )
                    Text("Try:", fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.muted)
                    listOf(
                        "\"Brief me\" — your day at a glance",
                        "\"Set an alarm for 7:30\"  ·  \"Text Alex I'm late\"  *",
                        "\"Play Daft Punk\"  ·  \"Torch on\"  ·  \"Navigate home\"  *",
                        "Tap the mic to talk — or say “Jarvis” when resident",
                    ).forEach {
                        Text("•  $it", fontFamily = JetBrainsMono, fontSize = 11.sp, color = c.muted)
                    }
                    Text(
                        "* device actions need Agent Tools (Setup). Add a cloud key in Setup for the " +
                            "smartest chat; tune Curiosity and review Memory there too.",
                        fontFamily = JetBrainsMono, fontSize = 9.sp, color = c.muted,
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(messages) { msg -> Bubble(msg.text, msg.isUser) }
                    if (streaming.isNotEmpty()) {
                        item { Bubble(streaming, isUser = false) }
                    }
                }
            }

            InputBar(busy = busy, voice = voiceInput, onSend = vm::send, onMic = onMic)
        }
    }
}

@Composable
private fun StatusLine(state: EngineState, cloud: String?) {
    val c = Pulse.colors
    val (label, color) = if (cloud != null) {
        "● CLOUD · ${cloud.uppercase(java.util.Locale.US)} ONLINE" to c.positive
    } else when (state) {
        is EngineState.Ready -> "● MATRIX ONLINE" to c.positive
        is EngineState.Preparing -> "◌ PREPARING MODEL" to c.amber
        is EngineState.Downloading -> "↓ DOWNLOADING ${state.pct}%" to c.amber
        is EngineState.Unavailable -> "○ PERSONA CORE (no model)" to c.muted
        is EngineState.Error -> "✕ ${state.message}" to c.magenta
    }
    Text(
        label,
        fontFamily = JetBrainsMono, fontSize = 9.sp, letterSpacing = 1.sp, color = color,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
    )
}

@Composable
private fun BanterLine(text: String) {
    val c = Pulse.colors
    Text(
        "▮ $text",
        fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.sky,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 6.dp),
    )
}

@Composable
private fun Bubble(text: String, isUser: Boolean) {
    val c = Pulse.colors
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        NeonPanel(
            modifier = Modifier.widthIn(max = 300.dp),
            borderColor = if (isUser) c.accent.copy(alpha = 0.5f) else c.lineSoft,
            background = if (isUser) c.raise else c.panel,
        ) {
            Column {
                Text(
                    if (isUser) "YOU" else "J.A.R.V.I.S.",
                    fontFamily = JetBrainsMono, fontSize = 8.sp, letterSpacing = 1.sp,
                    color = if (isUser) c.accent else c.sky,
                )
                Text(
                    text,
                    fontFamily = JetBrainsMono, fontSize = 12.sp, color = c.ink,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
        }
    }
}

@Composable
private fun InputBar(busy: Boolean, voice: VoiceInputState, onSend: (String) -> Unit, onMic: () -> Unit) {
    val c = Pulse.colors
    var input by remember { mutableStateOf("") }
    fun submit() {
        if (input.isNotBlank() && !busy) {
            onSend(input)
            input = ""
        }
    }
    Column {
        VoiceLine(voice)
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = {
                    Text("Speak to the Matrix…", fontFamily = JetBrainsMono, fontSize = 12.sp, color = c.muted)
                },
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSend = { submit() }),
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
            val listening = voice is VoiceInputState.Listening || voice is VoiceInputState.Preparing
            IconButton(onClick = onMic) {
                Icon(
                    if (listening) Icons.Filled.Stop else Icons.Filled.Mic,
                    contentDescription = if (listening) "Stop listening" else "Speak",
                    tint = if (listening) c.magenta else c.sky,
                )
            }
            IconButton(onClick = { submit() }, enabled = !busy) {
                Icon(
                    Icons.AutoMirrored.Filled.Send,
                    contentDescription = "Send",
                    tint = if (busy) c.muted else c.accent,
                )
            }
        }
    }
}

@Composable
private fun VoiceLine(voice: VoiceInputState) {
    val c = Pulse.colors
    val (text, color) = when (voice) {
        is VoiceInputState.Preparing -> ("◌ " + voice.status.ifBlank { "PREPARING VOICE…" }.uppercase()) to c.amber
        is VoiceInputState.Listening ->
            ("● LISTENING… " + voice.partial.ifBlank { "(speak now)" }) to c.magenta
        is VoiceInputState.Error -> "✕ ${voice.message}" to c.magenta
        is VoiceInputState.Idle -> return
    }
    Text(
        text,
        fontFamily = JetBrainsMono, fontSize = 10.sp, color = color,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    )
}
