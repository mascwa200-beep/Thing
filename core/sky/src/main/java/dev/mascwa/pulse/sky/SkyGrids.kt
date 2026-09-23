package dev.mascwa.pulse.sky

import dev.mascwa.pulse.core.telemetry.Ephemeris
import dev.mascwa.pulse.core.telemetry.MilkyWay
import dev.mascwa.pulse.core.telemetry.ReferenceCircles
import dev.mascwa.pulse.core.telemetry.SkyProjection

/**
 * The coordinate grids, the meridian and the galactic equator — the furniture Stellarium draws by
 * a keypress and this map did not draw at all.
 *
 * Three frames, and which one a line lives in decides everything about how it is drawn:
 *
 * * **Equatorial, of date** ([fillEquatorial]) — hour circles every hour of right ascension and
 *   parallels every ten degrees of declination, in the equinox of the date being drawn, carried
 *   into J2000 as they are built exactly as [ReferenceLines] carries the equator. They then go
 *   through [SkyFrame.project] like a star, refracted and aberrated, so a grid crossing sits on the
 *   star it should sit on. ⚠️ Of DATE, not J2000, because that is what Stellarium's default
 *   equatorial grid is — the grid the sky visibly turns about tonight — and because a J2000 grid
 *   would drift 22′ from the drawn equator, which IS of date.
 * * **Galactic** ([fillGalacticEquator]) — one great circle at galactic latitude zero, fixed on the
 *   J2000 sky, built through [MilkyWay.equatorialOf] so it cannot disagree with the raster it runs
 *   along the spine of. ⚠️ Derived, not typed: a test walks every vertex back through
 *   [MilkyWay.galacticOfVector] and requires latitude zero, and anchors the first vertex on the IAU
 *   galactic centre.
 * * **Horizon** ([fillHorizon], [fillMeridian]) — azimuth lines every fifteen degrees, altitude
 *   circles every ten, and the meridian, in the observer's own frame. These are projected straight
 *   through the horizon basis, NOT through [SkyFrame]: they are the grid of the coordinates you
 *   would read off an instrument, so an altitude circle at 10° is drawn at 10° and is not bent.
 *   The horizon polyline has always been drawn this way; these join it.
 *
 * ## ⚠️ Every line is cut into thirty-degree runs, for the reason [ReferenceCircles] gives
 *
 * The smallest cap containing a great circle is the whole sphere, so an uncut circle can never be
 * rejected by [SkyLines.visible] and a narrow field would project every vertex of every line to keep
 * a handful. At [RUN_SPAN_DEG] per run the cap test throws most of a grid away with one dot product
 * each, which is what makes ~5,400 vertices affordable at every frame. The counts below are exact by
 * construction — integer loops over [PER_RUN] vertices, never a Double accumulated to a limit — so
 * the preallocation a caller makes is the number of vertices it gets.
 *
 * Pure: no clock, no observer, no platform. The one input is the epoch the equatorial grid is OF.
 */
object SkyGrids {

    /** Degrees between adjacent vertices along any line — the same step the reference circles use. */
    const val STEP_DEG = ReferenceCircles.STEP_DEG

    /** Degrees one run covers, so its bounding cap is small enough for the cap test to earn its keep. */
    const val RUN_SPAN_DEG = ReferenceCircles.ARC_SPAN_DEG

    /** Vertices in one run, including the endpoint shared with the next. */
    const val PER_RUN = ReferenceCircles.PER_ARC

    /** Hour circles and azimuth lines: one every fifteen degrees, which is one hour of right ascension. */
    const val LINE_EVERY_DEG = 15.0

    /** Parallels and altitude circles: one every ten degrees, the equator and horizon excluded. */
    const val CIRCLE_EVERY_DEG = 10.0

    /** How many hour circles (or azimuth lines) go round. */
    const val LINES = (360.0 / LINE_EVERY_DEG).toInt()

    /** Runs in one pole-to-pole line: 180 degrees in thirty-degree pieces. */
    const val RUNS_PER_LINE = (180.0 / RUN_SPAN_DEG).toInt()

