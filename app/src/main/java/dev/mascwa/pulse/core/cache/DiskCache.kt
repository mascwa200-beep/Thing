package dev.mascwa.pulse.core.cache

import android.content.Context
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
 * A simple, robust JSON-on-disk cache with per-entry TTL. Used to give every
 * screen instant content offline and to fall back to the last good payload
 * when the network is down. No Room / annotation processors required.
 */
class DiskCache(
    context: Context,
    private val json: Json,
) {
    private val dir: File = File(context.filesDir, "pulse_cache").apply { mkdirs() }
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
