package dev.mascwa.pulse.data.weather

import android.content.Context

/**
 * Where [RecordedFix] is kept: one small private preferences file, `last_fix`.
 *
 * ⚠️ **Never leaves the device through a backup.** `backup_rules.xml` and
 * `data_extraction_rules.xml` include only `datastore/`, so a shared-preferences file is outside
 * both by construction.
 *
 * ⚠️ **Call from a background thread.** The first `getSharedPreferences` parses the file on whatever
 * thread asks — the main-thread decode this app swept twenty-two stores to remove. [LocationProvider]
 * is the only caller and does both halves on `Dispatchers.IO`.
 *
 * Platform-only on purpose, so it type-checks against the platform jar alone; the rule about what a
 * fix is good for lives in [RecordedFix], where a JVM test can hold it.
 */
class LastFixStore(context: Context) {

    private val appContext = context.applicationContext
    private val prefs by lazy { appContext.getSharedPreferences(FILE, Context.MODE_PRIVATE) }

    fun write(fix: RecordedFix) {
        // Doubles as their own `toString`, which is locale-independent — a comma-decimal device
        // would otherwise write a number this reader could not parse back.
        prefs.edit()
            .putString(KEY_LAT, fix.latitude.toString())
            .putString(KEY_LON, fix.longitude.toString())
            .putString(KEY_NAME, fix.name)
            .putLong(KEY_AT, fix.atMs)
            .apply()
    }

    /** The fix last written, or null when none has been — never a guess. */
    fun read(): RecordedFix? {
        val lat = prefs.getString(KEY_LAT, null)?.toDoubleOrNull() ?: return null
        val lon = prefs.getString(KEY_LON, null)?.toDoubleOrNull() ?: return null
        val at = prefs.getLong(KEY_AT, 0L).takeIf { it > 0L } ?: return null
        return RecordedFix(lat, lon, prefs.getString(KEY_NAME, null).orEmpty(), at)
    }

    private companion object {
        const val FILE = "last_fix"
        const val KEY_LAT = "lat"
        const val KEY_LON = "lon"
        const val KEY_NAME = "name"
        const val KEY_AT = "at_ms"
    }
}