    /** How many parallels (or altitude circles): ±10 … ±80, sixteen. Zero is the equator's own line. */
    const val CIRCLES = 2 * ((90.0 / CIRCLE_EVERY_DEG).toInt() - 1)

    /** Runs in one small circle right round the sky. */
    const val RUNS_PER_CIRCLE = ReferenceCircles.ARCS

    /** What [fillEquatorial] and [fillHorizon] hold once filled — pin these, do not recount. */
    const val GRID_RUNS = LINES * RUNS_PER_LINE + CIRCLES * RUNS_PER_CIRCLE
    const val GRID_VERTICES = GRID_RUNS * PER_RUN

    /** What [fillGalacticEquator] and [fillMeridian] hold: one great circle in [ReferenceCircles.ARCS] runs. */
    const val CIRCLE_RUNS = ReferenceCircles.ARCS
    const val CIRCLE_VERTICES = CIRCLE_RUNS * PER_RUN

    /**
     * The equatorial grid of the date [epochMs], in J2000 vectors ready for [SkyFrame.project].
     *
     * Hour circles first (each from the south pole to the north, [RUNS_PER_LINE] runs), then the
     * parallels ([RUNS_PER_CIRCLE] runs each, ±[CIRCLE_EVERY_DEG] outwards to eighty degrees). The
     * equator itself is left out: it is already drawn by [ReferenceLines], in its own ink, and a
     * second copy underneath it would only thicken the line.
     *
     * ⚠️ Rebuild this whenever the drawn date moves by more than a day or so — the grid precesses
     * with the equinox, half an arcminute a year. Across the day a scrubber offers that is nothing;
     * across the century a date picker offers it is a degree, and a grid a degree off its own equator
     * is exactly the wrong picture.
     */
    fun fillEquatorial(into: SkyLines, epochMs: Long) {
        into.clear()
        val v = DoubleArray(3)
        for (line in 0 until LINES) {
            val ra = line * LINE_EVERY_DEG
            for (run in 0 until RUNS_PER_LINE) {
                into.beginLine()
                for (i in 0 until PER_RUN) {
                    val dec = -90.0 + run * RUN_SPAN_DEG + i * STEP_DEG
                    equatorialOfDate(ra, dec, epochMs, v)
                    into.add(v[0], v[1], v[2])
                }
                into.endLine()
            }
        }
        forEachCircleDeclination { dec ->
            for (run in 0 until RUNS_PER_CIRCLE) {
                into.beginLine()
                for (i in 0 until PER_RUN) {
                    val ra = ReferenceCircles.longitudeOf(run, i)
                    equatorialOfDate(ra, dec, epochMs, v)
                    into.add(v[0], v[1], v[2])
                }
                into.endLine()
            }
        }
    }

    /**
     * The galactic equator: latitude zero, longitude right round, as J2000 vectors.
     *
     * Fixed on the sky, so fill it once. The first vertex is the galactic centre (longitude zero,
     * in Sagittarius); the traversal is [ReferenceCircles.longitudeOf], exactly as the celestial
     * equator's is.
     */
    fun fillGalacticEquator(into: SkyLines) {
        into.clear()
        for (run in 0 until ReferenceCircles.ARCS) {
            into.beginLine()
            for (i in 0 until PER_RUN) {
                val l = ReferenceCircles.longitudeOf(run, i)
                val eq = MilkyWay.equatorialOf(l, 0.0)
                val v = SkyProjection.equatorialVector(eq[0], eq[1])
                into.add(v[0], v[1], v[2])
            }
            into.endLine()
        }
    }

