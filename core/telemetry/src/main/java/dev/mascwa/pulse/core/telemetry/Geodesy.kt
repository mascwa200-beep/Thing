package dev.mascwa.pulse.core.telemetry

import java.util.Locale
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * The shared geodesy core — great-circle geometry plus the grid projections the map and sky work
 * need, as pure Kotlin so every `core:telemetry` core can depend on it.
 *
 * `core/util/Geo.kt` lives in the `:app` module, which means nothing in here could reuse it; the
 * four overlapping copies of "distance between two points" across the app all trace back to that.
 * This is the version cores share. [Geo][dev.mascwa.pulse.core.util.Geo] stays where it is for the
 * existing Android call sites — the maths is identical, so the two agree by construction.
 *
 * Everything is WGS-84. Distances are metres, bearings are degrees clockwise from true north.
 */
object Geodesy {

    /** Mean Earth radius (IUGG), the standard sphere for haversine work. */
    const val EARTH_RADIUS_M = 6_371_000.0

    // WGS-84 ellipsoid, used by the UTM/MGRS projection below.
    private const val WGS84_A = 6_378_137.0
    private const val WGS84_F = 1.0 / 298.257223563
    private const val UTM_K0 = 0.9996
    private const val UTM_FALSE_EASTING = 500_000.0
    private const val UTM_FALSE_NORTHING = 10_000_000.0

    // ---- great-circle basics -------------------------------------------------------------

