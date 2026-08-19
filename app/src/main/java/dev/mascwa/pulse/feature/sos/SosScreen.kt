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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import dev.mascwa.pulse.core.telemetry.EmergencyTriage
import dev.mascwa.pulse.core.telemetry.Geodesy
import dev.mascwa.pulse.feature.common.LcarsIcons
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
import dev.mascwa.pulse.feature.common.LcarsFrame
import dev.mascwa.pulse.feature.common.LcarsHeaderBar
import dev.mascwa.pulse.feature.common.PulseScaffold
import dev.mascwa.pulse.ui.theme.ChakraPetch
import dev.mascwa.pulse.ui.theme.JetBrainsMono
import dev.mascwa.pulse.ui.theme.Pulse

@Composable
fun SosScreen(vm: SosViewModel, onBack: (() -> Unit)? = null, onOpenGuide: ((String) -> Unit)? = null) {
    val state by vm.state.collectAsStateWithLifecycle()
    val c = Pulse.colors

    val smsPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* state recomputed on next textContacts() */ }

    PulseScaffold(
        title = "SOS",
        onBack = onBack,
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
                    fontFamily = JetBrainsMono, fontSize = 11.sp, color = c.ink2,
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
                )
            }
            state.lastAction?.let { msg ->
                item { Text(msg, fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.accent,
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) }
            }

            // Call / text / share
            item { LcarsHeaderBar("Call & send for help") }
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
                // Local vals: `state` is a delegated property, so `state.latitude` does not smart-cast
                // to non-null however it is guarded. The old `"%.5f".format(...)` took Any? and hid it.
                val lat = state.latitude
                val lon = state.longitude
                val coords = if (lat != null && lon != null) Geodesy.formatDecimal(lat, lon) else "Locating…"
                LcarsFrame(Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Your coordinates", fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.muted)
                        Text(coords, fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = c.ink)
                    }
                }
            }

            // First aid, one tap from the screen someone already opened in a crisis. It sits BELOW
            // the call actions on purpose: reading is never the first thing to do, and the layout
            // should not imply otherwise. The action line is shown inline because the instruction
            // that matters most must not itself require another tap.
            item { LcarsHeaderBar("First aid — while help is coming") }
            items(FAST_PATH) { e ->
                // Local val: guideId is a property of a class in core:telemetry, which does not
                // smart-cast across the module boundary however it is guarded.
                val gid = e.guideId
                val open = if (gid != null) onOpenGuide else null
                LcarsFrame(
                    Modifier.fillMaxWidth().let { m -> if (open != null) m.clickable { open(gid!!) } else m },
                    accent = c.magenta,
                ) {
                    Text(
                        e.label.uppercase(), fontFamily = ChakraPetch, fontWeight = FontWeight.Bold,
                        fontSize = 13.sp, color = c.ink,
                    )
                    Text(
                        e.firstAction, fontFamily = JetBrainsMono, fontSize = 10.sp,
                        lineHeight = 14.sp, color = c.ink2, modifier = Modifier.padding(top = 3.dp),
                    )
                    if (open != null) {
                        Text(
                            "TAP FOR THE FULL PROTOCOL", fontFamily = JetBrainsMono, fontSize = 8.sp,
                            letterSpacing = 0.8.sp, color = c.muted,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
            item {
                Text(
                    "Ask the Computer for anything not listed — it carries the whole library offline.",
                    fontFamily = JetBrainsMono, fontSize = 9.sp, color = c.muted,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }

            // Emergency card
            item { LcarsHeaderBar("Emergency card") }
            item {
                val card = state.card
                LcarsFrame(Modifier.fillMaxWidth()) {
                    if (card.isEmpty) {
                        Text("Add your medical info & contacts in Settings ▸ Safety.",
                            fontFamily = JetBrainsMono, fontSize = 11.sp, color = c.muted)
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
                item { LcarsHeaderBar("Emergency contacts") }
                items(state.contacts.size) { i ->
                    val ct = state.contacts[i]
                    LcarsFrame(Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(ct.name, fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = c.ink)
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
    LcarsFrame(Modifier.fillMaxWidth().clickable { onClick() }, accent = accent) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).background(accent.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = accent, modifier = Modifier.size(20.dp))
            }
            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                Text(title, fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = c.ink)
                Text(subtitle, fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.muted)
            }
        }
    }
}

@Composable
private fun CardRow(label: String, value: String) {
    val c = Pulse.colors
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.muted)
        Text(value, fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = c.ink, textAlign = TextAlign.End)
    }
}

/**
 * The emergencies worth putting one tap from the SOS screen.
 *
 * Taken from the head of [EmergencyTriage.EMERGENCIES], which is ordered by how fast each situation
 * kills — so this list stays correct as that table changes rather than drifting from it. Only ones
 * with a protocol behind them appear, because a row that cannot open anything is furniture.
 */
private val FAST_PATH: List<EmergencyTriage.Emergency> =
    EmergencyTriage.EMERGENCIES.filter { it.covered }.take(8)
