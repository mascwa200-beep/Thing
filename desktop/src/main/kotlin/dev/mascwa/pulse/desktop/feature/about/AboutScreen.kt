package dev.mascwa.pulse.desktop.feature.about

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mascwa.pulse.desktop.telemetry.UpdatePolicy
import dev.mascwa.pulse.desktop.theme.ChakraPetch
import dev.mascwa.pulse.desktop.theme.JetBrainsMono
import dev.mascwa.pulse.desktop.theme.NightwirePalette
import dev.mascwa.pulse.desktop.theme.LcarsBusyBar
import dev.mascwa.pulse.desktop.theme.LcarsButton
import dev.mascwa.pulse.desktop.theme.LcarsFillRow
import dev.mascwa.pulse.desktop.theme.LcarsFrame
import dev.mascwa.pulse.desktop.theme.LcarsHeaderBar
import dev.mascwa.pulse.desktop.theme.LcarsSwitch
import dev.mascwa.pulse.desktop.theme.LcarsTextField
import dev.mascwa.pulse.desktop.theme.Pulse

/**
 * ABOUT — which build this is, and getting the next one.
 *
 * The desktop had no settings surface at all and no way to update itself; this is both, kept to what it
 * genuinely needs rather than growing a settings tree nobody asked for.
 */
@Composable
fun AboutScreen(
    vm: AboutViewModel,
    onQuitForInstall: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by vm.state.collectAsState()
    val c = Pulse.colors

    Column(modifier.fillMaxSize().padding(horizontal = 20.dp).verticalScroll(rememberScrollState())) {
        LcarsHeaderBar("About", trailing = state.installed.uppercase())
        LcarsBusyBar(state.checking)

        LcarsFrame(Modifier.fillMaxWidth().padding(top = 10.dp)) {
            Column {
                Text(
                    "LCARS — desktop edition",
                    fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = c.ink,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Installed: ${state.installed}",
                    fontFamily = JetBrainsMono, fontSize = 12.sp, color = c.muted,
                )
                state.latestVersionName?.let {
                    Text("Published: $it", fontFamily = JetBrainsMono, fontSize = 12.sp, color = c.muted)
                }

                Spacer(Modifier.height(10.dp))
                Text(verdictLine(state), fontFamily = JetBrainsMono, fontSize = 12.sp, color = verdictColor(state, c))

                state.error?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, fontFamily = JetBrainsMono, fontSize = 11.sp, color = c.negative)
                }

                // Real progress, because the installer carries the whole bundled library and is a large
                // download — a spinner with no number would look stuck.
                state.downloadPct?.let { pct ->
                    Spacer(Modifier.height(10.dp))
                    Text("Downloading — $pct%", fontFamily = JetBrainsMono, fontSize = 11.sp, color = c.accent)
                    Spacer(Modifier.height(4.dp))
                    LcarsFillRow(
                        segments = listOf(pct.toFloat() to c.accent, (100 - pct).toFloat() to c.raise),
                        modifier = Modifier.fillMaxWidth().height(7.dp),
                        gap = 1.5.dp,
                    )
                }

                if (state.notes.isNotBlank() && state.update != null) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        state.notes,
                        fontFamily = JetBrainsMono, fontSize = 11.sp, color = c.muted, lineHeight = 16.sp,
                        modifier = Modifier.widthIn(max = 760.dp),
                    )
                }

                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LcarsButton("CHECK NOW", onClick = { vm.check() })
                    if (state.update != null && state.downloaded == null && state.downloadPct == null) {
                        LcarsButton("DOWNLOAD", onClick = { vm.download() }, accent = c.sky)
                    }
                    if (state.downloaded != null) {
                        // Quitting is part of the action, not an afterthought: Windows cannot replace
                        // files that are open, so the upgrade only proceeds once this app lets go.
                        LcarsButton(
                            "INSTALL AND QUIT",
                            onClick = { if (vm.install()) onQuitForInstall() },
                            accent = c.positive,
                        )
                    }
                }
            }
        }

        LcarsFrame(Modifier.fillMaxWidth().padding(top = 12.dp), accent = c.sky) {
            Column {
                Text(
                    "Updates",
                    fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = c.ink,
                )
                Spacer(Modifier.height(8.dp))
                LcarsSwitch(
                    label = "Check on launch",
                    checked = state.autoCheck,
                    onCheckedChange = { vm.setAutoCheck(it) },
                    subtitle = "One request. Nothing is downloaded or installed without you.",
                )
                Spacer(Modifier.height(10.dp))
                LcarsTextField(
                    label = "GITHUB TOKEN",
                    value = state.token,
                    onValueChange = { vm.setToken(it) },
                    placeholder = "read access to the repository",
                    modifier = Modifier.fillMaxWidth().widthIn(max = 620.dp),
                )
                Spacer(Modifier.height(6.dp))
                // ⚠️ Said here rather than left to be discovered. The phone keeps its copy behind the
                // secure element; nothing on this side can, so the honest thing is to say where it lives
                // and suggest the narrowest token that works.
                Text(
                    "The repository is private, so reading its releases needs a token. It is stored in " +
                        "plain text in this machine's settings file — read-only scope is all this needs.",
                    fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.faint,
                    modifier = Modifier.widthIn(max = 620.dp),
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "Installing runs the Windows installer, which asks for confirmation and cannot be " +
                        "skipped — an app is not allowed to replace itself silently.",
                    fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.faint,
                    modifier = Modifier.widthIn(max = 620.dp),
                )
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

private fun verdictLine(s: AboutUiState): String = when (s.verdict) {
    UpdatePolicy.Verdict.AVAILABLE -> "A newer build is ready to install."
    UpdatePolicy.Verdict.CURRENT -> "This is the latest published build."
    UpdatePolicy.Verdict.PENDING -> "A newer build exists but is still being built."
    // Two quite different situations, and neither is a claim about being up to date: either this copy was
    // built locally and has no run number, or the release did not say which build it is.
    UpdatePolicy.Verdict.UNKNOWN -> "Can't tell whether this is current."
    null -> if (s.checking) "Checking…" else "Not checked yet."
}

@Composable
private fun verdictColor(s: AboutUiState, c: NightwirePalette) = when (s.verdict) {
    UpdatePolicy.Verdict.AVAILABLE -> c.positive
    UpdatePolicy.Verdict.PENDING -> c.amber
    UpdatePolicy.Verdict.CURRENT -> c.muted
    else -> c.muted
}
