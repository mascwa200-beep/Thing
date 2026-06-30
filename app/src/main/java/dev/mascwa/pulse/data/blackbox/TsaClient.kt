package dev.mascwa.pulse.data.blackbox

import dev.mascwa.pulse.core.network.HttpClient
import dev.mascwa.pulse.core.telemetry.Rfc3161
import java.security.MessageDigest

/**
 * Best-effort RFC-3161 timestamp client: POSTs a `TimeStampReq` over a hash to a public TSA and parses the
 * granted token. Used to anchor the audit ledger's head to independent, trusted time — proof the chain
 * existed at/before that instant, which the device's own clock can't establish.
 *
 * Fully defensive: any network/parse/HTTP failure returns null, so anchoring never blocks or breaks the
 * ledger. Endpoints are **HTTPS** so the cleartext-egress guard never blocks them; tries each in turn until
 * one grants a token.
 */
class TsaClient(
    private val http: HttpClient,
    private val tsaUrls: List<String> = DEFAULT_TSAS,
) {
    /**
     * Timestamp [message] (e.g. the head-hash hex bytes). Returns the granted [Rfc3161.StampToken], or null
     * if no TSA could be reached / granted.
     */
    suspend fun stamp(message: ByteArray): Rfc3161.StampToken? {
        val imprint = runCatching { MessageDigest.getInstance("SHA-256").digest(message) }.getOrNull() ?: return null
        val query = runCatching { Rfc3161.buildTimeStampQuery(imprint, nonce = null, certReq = true) }.getOrNull() ?: return null
        for (url in tsaUrls) {
            val token = runCatching {
                val resp = http.postBinary(url, query, CONTENT_TYPE, accept = ACCEPT)
                Rfc3161.parseResponse(resp)
            }.getOrNull()
            if (token != null && token.granted && token.token != null) return token
        }
        return null
    }

    private companion object {
        // Public, free, keyless RFC-3161 TSAs over HTTPS. DigiCert is http-only, so it's not used here.
        val DEFAULT_TSAS = listOf("https://freetsa.org/tsr", "https://timestamp.sectigo.com")
        const val CONTENT_TYPE = "application/timestamp-query"
        const val ACCEPT = "application/timestamp-reply"
    }
}
