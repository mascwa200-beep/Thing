package dev.mascwa.pulse.feature.safety

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import dev.mascwa.pulse.feature.common.LcarsIcons
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
import dev.mascwa.pulse.feature.common.LcarsChip
import dev.mascwa.pulse.feature.common.LcarsFrame
import dev.mascwa.pulse.feature.common.LoadingState
import dev.mascwa.pulse.feature.common.PulseScaffold
import dev.mascwa.pulse.feature.common.StaleBanner
import dev.mascwa.pulse.ui.theme.ChakraPetch
import dev.mascwa.pulse.ui.theme.JetBrainsMono
import dev.mascwa.pulse.ui.theme.Pulse

@Composable
fun SafetyScreen(vm: SafetyViewModel, onBack: (() -> Unit)? = null) {
    val result by vm.result.collectAsStateWithLifecycle()
    val needsPermission by vm.needsPermission.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val c = Pulse.colors
    var typeFilter by remember { mutableStateOf<IncidentType?>(null) } // null = every type

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { r -> vm.onPermissionResult(r.values.any { it }) }

    PulseScaffold(
        title = "Nearby Safety",
        navigationIcon = {
            if (onBack != null) IconButton(onClick = onBack) { Icon(LcarsIcons.ArrowBack, "Back") }
        },
    ) { innerPadding ->
        Column(Modifier.padding(innerPadding)) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                LcarsChip("ALL", selected = typeFilter == null, onClick = { typeFilter = null })
                IncidentType.entries.forEach { t ->
                    LcarsChip(t.label, selected = typeFilter == t, onClick = { typeFilter = if (typeFilter == t) null else t })
                }
            }
            val filtered = remember(result.data, typeFilter) {
                val all = result.data?.incidents.orEmpty()
                if (typeFilter == null) all
                else all.filter { runCatching { IncidentType.valueOf(it.type) }.getOrDefault(IncidentType.OTHER) == typeFilter }
            }
            PullToRefreshBox(
                isRefreshing = result.loading && result.data != null,
                onRefresh = { vm.refresh() },
            ) {
                when {
                    needsPermission -> Column(Modifier.padding(16.dp)) {
                        LcarsFrame(Modifier.fillMaxWidth()) {
                            Column {
                                Text("Location needed", fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = c.ink)
                                Text("Grant location to see hazards and incidents near you.",
                                    fontFamily = JetBrainsMono, fontSize = 11.sp, color = c.muted,
                                    modifier = Modifier.padding(top = 4.dp))
                                Text(
                                    "▸ GRANT LOCATION",
                                    fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 1.sp, color = c.accent,
                                    modifier = Modifier.padding(top = 10.dp).border(1.dp, c.accent).clickable {
                                        permLauncher.launch(arrayOf(
                                            android.Manifest.permission.ACCESS_FINE_LOCATION,
                                            android.Manifest.permission.ACCESS_COARSE_LOCATION,
                                        ))
                                    }.padding(horizontal = 14.dp, vertical = 8.dp),
                                )
                            }
                        }
                    }
                    result.isInitialLoading -> LoadingState()
                    result.isError -> ErrorState(result.error ?: "Error", onRetry = { vm.refresh() })
                    filtered.isEmpty() -> EmptyState(
                        if (typeFilter != null) "No ${typeFilter!!.label.lowercase()} incidents near you right now."
                        else "No incidents reported near you right now.",
                    )
                    else -> LazyColumn(
                        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp, top = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (result.stale) item { StaleBanner(true) }
                        items(filtered, key = { it.id }) { incident ->
                            IncidentRow(incident) { incident.url?.let { openUrl(context, it) } }
                        }
                        item {
                            Text(
                                "Sources: USGS earthquakes, GDACS global disasters, US NWS alerts. " +
                                    "No proprietary crime feed is publicly available; coverage is hazard-based.",
                                fontFamily = JetBrainsMono, fontSize = 9.sp, color = c.muted,
                                modifier = Modifier.padding(8.dp),
                            )
                        }
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
    LcarsFrame(Modifier.fillMaxWidth().clickable { onClick() }, accent = color) {
        Column {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${type.label.uppercase()} · ${severity.name}", fontFamily = JetBrainsMono,
                    fontSize = 9.sp, color = color)
                Text(Formatters.relativeTime(incident.timeEpochMs), fontFamily = JetBrainsMono,
                    fontSize = 9.sp, color = c.muted)
            }
            Text(incident.title, fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                color = c.ink, modifier = Modifier.padding(top = 4.dp))
            val dist = if (incident.distanceMeters > 0)
                "${Geo.formatDistance(incident.distanceMeters)} · ${Geo.cardinal(incident.bearing)}" else "Your area"
            Text("$dist · ${incident.source}", fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.accent,
                modifier = Modifier.padding(top = 2.dp))
        }
    }
}
