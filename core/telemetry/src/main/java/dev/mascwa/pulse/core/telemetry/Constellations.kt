package dev.mascwa.pulse.core.telemetry

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The 88 constellations: their stick figures, their popular asterisms, and the IAU borders.
 *
 * Pure, so CI holds every rule here. The asset it reads is built by `tools/sky/build_constellations.py`
 * from the Stellarium modern skyculture joined to Hipparcos; that script's header records where each
 * number came from and what it was checked against.
 *
 * ## ⚠️ Everything comes out in J2000, because that is the frame the stars are in
 *
 * The borders are published in **B1875** — Delporte drew them along whole hours of right ascension
 * and whole degrees of declination in that equinox, which is why they are still stated there a
 * century later. The bundled star catalogues are J2000. Precession between the two is **1.75
 * degrees**, so drawing the borders in their published frame over stars in theirs would put every
 * border three and a half Moon-widths away from the stars it is supposed to enclose.
 *
 * So [b1875ToJ2000] runs on every border vertex and the whole picture is drawn in ONE frame. That is
 * not merely the cheaper of the two options — it is the correct one, and it stays correct if the
 * stars are later carried to the date being drawn, provided the borders are carried with them.
 *
 * ## ⚠️ A border is a straight line only in the frame it was drawn in
 *
 * A "parallel" segment follows a circle of constant declination, which is not a great circle, so it
 * is not straight on any projection at all. Measured over the real 781 edges, the worst bows **2.18
 * degrees** away from the great circle joining its endpoints — the Octans/Chamaeleon border, near
 * the south pole. Drawing endpoint-to-endpoint would put that line four Moon-diameters wrong.
 *
 * Hence [walkEdge], which interpolates in B1875 where the line genuinely is straight and precesses
 * each point on the way out.
 *
 * ## ⚠️ And the step cannot be a constant, which was measured rather than assumed
 *
 * The remaining error is the sagitta of drawing each interpolated piece as a straight line on
 * screen, and on a map that zooms 600-fold it depends on the field:
 *
 * | step | fov 0.25° | fov 1° | fov 5° | fov 60° |
 * |---|---|---|---|---|
 * | 0.5° | **111 px** | 28 px | 5.5 px | 0.5 px |
 * | 1.0° | 330 px | 110 px | 22 px | 1.8 px |
 * | 2.0° | 742 px | 404 px | 86 px | 7 px |
 *
 * (worst on-screen deviation over all 781 edges, on a 1280-pixel screen). A step chosen to look
 * right at the widest zoom is a hundred pixels wrong at the narrowest. The table fits
 * `deviation = [CHORD_K] × step² / fov` to better than 5% everywhere, which is what [stepDegFor]
 * inverts.
 */
object Constellations {

    private const val DEG = Math.PI / 180.0

    /**
     * Julian centuries from J2000 back to B1875.0, the equinox the IAU borders are drawn in.
     *
     * ⚠️ **Besselian, not Julian**, and the two are different epochs with the same number on them.
     * B1875.0 is JD 2405889.2586 — derived here from the defining B1900.0 = JD 2415020.31352 and
     * the Besselian year of 365.242198781 days, rather than written as a constant, so the arithmetic
     * is on the page. The Julian J1875.0 is about 0.24 days away, which is far too small to matter
     * for this; the reason to derive it anyway is that the same expression is what a reader checks.
     */
    val B1875_CENTURIES: Double =
        ((2415020.31352 - 25.0 * 365.242198781) - 2451545.0) / 36525.0

    /**
     * How the on-screen sagitta scales, measured over the real border set.
     *
     * `deviation_in_half_widths = CHORD_K × stepDeg² / fovDeg`. Fitted from the table in the class
     * documentation: 0.5°/0.25° gives 0.173, 1°/5° gives 0.0344 against a predicted 0.0346, 2°/60°
     * gives 0.0110 against 0.0115. The law is exact in the limit — a chord's sagitta is
     * proportional to the square of its length, and screen scale is inversely proportional to the
     * field — so the constant is the only thing that had to be measured.
     */
    const val CHORD_K = 0.173

    /**
     * How far a drawn line may sit from where it belongs, as a fraction of the screen's half-width.
     *
     * About one pixel on a 1280-pixel screen. Finer buys nothing anybody can see and costs vertices
     * in proportion to the square root.
     */
    const val SCREEN_TOLERANCE = 0.0016

