package dev.mascwa.pulse.feature.jarvis

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mascwa.pulse.feature.common.NeonPanel
import dev.mascwa.pulse.feature.common.PulseScaffold
import dev.mascwa.pulse.jarvis.inference.EngineState
import dev.mascwa.pulse.ui.theme.JetBrainsMono
import dev.mascwa.pulse.ui.theme.Pulse

@Composable
fun JarvisScreen(vm: JarvisViewModel, onBack: () -> Unit) {
    val c = Pulse.colors
    val messages by vm.messages.collectAsState()
    val streaming by vm.streaming.collectAsState()
    val busy by vm.busy.collectAsState()
    val engineState by vm.engineState.collectAsState()
    val listState = rememberLazyListState()

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
            IconButton(onClick = { vm.clearHistory() }) {
                Icon(Icons.Filled.DeleteSweep, contentDescription = "Clear", tint = c.muted)
            }
        },
    ) { innerPadding ->
        Column(Modifier.fillMaxSize().padding(innerPadding)) {
            StatusLine(engineState)

            if (messages.isEmpty() && streaming.isEmpty()) {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Text(
                        "J.A.R.V.I.S. MATRIX ONLINE\nEverything runs on this device.",
                        fontFamily = JetBrainsMono, fontSize = 11.sp, color = c.muted,
                        textAlign = TextAlign.Center,
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

            InputBar(busy = busy, onSend = vm::send)
        }
    }
}

@Composable
private fun StatusLine(state: EngineState) {
    val c = Pulse.colors
    val (label, color) = when (state) {
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
private fun InputBar(busy: Boolean, onSend: (String) -> Unit) {
    val c = Pulse.colors
    var input by remember { mutableStateOf("") }
    fun submit() {
        if (input.isNotBlank() && !busy) {
            onSend(input)
            input = ""
        }
    }
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
        IconButton(onClick = { submit() }, enabled = !busy) {
            Icon(
                Icons.AutoMirrored.Filled.Send,
                contentDescription = "Send",
                tint = if (busy) c.muted else c.accent,
            )
        }
    }
}
