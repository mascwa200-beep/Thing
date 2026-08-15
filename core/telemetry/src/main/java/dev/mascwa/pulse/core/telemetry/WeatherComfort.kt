package dev.mascwa.pulse.core.telemetry

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * What the weather actually does to a person.
 *
 * A temperature on its own says very little. Thirty degrees at fifteen percent humidity is a
 * pleasant dry day; thirty degrees at eighty percent is dangerous. Five degrees in still air is a
 * jacket; five degrees in a forty-kilometre wind is a risk of hypothermia. These are the published
 * indices that turn the raw numbers into that.
 *
 * Every one of them has a range outside which it is meaningless, and each returns null there
 * rather than a number. The wind-chill equation is fitted for cold and moving air, and asked about
 * a warm still afternoon it will confidently report a chill that does not exist. Refusing to
 * answer is the honest behaviour, and it is why these are not simply always-on readouts.
 */
object WeatherComfort {

    // ---- heat -------------------------------------------------------------------------------

    /** Below this the heat-index regression is not fitted and the NWS does not publish it. */
    const val HEAT_INDEX_MIN_F = 80.0

    /**
     * The top of the published heat-index chart, in Fahrenheit.
     *
     * The Rothfusz regression is a curve fit, and pushed past the table it was fitted to it keeps
     * climbing: 41 °C at 70% humidity comes out as an apparent 77 °C. That combination is barely
     * physical — its dew point exceeds anything reliably recorded on Earth — but a sensor glitch
     * or a bad parse can produce it, and "feels like 77 °C" printed on a card is worse than useless.
     * Clamped here, which changes nothing inside the chart and keeps the top band honest: past this
     * point the answer is "off the scale", not a number.
     */
    const val HEAT_INDEX_MAX_F = 137.0

    /**
     * Apparent temperature in heat, degrees Celsius, or null when it is not hot enough to matter.
     *
     * The NWS Rothfusz regression, including both of its documented corrections: very dry air is
     * subtracted from the index, and very humid air near the low end is added to it. The NWS's own
     * procedure is followed exactly — try the simple form first, and only escalate to the
     * regression when that clears eighty degrees Fahrenheit — because the regression alone
     * misbehaves at the bottom of its range.
     */
    fun heatIndexC(temperatureC: Double, humidityPercent: Double): Double? {
        if (!temperatureC.isFinite() || !humidityPercent.isFinite()) return null
        val r = humidityPercent.coerceIn(0.0, 100.0)
        val t = temperatureC * 9.0 / 5.0 + 32.0

        val simple = 0.5 * (t + 61.0 + ((t - 68.0) * 1.2) + (r * 0.094))
        if ((simple + t) / 2.0 < HEAT_INDEX_MIN_F) return null

        var hi = -42.379 + 2.04901523 * t + 10.14333127 * r -
            0.22475541 * t * r - 0.00683783 * t * t - 0.05481717 * r * r +
            0.00122874 * t * t * r + 0.00085282 * t * r * r - 0.00000199 * t * t * r * r

        if (r < 13.0 && t in 80.0..112.0) {
            hi -= ((13.0 - r) / 4.0) * sqrt((17.0 - abs(t - 95.0)) / 17.0)
        } else if (r > 85.0 && t in 80.0..87.0) {
            hi += ((r - 85.0) / 10.0) * ((87.0 - t) / 5.0)
        }
        return (hi.coerceAtMost(HEAT_INDEX_MAX_F) - 32.0) * 5.0 / 9.0
    }

    /** How dangerous the heat is, on the NWS bands. */
    enum class HeatRisk(val label: String, val advice: String) {
        NONE("Comfortable", ""),
        CAUTION("Caution", "Fatigue is possible with prolonged exposure."),
        EXTREME_CAUTION("Extreme caution", "Cramps and exhaustion are possible; take breaks in shade."),
        DANGER("Danger", "Heat exhaustion is likely. Limit exertion and drink steadily."),
        EXTREME_DANGER("Extreme danger", "Heat stroke is a real risk. Stay out of it."),
    }

    fun heatRisk(heatIndexC: Double?): HeatRisk = when {
        heatIndexC == null -> HeatRisk.NONE
        heatIndexC >= 54.0 -> HeatRisk.EXTREME_DANGER
        heatIndexC >= 41.0 -> HeatRisk.DANGER
        heatIndexC >= 32.0 -> HeatRisk.EXTREME_CAUTION
        heatIndexC >= 27.0 -> HeatRisk.CAUTION
        else -> HeatRisk.NONE
    }

    // ---- cold -------------------------------------------------------------------------------

