package dev.mascwa.pulse.data.weather

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import dev.mascwa.pulse.core.telemetry.Geodesy
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume

data class DeviceLocation(
    val latitude: Double,
    val longitude: Double,
    val name: String,
    /** Horizontal accuracy in metres (68% confidence), or null if the fix didn't report it. */
    val accuracyM: Float? = null,
    /** Altitude in metres above the WGS84 ellipsoid, or null if the fix didn't report it (→ elevation tracking). */
    val altitudeM: Double? = null,
    /** Ground speed in m/s, or null if the fix didn't report it (→ transport-mode classification). */
    val speedMps: Double? = null,
)

/** Reverse-geocoded administrative context for a coordinate (for region-scoped lookups like radio). */
data class GeoPlace(
    val countryCode: String?, // ISO 3166-1 alpha-2, e.g. "US"
    val country: String?,     // human-readable, e.g. "United States"
    val state: String?,       // admin area, e.g. "California"
    val locality: String?,    // city/town, e.g. "San Francisco"
)

/**
 * GPS-first, offline-capable location. A satellite fix needs no internet (only
 * the runtime location permission), so this prefers GPS and falls back to the
 * platform [LocationManager] (no Play Services required). Reverse geocoding is
 * online-only; offline we label with the raw coordinates instead of failing.
 */
class LocationProvider(private val context: Context) {

    private val locationManager get() = context.getSystemService<LocationManager>()

