package dev.mascwa.pulse.core.telemetry

/**
 * Which water reading belongs to where you are.
 *
 * ## Why this is not simply "tides"
 *
 * ⚠️ **There are no tide-prediction stations in Michigan at all** — the nearest to the owner is
 * 879 km away in Washington DC — because the Great Lakes are not tidal. A TIDES block would have sat
 * permanently empty for them. But NOAA publishes **water levels** for the lakes, and there is a
 * station 8.6 km from where they live.
 *
 * NOAA states this itself rather than leaving it to be guessed: asking a Great Lakes station for
 * predictions answers `400 "Great Lakes stations don't have Predictions data."` So the station list
 * carries which product each station actually supports, and the reading follows the coast or the
 * lake instead of assuming one of them.
 *
 * ## The reaches, and why they differ
 *
 * ⚠️ [TIDE_REACH_KM] is much shorter than [LEVEL_REACH_KM] on purpose. High water travels along a
 * coast, so a station far away is right about the height and wrong about the hour — the part of a
 * tide reading anybody acts on. A lake's level is basin-wide and moves by centimetres across the
 * whole of it, so a distant gauge is still telling you the truth.
 *
 * Beyond both, [nearest] returns null and the caller draws nothing. An absent block is honest; a
 * block reporting the tide 900 km away is not.
 */
object WaterStations {

    /** What a station publishes. */
    enum class Kind { TIDE, LEVEL }

    data class Station(
        val id: String,
        val lat: Double,
        val lon: Double,
        val kind: Kind,
        val name: String,
    )

    /** A station and how far away it is. */
    data class Near(val station: Station, val km: Double)

    /** One predicted turn of the tide. [at] is the station's OWN local time, `yyyy-MM-dd HH:mm`. */
    data class Turn(val at: String, val high: Boolean, val feet: Double)

    /**
     * How far a tide reading still means something.
     *
     * The height carries further than the timing does, and the timing is what a tide is read for.
     */
    const val TIDE_REACH_KM = 90.0

    /** A lake's level is basin-wide, so a gauge across the water is still describing your water. */
    const val LEVEL_REACH_KM = 220.0

    /**
     * One line of the bundled station list: `id  lat  lon  kind  name`, tab separated.
     *
     * Returns null for anything malformed rather than throwing — a single bad row must cost its own
     * station and not the whole file.
     */
    fun parse(line: String): Station? {
        val f = line.split('\t')
        if (f.size < 5) return null
        val lat = f[1].toDoubleOrNull() ?: return null
        val lon = f[2].toDoubleOrNull() ?: return null
        if (!lat.isFinite() || !lon.isFinite()) return null
        val kind = when (f[3]) {
            "T" -> Kind.TIDE
            "W" -> Kind.LEVEL
            else -> return null
        }
        val id = f[0].trim()
        if (id.isEmpty()) return null
        // ⚠️ An unnamed station is refused rather than carried, so a [Station] always has something
        // to call itself. [describeLevel] renders the name, and an empty one gives "WATER  <two
        // spaces> 579.6 FT" — a line that looks like a rendering fault rather than a missing field.
        val name = f[4].trim()
        if (name.isEmpty()) return null
        return Station(id, lat, lon, kind, name)
    }

    /**
     * The closest station whose reading is still about your water, or null.
     *
     * ⚠️ Compares each candidate against ITS OWN kind's reach, not a single bound: a lake gauge
     * 150 km away is a good reading and a tide gauge at the same distance is not, so one shared
     * limit would either discard the good one or admit the bad one.
     */
    fun nearest(stations: List<Station>, lat: Double, lon: Double): Near? {
        if (!lat.isFinite() || !lon.isFinite()) return null
        var best: Near? = null
        for (s in stations) {
            val km = Geodesy.distanceMeters(lat, lon, s.lat, s.lon) / 1000.0
            if (!km.isFinite()) continue
            val reach = if (s.kind == Kind.TIDE) TIDE_REACH_KM else LEVEL_REACH_KM
            if (km > reach) continue
            if (best == null || km < best.km) best = Near(s, km)
        }
        return best
    }

    /**
     * The turns still ahead of [nowLocal].
     *
     * ⚠️ Compared as STRINGS, and that is correct rather than lazy: NOAA returns `yyyy-MM-dd HH:mm`
     * in the station's own local time, a fixed-width format whose lexicographic order is its
     * chronological order. Parsing it into an instant would mean choosing a timezone, and the one
     * that matters is the station's, which the feed has already applied.
     */
    fun upcoming(turns: List<Turn>, nowLocal: String, max: Int = 2): List<Turn> =
        turns.filter { it.at > nowLocal }.sortedBy { it.at }.take(max)

    /**
     * The tide line, or null when nothing is still ahead.
     *
     * Shows the clock time only — the date is either today or tomorrow at these ranges, and a widget
     * row has no room to say which for a reading nobody plans a week around.
     */
    fun describeTides(turns: List<Turn>, nowLocal: String): String? {
        val next = upcoming(turns, nowLocal)
        if (next.isEmpty()) return null
        return "TIDE  " + next.joinToString("  ·  ") {
            "${if (it.high) "HIGH" else "LOW"} ${clock(it.at)}"
        }
    }

    /** The lake line. [feet] is the gauge reading on its own datum, which is why the datum is named. */
    fun describeLevel(name: String, feet: Double, datum: String): String? {
        if (!feet.isFinite()) return null
        val place = name.substringBefore(',').trim().ifEmpty { name }
        return "WATER  $place ${trim1(feet)} FT $datum"
    }

    /** `yyyy-MM-dd HH:mm` -> `HH:mm`, or the whole string if it is not that shape. */
    internal fun clock(at: String): String {
        val space = at.indexOf(' ')
        if (space < 0 || space + 6 > at.length) return at
        return at.substring(space + 1, space + 6)
    }

    /**
     * One decimal, locale-free.
     *
     * ⚠️ Built from the SCALED integer rather than from the whole and fractional parts separately.
     * The obvious version takes `v.toLong()` for the whole part, which is 0 for anything between -1
     * and 0 — so -0.4 would print as "0.4", silently losing the sign. Tide heights on MLLW genuinely
     * go negative at low water, so that is a real value and not a hypothetical one.
     *
     * ⚠️ Half away from zero, NOT `kotlin.math.round`, which is `Math.rint` — banker's rounding,
     * taking 0.5 to 0 and 1.5 to 2. This repository has already corrected that once for a displayed
     * figure (see `fieldValue` in the health screens), and the reason applies here with force: NOAA
     * publishes to two decimals, so one reading in ten lands exactly on a tie, and under banker's
     * rounding the direction would flip with the parity of the digit before it. Away from zero
     * rather than toward positive infinity so that a height and its negation print the same
     * magnitude — a low water of -0.45 ft must not read shallower than a high water of 0.45.
     *
     * `:core:telemetry` cannot reach `Formatters` — that lives in `:core:feeds`, which depends on
     * this module rather than the other way about — so this is a copy by necessity, not by choice.
     */
    internal fun trim1(v: Double): String {
        if (!v.isFinite()) return "—"
        val a = kotlin.math.floor(kotlin.math.abs(v) * 10.0 + 0.5).toLong()
        return (if (v < 0.0 && a != 0L) "-" else "") + (a / 10L) + "." + (a % 10L)
    }
}