    /**
     * The finest step [stepDegFor] will ask for, from a vertex budget rather than from taste.
     *
     * ⚠️ **Measured by running this over the real bundled asset, and the first number written here
     * was wrong because it counted only the borders.** The 781 border segments are 4,547 degrees of
     * arc; the 331 figure and asterism polylines are **6,135**, half as much again. At this step the
     * whole sky is about **92,000 vertices** — a 735 kB buffer of doubles, and roughly the same
     * count the star renderer already projects at its busiest measured zoom.
     *
     * ⚠️ That is affordable only because a renderer culls each polyline against the view before
     * touching its vertices. At a quarter-degree field essentially every line in the sky is off
     * screen, and projecting ninety thousand points to discard them all would be the whole frame.
     *
     * Below the field where this clamp bites, the drawn line starts to depart from the true one:
     * **1.6 pixels at a 1° field and 6 px at the 0.25° floor**. Stated rather than hidden — at a
     * quarter of a degree a constellation border is a straight line across the screen and there is
     * nothing to compare it against.
     */
    const val MIN_STEP_DEG = 0.12

    /** Coarser than this is pointless: at the widest field the tolerance is already met. */
    const val MAX_STEP_DEG = 2.0

    /** Whether a border segment runs along a meridian (constant RA) or a parallel (constant Dec). */
    enum class EdgeKind { MERIDIAN, PARALLEL }

    /**
     * One IAU border segment, in **B1875** — the frame it is published in.
     *
     * ⚠️ Both adjacent constellations are named because that is what stops every border being drawn
     * twice. The CDS point-cloud form of this data contains each segment once per constellation, and
     * its own documentation calls that out as a hazard.
     */
    class Edge(
        val kind: EdgeKind,
        val ra1Deg: Double,
        val dec1Deg: Double,
        val ra2Deg: Double,
        val dec2Deg: Double,
        val a: String,
        val b: String,
    ) {
        /**
         * The length of the PATH, not the separation of its ends.
         *
         * ⚠️ For a parallel those are different things, and the path is the one that decides how
         * many pieces it needs: a border running along declination −87° covers far less sky than its
         * change in right ascension suggests, and one along the equator covers exactly as much.
         */
        val arcDeg: Double = when (kind) {
            EdgeKind.MERIDIAN -> abs(dec2Deg - dec1Deg)
            EdgeKind.PARALLEL -> abs(deltaRa(ra1Deg, ra2Deg)) * cos(dec1Deg * DEG)
        }
    }

    /**
     * A named set of polylines over the star list — a stick figure, or an asterism.
     *
     * Each line is a run of indices into [Data.starRaDeg]/[Data.starDecDeg]: `[0, 1, 2]` is two
     * segments, not three. A figure and an asterism are the same shape and differ only in what they
     * mean, so they are one type — see [Data].
     */
    class Figure(val code: String, val name: String, val lines: List<IntArray>)

    /**
     * Everything the asset holds, decoded.
     *
     * @param starRaDeg J2000 right ascensions of the figure vertices. The builder resolves each
     *   Hipparcos number and carries it from the catalogue's 1991.25 epoch, so nothing here needs a
     *   cross-identification index at runtime.
     * @param figures the 88 IAU constellations.
     * @param asterisms the popular shapes that are not constellations — the Plough, the Summer
     *   Triangle — which people recognise far better than most of the 88.
     * @param boundaries the IAU borders, in B1875. Use [walkEdge] rather than reading them directly.
     */
    class Data(
        val starRaDeg: DoubleArray,
        val starDecDeg: DoubleArray,
        val figures: List<Figure>,
        val asterisms: List<Figure>,
        val boundaries: List<Edge>,
    )

