package dev.mascwa.pulse.feature.survive

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mascwa.pulse.core.util.Geo
import dev.mascwa.pulse.core.util.dialNumber
import dev.mascwa.pulse.core.util.openMaps
import dev.mascwa.pulse.data.places.Place
import dev.mascwa.pulse.data.places.PlaceCategory
import dev.mascwa.pulse.feature.common.EmptyState
import dev.mascwa.pulse.feature.common.ErrorState
import dev.mascwa.pulse.feature.common.LoadingState
import dev.mascwa.pulse.feature.common.NeonChip
import dev.mascwa.pulse.feature.common.NeonPanel
import dev.mascwa.pulse.feature.common.PulseScaffold
import dev.mascwa.pulse.feature.common.StaleBanner
import dev.mascwa.pulse.ui.theme.JetBrainsMono
import dev.mascwa.pulse.ui.theme.Pulse

@Composable
fun PlacesScreen(vm: PlacesViewModel, onBack: (() -> Unit)? = null) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val c = Pulse.colors

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result -> vm.onPermissionResult(result.values.any { it }) }

    PulseScaffold(
        title = "Nearest Help",
        navigationIcon = {
            if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
        },
    ) { innerPadding ->
        Column(Modifier.padding(innerPadding)) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PlaceCategory.entries.forEach { cat ->
                    NeonChip(cat.title, selected = cat == state.category, onClick = { vm.select(cat) })
                }
            }
            val res = state.result
            PullToRefreshBox(
                isRefreshing = res.loading && res.data != null,
                onRefresh = { vm.refresh() },
            ) {
                when {
                    state.needsPermission -> PermissionPrompt {
                        permLauncher.launch(arrayOf(
                            android.Manifest.permission.ACCESS_FINE_LOCATION,
                            android.Manifest.permission.ACCESS_COARSE_LOCATION,
                        ))
                    }
                    res.isInitialLoading -> LoadingState()
                    res.isError -> ErrorState(res.error ?: "Error", onRetry = { vm.refresh() })
                    res.data?.places.isNullOrEmpty() -> EmptyState("No ${state.category.title.lowercase()} found nearby.")
                    else -> LazyColumn(
                        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (res.stale) item { StaleBanner(true) }
                        items(res.data!!.places, key = { it.name + it.latitude }) { p ->
                            PlaceRow(p, onMap = { openMaps(context, p.latitude, p.longitude, p.name) },
                                onCall = { p.phone?.let { dialNumber(context, it) } })
                        }
                        item {
                            Text("Source: OpenStreetMap (Overpass). Coverage varies by area.",
                                style = MaterialTheme.typography.labelSmall, color = c.muted,
                                modifier = Modifier.padding(8.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaceRow(place: Place, onMap: () -> Unit, onCall: () -> Unit) {
    val c = Pulse.colors
    NeonPanel(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(place.name, style = MaterialTheme.typography.titleSmall, color = c.ink)
                Text(
                    "${Geo.formatDistance(place.distanceMeters)} · ${Geo.cardinal(place.bearing)} (${place.bearing.toInt()}°)",
                    fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.accent,
                )
                place.address?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = c.muted) }
            }
            if (place.phone != null) {
                IconButton(onClick = onCall) { Icon(Icons.Filled.Call, "Call", tint = c.positive) }
            }
            IconButton(onClick = onMap) { Icon(Icons.Filled.Place, "Open in maps", tint = c.accent) }
        }
    }
}

@Composable
private fun PermissionPrompt(onGrant: () -> Unit) {
    val c = Pulse.colors
    NeonPanel(Modifier.fillMaxWidth().padding(16.dp)) {
        Column {
            Text("Location needed", style = MaterialTheme.typography.titleMedium, color = c.ink)
            Text("Grant location to find the nearest hospitals, shelters and more.",
                style = MaterialTheme.typography.bodyMedium, color = c.muted,
                modifier = Modifier.padding(top = 4.dp))
            androidx.compose.material3.TextButton(onClick = onGrant, modifier = Modifier.padding(top = 6.dp)) {
                Text("Grant location")
            }
        }
    }
}