    const val WIND_CHILL_MAX_C = 10.0
    const val WIND_CHILL_MIN_WIND_KMH = 4.8

    /**
     * Wind chill in degrees Celsius, or null outside the range the formula is fitted for.
     *
     * The 2001 JAG/TI standard used by Environment Canada and the NWS. It applies at or below ten
     * degrees with wind above about five kilometres an hour; outside that the equation still
     * produces a number and the number is meaningless.
     */
    fun windChillC(temperatureC: Double, windKmh: Double): Double? {
        if (!temperatureC.isFinite() || !windKmh.isFinite()) return null
        if (temperatureC > WIND_CHILL_MAX_C || windKmh < WIND_CHILL_MIN_WIND_KMH) return null
        val v = windKmh.pow(0.16)
        return 13.12 + 0.6215 * temperatureC - 11.37 * v + 0.3965 * temperatureC * v
    }

    /** Time to frostbite on exposed skin, minutes, or null when there is no meaningful risk. */
    fun frostbiteMinutes(windChillC: Double?): Int? = when {
        windChillC == null -> null
        windChillC <= -55.0 -> 2
        windChillC <= -48.0 -> 5
        windChillC <= -40.0 -> 10
        windChillC <= -28.0 -> 30
        else -> null
    }

    // ---- moisture ---------------------------------------------------------------------------

    /**
     * Dew point in degrees Celsius, by the Magnus-Tetens approximation.
     *
     * Open-Meteo supplies this directly; this exists so the same number can be derived when only
     * temperature and humidity are to hand, and as a check on the two agreeing.
     */
    fun dewPointC(temperatureC: Double, humidityPercent: Double): Double? {
        if (!temperatureC.isFinite() || !humidityPercent.isFinite()) return null
        val rh = humidityPercent.coerceIn(1.0, 100.0)
        val gamma = ln(rh / 100.0) + (17.625 * temperatureC) / (243.04 + temperatureC)
        val dp = 243.04 * gamma / (17.625 - gamma)
        return if (dp.isFinite()) dp else null
    }

    /**
     * How the air feels to breathe, by dew point rather than relative humidity.
     *
     * Dew point is the better measure and the less familiar one: relative humidity of eighty
     * percent means something entirely different at five degrees than at thirty.
     */
    fun mugginess(dewPointC: Double?): String? = when {
        dewPointC == null -> null
        dewPointC >= 24.0 -> "Oppressive"
        dewPointC >= 21.0 -> "Very humid"
        dewPointC >= 18.0 -> "Humid"
        dewPointC >= 13.0 -> "Comfortable"
        dewPointC >= 5.0 -> "Dry"
        else -> "Very dry"
    }

    /**
     * Fog likelihood from the temperature/dew-point spread.
     *
     * Fog forms as the two converge. This is a rule of thumb rather than a forecast — it says the
     * air is close to saturation, not that fog will certainly form.
     */
    fun fogLikely(temperatureC: Double, dewPointC: Double): Boolean =
        temperatureC.isFinite() && dewPointC.isFinite() && (temperatureC - dewPointC) <= 2.5

    /** Frost is possible when the ground can radiate below freezing on a clear, calm night. */
    fun frostPossible(temperatureC: Double, dewPointC: Double, windKmh: Double): Boolean =
        temperatureC <= 4.0 && dewPointC <= 2.0 && windKmh < 12.0

    // ---- wind -------------------------------------------------------------------------------

    /** Beaufort force and its plain description, from a mean wind speed. */
    fun beaufort(windKmh: Double): Pair<Int, String> {
        val bands = listOf(
            1.0 to "Calm", 5.0 to "Light air", 11.0 to "Light breeze", 19.0 to "Gentle breeze",
            28.0 to "Moderate breeze", 38.0 to "Fresh breeze", 49.0 to "Strong breeze",
            61.0 to "Near gale", 74.0 to "Gale", 88.0 to "Strong gale", 102.0 to "Storm",
            117.0 to "Violent storm",
        )
        bands.forEachIndexed { i, (ceiling, label) -> if (windKmh < ceiling) return i to label }
        return 12 to "Hurricane force"
    }

