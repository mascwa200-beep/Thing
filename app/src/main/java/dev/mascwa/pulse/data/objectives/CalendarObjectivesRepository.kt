package dev.mascwa.pulse.data.objectives

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.os.Build
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume

/**
 * Reads upcoming device-calendar events that carry a location, geocodes the location string, and
 * exposes them as [Objective]s. On-device only (READ_CALENDAR, requested at runtime); returns an
 * empty list without permission or on any error — never throws.
 */
class CalendarObjectivesRepository(private val context: Context) {

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    suspend fun upcoming(daysAhead: Int = 14): List<Objective> = withContext(Dispatchers.IO) {
        if (!hasPermission()) return@withContext emptyList()
        runCatching { query(daysAhead) }.getOrDefault(emptyList())
    }

    private suspend fun query(daysAhead: Int): List<Objective> {
        val now = System.currentTimeMillis()
        val end = now + daysAhead * 24L * 60 * 60 * 1000
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
            .appendPath(now.toString())
            .appendPath(end.toString())
            .build()
        val projection = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.BEGIN,
        )
        val out = mutableListOf<Objective>()
        // Cache by location string + cap distinct geocodes: Android's Geocoder rate-limits after a
        // handful of rapid calls, so we never hammer it even with a calendar full of located events.
        val geoCache = HashMap<String, Pair<Double, Double>?>()
        context.contentResolver.query(uri, projection, null, null, "${CalendarContract.Instances.BEGIN} ASC")?.use { cur ->
            while (cur.moveToNext() && out.size < 25 && geoCache.size < MAX_GEOCODES) {
                val id = cur.getLong(0)
                val title = cur.getString(1)?.takeIf { it.isNotBlank() } ?: continue
                val loc = cur.getString(2)?.takeIf { it.isNotBlank() } ?: continue
                val begin = cur.getLong(3)
                val coords = if (geoCache.containsKey(loc)) geoCache[loc]
                    else geocode(loc).also { geoCache[loc] = it }
                if (coords == null) continue
                out += Objective(
                    id = "cal_${id}_$begin",
                    title = title,
                    kind = ObjectiveKind.SIDE, // calendar events are "side missions" (blue) by default
                    latitude = coords.first,
                    longitude = coords.second,
                    source = ObjectiveSource.CALENDAR,
                    whenLabel = loc,
                )
            }
        }
        return out
    }

    /** Geocode a place string to coordinates (online-only; null on failure). Reused for manual add. */
    suspend fun geocode(place: String): Pair<Double, Double>? = withContext(Dispatchers.IO) {
        if (place.isBlank() || !Geocoder.isPresent()) return@withContext null
        val geocoder = Geocoder(context, Locale.getDefault())
        runCatching {
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
        }.getOrNull()
    }

    private companion object {
        // Hard cap on distinct geocode calls per refresh — the platform Geocoder is rate-limited.
        const val MAX_GEOCODES = 20
    }
}
