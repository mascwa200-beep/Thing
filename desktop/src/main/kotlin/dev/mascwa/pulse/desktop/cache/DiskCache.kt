package dev.mascwa.pulse.desktop.cache

import dev.mascwa.pulse.desktop.AppPaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

/**
 * A simple, robust JSON-on-disk cache with per-entry TTL — a desktop port of the Android app's
 * `core/cache/DiskCache.kt`. That version takes an Android `Context` to locate `filesDir`; this resolves
 * its directory under the same OS-conventional app-data root ([AppPaths]) every other desktop store already
 * writes under. Gives every screen instant content offline and a fallback to the last good payload when the
 * network is down.
 */
class DiskCache(
    private val json: Json,
    subdirectory: String = "cache",
) {
    private val dir: File = AppPaths.dataDir.resolve(subdirectory).toFile().apply { mkdirs() }
    private val mutex = Mutex()

    @Serializable
    private data class Envelope(val savedAtMs: Long, val payload: String)

    suspend fun <T> write(key: String, value: T, serializer: KSerializer<T>) =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val payload = json.encodeToString(serializer, value)
                val env = Envelope(System.currentTimeMillis(), payload)
                fileFor(key).writeText(json.encodeToString(Envelope.serializer(), env))
            }
        }

    /** Reads only if the entry is fresher than [maxAgeMs]; otherwise null. */
    suspend fun <T> read(key: String, maxAgeMs: Long, serializer: KSerializer<T>): Cached<T>? =
        readAny(key, serializer)?.takeIf {
            System.currentTimeMillis() - it.savedAtMs <= maxAgeMs
        }

    /** Reads regardless of age (used as offline fallback). */
    suspend fun <T> readAny(key: String, serializer: KSerializer<T>): Cached<T>? =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val f = fileFor(key)
                if (!f.exists()) return@withContext null
                try {
                    val env = json.decodeFromString(Envelope.serializer(), f.readText())
                    val value = json.decodeFromString(serializer, env.payload)
                    Cached(value, env.savedAtMs)
                } catch (_: Exception) {
                    f.delete()
                    null
                }
            }
        }

    suspend fun clear() = withContext(Dispatchers.IO) {
        mutex.withLock { dir.listFiles()?.forEach { it.delete() } }
    }

    suspend fun sizeBytes(): Long = withContext(Dispatchers.IO) {
        dir.listFiles()?.sumOf { it.length() } ?: 0L
    }

    private fun fileFor(key: String): File {
        val md = MessageDigest.getInstance("SHA-256").digest(key.toByteArray())
        val name = md.joinToString("") { "%02x".format(it) }.take(40)
        return File(dir, "$name.json")
    }

    data class Cached<T>(val value: T, val savedAtMs: Long)
}
