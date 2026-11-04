package dev.mascwa.pulse.data.phone

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CallLog.Calls
import dev.mascwa.pulse.feature.phone.CallLogDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Android's call log, read for RECENTS, and the one write the phone app makes to it: marking missed
 * calls as seen.
 *
 * ⚠️ **Nothing read here is kept.** The log is Android's; LCARS reads it each time RECENTS is drawn
 * and holds nothing of it past the screen. `PhoneRecordsNothingTest` holds the phone packages to that.
 */
class CallLogRepository(private val context: Context) {

    fun canRead(): Boolean = granted(Manifest.permission.READ_CALL_LOG)

    fun canWrite(): Boolean = granted(Manifest.permission.WRITE_CALL_LOG)

    private fun granted(permission: String): Boolean =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    /**
     * The newest [limit] calls, newest first. Empty without permission; a failure when the provider
     * fails, for the reason [ContactsRepository.rows] gives.
     *
     * The limit rides on the URI ([Calls.LIMIT_PARAM_KEY]) — the call log's own way of asking for
     * "the newest N" — rather than reading years of calls to throw most of them away.
     */
    suspend fun recent(limit: Int = LIMIT): Result<List<CallLogDigest.Entry>> = withContext(Dispatchers.IO) {
        if (!canRead()) return@withContext Result.success(emptyList())
        runCatching {
            val uri = Calls.CONTENT_URI.buildUpon().appendQueryParameter(Calls.LIMIT_PARAM_KEY, limit.toString()).build()
            val out = ArrayList<CallLogDigest.Entry>()
            val cursor = context.contentResolver.query(uri, PROJECTION, null, null, "${Calls.DATE} DESC")
                ?: error("the call log answered nothing")
            cursor.use { c ->
                val id = c.getColumnIndexOrThrow(Calls._ID)
                val number = c.getColumnIndexOrThrow(Calls.NUMBER)
                val name = c.getColumnIndexOrThrow(Calls.CACHED_NAME)
                val type = c.getColumnIndexOrThrow(Calls.TYPE)
                val date = c.getColumnIndexOrThrow(Calls.DATE)
                val duration = c.getColumnIndexOrThrow(Calls.DURATION)
                val presentation = c.getColumnIndexOrThrow(Calls.NUMBER_PRESENTATION)
                val isNew = c.getColumnIndexOrThrow(Calls.NEW)
                while (c.moveToNext()) {
                    out += CallLogDigest.Entry(
                        id = c.getLong(id),
                        number = c.getString(number).orEmpty(),
                        cachedName = c.getString(name),
                        type = c.getInt(type),
                        dateMs = c.getLong(date),
                        durationSec = c.getLong(duration),
                        presentation = c.getInt(presentation),
                        unread = c.getInt(isNew) != 0,
                    )
                }
            }
            out
        }
    }

    /**
     * Mark every missed call as seen — what opening RECENTS means. The shape is Telecom's own
     * (`NEW = 1 AND TYPE = MISSED` → `NEW = 0, IS_READ = 1`), so Android's phone app, if it is ever
     * the phone app again, agrees about which calls were seen. False without permission or on failure.
     */
    suspend fun markMissedRead(): Boolean = withContext(Dispatchers.IO) {
        if (!canWrite()) return@withContext false
        runCatching {
            val values = ContentValues().apply {
                put(Calls.NEW, 0)
                put(Calls.IS_READ, 1)
            }
            context.contentResolver.update(
                Calls.CONTENT_URI,
                values,
                "${Calls.NEW} = 1 AND ${Calls.TYPE} = ?",
                arrayOf(Calls.MISSED_TYPE.toString()),
            )
            true
        }.getOrDefault(false)
    }

    private companion object {
        /** How much of the log RECENTS shows: months of calls for most people, and a bounded read. */
        const val LIMIT = 300

        val PROJECTION = arrayOf(
            Calls._ID,
            Calls.NUMBER,
            Calls.CACHED_NAME,
            Calls.TYPE,
            Calls.DATE,
            Calls.DURATION,
            Calls.NUMBER_PRESENTATION,
            Calls.NEW,
        )
    }
}
