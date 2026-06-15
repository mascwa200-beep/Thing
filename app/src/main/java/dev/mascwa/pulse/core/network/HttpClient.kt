package dev.mascwa.pulse.core.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.Json
import okhttp3.Headers.Companion.toHeaders
import okhttp3.OkHttpClient
import okhttp3.Request
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
    ): T {
        val text = getString(url, headers)
        return json.decodeFromString(deserializer, text)
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
        const val USER_AGENT =
            "PulseApp/1.0 (Android; Pixel 10 Pro XL; +https://localhost) okhttp"

        fun create(json: Json): HttpClient {
            val ok = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .callTimeout(30, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .followRedirects(true)
                .followSslRedirects(true)
                .build()
            return HttpClient(ok, json)
        }

        fun defaultJson(): Json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
            explicitNulls = false
        }
    }
}
