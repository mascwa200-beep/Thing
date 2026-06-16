package dev.mascwa.pulse.feature.sos

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Message
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mascwa.pulse.feature.common.NeonPanel
import dev.mascwa.pulse.feature.common.PulseScaffold
import dev.mascwa.pulse.feature.common.SectionBar
import dev.mascwa.pulse.ui.theme.ChakraPetch
import dev.mascwa.pulse.ui.theme.JetBrainsMono
import dev.mascwa.pulse.ui.theme.Pulse

@Composable
fun SosScreen(vm: SosViewModel, onBack: (() -> Unit)? = null) {
    val state by vm.state.collectAsStateWithLifecycle()
    val c = Pulse.colors

    val smsPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* state recomputed on next textContacts() */ }

    PulseScaffold(
        title = "SOS",
        navigationIcon = {
            if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.padding(innerPadding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Big SOS toggle
            item {
                Box(
                    Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier.size(180.dp).clip(CircleShape)
                            .background(if (state.active) c.magenta else c.magenta.copy(alpha = 0.16f))
                            .clickable { vm.toggleSos() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            if (state.active) "STOP" else "SOS",
                            fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 44.sp,
                            color = if (state.active) c.void else c.magenta,
                        )
                    }
                }
            }
            item {
                Text(
                    if (state.active) "Strobe + alarm active. Tap to stop."
                    else "Tap SOS to flash the light in morse and sound a loud alarm.",
                    style = MaterialTheme.typography.bodyMedium, color = c.ink2,
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
                )
            }
            state.lastAction?.let { msg ->
                item { Text(msg, fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.accent,
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) }
            }

            // Call / text / share
            item { SectionBar("Call & send for help") }
            item {
                ActionRow("Call ${state.emergencyNumber}", "Local emergency services", Icons.Filled.Call, c.magenta) {
                    vm.dialEmergency()
                }
            }
            item {
                ActionRow("Text my contacts", "SOS + live coordinates", Icons.Filled.Message, c.accent) {
                    vm.textContacts()
                }
            }
            item {
                ActionRow("Share my location", "Via any app", Icons.Filled.Share, c.accent) {
                    vm.shareLocation()
                }
            }
            if (state.autoSendEnabled) {
                item {
                    Text("AUTO-SEND ON — grant SMS permission so one tap sends without opening an app.",
                        fontFamily = JetBrainsMono, fontSize = 9.sp, color = c.amber,
                        modifier = Modifier.clickable { smsPermission.launch(android.Manifest.permission.SEND_SMS) }
                            .padding(vertical = 4.dp))
                }
            }

            // Coordinates
            item {
                val coords = if (state.latitude != null && state.longitude != null)
                    "%.5f, %.5f".format(state.latitude, state.longitude) else "Locating…"
                NeonPanel(Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Your coordinates", fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.muted)
                        Text(coords, style = MaterialTheme.typography.bodyMedium, color = c.ink)
                    }
                }
            }

            // Emergency card
            item { SectionBar("Emergency card") }
            item {
                val card = state.card
                NeonPanel(Modifier.fillMaxWidth()) {
                    if (card.isEmpty) {
                        Text("Add your medical info & contacts in Settings ▸ Safety.",
                            style = MaterialTheme.typography.bodyMedium, color = c.muted)
                    } else Column {
                        if (card.fullName.isNotBlank()) CardRow("Name", card.fullName)
                        if (card.bloodType.isNotBlank()) CardRow("Blood type", card.bloodType)
                        if (card.allergies.isNotBlank()) CardRow("Allergies", card.allergies)
                        if (card.medications.isNotBlank()) CardRow("Medications", card.medications)
                        if (card.conditions.isNotBlank()) CardRow("Conditions", card.conditions)
                        if (card.notes.isNotBlank()) CardRow("Notes", card.notes)
                    }
                }
            }
            if (state.contacts.isNotEmpty()) {
                item { SectionBar("Emergency contacts") }
                items(state.contacts.size) { i ->
                    val ct = state.contacts[i]
                    NeonPanel(Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(ct.name, style = MaterialTheme.typography.bodyLarge, color = c.ink)
                            Text(ct.phone, fontFamily = JetBrainsMono, fontSize = 12.sp, color = c.muted)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ActionRow(title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector,
                      accent: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    val c = Pulse.colors
    NeonPanel(Modifier.fillMaxWidth().clickable { onClick() }, borderColor = accent.copy(alpha = 0.5f), corners = true) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(accent.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = accent, modifier = Modifier.size(20.dp))
            }
            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = c.ink)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = c.muted)
            }
        }
    }
}

@Composable
private fun CardRow(label: String, value: String) {
    val c = Pulse.colors
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.muted)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = c.ink, textAlign = TextAlign.End)
    }
}
