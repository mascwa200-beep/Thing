package dev.mascwa.pulse.feature.safety

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mascwa.pulse.core.util.Formatters
import dev.mascwa.pulse.core.util.Geo
import dev.mascwa.pulse.core.util.openUrl
import dev.mascwa.pulse.data.safety.Incident
import dev.mascwa.pulse.data.safety.IncidentType
import dev.mascwa.pulse.data.safety.Severity
import dev.mascwa.pulse.feature.common.EmptyState
import dev.mascwa.pulse.feature.common.ErrorState
import dev.mascwa.pulse.feature.common.LoadingState
import dev.mascwa.pulse.feature.common.NeonPanel
import dev.mascwa.pulse.feature.common.PulseScaffold
import dev.mascwa.pulse.feature.common.StaleBanner
import dev.mascwa.pulse.ui.theme.JetBrainsMono
import dev.mascwa.pulse.ui.theme.Pulse

@Composable
fun SafetyScreen(vm: SafetyViewModel, onBack: (() -> Unit)? = null) {
    val result by vm.result.collectAsStateWithLifecycle()
    val needsPermission by vm.needsPermission.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val c = Pulse.colors

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { r -> vm.onPermissionResult(r.values.any { it }) }

    PulseScaffold(
        title = "Nearby Safety",
        navigationIcon = {
            if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
        },
    ) { innerPadding ->
        PullToRefreshBox(
            isRefreshing = result.loading && result.data != null,
            onRefresh = { vm.refresh() },
            modifier = Modifier.padding(innerPadding),
        ) {
            when {
                needsPermission -> Column(Modifier.padding(16.dp)) {
                    NeonPanel(Modifier.fillMaxWidth()) {
                        Column {
                            Text("Location needed", style = MaterialTheme.typography.titleMedium, color = c.ink)
                            Text("Grant location to see hazards and incidents near you.",
                                style = MaterialTheme.typography.bodyMedium, color = c.muted,
                                modifier = Modifier.padding(top = 4.dp))
                            TextButton(onClick = {
                                permLauncher.launch(arrayOf(
                                    android.Manifest.permission.ACCESS_FINE_LOCATION,
                                    android.Manifest.permission.ACCESS_COARSE_LOCATION,
                                ))
                            }) { Text("Grant location") }
                        }
                    }
                }
                result.isInitialLoading -> LoadingState()
                result.isError -> ErrorState(result.error ?: "Error", onRetry = { vm.refresh() })
                result.data?.incidents.isNullOrEmpty() -> EmptyState("No incidents reported near you right now.")
                else -> LazyColumn(
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp, top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (result.stale) item { StaleBanner(true) }
                    items(result.data!!.incidents, key = { it.id }) { incident ->
                        IncidentRow(incident) { incident.url?.let { openUrl(context, it) } }
                    }
                    item {
                        Text(
                            "Sources: USGS earthquakes, GDACS global disasters, US NWS alerts. " +
                                "No proprietary crime feed is publicly available; coverage is hazard-based.",
                            style = MaterialTheme.typography.labelSmall, color = c.muted,
                            modifier = Modifier.padding(8.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun IncidentRow(incident: Incident, onClick: () -> Unit) {
    val c = Pulse.colors
    val severity = runCatching { Severity.valueOf(incident.severity) }.getOrDefault(Severity.LOW)
    val type = runCatching { IncidentType.valueOf(incident.type) }.getOrDefault(IncidentType.OTHER)
    val color = when (severity) {
        Severity.EXTREME -> c.magenta
        Severity.HIGH -> c.negative
        Severity.MODERATE -> c.amber
        Severity.LOW -> c.muted
    }
    NeonPanel(Modifier.fillMaxWidth().clickable { onClick() }, borderColor = color.copy(alpha = 0.55f)) {
        Column {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${type.label.uppercase()} · ${severity.name}", fontFamily = JetBrainsMono,
                    fontSize = 9.sp, color = color)
                Text(Formatters.relativeTime(incident.timeEpochMs), fontFamily = JetBrainsMono,
                    fontSize = 9.sp, color = c.muted)
            }
            Text(incident.title, style = MaterialTheme.typography.titleSmall, color = c.ink,
                fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))
            val dist = if (incident.distanceMeters > 0)
                "${Geo.formatDistance(incident.distanceMeters)} · ${Geo.cardinal(incident.bearing)}" else "Your area"
            Text("$dist · ${incident.source}", fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.accent,
                modifier = Modifier.padding(top = 2.dp))
        }
    }
}
