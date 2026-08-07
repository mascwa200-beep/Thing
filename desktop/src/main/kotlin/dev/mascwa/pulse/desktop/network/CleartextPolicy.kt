package dev.mascwa.pulse.desktop.network

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

/**
 * Process-wide policy for HTTPS-only egress — a direct port of the Android app's
 * `core/network/CleartextPolicy.kt` (zero android.* imports there either). When [enabled], blocks any
 * non-HTTPS request made through the shared [HttpClient] unless its host is explicitly whitelisted.
 * Disabled by default → the interceptor is a no-op; no desktop settings toggle wires this yet (a future
 * slice's job, once there's a Security settings surface), but the mechanism ships now so the client
 * construction path matches the Android app's.
 */
object CleartextPolicy {

    @Volatile var enabled: Boolean = false
    @Volatile var allowedHosts: Set<String> = emptySet()

    @Volatile var onBlocked: ((String) -> Unit)? = null

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
