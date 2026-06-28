package dev.mascwa.pulse.feature.security

import android.content.Intent
import android.provider.Settings
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mascwa.pulse.core.telemetry.SecurityAudit
import dev.mascwa.pulse.feature.common.NeonPanel
import dev.mascwa.pulse.feature.common.PulseScaffold
import dev.mascwa.pulse.feature.common.SectionBar
import dev.mascwa.pulse.ui.theme.JetBrainsMono
import dev.mascwa.pulse.ui.theme.NightwirePalette
import dev.mascwa.pulse.ui.theme.Pulse

@Composable
fun SecurityAuditScreen(vm: SecurityAuditViewModel, onBack: () -> Unit) {
    val c = Pulse.colors
    val view by vm.view.collectAsState()
    val scanning by vm.scanning.collectAsState()
    val status by vm.status.collectAsState()
    val context = LocalContext.current
    // Usage-access is a binder call (AppOps). Compute it ONCE per scan, not on every recomposition — calling
    // it inline meant it fired on each frame, and during a scan it contended with the heavy package-manager
    // binder transaction on the main thread → ANR ("Pulse isn't responding"). Re-checks after each scan.
    val usageAccess = remember(view.lastScanMs) { runCatching { vm.hasUsageAccess() }.getOrDefault(false) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri -> uri?.let { vm.export(context, it) } }

    PulseScaffold(
        title = "SECURITY AUDIT",
        navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = c.ink) }
        },
    ) { innerPadding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(innerPadding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                NeonPanel(Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            Count("CRIT", view.summary.critical, c.magenta)
                            Count("WARN", view.summary.warning, c.accent)
                            Count("INFO", view.summary.info, c.muted)
                        }
                        val last = if (view.lastScanMs > 0)
                            DateUtils.getRelativeTimeSpanString(view.lastScanMs).toString() else "never scanned"
                        Text(
                            "$last · ${view.appsScanned} apps · ${view.userCaCount} user CAs · " +
                                "usage access ${if (view.usageAvailable) "on" else "off"}",
                            fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.muted,
                        )
                        if (status.isNotBlank()) {
                            Text(status, fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.accent)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AuditButton(if (scanning) "SCANNING…" else "RUN SCAN", c.accent) { if (!scanning) vm.scan() }
                            AuditButton("EXPORT", c.ink) { exportLauncher.launch("pulse-audit.txt") }
                        }
                    }
                }
            }

            // Usage Access hint — needed for the data-drain / exfil checks.
            if (!usageAccess) {
                item {
                    NeonPanel(Modifier.fillMaxWidth()) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Grant Usage Access", fontFamily = JetBrainsMono, fontSize = 12.sp,
                                fontWeight = FontWeight.Bold, color = c.accent)
                            Text(
                                "Per-app data-usage + exfiltration checks need Usage Access. Tap to grant it to Pulse.",
                                fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.muted,
                            )
                            AuditButton("OPEN USAGE ACCESS", c.accent) {
                                runCatching { context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
                            }
                        }
                    }
                }
            }

            // Honest note about what a non-root app can't see.
            item {
                Text(
                    "Local-only · read-only · never transmitted. Process/socket inspection, per-app battery, " +
                        "and other apps' files require root or a system signature and are not included.",
                    fontFamily = JetBrainsMono, fontSize = 9.sp, color = c.muted,
                    modifier = Modifier.padding(vertical = 2.dp),
                )
            }

            item { SectionBar("FINDINGS · ${view.findings.size}") }
            if (view.findings.isEmpty()) {
                item {
                    Text(
                        if (view.lastScanMs > 0) "No findings — nothing actionable from the last scan."
                        else "No scan yet. Tap RUN SCAN to audit installed apps, the trust store, encryption and data usage.",
                        fontFamily = JetBrainsMono, fontSize = 11.sp, color = c.muted,
                    )
                }
            }
            items(view.findings, key = { it.id }) { f ->
                FindingCard(f, c, onAck = { vm.whitelist(f.id) })
            }
            if (view.whitelistSize > 0) {
                item {
                    Text(
                        "${view.whitelistSize} finding(s) acknowledged (hidden).",
                        fontFamily = JetBrainsMono, fontSize = 9.sp, color = c.muted,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun Count(label: String, n: Int, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("$n", fontFamily = JetBrainsMono, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = color)
        Text(label, fontFamily = JetBrainsMono, fontSize = 8.sp, letterSpacing = 1.sp, color = color)
    }
}

@Composable
private fun FindingCard(f: SecurityAudit.Finding, c: NightwirePalette, onAck: () -> Unit) {
    val color = when (f.severity) {
        SecurityAudit.Severity.CRITICAL -> c.magenta
        SecurityAudit.Severity.WARNING -> c.accent
        SecurityAudit.Severity.INFO -> c.muted
    }
    NeonPanel(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(f.severity.name, fontFamily = JetBrainsMono, fontSize = 8.sp, letterSpacing = 1.sp, color = color)
                Text(f.category.name, fontFamily = JetBrainsMono, fontSize = 8.sp, color = c.muted)
            }
            Text(f.title, fontFamily = JetBrainsMono, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = c.ink)
            if (f.detail.isNotBlank()) {
                Text(f.detail, fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.muted)
            }
            AuditButton("ACKNOWLEDGE", c.muted, onAck)
        }
    }
}

@Composable
private fun AuditButton(text: String, color: Color, onClick: () -> Unit) {
    Row(
        Modifier
            .border(1.dp, color.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.08f), RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
    ) {
        Text(text, fontFamily = JetBrainsMono, fontSize = 10.sp, fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp, color = color)
    }
}