    /**
     * The azimuth/altitude grid in the horizon frame — azimuth lines from nadir to zenith every
     * [LINE_EVERY_DEG], altitude circles every [CIRCLE_EVERY_DEG] above and below the horizon.
     *
     * Fixed with respect to the observer, so fill it once. The horizon circle itself is left out
     * because the chart already draws it in its own ink. Below-horizon lines are included on purpose:
     * this map draws the sky under your feet, dimmed, and Stellarium with its ground off draws its
     * azimuthal grid there too; the GROUND fill is what hides them when it is on.
     */
    fun fillHorizon(into: SkyLines) {
        into.clear()
        for (line in 0 until LINES) {
            val az = line * LINE_EVERY_DEG
            for (run in 0 until RUNS_PER_LINE) {
                into.beginLine()
                for (i in 0 until PER_RUN) {
                    val alt = -90.0 + run * RUN_SPAN_DEG + i * STEP_DEG
                    val v = SkyProjection.unitVector(az, alt)
                    into.add(v[0], v[1], v[2])
                }
                into.endLine()
            }
        }
        forEachCircleDeclination { alt ->
            for (run in 0 until RUNS_PER_CIRCLE) {
                into.beginLine()
                for (i in 0 until PER_RUN) {
                    val az = ReferenceCircles.longitudeOf(run, i)
                    val v = SkyProjection.unitVector(az, alt)
                    into.add(v[0], v[1], v[2])
                }
                into.endLine()
            }
        }
    }

    /**
     * The meridian: the great circle through the north point, the zenith, the south point and the
     * nadir, in the horizon frame.
     *
     * Its own container rather than a member of [fillHorizon], because it is drawn whenever the
     * horizon grid is and a shade brighter — it is the line a transit happens on, and the one line
     * of that grid worth reading without the rest.
     */
    fun fillMeridian(into: SkyLines) {
        into.clear()
        for (run in 0 until ReferenceCircles.ARCS) {
            into.beginLine()
            for (i in 0 until PER_RUN) {
                val v = meridianPoint(ReferenceCircles.longitudeOf(run, i))
                into.add(v[0], v[1], v[2])
            }
            into.endLine()
        }
    }

    /**
     * A point on the meridian, parameterised by an angle from the north point through the zenith.
     *
     * 0 is due north on the horizon, 90 the zenith, 180 due south, 270 the nadir. Written as a
     * function of one angle so the traversal is the shared [ReferenceCircles.longitudeOf] and the
     * run count is [ReferenceCircles.ARCS] by construction.
     */
    fun meridianPoint(angleDeg: Double): DoubleArray {
        val t = ((angleDeg % 360.0) + 360.0) % 360.0
        return when {
            t <= 90.0 -> SkyProjection.unitVector(0.0, t)
            t <= 270.0 -> SkyProjection.unitVector(180.0, 180.0 - t)
            else -> SkyProjection.unitVector(0.0, t - 360.0)
        }
    }

    /** A text and where it goes on the sphere, in whichever frame the grid it labels lives in. */
    class Label(val text: String, val x: Double, val y: Double, val z: Double)

    /**
     * Where to write the equatorial grid's numbers: every other hour circle labelled on the
     * equator, and ±30°/±60° on the hour circles at 0h and 12h. J2000 vectors, like the grid.
     *
     * Every thirty degrees rather than every line, because a label on all twenty-four hour circles
     * and all sixteen parallels is a hundred and sixty strings fighting the stars they sit among.
     */
    fun equatorialLabels(epochMs: Long): List<Label> {
        val out = ArrayList<Label>(20)
        val v = DoubleArray(3)
        for (hour in 0 until 24 step 2) {
            equatorialOfDate(hour * 15.0, 0.0, epochMs, v)
            out.add(Label("${hour}h", v[0], v[1], v[2]))
        }
        for (dec in listOf(30.0, 60.0, -30.0, -60.0)) {
            for (ra in listOf(0.0, 180.0)) {
                equatorialOfDate(ra, dec, epochMs, v)
                out.add(Label(signedDegrees(dec), v[0], v[1], v[2]))
            }
        }
        return out
    }

    /**
     * Where to write the horizon grid's numbers: azimuths every thirty degrees along the horizon
     * (the four the chart already letters as N, E, S and W are left to it), and 30°/60° of altitude
     * on the meridian either side of the zenith. Horizon-frame vectors, like the grid.
     */
    fun horizonLabels(): List<Label> {
        val out = ArrayList<Label>(12)
        for (az in 30 until 360 step 30) {
            if (az % 90 == 0) continue
            val v = SkyProjection.unitVector(az.toDouble(), 0.0)
            out.add(Label("$az°", v[0], v[1], v[2]))
        }
        for (alt in listOf(30.0, 60.0)) {
            for (az in listOf(0.0, 180.0)) {
                val v = SkyProjection.unitVector(az, alt)
                out.add(Label("${alt.toInt()}°", v[0], v[1], v[2]))
            }
        }
        return out
    }

