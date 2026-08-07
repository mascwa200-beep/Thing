package dev.mascwa.pulse.desktop.feature.remote

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mascwa.pulse.desktop.remote.RemoteCommand
import dev.mascwa.pulse.desktop.theme.ChakraPetch
import dev.mascwa.pulse.desktop.theme.JetBrainsMono
import dev.mascwa.pulse.desktop.theme.LcarsBusyBar
import dev.mascwa.pulse.desktop.theme.LcarsButton
import dev.mascwa.pulse.desktop.theme.LcarsDataRow
import dev.mascwa.pulse.desktop.theme.LcarsFrame
import dev.mascwa.pulse.desktop.theme.LcarsGhostButton
import dev.mascwa.pulse.desktop.theme.LcarsHeaderBar
import dev.mascwa.pulse.desktop.theme.LcarsStatusDot
import dev.mascwa.pulse.desktop.theme.LcarsSwitch
import dev.mascwa.pulse.desktop.theme.LcarsTextField
import dev.mascwa.pulse.desktop.theme.Pulse

/**
 * The remote-control panel — the desktop app's reason to exist.
 *
 * Two states share one screen: unpaired shows the pairing form, paired shows live phone status and the
 * switches. Copy is written for a person standing at their desk with the phone in the other hand, so
 * failures say what to do rather than naming an exception.
 */
@Composable
fun RemoteScreen(vm: RemoteViewModel, modifier: Modifier = Modifier) {
    val state by vm.state.collectAsState()
    val c = Pulse.colors

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        LcarsHeaderBar(
            "Remote link",
            trailing = if (state.paired == null) "NOT PAIRED" else state.connection.label,
        )
        LcarsBusyBar(active = state.busy, modifier = Modifier.fillMaxWidth())

        if (state.paired == null) PairingPanel(vm, state) else ControlPanel(vm, state)

        state.message?.let { msg ->
            LcarsFrame(
                Modifier.fillMaxWidth().padding(top = 12.dp),
                accent = if (state.messageIsError) c.negative else c.positive,
            ) {
                Text(msg, fontFamily = JetBrainsMono, fontSize = 12.sp, color = c.ink)
            }
        }
    }
}

/** First-run: type where the phone is and the code it is showing. */
@Composable
private fun PairingPanel(vm: RemoteViewModel, state: RemoteUiState) {
    val c = Pulse.colors
    LcarsFrame(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(
                "Pair with your phone",
                fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 17.sp, color = c.ink,
            )
            Text(
                "On the phone open Menu → Remote link and switch it on. It will show an address and a " +
                    "six-digit code. Both devices must be on the same Wi-Fi.",
                fontFamily = JetBrainsMono, fontSize = 12.sp, color = c.muted,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                LcarsTextField(
                    label = "Phone address",
                    value = state.host,
                    onValueChange = vm::setHost,
                    placeholder = "192.168.1.42",
                    modifier = Modifier.weight(2f),
                    enabled = !state.busy,
                )
                LcarsTextField(
                    label = "Code",
                    value = state.code,
                    onValueChange = vm::setCode,
                    placeholder = "000000",
                    modifier = Modifier.weight(1f),
                    enabled = !state.busy,
                )
            }
            LcarsButton(
                text = if (state.busy) "Pairing…" else "Pair",
                onClick = vm::pair,
                enabled = !state.busy && state.host.isNotBlank() && state.code.length == 6,
            )
            Text(
                "This machine's fingerprint: ${state.fingerprint}",
                fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.faint,
            )
        }
    }
}

