package dev.mascwa.pulse.core.telemetry

/**
 * What a pollutant concentration actually means, measured against a published reference.
 *
 * An air quality index is a single number rolled up from several pollutants, which is convenient
 * and hides the only question worth asking: *what* is in the air. Two places can share an index of
 * 60 with one held there by traffic exhaust and the other by ozone on a hot afternoon, and the
 * advice for the two is different.
 *
 * So each pollutant is reported against the World Health Organization's 2021 global air quality
 * guideline for it, as a ratio rather than a verdict. A ratio is honest in a way a colour band is
 * not: the guidelines are averaging periods (mostly twenty-four hours) and a live reading is an
 * hour, so "about half the daily guideline" is a fair statement where "safe" would not be.
 *
 * Pure and CI-tested. Every entry point takes null and gives null back, because a feed that omits a
 * pollutant is normal and the caller's job is to say nothing rather than to guess.
 */
object AirQualityGuide {

    /**
     * How a reading sits against its guideline.
     *
     * Four bands rather than a fine gradient, because the underlying comparison — one hour against
     * a daily average — does not support more precision than this.
     */
    enum class Band(val label: String) {
        WELL_UNDER("Well under guideline"),
        WITHIN("Within guideline"),
        ABOVE("Above guideline"),
        FAR_ABOVE("Far above guideline"),
    }

    /**
     * The pollutants a general-purpose forecast feed carries, each with the WHO 2021 guideline it
     * is judged against, where it comes from, and what it does to a person.
     *
     * [guideline] is in the same unit as the reading (µg/m³ throughout). [averaging] is the period
     * that guideline is stated over and is carried explicitly rather than assumed, because ozone's
     * is an 8-hour daily maximum where the rest are 24-hour averages — a live hourly reading
     * compared to the wrong period is a quiet, plausible falsehood.
     */
    enum class Pollutant(
        val label: String,
        val guideline: Double,
        val averaging: String,
        val source: String,
        val effect: String,
    ) {
        PM2_5(
            "PM2.5",
            15.0,
            "24-hour average",
            "Combustion — engines, wood smoke, wildfire, industry.",
            "Small enough to reach deep into the lungs and cross into the blood. The pollutant with " +
                "the clearest link to long-term harm, which is why its guideline is the strictest.",
        ),
        PM10(
            "PM10",
            45.0,
            "24-hour average",
            "Dust, road wear, construction, pollen and sea salt as well as combustion.",
            "Caught higher in the airway than PM2.5. Irritates eyes, nose and throat, and provokes " +
                "asthma.",
        ),
        OZONE(
            "Ozone",
            100.0,
            "8-hour daily maximum",
            "Not emitted directly — sunlight cooking traffic and industrial exhaust, so it peaks on " +
                "hot, bright, still afternoons.",
            "Inflames the airway. The one pollutant that is often worse in clean-looking suburban " +
                "air than in the city centre upwind of it.",
        ),
        NITROGEN_DIOXIDE(
            "Nitrogen dioxide",
            25.0,
            "24-hour average",
            "Traffic above all, especially diesel; also gas cooking and heating indoors.",
            "Irritates the airway and worsens asthma. A good proxy for how close you are to traffic.",
        ),
        SULPHUR_DIOXIDE(
            "Sulphur dioxide",
            40.0,
            "24-hour average",
            "Burning sulphur-bearing fuel — coal and heavy oil, smelting, shipping, volcanoes.",
            "Sharp irritant that constricts the airway within minutes in people with asthma.",
        ),
        CARBON_MONOXIDE(
            "Carbon monoxide",
            4000.0,
            "24-hour average",
            "Incomplete combustion. Outdoors it is traffic; indoors it is a faulty flue, and that " +
                "is the dangerous case this figure says nothing about.",
            "Displaces oxygen in the blood. Outdoor levels are rarely a concern; an alarm indoors is.",
        ),
    }

    /** One pollutant, measured. [ratio] is the reading over its guideline. */
    data class Reading(
        val pollutant: Pollutant,
        val value: Double,
        val ratio: Double,
        val band: Band,
    )