    private fun signedDegrees(deg: Double): String =
        if (deg >= 0.0) "+${deg.toInt()}°" else "−${(-deg).toInt()}°"

    /** ±10, ±20 … ±80, in the order the circles are filled. */
    private inline fun forEachCircleDeclination(block: (Double) -> Unit) {
        val steps = (90.0 / CIRCLE_EVERY_DEG).toInt() - 1
        for (k in 1..steps) {
            block(k * CIRCLE_EVERY_DEG)
            block(-k * CIRCLE_EVERY_DEG)
        }
    }

    /** RA/Dec of date → J2000 unit vector, in place, through the same rotation the reference circles use. */
    private fun equatorialOfDate(raDeg: Double, decDeg: Double, epochMs: Long, out: DoubleArray) {
        val u = SkyProjection.equatorialVector(raDeg, decDeg)
        out[0] = u[0]
        out[1] = u[1]
        out[2] = u[2]
        Ephemeris.ofDateVectorToJ2000(out, epochMs)
    }
}

/**
 * Project polylines that live in the HORIZON frame — the azimuthal grid and the meridian — into a
 * batch of screen segments, through a horizon basis rather than through [SkyFrame].
 *
 * The twin of [collectLines], and deliberately not a parameter on it: that function's whole point is
 * that a catalogue vector is refracted and aberrated on its way to the screen, and these lines must
 * NOT be. An altitude circle drawn at ten degrees is the ten-degree line an instrument reads; bending
 * it would put it where a star at ten degrees appears, which is a different fact.
 *
 * @param forwardX the view direction in the horizon frame — [SkyProjection.unitVector] of the view's
 *   azimuth and altitude, or the pointed forward when following the handset. Only the cap test reads
 *   it; the projection itself is [basis]'s. ⚠️ Passed separately because [SkyProjection.Basis] keeps
 *   its axes module-private, and rebuilding the forward vector here from a view would be a second
 *   statement of where the view looks.
 * @param edgeMargin how far past the viewport a segment may reach before both its ends are called
 *   outside — [SkyRenderer.EDGE_MARGIN], passed in rather than read here so this file stays free of
 *   the renderer's Compose imports and a JVM test can drive it.
 */
fun collectHorizonLines(
    lines: SkyLines,
    basis: SkyProjection.Basis,
    forwardX: Double,
    forwardY: Double,
    forwardZ: Double,
    viewport: SkyProjection.Viewport,
    coneCos: Double,
    coneSin: Double,
    halfPx: Float,
    centreX: Float,
    centreY: Float,
    edgeMargin: Double,
    into: LineBatch,
): Int {
    val marginW = viewport.halfWidth + edgeMargin
    val marginH = viewport.halfHeight + edgeMargin
    var drawn = 0
    for (run in 0 until lines.lines) {
        if (!lines.visible(run, forwardX, forwardY, forwardZ, coneCos, coneSin)) continue
        val from = lines.start[run]
        val to = from + lines.length[run]
        var haveLast = false
        var lastX = 0.0
        var lastY = 0.0
        for (i in from until to) {
            val p = SkyProjection.projectUnit(lines.vx[i], lines.vy[i], lines.vz[i], basis)
            if (!p.visible) {
                // Broken, not joined, across a vertex behind the viewer — see collectLines.
                haveLast = false
                continue
            }
            if (haveLast) {
                val outside =
                    (lastX > marginW && p.x > marginW) || (lastX < -marginW && p.x < -marginW) ||
                        (lastY > marginH && p.y > marginH) || (lastY < -marginH && p.y < -marginH)
                if (!outside) {
                    into.add(
                        centreX + (lastX * halfPx).toFloat(),
                        centreY + (lastY * halfPx).toFloat(),
                        centreX + (p.x * halfPx).toFloat(),
                        centreY + (p.y * halfPx).toFloat(),
                    )
                    drawn++
                }
            }
            lastX = p.x
            lastY = p.y
            haveLast = true
        }
    }
    return drawn
}
