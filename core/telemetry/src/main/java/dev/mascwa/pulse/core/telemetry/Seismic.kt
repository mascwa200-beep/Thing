package dev.mascwa.pulse.core.telemetry

import java.util.Locale
import kotlin.math.roundToInt

/**
 * Earthquakes in plain English.
 *
 * The USGS feed carries twenty-six fields per event and the app read three. The ones that matter
 * most are the ones a magnitude alone hides: a shallow magnitude 5 does far more damage than a deep
 * magnitude 6, and the magnitude *scales* are not interchangeable — a number measured as `mb`
 * saturates on large events and understates them.
 *
 * Pure and CI-tested, like every other explainer set here.
 */
object Seismic {

    /** How big, in the words a news report would use. */
    enum class Severity { MICRO, MINOR, LIGHT, MODERATE, STRONG, MAJOR, GREAT }

    fun severity(magnitude: Double): Severity = when {
        magnitude < 3.0 -> Severity.MICRO
        magnitude < 4.0 -> Severity.MINOR
        magnitude < 5.0 -> Severity.LIGHT
        magnitude < 6.0 -> Severity.MODERATE
        magnitude < 7.0 -> Severity.STRONG
        magnitude < 8.0 -> Severity.MAJOR
        else -> Severity.GREAT
    }

    /** How deep, in the bands that actually change what is felt at the surface. */
    enum class DepthBand { VERY_SHALLOW, SHALLOW, INTERMEDIATE, DEEP }

    fun depthBand(depthKm: Double): DepthBand = when {
        depthKm < 10.0 -> DepthBand.VERY_SHALLOW
        depthKm < 70.0 -> DepthBand.SHALLOW
        depthKm < 300.0 -> DepthBand.INTERMEDIATE
        else -> DepthBand.DEEP
    }

    fun magnitude(magnitude: Double): Explainer {
        val band = when (severity(magnitude)) {
            Severity.MICRO -> "Micro" to
                "Detected by instruments. Almost nobody feels one of these."
            Severity.MINOR -> "Minor" to
                "Felt indoors by people nearby, like a lorry passing. No damage."
            Severity.LIGHT -> "Light" to
                "Noticeably shakes indoor objects. Rarely more than superficial damage."
            Severity.MODERATE -> "Moderate" to
                "Can damage poorly built structures near the epicentre; well-built ones ride it out."
            Severity.STRONG -> "Strong" to
                "Damaging across a populated area, and destructive close in."
            Severity.MAJOR -> "Major" to
                "Serious damage over a wide region. These make international news."
            Severity.GREAT -> "Great" to
                "Devastating across hundreds of kilometres. A handful happen per decade."
        }
        return Explainer("M${fmt(magnitude)} — ${band.first}", band.second)
    }

    /**
     * Depth, and why it matters.
     *
     * This is the single most under-appreciated number in an earthquake report: the same magnitude
     * at 8 km and at 300 km are entirely different events at the surface.
     */
    fun depth(depthKm: Double): Explainer {
        val band = when (depthBand(depthKm)) {
            DepthBand.VERY_SHALLOW -> "Very shallow" to
                "Barely below the surface, so the energy arrives concentrated. Shallow quakes do " +
                    "far more damage than deeper ones of the same size."
            DepthBand.SHALLOW -> "Shallow" to
                "Shallow enough to be felt strongly nearby. Most damaging earthquakes are in this range."
            DepthBand.INTERMEDIATE -> "Intermediate" to
                "Deep enough that the shaking spreads out and weakens before it reaches the surface."
            DepthBand.DEEP -> "Deep" to
                "Far down in the subducting slab. Felt over a wide area but usually gently, and " +
                    "rarely damaging."
        }
        return Explainer("${depthKm.roundToInt()} km deep — ${band.first}", band.second)
    }

    /**
     * The magnitude *scale* used, which is not a formality.
     *
     * Different scales measure different parts of the seismic wave and disagree on large events:
     * a body-wave magnitude saturates around 6.5 and will understate anything bigger.
     */
    fun magnitudeType(type: String): Explainer = when (type.lowercase().trim()) {
        "mww", "mw", "mwc", "mwb", "mwr", "mwp" -> Explainer(
            "Moment magnitude ($type)",
            "The modern standard, derived from how much rock moved and how far. It is the only " +
                "scale that stays accurate for the largest earthquakes.",
        )
        "mb" -> Explainer(
            "Body-wave magnitude (mb)",
            "Measured from the first waves to arrive. Quick to compute, but it saturates around " +
                "6.5 — a large quake reported this way is probably bigger than the number suggests.",
        )
        "ml" -> Explainer(
            "Local magnitude (ml)",
            "The original Richter scale. Reliable for small, nearby earthquakes and not intended " +
                "for distant or very large ones.",
        )
        "ms", "ms_20", "mss" -> Explainer(
            "Surface-wave magnitude (ms)",
            "Measured from waves travelling along the surface. Works well for shallow, distant " +
                "quakes but saturates around magnitude 8.",
        )
        "md", "mc" -> Explainer(
            "Duration magnitude (md)",
            "Estimated from how long the shaking lasts. Used for small local events where the " +
                "amplitude is hard to measure cleanly.",
        )
        "mi", "mwi", "me" -> Explainer(
            "Energy magnitude ($type)",
            "Derived from the radiated seismic energy rather than the size of the rupture.",
        )
        else -> Explainer(
            "Magnitude scale: $type",
            "One of several scales the observatories use. They agree closely for moderate " +
                "earthquakes and diverge for the very largest.",
        )
    }