    fun hasPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
        return fine == PackageManager.PERMISSION_GRANTED || coarse == PackageManager.PERMISSION_GRANTED
    }

    suspend fun current(): DeviceLocation? {
        if (!hasPermission()) return null
        // On a de-Googled build (e.g. GrapheneOS with no sandboxed Play Services)
        // skip the fused path entirely and go straight to the platform GPS provider —
        // faster first fix, and no failed Play-Services round-trip.
        val loc = (if (isGmsAvailable()) fusedFix() else null) ?: managerFix() ?: lastKnown() ?: return null
        val name = reverseGeocode(loc.latitude, loc.longitude) ?: formatCoords(loc.latitude, loc.longitude)
        return DeviceLocation(
            loc.latitude, loc.longitude, name,
            if (loc.hasAccuracy()) loc.accuracy else null,
            altitudeM = if (loc.hasAltitude()) loc.altitude else null,
            speedMps = if (loc.hasSpeed()) loc.speed.toDouble() else null,
        )
    }

    /**
     * True only when Google Play Services is actually installed. Lets us avoid touching
     * any `com.google.android.gms` class on Play-Services-free devices (GrapheneOS, etc.).
     */
    @Suppress("DEPRECATION")
    private fun isGmsAvailable(): Boolean = runCatching {
        context.packageManager.getPackageInfo(GMS_PACKAGE, 0)
        true
    }.getOrDefault(false)

    /** Google Play Services fix (fast, GPS-accuracy). Works offline for GPS. */
    private suspend fun fusedFix(): Location? = try {
        val client = LocationServices.getFusedLocationProviderClient(context)
        withTimeoutOrNull(FIX_TIMEOUT_MS) {
            client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null).await()
        } ?: withTimeoutOrNull(2_000) { client.lastLocation.await() }
    } catch (_: SecurityException) {
        null
    } catch (_: LinkageError) {
        // Play Services classes somehow unavailable at runtime — fall back to platform GPS.
        null
    } catch (_: Exception) {
        null
    }

    /** Pure-platform GPS fix — no Play Services, no network. */
    private suspend fun managerFix(): Location? {
        val lm = locationManager ?: return null
        if (!runCatching { lm.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrDefault(false)) return null
        return try {
            withTimeoutOrNull(FIX_TIMEOUT_MS) {
                suspendCancellableCoroutine { cont: CancellableContinuation<Location?> ->
                    val signal = CancellationSignal()
                    cont.invokeOnCancellation { signal.cancel() }
                    lm.getCurrentLocation(LocationManager.GPS_PROVIDER, signal, context.mainExecutor) { loc ->
                        if (cont.isActive) cont.resume(loc)
                    }
                }
            }
        } catch (_: SecurityException) {
            null
        } catch (_: Exception) {
            null
        }
    }

    /** Freshest cached fix from any provider. */
    private fun lastKnown(): Location? {
        val lm = locationManager ?: return null
        val providers = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER,
        )
        return providers
            .mapNotNull { p -> runCatching { lm.getLastKnownLocation(p) }.getOrNull() }
            .maxByOrNull { it.time }
    }

    /**
     * The fallback name for a place when reverse geocoding cannot supply one — which is exactly the
     * offline case, since the geocoder needs a connection.
     *
     * ⚠️ Not `"%.3f, %.3f".format(...)`. That uses the device locale, so on a comma-decimal one the
     * label read "48,857, 2,352" — four comma-separated numbers where two were meant, in the string
     * standing in for the user's location. Same defect as the SOS message carried until recently, and
     * [Geodesy.formatDecimal] is the utility that fixed it.
     */
    private fun formatCoords(lat: Double, lon: Double): String =
        Geodesy.formatDecimal(lat, lon, decimals = 3)

    /** Forward geocode: place/address text -> coordinates. Online-only; null on failure/offline. */
    suspend fun geocode(place: String): Pair<Double, Double>? = withContext(Dispatchers.IO) {
        if (place.isBlank() || !Geocoder.isPresent()) return@withContext null
        val geocoder = Geocoder(context, Locale.getDefault())
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                withTimeoutOrNull(4_000) {
                    suspendCancellableCoroutine { cont: CancellableContinuation<Pair<Double, Double>?> ->
                        geocoder.getFromLocationName(place, 1) { results ->
                            cont.resume(results.firstOrNull()?.let { it.latitude to it.longitude })
                        }
                    }
                }
            } else {
                @Suppress("DEPRECATION")
                geocoder.getFromLocationName(place, 1)?.firstOrNull()?.let { it.latitude to it.longitude }
            }
        } catch (_: Exception) {
            null
        }
    }

    /** Online-only place name; returns null offline so the caller uses coordinates. */
    private suspend fun reverseGeocode(lat: Double, lon: Double): String? = withContext(Dispatchers.IO) {        if (!Geocoder.isPresent()) return@withContext null
        val geocoder = Geocoder(context, Locale.getDefault())
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                withTimeoutOrNull(4_000) {
                    suspendCancellableCoroutine { cont: CancellableContinuation<String?> ->
                        geocoder.getFromLocation(lat, lon, 1) { results ->
                            cont.resume(results.firstOrNull()?.let { it.locality ?: it.subAdminArea ?: it.adminArea })
                        }
                    }
                }
            } else {
                @Suppress("DEPRECATION")
                val results = geocoder.getFromLocation(lat, lon, 1)
                results?.firstOrNull()?.let { it.locality ?: it.subAdminArea ?: it.adminArea }
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Reverse-geocode a coordinate to its administrative context (country code/name, state, locality).
     * Online-only (uses the platform [Geocoder]); returns null offline or on failure.
     */
    suspend fun describePlace(lat: Double, lon: Double): GeoPlace? = withContext(Dispatchers.IO) {
        if (!Geocoder.isPresent()) return@withContext null
        val geocoder = Geocoder(context, Locale.getDefault())
        try {
            val address = if (Build.VERSION.SDK_INT >= 33) {
                withTimeoutOrNull(4_000) {
                    suspendCancellableCoroutine { cont: CancellableContinuation<android.location.Address?> ->
                        geocoder.getFromLocation(lat, lon, 1) { results -> cont.resume(results.firstOrNull()) }
                    }
                }
            } else {
                @Suppress("DEPRECATION")
                geocoder.getFromLocation(lat, lon, 1)?.firstOrNull()
            } ?: return@withContext null
            GeoPlace(
                countryCode = address.countryCode?.takeIf { it.isNotBlank() },
                country = address.countryName?.takeIf { it.isNotBlank() },
                state = address.adminArea?.takeIf { it.isNotBlank() },
                locality = (address.locality ?: address.subAdminArea)?.takeIf { it.isNotBlank() },
            )
        } catch (_: Exception) {
            null
        }
    }

    private companion object {
        const val FIX_TIMEOUT_MS = 8_000L
        const val GMS_PACKAGE = "com.google.android.gms"
    }
}
