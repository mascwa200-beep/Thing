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
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

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

    /**
     * A CAP timestamp to epoch millis, or 0 when it cannot be read.
     *
     * ⚠️ **`java.time`, not `SimpleDateFormat`, and this is the same defect `SafetyRepository`
     * already carried.** A `SimpleDateFormat` keeps a mutable `Calendar` inside it, so a shared one
     * is not thread-safe — and this repository is a singleton with two independent callers on
     * different schedules and different threads: `BriefEngine.publish`, which runs from the
     * background worker, and `EmergencyWatchService.sweep`, which polls on its own IO scope every
     * sixty seconds. Concurrent `parse` on one instance does not merely throw; it can return a time
     * assembled from two different strings. On this path that is an expired warning shown as
     * current, or a live one dropped. `DateTimeFormatter` is immutable and safe to share.
     *
     * ⚠️ It also removes a wrong-by-hours fallback. The old pattern rejected fractional seconds, so
     * `2026-08-28T14:30:00.000-05:00` fell through to the offset-less branch, which reads the first
     * nineteen characters AS UTC — five hours out, silently. `ISO_OFFSET_DATE_TIME` accepts the
     * fractional form, so that branch is now reached only by a string that genuinely states no zone,
     * which is the one case where reading it as UTC is the documented intent rather than an accident.
     */
    private fun parseIso(s: String?): Long {
        if (s.isNullOrBlank()) return 0L
        runCatching { OffsetDateTime.parse(s, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant().toEpochMilli() }
            .getOrNull()?.let { return it }
        // No zone stated: read it as UTC, as the previous implementation did.
        return runCatching {
            LocalDateTime.parse(s.take(19), DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                .toInstant(ZoneOffset.UTC).toEpochMilli()
        }.getOrDefault(0L)
    }
}