    /**
     * The USGS PAGER alert — an estimate of humanitarian impact, not of size.
     *
     * A great earthquake in the deep ocean can be green; a moderate one under a city can be red.
     */
    fun pagerAlert(alert: String?): Explainer? = when (alert?.lowercase()?.trim()) {
        "green" -> Explainer(
            "PAGER green — no significant impact expected",
            "Estimated fatalities and losses are low. Usually remote, offshore, or deep.",
        )
        "yellow" -> Explainer(
            "PAGER yellow — local impact",
            "Some casualties and damage are likely. A local or regional response is expected.",
        )
        "orange" -> Explainer(
            "PAGER orange — significant impact",
            "Substantial casualties and damage are likely. A regional or national response is expected.",
        )
        "red" -> Explainer(
            "PAGER red — extensive impact",
            "High casualties and widespread destruction are likely. International response expected.",
        )
        else -> null
    }

    /**
     * Modified Mercalli intensity — what the shaking actually felt like, as opposed to how much
     * energy was released. Magnitude is one number for the whole event; intensity varies by place.
     */
    fun shaking(mmi: Double): Explainer {
        val level = mmi.coerceIn(1.0, 12.0)
        val band = when {
            level < 2 -> "I · not felt" to "Detected only by instruments."
            level < 4 -> "II-III · weak" to "Noticed by a few people indoors, like a passing truck."
            level < 5 -> "IV · light" to "Felt indoors by many; dishes and windows rattle."
            level < 6 -> "V · moderate" to "Felt by nearly everyone; unstable objects overturn."
            level < 7 -> "VI · strong" to "Felt by all, some heavy furniture moves; slight damage."
            level < 8 -> "VII · very strong" to "Considerable damage to poorly built structures."
            level < 9 -> "VIII · severe" to "Serious damage even to ordinary buildings."
            level < 10 -> "IX · violent" to "Well-designed buildings damaged; foundations shift."
            else -> "X-XII · extreme" to "Most structures destroyed; ground visibly cracked."
        }
        return Explainer("Shaking ${band.first}", band.second)
    }

    /** How many people told the USGS they felt it, and how strongly. */
    fun feltReports(felt: Int, communityIntensity: Double?): Explainer {
        val strength = communityIntensity?.let { " at an average intensity of ${fmt(it)}" }.orEmpty()
        val detail = when {
            felt <= 0 -> "Nobody has filed a report. Common for offshore or remote events."
            felt < 10 -> "A handful of people reported feeling it$strength."
            felt < 100 -> "Dozens of people reported feeling it$strength."
            felt < 1000 -> "Hundreds of people reported feeling it$strength."
            else -> "Thousands of people reported feeling it$strength."
        }
        return Explainer("$felt felt reports", "$detail These come from the public, not instruments.")
    }

    /**
     * Whether a human has looked at it yet. An automatic solution can be revised, and occasionally
     * withdrawn entirely, so a magnitude worth acting on should say which it is.
     */
    fun reviewStatus(status: String?): Explainer = when (status?.lowercase()?.trim()) {
        "reviewed" -> Explainer(
            "Reviewed",
            "A seismologist has checked this solution. The location and magnitude are settled.",
        )
        "automatic" -> Explainer(
            "Automatic",
            "Computed without human review. The magnitude and depth may be revised, and small " +
                "automatic solutions are occasionally withdrawn.",
        )
        else -> Explainer("Status unknown", "The feed did not say whether this has been reviewed.")
    }

    /**
     * The one line that combines size and depth honestly, because neither means much alone.
     */
    fun impact(magnitude: Double, depthKm: Double): String {
        val big = magnitude >= 5.5
        val shallow = depthBand(depthKm) == DepthBand.VERY_SHALLOW ||
            depthBand(depthKm) == DepthBand.SHALLOW
        return when {
            big && shallow ->
                "Large and shallow — the combination that causes damage."
            big && !shallow ->
                "Large but deep, so the shaking arrives spread out and weakened."
            !big && depthBand(depthKm) == DepthBand.VERY_SHALLOW ->
                "Modest, but very shallow, so it may have been felt sharply nearby."
            else ->
                "Unlikely to have caused damage."
        }
    }

