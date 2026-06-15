package dev.mascwa.pulse.data.weather

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.os.Build
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.coroutines.resume

data class DeviceLocation(val latitude: Double, val longitude: Double, val name: String)

/** Wraps FusedLocationProvider + reverse geocoding into suspend functions. */
class LocationProvider(private val context: Context) {

    fun hasPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
        return fine == PackageManager.PERMISSION_GRANTED || coarse == PackageManager.PERMISSION_GRANTED
    }

    suspend fun current(): DeviceLocation? {
        if (!hasPermission()) return null
        return try {
            val client = LocationServices.getFusedLocationProviderClient(context)
            val loc = client.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, null).await()
                ?: client.lastLocation.await()
                ?: return null
            val name = reverseGeocode(loc.latitude, loc.longitude) ?: "Current location"
            DeviceLocation(loc.latitude, loc.longitude, name)
        } catch (_: SecurityException) {
            null
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun reverseGeocode(lat: Double, lon: Double): String? = withContext(Dispatchers.IO) {
        if (!Geocoder.isPresent()) return@withContext null
        val geocoder = Geocoder(context, Locale.getDefault())
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                suspendCancellableCoroutine { cont: CancellableContinuation<String?> ->
                    geocoder.getFromLocation(lat, lon, 1) { results ->
                        cont.resume(results.firstOrNull()?.let { it.locality ?: it.subAdminArea ?: it.adminArea })
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
}
