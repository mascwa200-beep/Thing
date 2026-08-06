package dev.mascwa.pulse.desktop.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.Json
import okhttp3.Cache
import okhttp3.Dispatcher
import okhttp3.Headers.Companion.toHeaders
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Thrown for any non-2xx response or transport failure. */
class HttpException(val code: Int, message: String) : IOException(message)

/**
 * Minimal coroutine-friendly HTTP wrapper over OkHttp — a direct port of the Android app's
 * `core/network/HttpClient.kt` (that file has zero android.* imports, so this is a faithful copy with only
 * the desktop-appropriate User-Agent string changed). One client, many hosts. Handles JSON (via
 * kotlinx.serialization) and raw text (RSS/XML, CSV).
 */
class HttpClient(
    private val client: OkHttpClient,
    val json: Json,
) {
    suspend fun getString(
        url: String,
        headers: Map<String, String> = emptyMap(),
    ): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .headers(defaultHeaders(headers).toHeaders())
            .get()
            .build()
        client.newCall(request).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw HttpException(resp.code, "HTTP ${resp.code} for $url")
            }
            body
        }
    }

    suspend fun <T> getJson(
        url: String,
        deserializer: DeserializationStrategy<T>,
        headers: Map<String, String> = emptyMap(),
    ): T = withContext(Dispatchers.IO) {
        val text = getString(url, headers)
        json.decodeFromString(deserializer, text)
    }

    /** Stream a URL to [dest], aborting if it exceeds [maxBytes]. Returns (bytesWritten, contentType?). */
    suspend fun download(
        url: String,
        dest: File,
        maxBytes: Long,
        headers: Map<String, String> = emptyMap(),
    ): Pair<Long, String?> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .headers(defaultHeaders(headers).toHeaders())
            .get()
            .build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw HttpException(resp.code, "HTTP ${resp.code} for $url")
            val body = resp.body ?: throw IOException("empty response")
            val type = resp.header("Content-Type")
            dest.outputStream().use { out ->
                body.byteStream().use { input ->
                    val buf = ByteArray(1 shl 16)
                    var total = 0L
                    var n = input.read(buf)
                    while (n >= 0) {
                        total += n
                        if (total > maxBytes) throw IOException("file exceeds the ${maxBytes / (1024 * 1024)} MB limit")
                        out.write(buf, 0, n)
                        n = input.read(buf)
                    }
                    total to type
                }
            }
        }
    }

    /** POST raw [body] with [contentType], returning the response bytes. Throws on non-2xx / transport failure. */
    suspend fun postBinary(
        url: String,
        body: ByteArray,
        contentType: String,
        accept: String = "*/*",
        headers: Map<String, String> = emptyMap(),
    ): ByteArray = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .headers((mapOf("User-Agent" to USER_AGENT, "Accept" to accept) + headers).toHeaders())
            .post(body.toRequestBody(contentType.toMediaType()))
            .build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw HttpException(resp.code, "HTTP ${resp.code} for $url")
            resp.body?.bytes() ?: throw IOException("empty response")
        }
    }

    private fun defaultHeaders(extra: Map<String, String>): Map<String, String> {
        // A real UA avoids 403s from feeds (e.g. Google News, Stooq) that reject empty/default clients.
        val base = mapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "application/json, text/xml, application/xml, text/csv, */*",
            "Accept-Language" to "en",
        )
        return base + extra
    }

    companion object {
        const val USER_AGENT =
            "PulseDesktop/1.0 (Windows; +https://localhost) okhttp"

        fun create(json: Json, cacheDir: File? = null): HttpClient {
            // Allow more concurrent requests per host so parallel fan-outs aren't throttled to 5 at a time.
            val dispatcher = Dispatcher().apply {
                maxRequests = 64
                maxRequestsPerHost = 12
            }
            val builder = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .callTimeout(30, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .followRedirects(true)
                .followSslRedirects(true)
                .addInterceptor(CleartextGuardInterceptor())
                .dispatcher(dispatcher)
            if (cacheDir != null) {
                runCatching {
                    builder.cache(Cache(File(cacheDir, "http_cache"), 16L * 1024 * 1024))
                }
            }
            return HttpClient(builder.build(), json)
        }

        fun defaultJson(): Json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
            explicitNulls = false
        }
    }
}
