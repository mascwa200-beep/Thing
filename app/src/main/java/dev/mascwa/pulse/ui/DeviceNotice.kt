package dev.mascwa.pulse.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mascwa.pulse.core.device.DeviceGate
import dev.mascwa.pulse.feature.common.LcarsButton
import dev.mascwa.pulse.feature.common.LcarsFrame
import dev.mascwa.pulse.feature.common.LcarsIcons
import dev.mascwa.pulse.ui.theme.ChakraPetch
import dev.mascwa.pulse.ui.theme.JetBrainsMono
import dev.mascwa.pulse.ui.theme.Pulse

/**
 * A one-time notice about what this phone can and cannot do — **not a gate**.
 *
 * ## What changed, and why
 *
 * ⚠️ This used to replace the entire application until it was dismissed, on any device that was not
 * a Pixel 10 Pro XL running GrapheneOS. It offered "Exit" as a first-class choice. That is a block
 * however politely it is worded, and it made the app unusable on the hardware most people have for
 * the sake of a handful of capabilities that were never load-bearing to reading the news.
 *
 * It is now drawn OVER a running app, dismissed with one button, and shown once. Nothing is
 * withheld behind it.
 *
 * ## The rule this content obeys
 *
 * ⚠️ **Name the capability that is actually missing, never "some features may not work".** A hedge
 * teaches the reader that the whole notice is boilerplate, and the specific facts here are short
 * enough to just say. Everything listed is measured from what the code does, not from what the
 * product would like to claim:
 *
 * - The device-owner controls are unavailable, and `DevicePolicyController.unavailableReason` says
 *   why — one sentence, in one place, that both this and Settings read.
 * - The hardware-attestation readout compares against GrapheneOS's own verified-boot key, so on
 *   stock Android it will report a mismatch. That is the OS being different, not a fault.
 * - Everything else — every feed, the library, the assistant, health, the map — is untouched.
 *
 * ⚠️ It also states the two things that genuinely cannot be relaxed, because "installs anywhere"
 * would otherwise be a promise the packaging cannot keep: this APK carries **arm64-v8a only**
 * (Chaquopy refuses to configure without an ABI filter, and whisper/llama/QuickJS are built for
 * that one), and `minSdk` is **31**. A phone outside either cannot install this build at all, and
 * a person who reads a reassuring notice and then cannot install it is worse off than one told
 * plainly.
 */
@Composable
fun DeviceNotice(
    result: DeviceGate.Result,
    grapheneOk: Boolean,
    osDetail: String,
    attestationOk: Boolean? = null,
    attestationDetail: String? = null,
    privilegeDetail: String? = null,
    onDismiss: () -> Unit,
) {
    val c = Pulse.colors
    // A scrim, so the notice reads as something over the app rather than as the app.
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.82f))
            .padding(20.dp),
        contentAlignment = Alignment.Center,
    ) {
        LcarsFrame(Modifier.widthIn(max = 520.dp)) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    "THIS IS NOT THE PHONE LCARS WAS BUILT ON",
                    color = c.accent,
                    fontFamily = ChakraPetch,
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp,
                )
                Text(
                    "It runs here anyway, and everything you actually use runs with it — every feed, " +
                        "the library, the assistant, health, the map, the radio. Nothing is held back.",
                    color = c.ink,
                    fontFamily = ChakraPetch,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                )

                Requirement(
                    label = "Pixel 10 Pro XL",
                    ok = result.isMatch,
                    detail = "${result.detectedModel} (${result.detectedDevice})",
                )
                Requirement(label = "GrapheneOS", ok = grapheneOk, detail = osDetail)
                if (attestationDetail != null) {
                    Requirement(
                        label = "Hardware attestation",
                        ok = attestationOk == true,
                        detail = attestationDetail,
                    )
                }

                if (privilegeDetail != null) {
                    Text(
                        "WHAT IS UNAVAILABLE",
                        color = c.accent,
                        fontFamily = ChakraPetch,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    Text(
                        privilegeDetail,
                        color = c.muted,
                        fontFamily = JetBrainsMono,
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                    )
                }

                Text(
                    "The attestation line compares against GrapheneOS's own verified-boot key, so on " +
                        "stock Android it reports a mismatch. That is the operating system being " +
                        "different, not a fault.",
                    color = c.muted,
                    fontFamily = ChakraPetch,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )

                LcarsButton(
                    text = "UNDERSTOOD",
                    onClick = onDismiss,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun Requirement(label: String, ok: Boolean, detail: String) {
    val c = Pulse.colors
    Row(
        Modifier.padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (ok) Icons.Filled.Check else LcarsIcons.Close, null,
            modifier = Modifier.size(20.dp),
            tint = if (ok) c.positive else c.muted,
        )
        Column(Modifier.padding(start = 12.dp)) {
            Text(label, color = c.ink, fontFamily = ChakraPetch, fontSize = 13.sp)
            Text(detail, color = c.muted, fontFamily = JetBrainsMono, fontSize = 10.sp)
        }
    }
}
