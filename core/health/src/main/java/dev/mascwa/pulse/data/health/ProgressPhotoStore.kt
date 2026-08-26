package dev.mascwa.pulse.data.health

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

private val Context.photoDataStore: DataStore<Preferences> by
    preferencesDataStore(name = "pulse_progressphoto")

/**
 * Photographs of a body over time, which is the measurement the scale is worst at.
 *
 * Weight moves for reasons that have nothing to do with what anybody is trying to change — water,
 * salt, the time of day, a heavy meal — and the tape measure only catches some of the rest. A
 * photograph from twelve weeks ago is the one record that shows the thing itself, and it is the
 * commonest reason somebody who IS making progress stops believing they are.
 *
 * ## Two rules, and the first one is the whole reason this is not five lines
 *
 * ⚠️ **The files live in `filesDir`, NEVER in `cacheDir`.** The camera helper this app already had
 * (`Links.createCameraImageUri`) writes into the cache, which is correct for a photo that is read
 * once and interpreted — and catastrophic here. Android reclaims cache without asking and without
 * telling anybody, so a twelve-week comparison would lose its "before" at some arbitrary point with
 * no error, no gap in the list, and nothing to say what happened.
 *
 * ⚠️ **They are not in the MediaStore either, so they never appear in the camera roll.** A body
 * photograph turning up in a gallery somebody hands to a friend is a privacy failure the person
 * would not have predicted from tapping a button in a nutrition app. They stay app-private, which
 * also means uninstalling takes them with it — said out loud on the screen, because that is a real
 * cost of the same decision.
 *
 * ⚠️ Nothing is capped, exactly as [BodyStore] is not. A JPEG is a megabyte or two and a weekly
 * photograph for a year is around a hundred; silently deleting the oldest is silently deleting the
 * only "before" the whole feature exists to keep.
 */
class ProgressPhotoStore(
    private val context: Context,
    private val json: Json,
) {

    /** One photograph. [id] is also its file name, so the two can never disagree. */
    @Serializable
    data class Photo(val id: String, val atMs: Long)

    @Serializable
    private data class Stored(val photos: List<Photo> = emptyList())

    private val prefsKey = stringPreferencesKey("progress_photos_json")
    private val mutex = Mutex()
    private var loaded: Stored? = null

    private val _photos = MutableStateFlow<List<Photo>>(emptyList())

    /** Newest first, which is how a gallery of these is read. */
    val photos: StateFlow<List<Photo>> = _photos.asStateFlow()

    private fun dir(): File = File(context.filesDir, DIR).apply { mkdirs() }

    private fun fileFor(id: String): File = File(dir(), id)

    /** A `content://` URI the viewer can render and the camera can write into. */
    fun uriFor(id: String): Uri? = runCatching {
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", fileFor(id))
    }.getOrNull()

    private suspend fun loadLocked(): Stored = loaded ?: run {
        val raw = context.photoDataStore.data.first()[prefsKey]
        val s = raw
            ?.let { runCatching { json.decodeFromString(Stored.serializer(), it) }.getOrNull() }
            ?: Stored()
        // ⚠️ An index row whose FILE has gone is dropped on load rather than rendered as a broken
        // thumbnail. The two can only diverge if something outside this app removed the file, and a
        // row that can never draw is worse than an absence: it looks like the picture failed to load
        // and invites tapping it forever.
        val alive = s.photos.filter { fileFor(it.id).exists() }
        val next = if (alive.size == s.photos.size) s else Stored(alive)
        loaded = next
        _photos.value = next.photos.sortedByDescending { it.atMs }
        if (next !== s) writeLocked(next)
        next
    }

    suspend fun load() {
        mutex.withLock { loadLocked() }
    }

    // ------------------------------------------------------------------------------------ writing

    /**
     * Reserve a slot and hand back where the camera should write.
     *
     * ⚠️ The row is NOT recorded here. A capture that the person cancels, or that the camera app
     * fails, would otherwise leave an index entry pointing at a zero-byte file — and the load-time
     * sweep above would not catch it, because the file does exist. [confirm] is what records it, and
     * it is called only once the capture reports success.
     */
    fun reserve(nowMs: Long): Pair<String, Uri>? {
        val id = "progress_$nowMs.jpg"
        val uri = uriFor(id) ?: return null
        return id to uri
    }

    /** Record a capture that actually happened. Refuses an empty file, which is a failed capture. */
    suspend fun confirm(id: String, atMs: Long): Boolean {
        val f = fileFor(id)
        if (!f.exists() || f.length() <= 0L) {
            runCatching { f.delete() }
            return false
        }
        mutex.withLock {
            val s = loadLocked()
            if (s.photos.any { it.id == id }) return@withLock
            val next = Stored(s.photos + Photo(id, atMs))
            loaded = next
            _photos.value = next.photos.sortedByDescending { it.atMs }
            writeLocked(next)
        }
        return true
    }

    /** Forget a photograph, and delete the file — a "delete" that leaves the bytes is not one. */
    suspend fun remove(id: String) {
        mutex.withLock {
            val s = loadLocked()
            val next = Stored(s.photos.filterNot { it.id == id })
            loaded = next
            _photos.value = next.photos.sortedByDescending { it.atMs }
            writeLocked(next)
        }
        runCatching { fileFor(id).delete() }
    }

    suspend fun clear() {
        mutex.withLock {
            loaded = Stored()
            _photos.value = emptyList()
            writeLocked(Stored())
        }
        runCatching { dir().listFiles()?.forEach { it.delete() } }
    }

    /** How much disk the photographs are using, so the screen can say rather than imply. */
    suspend fun bytesOnDisk(): Long = mutex.withLock {
        loadLocked().photos.sumOf { runCatching { fileFor(it.id).length() }.getOrDefault(0L) }
    }

    /**
     * ⚠️ Written straight through rather than on a debounce, unlike every other store here.
     * The index is a few dozen bytes and each write follows a deliberate act — a photograph taken or
     * deleted, seconds apart at most — so there is nothing to coalesce; and an index that lags the
     * files it describes is the one state this store must never be in.
     */
    private suspend fun writeLocked(s: Stored) {
        runCatching {
            context.photoDataStore.edit { it[prefsKey] = json.encodeToString(Stored.serializer(), s) }
        }
    }

    private companion object {
        /** Must match the `files-path` entry in `res/xml/file_paths.xml` or the URI cannot be made. */
        const val DIR = "progress"
    }
}
