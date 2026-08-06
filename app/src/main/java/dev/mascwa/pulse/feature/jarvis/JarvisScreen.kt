package dev.mascwa.pulse.feature.jarvis

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import dev.mascwa.pulse.feature.common.LcarsIcons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
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
    val pendingCode by vm.pendingCode.collectAsState()
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
        title = "COMPUTER",
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(LcarsIcons.ArrowBack, contentDescription = "Back", tint = c.ink)
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
            // Everything else (Approvals, Memory, Lockdown, Clear chat) now lives in Setup.
            IconButton(onClick = onOpenSetup) {
                Icon(Icons.Filled.Tune, contentDescription = "Computer settings", tint = c.sky)
            }
        },
    ) { innerPadding ->
        // The chat bar docks FLUSH on the soft keyboard (Claude-app style). PulseApp wraps this screen in a
        // Box that consumes the outer Scaffold padding (the bottom-nav height) as an inset, so this imePadding()
        // lifts the bar by (keyboard − nav-bar) and seats it exactly on the keyboard's top edge — not one
        // nav-bar height above it (the old gap) nor behind it (no imePadding at all). The idle reactor fills
        // the weight(1f) space above, so the input bar always sits at the very bottom of the content.
        Column(Modifier.fillMaxSize().padding(innerPadding).imePadding()) {
            StatusLine(engineState, cloudStatus)
            if (banter.isNotBlank()) BanterLine(banter)

            if (messages.isEmpty() && streaming.isEmpty()) {
                // Clean HUD idle state — the reactor + wordmark, no hint clutter.
                Column(
                    Modifier.fillMaxWidth().weight(1f).padding(horizontal = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
                ) {
                    HudReactor(
                        color = c.sky, accent = c.accent, active = false,
                        modifier = Modifier.size(208.dp),
                    )
                    Text(
                        "COMPUTER",
                        fontFamily = JetBrainsMono, fontSize = 22.sp, letterSpacing = 9.sp, color = c.sky,
                    )
                    Text(
                        "LCARS INTERFACE · ONLINE",
                        fontFamily = JetBrainsMono, fontSize = 8.sp, letterSpacing = 3.sp, color = c.muted,
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

            pendingCode?.let { action ->
                CodeApprovalCard(
                    action = action,
                    onApprove = { vm.approveCode(action) },
                    onReject = { vm.rejectCode(action) },
                    enabled = !busy,
                )
            }

            InputBar(
                busy = busy,
                voice = voiceInput,
                onSend = vm::send,
                onMic = onMic,
                onSendImage = { uri, cap -> vm.sendImage(context, uri, cap) },
                onSendFile = { uri, cap -> vm.sendFile(context, uri, cap) },
            )
        }
    }
}

/** Inline approve/reject for a self-code change J.A.R.V.I.S. has staged — so the user can ship a code
 *  change without leaving the console. The PR opens only on APPROVE (via the shared ApprovalGate). */
@Composable
private fun CodeApprovalCard(
    action: dev.mascwa.pulse.data.selfedit.PendingAction,
    onApprove: () -> Unit,
    onReject: () -> Unit,
    enabled: Boolean,
) {
    val c = Pulse.colors
    NeonPanel(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        borderColor = c.amber.copy(alpha = 0.6f),
        background = c.panel,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "◆ ${action.title}",
                fontFamily = JetBrainsMono, fontSize = 11.sp, color = c.amber,
            )
            val diff = action.payload["diff"]
            if (!diff.isNullOrBlank()) {
                dev.mascwa.pulse.feature.common.DiffText(diff, maxLines = 14)
                Text(
                    "Full diff in the Approvals screen",
                    fontFamily = JetBrainsMono, fontSize = 8.sp, color = c.muted,
                )
            } else {
                Text(
                    action.preview.take(280),
                    fontFamily = JetBrainsMono, fontSize = 9.sp, color = c.muted,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IconButton(onClick = onApprove, enabled = enabled) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Approve & open PR", tint = c.positive)
                }
                IconButton(onClick = onReject, enabled = enabled) {
                    Icon(Icons.Filled.DeleteSweep, contentDescription = "Reject", tint = c.magenta)
                }
                Text(
                    "Approve → open PR",
                    fontFamily = JetBrainsMono, fontSize = 9.sp, color = c.muted,
                    modifier = Modifier.padding(top = 14.dp),
                )
            }
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
    // Spin the HUD up while a model/cloud brain is live; tick over otherwise.
    val live = cloud != null || state is EngineState.Ready
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        HudReactor(color = c.sky, accent = c.accent, active = live, modifier = Modifier.size(34.dp))
        Column {
            Text(
                "COMPUTER // LCARS",
                fontFamily = JetBrainsMono, fontSize = 7.sp, letterSpacing = 2.sp,
                color = c.sky.copy(alpha = 0.65f),
            )
            Text(label, fontFamily = JetBrainsMono, fontSize = 9.sp, letterSpacing = 1.sp, color = color)
        }
    }
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
            borderColor = if (isUser) c.accent.copy(alpha = 0.5f) else c.sky.copy(alpha = 0.4f),
            background = if (isUser) c.raise else c.panel,
        ) {
            Column {
                Text(
                    if (isUser) "YOU" else "COMPUTER",
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
private fun InputBar(
    busy: Boolean,
    voice: VoiceInputState,
    onSend: (String) -> Unit,
    onMic: () -> Unit,
    onSendImage: (android.net.Uri, String) -> Unit,
    onSendFile: (android.net.Uri, String) -> Unit,
) {
    val c = Pulse.colors
    var input by remember { mutableStateOf("") }
    var attachOpen by remember { mutableStateOf(false) }
    fun submit() {
        if (input.isNotBlank() && !busy) {
            onSend(input)
            input = ""
            attachOpen = false
        }
    }
    // Free system photo picker (no permission). The current text becomes the caption/question.
    val pickImage = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null && !busy) {
            onSendImage(uri, input)
            input = ""
        }
        attachOpen = false
    }
    // Any file (PDF/text/code/image) — routed by type for J.A.R.V.I.S. to interpret.
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null && !busy) {
            onSendFile(uri, input)
            input = ""
        }
        attachOpen = false
    }
    // Take a photo with the camera (Claude.ai parity) → interpreted via the same vision path as a gallery
    // image. The capture writes into a FileProvider URI we hand the camera app.
    val context = LocalContext.current
    var cameraUri by remember { mutableStateOf<android.net.Uri?>(null) }
    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success && !busy) {
            cameraUri?.let { onSendImage(it, input) }
            input = ""
        }
        attachOpen = false
    }
    val listening = voice is VoiceInputState.Listening || voice is VoiceInputState.Preparing
    Column {
        VoiceLine(voice)
        // One wide HUD chat box; the ⊕ pops out attach options, mic + send sit inside on the right.
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp)
                .clip(RoundedCornerShape(26.dp))
                .background(c.panel)
                .border(1.dp, c.sky.copy(alpha = 0.45f), RoundedCornerShape(26.dp))
                .padding(start = 18.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f).padding(top = 14.dp, bottom = 14.dp, end = 6.dp),
                textStyle = TextStyle(color = c.ink, fontFamily = JetBrainsMono, fontSize = 13.sp),
                cursorBrush = SolidColor(c.sky),
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSend = { submit() }),
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (input.isEmpty()) {
                            Text("Message J.A.R.V.I.S.", fontFamily = JetBrainsMono, fontSize = 13.sp, color = c.muted)
                        }
                        inner()
                    }
                },
            )
            // Attach options fan out from the ⊕ when open.
            AnimatedVisibility(visible = attachOpen) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = {
                            if (!busy) pickImage.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                            )
                        },
                    ) { Icon(Icons.Filled.Image, contentDescription = "Attach image", tint = c.sky) }
                    IconButton(
                        onClick = {
                            if (!busy) {
                                val uri = dev.mascwa.pulse.core.util.createCameraImageUri(context)
                                if (uri != null) { cameraUri = uri; takePhoto.launch(uri) }
                            }
                        },
                    ) { Icon(Icons.Filled.PhotoCamera, contentDescription = "Take photo", tint = c.sky) }
                    IconButton(onClick = { if (!busy) pickFile.launch("*/*") }) {
                        Icon(Icons.Filled.AttachFile, contentDescription = "Attach file", tint = c.sky)
                    }
                }
            }
            IconButton(onClick = { attachOpen = !attachOpen }) {
                Icon(
                    Icons.Filled.AddCircle,
                    contentDescription = if (attachOpen) "Close attachments" else "Attach file or image",
                    tint = c.sky,
                    modifier = Modifier.rotate(if (attachOpen) 45f else 0f),
                )
            }
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
