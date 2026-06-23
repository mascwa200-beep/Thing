package dev.mascwa.pulse.core.network

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

/**
 * Process-wide policy for HTTPS-only egress, driven by the user's "HTTPS-only" security setting. When
 * enabled, [CleartextGuardInterceptor] blocks any non-HTTPS request made through the shared [HttpClient]
 * (the app's data/API traffic — markets, news, the assistant's cloud calls, GitHub) unless its host is
 * explicitly whitelisted. This is the "no unencrypted data leaves the device unless whitelisted" guarantee
 * for the OkHttp egress path. (The radio player streams audio via Android MediaPlayer on a separate path
 * and is governed by the manifest's cleartext flag, not this guard.)
 *
 * A plain holder updated from settings so the long-lived OkHttp client doesn't need rebuilding when the
 * toggle flips. Disabled by default → the interceptor is a no-op.
 */
object CleartextPolicy {

    @Volatile var enabled: Boolean = false
    @Volatile var allowedHosts: Set<String> = emptySet()

    /** Invoked with the blocked host so the app can log the security event. */
    @Volatile var onBlocked: ((String) -> Unit)? = null

    /** Whether a request to [scheme]://[host] must be blocked under the current policy. */
    fun isBlocked(scheme: String, host: String): Boolean {
        if (!enabled) return false
        if (scheme.equals("https", ignoreCase = true)) return false
        val h = host.lowercase()
        return allowedHosts.none { h == it || h.endsWith(".$it") }
    }
}

/** Enforces [CleartextPolicy] on every call through the client it's installed on. */
class CleartextGuardInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val url = chain.request().url
        if (CleartextPolicy.isBlocked(url.scheme, url.host)) {
            CleartextPolicy.onBlocked?.invoke(url.host)
            throw IOException("Blocked cleartext request to ${url.host} — HTTPS-only mode is on")
        }
        return chain.proceed(chain.request())
    }
}