    /** Distance in metres between two points (haversine, spherical Earth). */
    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return EARTH_RADIUS_M * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    /** Initial bearing (0..360, true north) travelling from point 1 to point 2. */
    fun initialBearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val dLon = Math.toRadians(lon2 - lon1)
        val y = sin(dLon) * cos(phi2)
        val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(dLon)
        return normalizeBearing(Math.toDegrees(atan2(y, x)))
    }

    /**
     * Bearing on *arrival* at point 2. A great circle curves, so this differs from
     * [initialBearing] by the convergence — over a long leg by a lot.
     */
    fun finalBearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double =
        normalizeBearing(initialBearing(lat2, lon2, lat1, lon1) + 180.0)

    /** The point reached by travelling [distanceMeters] along [bearingDeg] from a start point. */
    fun destination(
        lat: Double,
        lon: Double,
        bearingDeg: Double,
        distanceMeters: Double,
    ): Pair<Double, Double> {
        val delta = distanceMeters / EARTH_RADIUS_M
        val theta = Math.toRadians(bearingDeg)
        val phi1 = Math.toRadians(lat)
        val lambda1 = Math.toRadians(lon)
        val sinPhi2 = sin(phi1) * cos(delta) + cos(phi1) * sin(delta) * cos(theta)
        val phi2 = asin(sinPhi2.coerceIn(-1.0, 1.0))
        val lambda2 = lambda1 + atan2(
            sin(theta) * sin(delta) * cos(phi1),
            cos(delta) - sin(phi1) * sinPhi2,
        )
        return Math.toDegrees(phi2) to normalizeLongitude(Math.toDegrees(lambda2))
    }

    /** Midpoint of the great-circle leg between two points. */
    fun midpoint(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Pair<Double, Double> {
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val lambda1 = Math.toRadians(lon1)
        val dLon = Math.toRadians(lon2 - lon1)
        val bx = cos(phi2) * cos(dLon)
        val by = cos(phi2) * sin(dLon)
        val phi3 = atan2(
            sin(phi1) + sin(phi2),
            sqrt((cos(phi1) + bx) * (cos(phi1) + bx) + by * by),
        )
        val lambda3 = lambda1 + atan2(by, cos(phi1) + bx)
        return Math.toDegrees(phi3) to normalizeLongitude(Math.toDegrees(lambda3))
    }

    /**
     * Signed perpendicular distance from a point to the great circle through start→end.
     * Positive means the point lies to the **right** of the track. Useful for "how far off the
     * route am I" without re-routing.
     */
    fun crossTrackMeters(
        lat: Double,
        lon: Double,
        startLat: Double,
        startLon: Double,
        endLat: Double,
        endLon: Double,
    ): Double {
        val d13 = distanceMeters(startLat, startLon, lat, lon) / EARTH_RADIUS_M
        if (d13 == 0.0) return 0.0
        val theta13 = Math.toRadians(initialBearing(startLat, startLon, lat, lon))
        val theta12 = Math.toRadians(initialBearing(startLat, startLon, endLat, endLon))
        return asin((sin(d13) * sin(theta13 - theta12)).coerceIn(-1.0, 1.0)) * EARTH_RADIUS_M
    }

    /** Distance travelled *along* the start→end track to the point's closest approach. */
    fun alongTrackMeters(
        lat: Double,
        lon: Double,
        startLat: Double,
        startLon: Double,
        endLat: Double,
        endLon: Double,
    ): Double {
        val d13 = distanceMeters(startLat, startLon, lat, lon) / EARTH_RADIUS_M
        if (d13 == 0.0) return 0.0
        val xt = crossTrackMeters(lat, lon, startLat, startLon, endLat, endLon) / EARTH_RADIUS_M
        val ratio = (cos(d13) / cos(xt)).coerceIn(-1.0, 1.0)
        return kotlin.math.acos(ratio) * EARTH_RADIUS_M
    }

    // ---- presentation --------------------------------------------------------------------

    /** Wrap any bearing into 0..360. */
    fun normalizeBearing(deg: Double): Double = ((deg % 360.0) + 360.0) % 360.0

    /** Wrap a longitude into -180..180. */
    fun normalizeLongitude(deg: Double): Double = ((deg + 540.0) % 360.0) - 180.0

    /** 16-point compass label ("N", "ENE", …). */
    fun cardinal(bearing: Double): String =
        CARDINALS[(normalizeBearing(bearing) / 22.5).roundToInt() % 16]

    /** "840 m" / "1.2 km", or feet/miles when [metric] is false. */
    fun formatDistance(meters: Double, metric: Boolean = true): String = if (metric) {
        if (meters < 1000) "${meters.roundToInt()} m"
        else String.format(Locale.US, "%.1f km", meters / 1000)
    } else {
        val miles = meters / 1609.344
        if (miles < 0.1) "${(meters / 0.3048).roundToInt()} ft"
        else String.format(Locale.US, "%.1f mi", miles)
    }

    /** Latitude/longitude in degrees-minutes-seconds, e.g. `51°28'40"N 0°00'05"W`. */
    fun formatDms(lat: Double, lon: Double): String =
        "${dms(abs(lat), if (lat >= 0) "N" else "S")} ${dms(abs(lon), if (lon >= 0) "E" else "W")}"

    private fun dms(value: Double, hemi: String): String {
        // Carry seconds that round to 60 up into the minutes, and minutes that reach 60 into the
        // degrees — otherwise you print 51°28'60".
        var deg = floor(value).toInt()
        var min = floor((value - deg) * 60).toInt()
        var sec = ((value - deg - min / 60.0) * 3600).roundToInt()
        if (sec == 60) { sec = 0; min += 1 }
        if (min == 60) { min = 0; deg += 1 }
        return "$deg°${min.toString().padStart(2, '0')}'${sec.toString().padStart(2, '0')}\"$hemi"
    }

    // ---- UTM / MGRS ----------------------------------------------------------------------

    /** A UTM grid reference. [northing] is always the value printed on the grid for its hemisphere. */
    data class Utm(
        val zone: Int,
        val northernHemisphere: Boolean,
        val easting: Double,
        val northing: Double,
    ) {
        fun format(): String = String.format(
            Locale.US, "%d%s %.0fE %.0fN", zone, if (northernHemisphere) "N" else "S", easting, northing,
        )
    }

    /** The UTM zone number for a longitude, including the Norway/Svalbard exceptions. */
    fun utmZone(lat: Double, lon: Double): Int {
        val normalized = normalizeLongitude(lon)
        var zone = floor((normalized + 180.0) / 6.0).toInt() + 1
        // South-west Norway widens zone 32 at the expense of 31.
        if (lat in 56.0..<64.0 && normalized >= 3.0 && normalized < 12.0) zone = 32
        // Svalbard: zones 31/33/35/37 are widened, 32/34/36 removed.
        if (lat in 72.0..<84.0 && normalized >= 0.0 && normalized < 42.0) {
            zone = when {
                normalized < 9.0 -> 31
                normalized < 21.0 -> 33
                normalized < 33.0 -> 35
                else -> 37
            }
        }
        return zone
    }

    /**
     * Project a WGS-84 lat/lon to UTM (Snyder's series, accurate to well under a metre inside a
     * zone). Latitudes outside ±84/80 have no UTM zone and return null rather than a wrong answer.
     */
    fun toUtm(lat: Double, lon: Double): Utm? {
        if (lat > 84.0 || lat < -80.0) return null
        val zone = utmZone(lat, lon)
        val lon0 = (zone - 1) * 6.0 - 180.0 + 3.0
        val e2 = WGS84_F * (2 - WGS84_F)
        val ep2 = e2 / (1 - e2)
        val phi = Math.toRadians(lat)
        val a = Math.toRadians(normalizeLongitude(lon - lon0)) * cos(phi)

        val n = WGS84_A / sqrt(1 - e2 * sin(phi) * sin(phi))
        val t = tan(phi) * tan(phi)
        val c = ep2 * cos(phi) * cos(phi)
        val m = WGS84_A * (
            (1 - e2 / 4 - 3 * e2 * e2 / 64 - 5 * e2.pow(3) / 256) * phi -
                (3 * e2 / 8 + 3 * e2 * e2 / 32 + 45 * e2.pow(3) / 1024) * sin(2 * phi) +
                (15 * e2 * e2 / 256 + 45 * e2.pow(3) / 1024) * sin(4 * phi) -
                (35 * e2.pow(3) / 3072) * sin(6 * phi)
            )

        val easting = UTM_K0 * n * (
            a + (1 - t + c) * a.pow(3) / 6 +
                (5 - 18 * t + t * t + 72 * c - 58 * ep2) * a.pow(5) / 120
            ) + UTM_FALSE_EASTING
        var northing = UTM_K0 * (
            m + n * tan(phi) * (
                a * a / 2 + (5 - t + 9 * c + 4 * c * c) * a.pow(4) / 24 +
                    (61 - 58 * t + t * t + 600 * c - 330 * ep2) * a.pow(6) / 720
                )
            )
        if (lat < 0) northing += UTM_FALSE_NORTHING
        return Utm(zone, lat >= 0, easting, northing)
    }

    /** Inverse of [toUtm] — used to prove the projection round-trips. */
    fun fromUtm(utm: Utm): Pair<Double, Double> {
        val e2 = WGS84_F * (2 - WGS84_F)
        val ep2 = e2 / (1 - e2)
        val e1 = (1 - sqrt(1 - e2)) / (1 + sqrt(1 - e2))
        val x = utm.easting - UTM_FALSE_EASTING
        val y = if (utm.northernHemisphere) utm.northing else utm.northing - UTM_FALSE_NORTHING

        val m = y / UTM_K0
        val mu = m / (WGS84_A * (1 - e2 / 4 - 3 * e2 * e2 / 64 - 5 * e2.pow(3) / 256))
        val phi1 = mu +
            (3 * e1 / 2 - 27 * e1.pow(3) / 32) * sin(2 * mu) +
            (21 * e1 * e1 / 16 - 55 * e1.pow(4) / 32) * sin(4 * mu) +
            (151 * e1.pow(3) / 96) * sin(6 * mu) +
            (1097 * e1.pow(4) / 512) * sin(8 * mu)

        val c1 = ep2 * cos(phi1) * cos(phi1)
        val t1 = tan(phi1) * tan(phi1)
        val n1 = WGS84_A / sqrt(1 - e2 * sin(phi1) * sin(phi1))
        val r1 = WGS84_A * (1 - e2) / (1 - e2 * sin(phi1) * sin(phi1)).pow(1.5)
        val d = x / (n1 * UTM_K0)

        val lat = phi1 - (n1 * tan(phi1) / r1) * (
            d * d / 2 - (5 + 3 * t1 + 10 * c1 - 4 * c1 * c1 - 9 * ep2) * d.pow(4) / 24 +
                (61 + 90 * t1 + 298 * c1 + 45 * t1 * t1 - 252 * ep2 - 3 * c1 * c1) * d.pow(6) / 720
            )
        val lon = (
            d - (1 + 2 * t1 + c1) * d.pow(3) / 6 +
                (5 - 2 * c1 + 28 * t1 - 3 * c1 * c1 + 8 * ep2 + 24 * t1 * t1) * d.pow(5) / 120
            ) / cos(phi1)
        val lon0 = (utm.zone - 1) * 6.0 - 180.0 + 3.0
        return Math.toDegrees(lat) to normalizeLongitude(lon0 + Math.toDegrees(lon))
    }

    /**
     * The MGRS latitude band letter (C..X, no I or O). Band X covers 12° rather than 8°, which is
     * why the top band is clamped rather than computed.
     */
    fun mgrsBand(lat: Double): Char? {
        if (lat > 84.0 || lat < -80.0) return null
        if (lat >= 72.0) return 'X'
        return MGRS_BANDS[floor((lat + 80.0) / 8.0).toInt().coerceIn(0, MGRS_BANDS.lastIndex)]
    }

    /**
     * Full MGRS reference at [digits] precision per axis (5 = 1 m, 4 = 10 m, … 1 = 10 km),
     * e.g. `18SUJ2341506519`. Null outside the UTM band.
     */
    fun toMgrs(lat: Double, lon: Double, digits: Int = 5): String? {
        val utm = toUtm(lat, lon) ?: return null
        val band = mgrsBand(lat) ?: return null
        val d = digits.coerceIn(1, 5)

        // 100 km square: the column letter cycles through three 8-letter sets by zone, and the row
        // letter walks a 20-letter alphabet that is offset by 5 on even zones.
        val colSet = (utm.zone - 1) % 3
        val colIndex = (floor(utm.easting / 100_000.0).toInt() - 1).coerceIn(0, 7)
        val col = MGRS_COLUMNS[colSet][colIndex]
        var rowIndex = floor((utm.northing % 2_000_000.0) / 100_000.0).toInt()
        if (utm.zone % 2 == 0) rowIndex += 5
        val row = MGRS_ROWS[((rowIndex % 20) + 20) % 20]

        val divisor = 10.0.pow(5 - d)
        val e = (floor(utm.easting % 100_000.0 / divisor)).roundToLong()
        val n = (floor(utm.northing % 100_000.0 / divisor)).roundToLong()
        return "${utm.zone}$band$col$row" +
            e.toString().padStart(d, '0') +
            n.toString().padStart(d, '0')
    }

    private val CARDINALS = listOf(
        "N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
        "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW",
    )
    private const val MGRS_BANDS = "CDEFGHJKLMNPQRSTUVWX"
    private val MGRS_COLUMNS = listOf("ABCDEFGH", "JKLMNPQR", "STUVWXYZ")
    private const val MGRS_ROWS = "ABCDEFGHJKLMNPQRSTUV"
}