    /**
     * Decode the bundled asset.
     *
     * ⚠️ Returns null rather than throwing, and drops anything malformed rather than guessing: a
     * chart with no constellation lines is a working chart, and a chart with a line drawn to a star
     * that is not there is a bug that looks like a design.
     */
    fun parse(json: String): Data? = runCatching {
        val root = Json.parseToJsonElement(json).jsonObject
        val stars = root["stars"]?.jsonArray ?: return null
        val n = stars.size
        if (n == 0) return null
        val ra = DoubleArray(n)
        val dec = DoubleArray(n)
        for (i in 0 until n) {
            val p = stars[i].jsonArray
            ra[i] = p[0].jsonPrimitive.doubleOrNull ?: return null
            dec[i] = p[1].jsonPrimitive.doubleOrNull ?: return null
        }
        Data(
            starRaDeg = ra,
            starDecDeg = dec,
            figures = figures(root["figures"], n),
            asterisms = figures(root["asterisms"], n),
            boundaries = edges(root["boundaries"]),
        )
    }.getOrNull()

    private fun figures(element: JsonElement?, stars: Int): List<Figure> {
        val array = element as? JsonArray ?: return emptyList()
        val out = ArrayList<Figure>(array.size)
        for (entry in array) {
            val o = entry as? JsonObject ?: continue
            val code = o["code"]?.jsonPrimitive?.contentOrNull ?: continue
            val name = o["name"]?.jsonPrimitive?.contentOrNull ?: code
            val lines = ArrayList<IntArray>()
            for (line in o["lines"]?.jsonArray.orEmpty()) {
                val run = (line as? JsonArray) ?: continue
                if (run.size < 2) continue
                val ids = IntArray(run.size)
                var ok = true
                for (i in ids.indices) {
                    val v = run[i].jsonPrimitive.doubleOrNull?.toInt() ?: -1
                    // A vertex outside the star list would draw a line to nowhere, so the whole run
                    // goes rather than a silently shortened one.
                    if (v !in 0 until stars) { ok = false; break }
                    ids[i] = v
                }
                if (ok) lines.add(ids)
            }
            if (lines.isNotEmpty()) out.add(Figure(code, name, lines))
        }
        return out
    }

    private fun edges(element: JsonElement?): List<Edge> {
        val array = element as? JsonArray ?: return emptyList()
        val out = ArrayList<Edge>(array.size)
        for (entry in array) {
            val row = entry as? JsonArray ?: continue
            if (row.size < 7) continue
            val kind = when (row[0].jsonPrimitive.contentOrNull) {
                "M" -> EdgeKind.MERIDIAN
                "P" -> EdgeKind.PARALLEL
                else -> continue
            }
            val v = DoubleArray(4)
            var ok = true
            for (i in 0 until 4) {
                val d = row[i + 1].jsonPrimitive.doubleOrNull
                if (d == null) { ok = false; break }
                v[i] = d
            }
            if (!ok) continue
            out.add(
                Edge(
                    kind, v[0], v[1], v[2], v[3],
                    row[5].jsonPrimitive.contentOrNull ?: "",
                    row[6].jsonPrimitive.contentOrNull ?: "",
                ),
            )
        }
        return out
    }

    /**
     * A B1875 position expressed in J2000.
     *
     * Delegates to [Ephemeris.precessToJ2000], where the direction is checked against a published
     * answer — see that function. Kept here as a named operation so nothing has to remember which
     * sign B1875 takes.
     */
    fun b1875ToJ2000(raDeg: Double, decDeg: Double): DoubleArray =
        Ephemeris.precessToJ2000(raDeg, decDeg, B1875_CENTURIES)

    /**
     * How finely to cut a line so it looks straight where it should be straight.
     *
     * Inverts `deviation = [CHORD_K] × step² / fov` at [SCREEN_TOLERANCE], clamped to the vertex
     * budget at one end and to pointlessness at the other. A degenerate field answers the coarsest
     * step rather than dividing by zero.
     */
    fun stepDegFor(fovDeg: Double): Double {
        if (!fovDeg.isFinite() || fovDeg <= 0.0) return MAX_STEP_DEG
        val ideal = sqrt(SCREEN_TOLERANCE * fovDeg / CHORD_K)
        return min(MAX_STEP_DEG, max(MIN_STEP_DEG, ideal))
    }

    /** How many points [walkEdge] will emit for this edge at this step. */
    fun pointsFor(arcDeg: Double, stepDeg: Double): Int =
        max(1, ceil(arcDeg / max(stepDeg, 1e-6)).toInt()) + 1

