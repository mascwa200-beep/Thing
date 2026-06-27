package dev.mascwa.pulse.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PhonelinkLock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.mascwa.pulse.core.device.DeviceGate

/**
 * Pulse is an exclusive build for the Google Pixel 10 Pro XL **on GrapheneOS**. The gate shows both
 * requirements and whether each is met. Detection of GrapheneOS is heuristic, so the owner is never
 * hard-locked — "Continue anyway" is kept as a safety so a detection miss can't brick the app.
 */
@Composable
fun DeviceGateScreen(
    result: DeviceGate.Result,
    grapheneOk: Boolean,
    osDetail: String,
    attestationOk: Boolean? = null,
    attestationDetail: String? = null,
    onContinue: () -> Unit,
    onExit: () -> Unit,
) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                Icons.Filled.PhonelinkLock, null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                "Exclusive build",
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 16.dp),
            )
            Text(
                "Pulse is built only for the Google Pixel 10 Pro XL running GrapheneOS. " +
                    "On anything else, layout and some hardening features aren't guaranteed.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp),
            )

            Requirement(
                label = "Pixel 10 Pro XL",
                ok = result.isMatch,
                detail = "${result.detectedModel} (${result.detectedDevice})",
            )
            Requirement(label = "GrapheneOS", ok = grapheneOk, detail = osDetail)
            if (attestationDetail != null) {
                Requirement(label = "Hardware attestation", ok = attestationOk == true, detail = attestationDetail)
            }

            Button(onClick = onContinue, modifier = Modifier.padding(top = 24.dp)) {
                Text("Continue anyway")
            }
            OutlinedButton(onClick = onExit, modifier = Modifier.padding(top = 8.dp)) {
                Text("Exit")
            }
        }
    }
}

@Composable
private fun Requirement(label: String, ok: Boolean, detail: String) {
    Row(
        Modifier.padding(top = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (ok) Icons.Filled.Check else Icons.Filled.Close, null,
            modifier = Modifier.size(22.dp),
            tint = if (ok) Color(0xFF39FF14) else MaterialTheme.colorScheme.error,
        )
        Column(Modifier.padding(start = 12.dp)) {
            Text(label, style = MaterialTheme.typography.titleSmall)
            Text(
                detail,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
