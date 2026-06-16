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
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume

data class DeviceLocation(val latitude: Double, val longitude: Double, val name: String)

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
        return DeviceLocation(loc.latitude, loc.longitude, name)
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

    private fun formatCoords(lat: Double, lon: Double): String =
        "%.3f, %.3f".format(lat, lon)

    /** Online-only place name; returns null offline so the caller uses coordinates. */
    private suspend fun reverseGeocode(lat: Double, lon: Double): String? = withContext(Dispatchers.IO) {
        if (!Geocoder.isPresent()) return@withContext null
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

    private companion object {
        const val FIX_TIMEOUT_MS = 8_000L
        const val GMS_PACKAGE = "com.google.android.gms"
    }
}