    /**
     * The tsunami flag, which the feed sets on every event and which nothing here read until now.
     *
     * USGS sets it when the event is in a region where a tsunami evaluation is warranted — it is a
     * "this is being assessed" marker rather than a warning in force. Say that plainly, because
     * overstating it is as bad as ignoring it.
     */
    fun tsunami(flagged: Boolean): Explainer? = if (!flagged) null else Explainer(
        "Tsunami evaluation",
        "This event is in an oceanic region where a tsunami is possible, so the warning centres " +
            "are assessing it. It is not itself a warning — check your national warning service " +
            "for one, and if you are on the coast and the shaking was hard enough to make " +
            "standing difficult, move inland and uphill without waiting to be told.",
    )

    /**
     * How loudly the app should react: the four grades the safety screen colours by and the
     * notification gates on.
     *
     * Distinct from [severity], which describes how big the earthquake was. A great earthquake in
     * the deep ocean deserves a quiet grade; a moderate one under a city does not. Magnitude alone
     * cannot tell those apart, which is why this exists.
     *
     * Every argument is nullable because the feed omits most of them most of the time, and the
     * rules below are written so that **absent never raises the grade** — unknown is not danger.
     */
    enum class Alert { LOW, MODERATE, HIGH, EXTREME }

    /**
     * The rules, in the order they apply:
     *
     * 1. Magnitude sets the base, using the bands the app already shipped, so an event carrying
     *    nothing else grades exactly as it did before this function existed.
     * 2. **PAGER outranks magnitude when present**, because it folds in population exposure and a
     *    raw magnitude does not. Orange and red are the two that mean people are affected.
     * 3. **A tsunami evaluation never lowers the grade and pins it at least HIGH.** Coastal
     *    evacuation is time-critical and a missed alert is the expensive error.
     * 4. **Depth may de-escalate only when USGS itself is unconcerned** — PAGER green or absent.
     *    A deep quake really is felt far less at the surface, but the app should not talk itself
     *    out of an alert that USGS has flagged as consequential.
     */
    fun alertLevel(
        magnitude: Double?,
        depthKm: Double? = null,
        tsunami: Boolean = false,
        pagerAlert: String? = null,
    ): Alert {
        val mag = magnitude ?: 0.0
        val base = when {
            mag >= 6.5 -> Alert.EXTREME
            mag >= 5.5 -> Alert.HIGH
            mag >= 4.0 -> Alert.MODERATE
            else -> Alert.LOW
        }
        val pager = pagerAlert?.lowercase()?.trim()
        val fromPager = when (pager) {
            "red" -> Alert.EXTREME
            "orange" -> Alert.HIGH
            "yellow" -> Alert.MODERATE
            else -> null // green and absent carry no escalation of their own
        }
        // Rule 2: PAGER raises, and being an impact estimate it is allowed to outrank magnitude.
        var level = maxOf(base, fromPager ?: base)

        // Rule 3: a tsunami evaluation is a floor, applied after everything that could lower one.
        if (tsunami) return maxOf(level, Alert.HIGH)

        // Rule 4: depth de-escalates only where USGS is unconcerned, and only by one grade.
        val usgsUnconcerned = pager == null || pager == "green"
        if (usgsUnconcerned && depthKm != null && depthBand(depthKm) == DepthBand.DEEP) {
            level = Alert.entries[maxOf(0, level.ordinal - 1)]
        }
        return level
    }

    /**
     * The short facts a list row or a map card can show without becoming a paragraph, ordered by
     * how much they change what the reader should do.
     *
     * Lives here rather than in either screen because the safety list and the map card show the
     * same earthquake, and two copies of this would eventually disagree — which is the whole
     * reason the app was showing one fidelity on one screen and another elsewhere.
     */
    fun compactFacts(
        depthKm: Double? = null,
        tsunami: Boolean = false,
        pagerAlert: String? = null,
        magType: String? = null,
    ): List<String> = buildList {
        if (tsunami) add("TSUNAMI EVALUATION")
        when (pagerAlert?.lowercase()?.trim()) {
            "red" -> add("PAGER RED")
            "orange" -> add("PAGER ORANGE")
            "yellow" -> add("PAGER YELLOW")
            // Green is USGS saying "no significant impact". Worth nothing on a crowded row.
        }
        depthKm?.let {
            val band = when (depthBand(it)) {
                DepthBand.VERY_SHALLOW -> "very shallow"
                DepthBand.SHALLOW -> "shallow"
                DepthBand.INTERMEDIATE -> "intermediate"
                DepthBand.DEEP -> "deep"
            }
            add("${it.roundToInt()} km deep · $band")
        }
        // Only worth saying when it changes how to read the number: mb saturates and understates.
        if (magType?.lowercase()?.trim() == "mb") add("mb — may understate")
    }

    /** "M6.1 · 10 km deep · 58 km N of Ende, Indonesia" */
    fun headline(magnitude: Double, depthKm: Double, place: String): String =
        listOf("M${fmt(magnitude)}", "${depthKm.roundToInt()} km deep", place.ifBlank { "location unknown" })
            .joinToString(" · ")

    /** Locale.US — this is a number, and a comma decimal reads as a different magnitude. */
    private fun fmt(v: Double): String = String.format(Locale.US, "%.1f", v)
}
