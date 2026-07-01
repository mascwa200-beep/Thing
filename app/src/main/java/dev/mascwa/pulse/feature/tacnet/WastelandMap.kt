package dev.mascwa.pulse.feature.tacnet

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.mascwa.pulse.core.telemetry.GameLocation
import dev.mascwa.pulse.core.telemetry.LocationKind
import dev.mascwa.pulse.data.weather.DeviceLocation
import dev.mascwa.pulse.ui.theme.NightwirePalette
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Point

private const val W_STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"
private const val W_USER_SOURCE = "wl-user"
private const val W_USER_LAYER = "wl-user-dot"
private const val W_LOC_SOURCE = "wl-loc"
private const val W_LOC_LAYER = "wl-loc-dot"

/**
 * The literal Pokémon-Go map: an OSM map centred on the player with the nearby real-shop game locations
 * as colour-coded dots (by [LocationKind]). Tapping a dot fires [onSelect] with the location id (the STAT
 * tab opens that location's shop/conversation). Compact + embedded, so map gestures are disabled — it
 * pans with the player, not the finger, and vertical scroll passes through to the page. Clones NAV's
 * proven MapLibre-in-Compose wiring (lifecycle-bound [MapView], GeoJSON sources, tap query).
 */
@Composable
fun WastelandMap(
    locations: List<GameLocation>,
    gps: DeviceLocation?,
    c: NightwirePalette,
    modifier: Modifier = Modifier,
    onSelect: (String) -> Unit,
) {
    val mapView = rememberWastelandMapView()
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var centered by remember { mutableStateOf(false) }

    LaunchedEffect(mapView) {
        mapView.getMapAsync { ml ->
            map = ml
            ml.uiSettings.apply {
                isScrollGesturesEnabled = false
                isZoomGesturesEnabled = false
                isRotateGesturesEnabled = false
                isTiltGesturesEnabled = false
                isDoubleTapGesturesEnabled = false
                isCompassEnabled = false
                isLogoEnabled = false
                isAttributionEnabled = true // keep the required OSM/OpenFreeMap attribution
            }
            ml.setStyle(Style.Builder().fromUri(W_STYLE_URL)) { style ->
                style.addSource(GeoJsonSource(W_LOC_SOURCE))
                style.addLayer(
                    CircleLayer(W_LOC_LAYER, W_LOC_SOURCE).withProperties(
                        PropertyFactory.circleColor(Expression.get("color")),
                        PropertyFactory.circleRadius(8f),
                        PropertyFactory.circleStrokeColor(c.void.toArgb()),
                        PropertyFactory.circleStrokeWidth(2f),
                    ),
                )
                style.addSource(GeoJsonSource(W_USER_SOURCE))
                style.addLayer(
                    CircleLayer(W_USER_LAYER, W_USER_SOURCE).withProperties(
                        PropertyFactory.circleColor(c.sky.toArgb()),
                        PropertyFactory.circleRadius(6f),
                        PropertyFactory.circleStrokeColor(c.void.toArgb()),
                        PropertyFactory.circleStrokeWidth(2.5f),
                    ),
                )
            }
            ml.addOnMapClickListener { latLng ->
                val pt = ml.projection.toScreenLocation(latLng)
                val id = ml.queryRenderedFeatures(pt, W_LOC_LAYER).firstOrNull()?.getStringProperty("id")
                if (id != null) {
                    onSelect(id)
                    true
                } else {
                    false
                }
            }
        }
    }

    // Pin the player dot to the live fix; recentre the camera on the first fix only.
    LaunchedEffect(gps, map) {
        val style = map?.style ?: return@LaunchedEffect
        val loc = gps ?: return@LaunchedEffect
        style.getSourceAs<GeoJsonSource>(W_USER_SOURCE)?.setGeoJson(Point.fromLngLat(loc.longitude, loc.latitude))
        if (!centered) {
            map?.moveCamera(
                CameraUpdateFactory.newCameraPosition(
                    CameraPosition.Builder().target(LatLng(loc.latitude, loc.longitude)).zoom(14.0).build(),
                ),
            )
            centered = true
        }
    }

    // Refresh the location dots whenever the scan results change.
    LaunchedEffect(locations, map) {
        val style = map?.style ?: return@LaunchedEffect
        style.getSourceAs<GeoJsonSource>(W_LOC_SOURCE)?.setGeoJson(wastelandGeoJson(locations))
    }

    AndroidView(factory = { mapView }, modifier = modifier)
}

/** A [MapView] whose GL lifecycle follows the composition (clone of NAV's helper). */
@Composable
private fun rememberWastelandMapView(): MapView {
    val context = LocalContext.current
    val mapView = remember {
        MapLibre.getInstance(context)
        MapView(context).apply { onCreate(null) }
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        mapView.onStart()
        mapView.onResume()
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onPause()
            mapView.onStop()
            mapView.onDestroy()
        }
    }
    return mapView
}

private fun kindColorHex(kind: LocationKind): String = when (kind) {
    LocationKind.TRADER -> "#FFC542"
    LocationKind.MEDIC -> "#FF5A5A"
    LocationKind.FIXER -> "#5AD1FF"
    LocationKind.BARKEEP -> "#B98CFF"
    LocationKind.OUTPOST -> "#5BFF9B"
}

private fun wastelandGeoJson(locs: List<GameLocation>): String {
    val features = StringBuilder()
    var first = true
    for (l in locs) {
        if (!first) features.append(',')
        first = false
        features.append("{\"type\":\"Feature\",\"geometry\":{\"type\":\"Point\",\"coordinates\":[")
            .append(l.lon).append(',').append(l.lat)
            .append("]},\"properties\":{\"id\":").append(wJsonString(l.id))
            .append(",\"color\":\"").append(kindColorHex(l.kind)).append("\"}}")
    }
    return "{\"type\":\"FeatureCollection\",\"features\":[$features]}"
}

private fun wJsonString(s: String): String {
    val sb = StringBuilder("\"")
    for (ch in s) when (ch) {
        '\\' -> sb.append("\\\\")
        '"' -> sb.append("\\\"")
        '\n' -> sb.append("\\n")
        '\r' -> sb.append("\\r")
        '\t' -> sb.append("\\t")
        else -> if (ch < ' ') sb.append("\\u%04x".format(ch.code)) else sb.append(ch)
    }
    return sb.append('"').toString()
}