/** Paired: the live readout and the switches. */
@Composable
private fun ControlPanel(vm: RemoteViewModel, state: RemoteUiState) {
    val c = Pulse.colors
    val paired = state.paired ?: return
    val online = state.connection == ConnectionState.ONLINE

    LcarsFrame(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        paired.name,
                        fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 17.sp, color = c.ink,
                    )
                    Text(
                        "${paired.host}:${paired.port}",
                        fontFamily = JetBrainsMono, fontSize = 11.sp, color = c.muted,
                    )
                }
                LcarsStatusDot(
                    label = state.connection.label,
                    color = when (state.connection) {
                        ConnectionState.ONLINE -> c.positive
                        ConnectionState.OFFLINE -> c.negative
                        ConnectionState.UNKNOWN -> c.muted
                    },
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                LcarsButton("Refresh", vm::refreshStatus, enabled = !state.busy)
                LcarsGhostButton("Forget device", vm::forget, enabled = !state.busy, accent = c.negative)
            }
        }
    }

    if (state.status.isNotEmpty()) {
        LcarsHeaderBar("Phone status")
        LcarsFrame(Modifier.fillMaxWidth()) {
            Column {
                for ((label, value) in state.readout()) LcarsDataRow(label, value)
            }
        }
    }

    LcarsHeaderBar("Switches")
    LcarsFrame(Modifier.fillMaxWidth()) {
        Column {
            if (!online) {
                Text(
                    "Reconnect to change these — the phone is not answering.",
                    fontFamily = JetBrainsMono, fontSize = 11.sp, color = c.muted,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            for (toggle in TOGGLES) {
                LcarsSwitch(
                    label = toggle.label,
                    subtitle = toggle.subtitle,
                    checked = state.flag(toggle.statusKey),
                    enabled = online && !state.busy,
                    onCheckedChange = { on -> vm.setFlag(toggle.command, toggle.statusKey, on) },
                )
            }
        }
    }

    LcarsHeaderBar("Actions")
    LcarsFrame(Modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            LcarsButton("Refresh feeds", { vm.act(RemoteCommand.REFRESH_NOW) }, enabled = online && !state.busy)
            LcarsButton("Send brief", { vm.act(RemoteCommand.SEND_BRIEF) }, enabled = online && !state.busy)
            LcarsGhostButton("Stop radio", { vm.act(RemoteCommand.RADIO_STOP) }, enabled = online && !state.busy)
        }
    }

    Column(Modifier.padding(top = 16.dp, bottom = 24.dp)) {
        Text(
            "Paired device fingerprint: ${paired.fingerprint}",
            fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.faint,
        )
        Text(
            "Commands are encrypted and only this paired machine can issue them. Everything the phone " +
                "accepts is recorded in its audit ledger.",
            fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.faint,
            modifier = Modifier.padding(top = 4.dp).width(560.dp),
        )
    }
}

/** One switch row: the command that sets it, and the status key that reports it. */
internal data class RemoteToggle(
    val label: String,
    val subtitle: String,
    val command: RemoteCommand,
    val statusKey: String,
)

internal val TOGGLES = listOf(
    RemoteToggle("Notifications", "The situation board and every alert.", RemoteCommand.SET_NOTIFICATIONS, "notifications"),
    RemoteToggle("Quiet hours", "Hold non-urgent alerts overnight.", RemoteCommand.SET_QUIET_HOURS, "quietHours"),
    RemoteToggle("Breaking takeover", "Let a major event take over the screen.", RemoteCommand.SET_BREAKING_INTERRUPT, "breakingInterrupt"),
    RemoteToggle("Live news polling", "Check for breaking news every 90 seconds.", RemoteCommand.SET_LIVE_NEWS, "liveNews"),
    RemoteToggle("Assistant service", "Keep the Computer resident in the background.", RemoteCommand.SET_ASSISTANT, "assistant"),
    RemoteToggle("Wake word", "Listen for the wake word.", RemoteCommand.SET_WAKE_WORD, "wakeWord"),
    RemoteToggle("Vitals tracking", "Stay connected to a heart-rate strap.", RemoteCommand.SET_VITALS, "vitals"),
    RemoteToggle("Voice replies", "Speak answers aloud.", RemoteCommand.SET_VOICE_REPLIES, "voiceReplies"),
)
