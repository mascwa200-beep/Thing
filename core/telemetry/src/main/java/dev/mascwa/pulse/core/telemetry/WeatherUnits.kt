package dev.mascwa.pulse.core.telemetry

/**
 * Getting weather numbers into the units the physics is written in.
 *
 * A forecast service hands back whatever unit was asked for, and the published comfort indices in
 * [WeatherComfort] are defined in Celsius and kilometres per hour. Feeding one into the other
 * without converting produces a confident, plausible, wrong answer — a wind chill computed from
 * miles per hour is simply a different number, not an error anything would catch.
 *
 * So the conversion is done once, at the point where the unit is actually known, and the result is
 * carried alongside the display value rather than re-derived by every consumer that happens to
 * need it.
 */
object WeatherUnits {

    /**
     * Feet per metre.
     *
     * Here because of a real surprise: the forecast service documents its visibility field as
     * metres, and returns feet when the request asks for imperial precipitation. The same place at
     * the same moment came back as 25240.0 and 82808.4 — exactly this ratio apart. The constant is
     * pinned by a test so the discovery is not lost to a later tidy-up.
     */
    const val FEET_PER_METRE = 3.28084

    /** How a temperature arrived. Mirrors the app's own setting rather than importing it. */
    enum class Temperature { CELSIUS, FAHRENHEIT }

    /** How a speed arrived. */
    enum class Speed { KMH, MPH, MS, KNOTS }

    /** Whichever distance family the request used; the service switches visibility with it. */
    enum class Distance { METRIC, IMPERIAL }

    /** To Celsius, which is what every index here is defined in. Null passes through. */
    fun toCelsius(value: Double?, unit: Temperature): Double? = when {
        value == null || !value.isFinite() -> null
        unit == Temperature.CELSIUS -> value
        else -> (value - 32.0) * 5.0 / 9.0
    }

    /** To kilometres per hour, which is what wind chill and Beaufort are defined in. */
    fun toKmh(value: Double?, unit: Speed): Double? = when {
        value == null || !value.isFinite() -> null
        unit == Speed.KMH -> value
        unit == Speed.MPH -> value * 1.609344
        unit == Speed.MS -> value * 3.6
        else -> value * 1.852 // knots
    }

    /** To metres. Only the imperial case does anything. */
    fun toMetres(value: Double?, unit: Distance): Double? = when {
        value == null || !value.isFinite() -> null
        unit == Distance.METRIC -> value
        else -> value / FEET_PER_METRE
    }

    /**
     * A distance a person would say out loud, from metres.
     *
     * Visibility is reported to the nearest ten metres near the ground and capped by the model
     * well below the horizon, so a large figure means "clear" rather than a measurement, and is
     * phrased that way.
     */
    fun describeVisibility(metres: Double?, imperial: Boolean): String? {
        if (metres == null || !metres.isFinite() || metres < 0.0) return null
        if (metres >= 20_000.0) return "Clear"
        return if (imperial) {
            val miles = metres / 1609.344
            if (miles < 1.0) "${(metres * FEET_PER_METRE / 100).toInt() * 100} ft" else "${round1(miles)} mi"
        } else {
            if (metres < 1000.0) "${(metres / 100).toInt() * 100} m" else "${round1(metres / 1000.0)} km"
        }
    }

    /**
     * One decimal place, without dragging a locale-sensitive formatter into a core module.
     *
     * Half-up deliberately. `kotlin.math.round` is `Math.rint`, which rounds ties to even — so
     * 1.45 km would print as 1.4 while 1.55 printed as 1.6, and a displayed number that changes
     * direction depending on the parity of the digit before it is a small mystery nobody needs.
     * Every value reaching here is non-negative, which is what makes the floor form safe.
     */
    private fun round1(v: Double): String {
        val scaled = kotlin.math.floor(v * 10.0 + 0.5).toLong()
        return "${scaled / 10}.${scaled % 10}"
    }
}