    /**
     * Walk an IAU border, emitting J2000 positions.
     *
     * ⚠️ **Interpolated in B1875 and precessed point by point, in that order.** Precessing only the
     * two ends and interpolating between them in J2000 would straighten the very curve this exists
     * to draw — and it would compile, and it would look almost right.
     *
     * @param out called with each `(raDeg, decDeg)` in J2000, from one end to the other inclusive.
     *   A callback rather than a returned list because the caller is filling a vertex buffer and
     *   this runs over tens of thousands of points.
     */
    inline fun walkEdge(edge: Edge, stepDeg: Double, out: (Double, Double) -> Unit) {
        val n = pointsFor(edge.arcDeg, stepDeg) - 1
        val dRa = deltaRa(edge.ra1Deg, edge.ra2Deg)
        val dDec = edge.dec2Deg - edge.dec1Deg
        for (i in 0..n) {
            val t = i.toDouble() / n
            // One of the two is constant by construction; interpolating both anyway costs nothing
            // and means a mislabelled edge draws its real shape rather than a straight line to the
            // wrong place.
            val p = b1875ToJ2000(edge.ra1Deg + dRa * t, edge.dec1Deg + dDec * t)
            out(p[0], p[1])
        }
    }

    /**
     * Walk the great circle between two J2000 positions, emitting J2000 positions.
     *
     * For the stick figures, whose vertices are stars rather than coordinates. ⚠️ **Subdividing
     * matters here too and it is easy to assume otherwise**, because a figure line joins two points
     * and looks like it should be one screen segment. At a 60° field a 20°-long line drawn as a
     * single chord sits over a screen-width away from the great circle at its middle — the same
     * `step²/fov` law, with a very long step.
     *
     * Interpolation is spherical (slerp), so the path is the great circle a chart is expected to
     * draw and not a straight line through the inside of the sphere.
     */
    inline fun walkGreatCircle(
        ra1Deg: Double,
        dec1Deg: Double,
        ra2Deg: Double,
        dec2Deg: Double,
        stepDeg: Double,
        out: (Double, Double) -> Unit,
    ) {
        val u = SkyProjection.equatorialVector(ra1Deg, dec1Deg)
        val v = SkyProjection.equatorialVector(ra2Deg, dec2Deg)
        val dot = (u[0] * v[0] + u[1] * v[1] + u[2] * v[2]).coerceIn(-1.0, 1.0)
        val angle = kotlin.math.acos(dot)
        val n = pointsFor(Math.toDegrees(angle), stepDeg) - 1
        val sinA = kotlin.math.sin(angle)
        for (i in 0..n) {
            val t = i.toDouble() / n
            // Antipodal or coincident endpoints have no unique arc; fall back to the linear blend,
            // which for coincident points is the point itself and for antipodal ones is arbitrary
            // — as it must be, since every great circle through them is equally correct.
            val x: Double
            val y: Double
            val z: Double
            if (sinA < 1e-9) {
                x = u[0] + (v[0] - u[0]) * t
                y = u[1] + (v[1] - u[1]) * t
                z = u[2] + (v[2] - u[2]) * t
            } else {
                val a = kotlin.math.sin((1.0 - t) * angle) / sinA
                val b = kotlin.math.sin(t * angle) / sinA
                x = u[0] * a + v[0] * b
                y = u[1] * a + v[1] * b
                z = u[2] * a + v[2] * b
            }
            val r = sqrt(x * x + y * y + z * z)
            if (r < 1e-12) continue
            out(
                norm360(Math.toDegrees(kotlin.math.atan2(y / r, x / r))),
                Math.toDegrees(kotlin.math.asin((z / r).coerceIn(-1.0, 1.0))),
            )
        }
    }

    /**
     * The shorter way round from one right ascension to another, in degrees, signed.
     *
     * ⚠️ Published as part of the API because [walkEdge] is inline and its callers are elsewhere.
     * The shortest way is always the right one here: the longest single border segment in the whole
     * IAU set covers thirty degrees of arc, so no edge is anywhere near the half-turn where the two
     * ways round become ambiguous.
     */
    fun deltaRa(fromDeg: Double, toDeg: Double): Double {
        var d = toDeg - fromDeg
        while (d > 180.0) d -= 360.0
        while (d < -180.0) d += 360.0
        return d
    }

    /** Fold an angle into `[0, 360)`. Public for the same inline reason as [deltaRa]. */
    fun norm360(deg: Double): Double {
        val d = deg % 360.0
        return if (d < 0) d + 360.0 else d
    }
}
