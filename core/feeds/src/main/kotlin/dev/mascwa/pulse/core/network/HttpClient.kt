package dev.mascwa.pulse.core.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.Json
import dev.mascwa.pulse.core.telemetry.DeviceClass
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
 * Minimal coroutine-friendly HTTP wrapper over OkHttp. One client, many hosts.
 * Handles JSON (via kotlinx.serialization), and raw text (RSS/XML, CSV).
 */
class HttpClient(
    private val client: OkHttpClient,
    val json: Json,
) {
    /**
     * Throw away the HTTP response cache, if this client was built with one.
     *
     * ⚠️ **Through OkHttp's own `evictAll`, never by deleting the directory.** A `Cache` holds a
     * `DiskLruCache` open with a journal; removing files underneath it leaves that journal
     * describing entries that are no longer there, and the failure surfaces later as reads that
     * miss or throw rather than as anything connected to the button that was pressed. The same rule
     * applies to Coil's disk cache, which has its own `clear()` for the same reason.
     *
     * Returns how many bytes it was holding, so a caller can say what it freed rather than imply it.
     */
    suspend fun evictCache(): Long = withContext(Dispatchers.IO) {
        val cache = client.cache ?: return@withContext 0L
        runCatching {
            val before = cache.size()
            cache.evictAll()
            before
        }.getOrDefault(0L)
    }

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
        // Parse on IO, not the caller's dispatcher: AsyncLoader/ViewModels invoke fetch() directly in
        // viewModelScope (Main.immediate), so without this the deserialize (large weather/news/markets
        // arrays) would run on the main thread and drop frames. getString already runs on IO; the nested
        // withContext is a no-op there.
        val text = getString(url, headers)
        json.decodeFromString(deserializer, text)
    }

    /** A text response, with where it actually came from. */
    data class TextResponse(val body: String, val finalUrl: String, val contentType: String?)

    /**
     * Fetch text, giving up past [maxChars], and report the URL the response really came from.
     *
     * ⚠️ Two things [getString] does not do, both of which matter for an arbitrary web page rather
     * than a known feed. **A page has no size a caller can assume** — a feed is a few hundred
     * kilobytes by construction, a news article can be several megabytes of inlined script, and
     * reading it whole into a string on a phone is how that becomes an out-of-memory. And **the
     * final URL is not the requested one**: an http→https hop or a canonical redirect is routine,
     * and resolving a page's relative images against the address that was asked for rather than the
     * one that answered points them at the wrong host.
     *
     * The cap counts characters, not bytes, which is close enough for a ceiling and lets the charset
     * be OkHttp's problem.
     */
    suspend fun getTextCapped(
        url: String,
        maxChars: Int,
        headers: Map<String, String> = emptyMap(),
    ): TextResponse = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .headers(defaultHeaders(headers).toHeaders())
            .get()
            .build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw HttpException(resp.code, "HTTP ${resp.code} for $url")
            val body = resp.body ?: throw IOException("empty response")
            val sb = StringBuilder()
            body.charStream().use { reader ->
                val buf = CharArray(1 shl 15)
                var n = reader.read(buf)
                while (n >= 0 && sb.length < maxChars) {
                    sb.appendRange(buf, 0, minOf(n, maxChars - sb.length))
                    n = reader.read(buf)
                }
            }
            TextResponse(sb.toString(), resp.request.url.toString(), resp.header("Content-Type"))
        }
    }

    /**
     * Stream a URL to [dest], aborting if it exceeds [maxBytes]. Returns (bytesWritten, contentType?).
     *
     * @param onProgress 0..100 as the body arrives. **Never called when the server does not state a
     *   length** — a percentage of an unknown total is a number the caller would render as fact, and
     *   a bar that races to 90% and stops is worse than one that never appeared. Callers that want to
     *   show something in that case should say "downloading…" instead.
     */
    suspend fun download(
        url: String,
        dest: File,
        maxBytes: Long,
        headers: Map<String, String> = emptyMap(),
        onProgress: (Int) -> Unit = {},
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
            val expected = body.contentLength().takeIf { it > 0L }
            dest.outputStream().use { out ->
                body.byteStream().use { input ->
                    val buf = ByteArray(1 shl 16)
                    var total = 0L
                    var reported = -1
                    var n = input.read(buf)
                    while (n >= 0) {
                        total += n
                        if (total > maxBytes) throw IOException("file exceeds the ${maxBytes / (1024 * 1024)} MB limit")
                        out.write(buf, 0, n)
                        if (expected != null) {
                            val pct = ((total * 100) / expected).toInt().coerceIn(0, 100)
                            // Only on a whole-percent change: a 64 KB buffer over a large file would
                            // otherwise call back thousands of times to move the same bar.
                            if (pct != reported) {
                                reported = pct
                                onProgress(pct)
                            }
                        }
                        n = input.read(buf)
                    }
                    total to type
                }
            }
        }
    }

    /**
     * GET raw bytes. Throws on non-2xx or transport failure.
     *
     * For a response that is an image rather than text or JSON — a map tile, chiefly. [download]
     * already existed but writes to a file, which is the wrong shape for something decoded straight
     * into memory and never kept.
     *
     * @param maxBytes a ceiling, because the caller has no way to know what a host will send and a
     *   map asks a lot of different hosts. A tile is tens of kilobytes; anything past this is a
     *   mistake or an error page, and reading it into memory would be the wrong response to either.
     */
    suspend fun getBytes(
        url: String,
        headers: Map<String, String> = emptyMap(),
        maxBytes: Long = 8L * 1024 * 1024,
    ): ByteArray = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .headers(defaultHeaders(headers).toHeaders())
            .get()
            .build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw HttpException(resp.code, "HTTP ${resp.code} for $url")
            val body = resp.body ?: throw IOException("empty response")
            val declared = body.contentLength()
            if (declared > maxBytes) throw IOException("response of $declared bytes exceeds the limit")
            // ⚠️ A hand-rolled loop rather than `readNBytes`, which is Java 9 but only reached Android
            // in API 33 — and this module is shared with an app whose minSdk is 31 and which has core
            // library desugaring switched off. That call compiles cleanly and throws NoSuchMethodError
            // on an Android 12 device, which no gate in this project would catch.
            //
            // The limit is enforced here as well as against the header because a chunked response
            // declares no length at all, so the header check cannot be the only one.
            val out = java.io.ByteArrayOutputStream(if (declared > 0) declared.toInt() else 32 * 1024)
            body.byteStream().use { input ->
                val buf = ByteArray(1 shl 15)
                var n = input.read(buf)
                while (n >= 0) {
                    if (out.size() + n > maxBytes) {
                        throw IOException("response exceeds the ${maxBytes / 1024} KB limit")
                    }
                    out.write(buf, 0, n)
                    n = input.read(buf)
                }
            }
            out.toByteArray()
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
        // A real UA avoids 403s from feeds (e.g. Google News, Stooq) that reject
        // empty/default clients.
        val base = mapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "application/json, text/xml, application/xml, text/csv, */*",
            "Accept-Language" to "en",
        )
        return base + extra
    }

    companion object {
        /**
         * How this client introduces itself.
         *
         * A real UA avoids 403s from feeds (Google News, Stooq) that reject empty or default clients, and
         * several of them answer differently depending on what they think they are talking to — so this is
         * a `var` each application sets once at startup rather than a constant. Set it before the first
         * request; nothing reads it earlier.
         */
        @Volatile
        var USER_AGENT: String =
            "PulseApp/1.0 (Android; Pixel 10 Pro XL; +https://localhost) okhttp"

        /**
         * @param cacheBytes how much disk the HTTP cache may use.
         *
         * ⚠️ A parameter rather than a constant because a map is not like a feed. One 4K view is
         * roughly 250 tiles at tens of kilobytes each, so a map sharing the feeds' cache would evict
         * the news, the weather and the markets on every pan — the other screens would still work,
         * they would simply refetch everything, forever. A caller that draws tiles builds its own
         * client with its own bounded cache instead.
         */
        fun create(
            json: Json,
            cacheDir: File? = null,
            cacheBytes: Long = 16L * 1024 * 1024,
            parallelism: Int = DeviceClass.FULL_PARALLELISM,
        ): HttpClient {
            // Allow more concurrent requests per host so parallel fan-outs
            // (e.g. Hacker News item fetches) aren't throttled to 5 at a time.
            //
            // ⚠️ These two numbers were chosen against what a SERVER tolerates, and that reason does
            // not stop being true on a cheap phone — so [DeviceClass.scaleFanOut] only ever scales
            // them DOWN, and at full strength returns them unchanged. A phone that cannot afford 64
            // concurrent calls does not merely run them slowly: each one is a thread, a socket and a
            // read buffer, and sixty-four of those on a 2 GB device is a low-memory kill rather than
            // a slow screen.
            //
            // ⚠️ Sized once, from the DURABLE budget — hardware only. A `Dispatcher` is built with
            // the client and kept for the process, so folding a momentary thermal reading into it
            // would leave a phone that happened to be warm at launch throttled all day, and nothing
            // here has a driver that could re-tune it as the phone cooled.
            val dispatcher = Dispatcher().apply {
                maxRequests = DeviceClass.scaleFanOut(64, parallelism)
                maxRequestsPerHost = DeviceClass.scaleFanOut(12, parallelism)
            }
            val builder = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .callTimeout(30, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .followRedirects(true)
                .followSslRedirects(true)
                // Enforce HTTPS-only egress when the user's security setting is on (no-op otherwise).
                .addInterceptor(CleartextGuardInterceptor())
                .dispatcher(dispatcher)
            if (cacheDir != null) {
                runCatching {
                    builder.cache(Cache(File(cacheDir, "http_cache"), cacheBytes))
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
