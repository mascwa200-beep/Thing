package dev.mascwa.pulse.feature.map

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mascwa.pulse.feature.common.LoadingState
import dev.mascwa.pulse.feature.common.NeonPanel
import dev.mascwa.pulse.feature.common.PulseScaffold
import dev.mascwa.pulse.ui.theme.Pulse
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker

@Composable
fun MapScreen(vm: MapViewModel, onBack: (() -> Unit)? = null) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val c = Pulse.colors

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { r -> vm.onPermissionResult(r.values.any { it }) }

    PulseScaffold(
        title = "Map",
        navigationIcon = {
            if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
        },
        actions = { IconButton(onClick = { vm.refresh() }) { Icon(Icons.Filled.Refresh, "Refresh") } },
    ) { innerPadding ->
        Box(Modifier.padding(innerPadding).fillMaxSize()) {
            when {
                state.needsPermission -> Column(Modifier.padding(16.dp)) {
                    NeonPanel(Modifier.fillMaxWidth()) {
                        Column {
                            Text("Location needed", style = MaterialTheme.typography.titleMedium, color = c.ink)
                            Text("Grant location to map nearby incidents and help.",
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
                state.loading && state.markers.isEmpty() -> LoadingState()
                else -> AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        Configuration.getInstance().userAgentValue = ctx.packageName
                        MapView(ctx).apply {
                            setTileSource(TileSourceFactory.MAPNIK)
                            setMultiTouchControls(true)
                            controller.setZoom(13.0)
                            onResume()
                        }
                    },
                    update = { map ->
                        map.overlays.clear()
                        state.markers.forEach { m ->
                            map.overlays.add(
                                Marker(map).apply {
                                    position = GeoPoint(m.latitude, m.longitude)
                                    title = m.title
                                    snippet = m.snippet
                                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                                },
                            )
                        }
                        if (state.centerLat != null && state.centerLon != null) {
                            map.controller.setCenter(GeoPoint(state.centerLat!!, state.centerLon!!))
                        }
                        map.invalidate()
                    },
                )
            }
        }
    }
}