    /** Null in, null out — an absent pollutant is normal and must not be invented. */
    fun assess(pollutant: Pollutant, value: Double?): Reading? {
        if (value == null || !value.isFinite() || value < 0.0) return null
        val ratio = value / pollutant.guideline
        val band = when {
            ratio <= 0.5 -> Band.WELL_UNDER
            ratio <= 1.0 -> Band.WITHIN
            ratio <= 2.0 -> Band.ABOVE
            else -> Band.FAR_ABOVE
        }
        return Reading(pollutant, value, ratio, band)
    }

    /**
     * The pollutant sitting furthest above its own guideline — what is actually driving the index.
     *
     * Comparing ratios rather than concentrations is the whole point: 60 µg/m³ of ozone is an
     * ordinary afternoon while 60 of PM2.5 is smoke, and only the ratio says so.
     */
    fun dominant(readings: List<Reading>): Reading? = readings.maxByOrNull { it.ratio }

    /** "About 40% of the daily guideline" / "1.8× the daily guideline" — the ratio, said out loud. */
    fun describeRatio(ratio: Double): String = when {
        !ratio.isFinite() -> "—"
        ratio < 0.01 -> "negligible against the guideline"
        ratio < 1.0 -> "about ${(ratio * 100).toIntHalfUp()}% of the guideline"
        ratio < 1.05 -> "right at the guideline"
        else -> "${oneDecimal(ratio)}× the guideline"
    }

    /**
     * A short verdict across everything measured.
     *
     * Deliberately names the driver rather than averaging, because advice follows the driver: ozone
     * means stay in during the afternoon, particulates mean keep the windows shut.
     */
    fun summary(readings: List<Reading>): String? {
        val worst = dominant(readings) ?: return null
        return when (worst.band) {
            Band.WELL_UNDER -> "Clean air. Nothing measured is near its guideline."
            Band.WITHIN -> "Ordinary air. ${worst.pollutant.label} is the highest reading and is " +
                "still within guideline."
            Band.ABOVE -> "${worst.pollutant.label} is above guideline — " +
                "${describeRatio(worst.ratio)}. Sensitive people will notice it."
            Band.FAR_ABOVE -> "${worst.pollutant.label} is ${describeRatio(worst.ratio)}. Limit hard " +
                "exertion outdoors and keep windows shut."
        }
    }

    // ---- pollen ---------------------------------------------------------------------------------

    /**
     * Pollen, on a general scale.
     *
     * Stated plainly because it matters: the count that provokes symptoms differs by species and
     * differs enormously between people, so this is a rough magnitude and not a clinical threshold.
     * The feed only carries pollen over Europe, so everywhere else this is simply absent.
     */
    fun pollenBand(grainsPerM3: Double?): String? {
        if (grainsPerM3 == null || !grainsPerM3.isFinite() || grainsPerM3 < 0.0) return null
        return when {
            grainsPerM3 < 1.0 -> "None"
            grainsPerM3 < 10.0 -> "Low"
            grainsPerM3 < 50.0 -> "Moderate"
            grainsPerM3 < 500.0 -> "High"
            else -> "Very high"
        }
    }

    /**
     * Why the European and US indices disagree about the same air.
     *
     * They are not two measurements — they are one set of concentrations run through two different
     * sets of breakpoints, and the US scale is steeper for fine particulates. A reader who sees 39
     * and 69 side by side deserves to know that neither is wrong.
     */
    fun scaleGap(euAqi: Double?, usAqi: Double?): String? {
        if (euAqi == null || usAqi == null) return null
        if (!euAqi.isFinite() || !usAqi.isFinite()) return null
        val gap = usAqi - euAqi
        if (kotlin.math.abs(gap) < 10.0) return null
        val higher = if (gap > 0) "US" else "European"
        return "The $higher index reads higher for the same air. Both are the same concentrations " +
            "put through different breakpoints, so neither is wrong — the US scale is simply " +
            "steeper for fine particulates."
    }

    // ---- small local formatting -----------------------------------------------------------------
    // Core modules stay clear of locale-sensitive formatters: a comma decimal separator reaching a
    // string that is then parsed, compared or concatenated is a bug that only appears on someone
    // else's phone.

    private fun Double.toIntHalfUp(): Long = kotlin.math.floor(this + 0.5).toLong()

    private fun oneDecimal(v: Double): String {
        val scaled = kotlin.math.floor(v * 10.0 + 0.5).toLong()
        return "${scaled / 10}.${scaled % 10}"
    }
}
