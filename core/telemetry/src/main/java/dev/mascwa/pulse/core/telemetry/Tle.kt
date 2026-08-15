package dev.mascwa.pulse.core.telemetry

/**
 * Two-line element sets — the fixed-column text format every satellite catalogue publishes.
 *
 * The format is column-positional, not whitespace-delimited: fields butt up against each other
 * (a three-digit RAAN leaves no space before eccentricity), so this slices by column exactly as
 * the specification defines and never splits on spaces.
 */
object Tle {

    private const val TWO_PI = 2.0 * Math.PI
    private const val DEG_TO_RAD = Math.PI / 180.0

    /**
     * Parse a TLE. [name] is the optional line 0 that catalogues put above the pair.
     * Returns null for anything malformed rather than a half-populated element set.
     */
    fun parse(line1: String, line2: String, name: String = ""): Sgp4.Elements? {
        val l1 = line1.trimEnd()
        val l2 = line2.trimEnd()
        // Both lines must be long enough to hold every field and carry their line numbers.
        if (l1.length < 64 || l2.length < 63) return null
        if (l1.getOrNull(0) != '1' || l2.getOrNull(0) != '2') return null

        val noradId = l1.slice(2..6).trim().toIntOrNull() ?: return null
        // The catalogue number appears on both lines and must agree — a mismatch means the two
        // lines came from different satellites, which would otherwise propagate silently.
        val noradId2 = l2.slice(2..6).trim().toIntOrNull() ?: return null
        if (noradId != noradId2) return null

        val epochYear = l1.slice(18..19).trim().toIntOrNull() ?: return null
        val epochDay = l1.slice(20..31).trim().toDoubleOrNull() ?: return null
        val bstar = decimalPoint(l1.slice(53..60)) ?: return null

        val inclination = l2.slice(8..15).trim().toDoubleOrNull() ?: return null
        val raan = l2.slice(17..24).trim().toDoubleOrNull() ?: return null
        // Eccentricity is stored with an implied leading decimal point: "0007568" is 0.0007568.
        val ecc = ("0." + l2.slice(26..32).trim()).toDoubleOrNull() ?: return null
        val argPerigee = l2.slice(34..41).trim().toDoubleOrNull() ?: return null
        val meanAnomaly = l2.slice(43..50).trim().toDoubleOrNull() ?: return null
        val meanMotion = l2.slice(52..62).trim().toDoubleOrNull() ?: return null

        if (meanMotion <= 0.0 || ecc < 0.0 || ecc >= 1.0) return null

        return Sgp4.Elements(
            noradId = noradId,
            name = name.trim(),
            epochJulian = epochToJulian(epochYear, epochDay),
            noKozai = meanMotion * TWO_PI / 1440.0, // revs/day -> radians/minute
            eccentricity = ecc,
            inclinationRad = inclination * DEG_TO_RAD,
            raanRad = raan * DEG_TO_RAD,
            argPerigeeRad = argPerigee * DEG_TO_RAD,
            meanAnomalyRad = meanAnomaly * DEG_TO_RAD,
            bstar = bstar,
        )
    }

    /** Parse a 3-line block ("name / line1 / line2") as catalogues serve it. */
    fun parseBlock(text: String): List<Sgp4.Elements> {
        val lines = text.lineSequence().map { it.trimEnd() }.filter { it.isNotBlank() }.toList()
        val out = mutableListOf<Sgp4.Elements>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            when {
                // A name line followed by its two element lines.
                !line.startsWith("1 ") && i + 2 < lines.size &&
                    lines[i + 1].startsWith("1 ") && lines[i + 2].startsWith("2 ") -> {
                    parse(lines[i + 1], lines[i + 2], line)?.let { out += it }
                    i += 3
                }
                // A bare pair with no name line.
                line.startsWith("1 ") && i + 1 < lines.size && lines[i + 1].startsWith("2 ") -> {
                    parse(line, lines[i + 1])?.let { out += it }
                    i += 2
                }
                else -> i += 1
            }
        }
        return out
    }

    /**
     * TLE exponent fields pack a sign, five mantissa digits and a signed exponent into eight
     * columns with the decimal point implied: `" 10032-3"` means 0.10032e-3, and `" 00000+0"`
     * means zero.
     */
    internal fun decimalPoint(field: String): Double? {
        val s = field.trim()
        if (s.isEmpty()) return 0.0
        val sign = if (s.startsWith("-")) -1.0 else 1.0
        val body = s.removePrefix("-").removePrefix("+")
        // The exponent sign is the last +/- in the body, and is never at position 0.
        val splitAt = body.indexOfLast { it == '+' || it == '-' }
        val (mantissaText, exponentText) = if (splitAt > 0) {
            body.substring(0, splitAt) to body.substring(splitAt)
        } else {
            body to "0"
        }
        val mantissa = ("0." + mantissaText.trim()).toDoubleOrNull() ?: return null
        val exponent = exponentText.toIntOrNull() ?: return null
        return sign * mantissa * Math.pow(10.0, exponent.toDouble())
    }

    /**
     * TLE epochs are a two-digit year plus a fractional day-of-year. Per the format's own
     * convention, 57-99 means 1957-1999 and 00-56 means 2000-2056.
     */
    internal fun epochToJulian(twoDigitYear: Int, dayOfYear: Double): Double {
        val year = if (twoDigitYear < 57) twoDigitYear + 2000 else twoDigitYear + 1900
        // Julian date of January 0.0 of that year, then add the fractional day directly.
        val y = year - 1
        val a = y / 100
        val b = 2 - a + a / 4
        val janZero = kotlin.math.floor(365.25 * y).toLong() +
            kotlin.math.floor(30.6001 * 14).toLong() + 1720994L + b
        return janZero + 0.5 + dayOfYear
    }
}
