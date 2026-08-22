package dev.mascwa.pulse.data.safety

import dev.mascwa.pulse.core.network.HttpClient
import dev.mascwa.pulse.core.telemetry.CapAlerts
import dev.mascwa.pulse.core.telemetry.EmergencyAlert
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Live government emergency alerts for one point, fetched fresh every time.
 *
 * ⚠️ **Deliberately separate from [SafetyRepository], which reads the same endpoint.** That one is a
 * ten-minute-cached, four-source, distance-sorted picture of what is going on nearby, and it is the
 * right shape for a screen you open. This is the shape a warning needs: one source, no cache, every
 * field the issuer published, called every minute by a service whose entire job is to be early. A
 * warning served from a ten-minute cache is a warning that can be ten minutes late.
 *
 * The parsing overlaps by a few lines and that is accepted: making SafetyRepository's private `nws()`
 * serve both would tie a cached screen feed and an uncached alarm path to one lifetime, and the next
 * person changing the cache policy for one would silently change it for the other.
 *
 * **Coverage is a hard limit, not a preference.** `api.weather.gov` answers HTTP 400 "out of bounds"
 * outside the United States — it states its own reach — so this returns nothing at all elsewhere and
 * the caller must not read that as "no danger". The owner is in the US, where it is the same data
 * that drives Wireless Emergency Alerts.
 */
class EmergencyAlertRepository(private val http: HttpClient) {

    private val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
    private val isoZ = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    /**
     * Every active official alert covering this point, newest first.
     *
     * Throws nothing: a failure returns empty, because the caller is a watch loop that must keep
     * running. ⚠️ That means empty is genuinely ambiguous here — nothing published, or we could not
     * ask — which is exactly why nothing downstream may render it as an all-clear.
     */
    suspend fun active(lat: Double, lon: Double): List<EmergencyAlert.Official> = runCatching {
        val url = "https://api.weather.gov/alerts/active?point=" +
            String.format(Locale.US, "%.4f,%.4f", lat, lon)
        val text = http.getString(url)
        val features = withContext(Dispatchers.IO) { http.json.parseToJsonElement(text) }
            .jsonObject["features"]?.jsonArray ?: return@runCatching emptyList()
        features.mapNotNull { f ->
            val o = f.jsonObject
            val p = o["properties"]?.jsonObject ?: return@mapNotNull null
            val event = p["event"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            EmergencyAlert.Official(
                id = o["id"]?.jsonPrimitive?.contentOrNull
                    ?: p["id"]?.jsonPrimitive?.contentOrNull
                    ?: return@mapNotNull null,
                event = event,
                headline = p["headline"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                area = p["areaDesc"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                severity = p["severity"]?.jsonPrimitive?.contentOrNull,
                urgency = p["urgency"]?.jsonPrimitive?.contentOrNull,
                certainty = p["certainty"]?.jsonPrimitive?.contentOrNull,
                // The field that says what to DO. Present on 78 of 80 live alerts when this feed was
                // last measured, and never paraphrased anywhere downstream — it is the part that
                // saves lives, and rewording official instructions is not ours to do.
                instruction = CapAlerts.instruction(p["instruction"]?.jsonPrimitive?.contentOrNull),
                expiresMs = parseIso(p["expires"]?.jsonPrimitive?.contentOrNull).takeIf { it > 0L },
                effectiveMs = parseIso(p["effective"]?.jsonPrimitive?.contentOrNull),
                source = p["senderName"]?.jsonPrimitive?.contentOrNull ?: "NWS",
            )
        }.sortedByDescending { it.effectiveMs }
    }.getOrDefault(emptyList())

    private fun parseIso(s: String?): Long {
        if (s.isNullOrBlank()) return 0L
        return runCatching { iso.parse(s)?.time }.getOrNull()
            ?: runCatching { isoZ.parse(s.take(19))?.time }.getOrNull() ?: 0L
    }
}