    /**
     * What the gusts are doing over and above the mean wind, when that is worth saying.
     *
     * The mean is what a forecast quotes; the gust is what takes a branch down or pushes a vehicle
     * out of its lane. A gust far above the mean means gusty rather than merely windy, which
     * matters for anything being carried, pitched or ridden.
     */
    fun gustNote(windKmh: Double?, gustKmh: Double?): String? {
        if (windKmh == null || gustKmh == null) return null
        if (!windKmh.isFinite() || !gustKmh.isFinite() || gustKmh <= 0.0) return null
        // Below this the gusts are ordinary turbulence and saying so would be noise.
        if (gustKmh < 25.0 || gustKmh < windKmh * 1.3) return null
        return when {
            gustKmh >= 90.0 -> "Gusting ${gustKmh.roundToInt()} — damaging"
            gustKmh >= 62.0 -> "Gusting ${gustKmh.roundToInt()} — hard to stand in"
            gustKmh >= 40.0 -> "Gusting ${gustKmh.roundToInt()} — awkward underfoot"
            else -> "Gusting ${gustKmh.roundToInt()}"
        }
    }

    // ---- sun and storm ----------------------------------------------------------------------

    /**
     * Minutes of midday sun before fair, unprotected skin burns.
     *
     * Roughly the standard "minimal erythemal dose" rule of thumb — about 200 minutes divided by
     * the UV index for skin that burns easily. Null below UV 3, where burning takes long enough
     * not to be the point.
     */
    fun burnMinutes(uvIndex: Double?): Int? {
        if (uvIndex == null || !uvIndex.isFinite() || uvIndex < 3.0) return null
        return (200.0 / uvIndex).roundToInt().coerceAtLeast(5)
    }

    fun uvLabel(uvIndex: Double?): String? = when {
        uvIndex == null || !uvIndex.isFinite() -> null
        uvIndex >= 11.0 -> "Extreme"
        uvIndex >= 8.0 -> "Very high"
        uvIndex >= 6.0 -> "High"
        uvIndex >= 3.0 -> "Moderate"
        else -> "Low"
    }

    /**
     * Thunderstorm potential from CAPE — the energy available to a rising parcel of air.
     *
     * Energy is not a trigger: a thousand joules with nothing to lift the air produces a pleasant
     * afternoon. This says how much fuel is present, which is why the wording is about potential
     * rather than about storms happening.
     */
    fun thunderPotential(capeJkg: Double?): String? = when {
        capeJkg == null || !capeJkg.isFinite() || capeJkg < 300.0 -> null
        capeJkg >= 3000.0 -> "Extreme instability — severe storms possible"
        capeJkg >= 2000.0 -> "Strong instability — thunderstorms likely if triggered"
        capeJkg >= 1000.0 -> "Moderate instability — thunderstorms possible"
        else -> "Slight instability"
    }

    // ---- the one-line summary ----------------------------------------------------------------

    /**
     * The single most useful thing to say about how it feels out there, or null when the plain
     * temperature already says it.
     *
     * Deliberately picks one line rather than listing everything. Heat and cold outrank the rest
     * because they are the ones that hurt.
     */
    fun headline(
        temperatureC: Double,
        humidityPercent: Double?,
        windKmh: Double?,
        gustKmh: Double? = null,
        dewPointC: Double? = null,
        unitSymbol: String = "°C",
    ): String? {
        val heat = humidityPercent?.let { heatIndexC(temperatureC, it) }
        val risk = heatRisk(heat)
        if (heat != null && risk != HeatRisk.NONE) {
            return "Feels like ${display(heat, unitSymbol)} — ${risk.label.lowercase()}. ${risk.advice}".trim()
        }
        val chill = windKmh?.let { windChillC(temperatureC, it) }
        if (chill != null && chill <= temperatureC - 1.0) {
            val bite = frostbiteMinutes(chill)
            val tail = if (bite != null) " Exposed skin freezes in about $bite min." else ""
            return "Feels like ${display(chill, unitSymbol)} in the wind.$tail"
        }
        gustNote(windKmh, gustKmh)?.let { return it }
        val fog = dewPointC?.let { if (fogLikely(temperatureC, it)) "Air is near saturation — fog is likely." else null }
        return fog
    }

    /** Celsius in, whatever the user reads out — the indices are defined in Celsius. */
    private fun display(valueC: Double, unitSymbol: String): String {
        val v = if (unitSymbol.contains("F")) valueC * 9.0 / 5.0 + 32.0 else valueC
        return "${v.roundToInt()}$unitSymbol"
    }

    /** e in hPa, for anyone needing the vapour pressure the humidex is built on. */
    fun vapourPressureHpa(dewPointC: Double): Double =
        6.11 * exp(5417.7530 * ((1.0 / 273.16) - (1.0 / (273.15 + dewPointC))))

    /** The Canadian humidex, which folds humidity into a single "feels like" number. */
    fun humidex(temperatureC: Double, dewPointC: Double): Double? {
        if (!temperatureC.isFinite() || !dewPointC.isFinite()) return null
        return temperatureC + 0.5555 * (vapourPressureHpa(dewPointC) - 10.0)
    }
}
