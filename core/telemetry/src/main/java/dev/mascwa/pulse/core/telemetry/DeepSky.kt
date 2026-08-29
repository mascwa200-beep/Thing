package dev.mascwa.pulse.core.telemetry

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.sin

/**
 * The deep sky — galaxies, clusters and nebulae — as numbers a renderer can draw.
 *
 * The bundled catalogue is OpenNGC, 12,579 objects, carried as a lean tab-separated asset. Its
 * provenance, its licence and everything done to it are in `assets/sky/NOTICE.txt`; what follows is
 * only the reasoning that has to live beside the code.
 *
 * ## Why this is a linear scan and the star catalogue is not
 *
 * Twelve thousand objects is 632 kB of text. Every one can be walked on every frame and the whole
 * set held as a handful of arrays — the same shape the constellation lines use. The stars are three
 * million records in a packed, HEALPix-indexed, memory-mapped file because three million is three
 * orders of magnitude more, and nothing here is an argument against that.
 */
object DeepSky {

    private const val DEG = Math.PI / 180.0

    /**
     * What an object is, for the purpose of drawing it.
     *
     * ⚠️ **The asset carries OpenNGC's own type string and this mapping is where it becomes a shape**,
     * deliberately: deciding whether a `Cl+N` is a cluster or a nebula is a judgement with an edge
     * case, and it belongs somewhere a test can argue with it rather than in a build script whose
     * output nothing checks.
     */
    enum class Kind {
        /** A galaxy, or a pair, triple or group treated as one smudge. */
        GALAXY,

        /** A globular cluster: tens of thousands of stars in a ball. */
        GLOBULAR,

        /** An open cluster or a loose stellar association. */
        OPEN_CLUSTER,

        /** A cluster still wrapped in the gas it formed from. */
        CLUSTER_NEBULA,

        /** A planetary nebula — a shell thrown off by a dying star, so a ring more often than a disc. */
        PLANETARY,

        /** Emission, reflection and unclassified bright nebulae. */
        NEBULA,

        /** A supernova remnant: filaments rather than a smooth glow. */
        SUPERNOVA_REMNANT,

        /**
         * A dark nebula — dust seen in ABSORPTION against the sky behind it.
         *
         * ⚠️ Its own kind because drawing one as a glow would be a straightforward lie: these are the
         * places where there is LESS light, not more.
         */
        DARK_NEBULA,

        /** OpenNGC could not classify it, or this map has not learned to. */
        OTHER,
    }

    /**
     * OpenNGC's type string to a shape.
     *
     * Unknown strings become [Kind.OTHER] rather than throwing: an upstream catalogue that grows a
     * new type should cost one unlabelled marker, not a blank sky. The builder refuses a type it has
     * never seen, so a new one is caught before it ships — see `tools/sky/build_deepsky.py`.
     */
    fun kindOf(type: String): Kind = when (type) {
        "G", "GPair", "GTrpl", "GGroup" -> Kind.GALAXY
        "GCl" -> Kind.GLOBULAR
        "OCl", "*Ass" -> Kind.OPEN_CLUSTER
        "Cl+N" -> Kind.CLUSTER_NEBULA
        "PN" -> Kind.PLANETARY
        "Neb", "RfN", "EmN", "HII" -> Kind.NEBULA
        "SNR" -> Kind.SUPERNOVA_REMNANT
        "DrkN" -> Kind.DARK_NEBULA
        else -> Kind.OTHER
    }

    /**
     * One object.
     *
     * Every measured field is nullable and null means **not measured**, never zero. A third of this
     * catalogue has no size and a tenth has no brightness; folding that into a number would put a
     * claim where nobody made a measurement, which is the same rule the star catalogue follows for
     * an unmeasured colour.
     *
     * @param magnitude V where the catalogue measured it, B otherwise — see [band]. Not comparable
     *   across the two without care; the NOTICE explains why no conversion is applied.
     * @param band `'V'` or `'B'`. Null when there is no magnitude to have been measured in either,
     *   which is the guarantee — no magnitude implies no band. It can also be null with a magnitude
     *   present, if a row states a brightness and not which system it was measured in; that does not
     *   happen in the bundled asset, whose builder always writes both, and this is defensive.
     *
     *   ⚠️ Nullable rather than a blank sentinel, and the reason is a defect this file shipped for
     *   about an hour: it was a `Char` with `' '` for "none", a stray NUL byte landed inside that
     *   literal, and the sentinel silently became the NUL character. Kotlin compiles that without a
     *   murmur and `grep` reports the file as binary rather than showing the line. A nullable field
     *   has no sentinel to corrupt.
     * @param label the first common name, else the Messier number, else empty. 227 of 12,579 have one.
     */
    class Entry(
        val id: String,
        val kind: Kind,
        val rightAscensionDeg: Double,
        val declinationDeg: Double,
        val magnitude: Double?,
        val band: Char?,
        val majorAxisArcmin: Double?,
        val minorAxisArcmin: Double?,
        val positionAngleDeg: Double?,
        val label: String,
    )

    /**
     * Read the bundled asset.
     *
     * Defensive throughout: a row that will not parse is dropped rather than throwing, because a
     * catalogue that fails to load costs the whole layer while a row that fails to load costs one
     * galaxy. The asset ships with the app and is guarded by its own test, so this is a floor rather
     * than an expectation.
     */
    fun parse(text: String): List<Entry> {
        val out = ArrayList<Entry>(13_000)
        for (line in text.lineSequence()) {
            if (line.isEmpty() || line[0] == '#') continue
            val f = line.split('\t')
            if (f.size < 10) continue
            val ra = f[2].toDoubleOrNull() ?: continue
            val dec = f[3].toDoubleOrNull() ?: continue
            val mag = f[4].toDoubleOrNull()
            out.add(
                Entry(
                    id = f[0],
                    kind = kindOf(f[1]),
                    rightAscensionDeg = ra,
                    declinationDeg = dec,
                    magnitude = mag,
                    band = if (mag == null) null else f[5].firstOrNull(),
                    majorAxisArcmin = f[6].toDoubleOrNull(),
                    minorAxisArcmin = f[7].toDoubleOrNull(),
                    positionAngleDeg = f[8].toDoubleOrNull(),
                    label = f[9],
                ),
            )
        }
        return out
    }

    // ---- how deep to cut -------------------------------------------------------------------

    /**
     * How faint to go at a given field, in magnitudes.
     *
     * ⚠️ **This is NOT [SkyProjection.magnitudeLimit] with different numbers, and reusing that one
     * would be wrong.** Its constants are fitted to stars, whose counts rise about **2.8-fold per
     * magnitude**. Deep-sky counts rise about **1.7-fold** — measured across the bundled catalogue at
     * 90 objects brighter than 6, 564 brighter than 10, 1,788 brighter than 12 and 5,025 brighter
     * than 14, which is 1.58, 1.78 and 1.70 per magnitude over the three intervals. A population that
     * deepens more slowly needs the limit to deepen faster to keep the same number on screen.
     *
     * Holding the drawn count level means [MAGNITUDES_PER_DECADE] ≈ 2 / log₁₀(1.7) = 8.7. The shipped
     * 10.0 is a little steeper, so the count grows slightly as you zoom in, which is the direction you
     * want it to err.
     *
     * Measured by running this function over the real catalogue — objects on screen at a circular
     * field of that width:
     *
     *     150°  63.4      30°  85.0       4°  3.7
     *      90°  62.2      15°  48.2       2°  0.9
     *      60°  61.4       8°  14.7       1°  0.2
     *
     * Level to fifteen degrees, and below that the catalogue is exhausted and the count falls purely
     * by geometry — which is correct. There are not twelve thousand galaxies to fill a two-degree
     * field with; at two degrees you are looking at one, and it fills the screen.
     *
     * ⚠️ **No `deepest` parameter, and that is deliberate — the star law's is required for a reason
     * that does not apply here.** There, a limit past the catalogue's floor silently promised rows
     * that were not there. Here the geometry does the bounding below fifteen degrees, so an
     * unclamped limit changes nothing about what is drawn and lets a deeper catalogue than this one
     * simply work.
     */
    fun magnitudeLimit(fovDeg: Double): Double {
        val fov = fovDeg.coerceIn(SkyProjection.MIN_FOV_DEG, SkyProjection.MAX_FOV_DEG)
        return WIDEST_LIMIT + MAGNITUDES_PER_DECADE * log10(SkyProjection.MAX_FOV_DEG / fov)
    }

    /**
     * What the map draws at the widest field.
     *
     * Sixty-odd objects over the whole visible sky, which is about the size of the Messier list — so
     * the widest view shows roughly the things anybody would name, and no more.
     */
    const val WIDEST_LIMIT = 7.0

    /** See [magnitudeLimit] — 8.7 holds the count level, and 10.0 lets it grow a little on zoom. */
    const val MAGNITUDES_PER_DECADE = 10.0

    /**
     * Whether to draw this object at all.
     *
     * ⚠️ **The size clause is scoped to objects with no magnitude, and it is not a substitute
     * brightness.** 1,115 of 12,579 have none, and among them are the **Hyades** at 329 arcminutes
     * and the **Coma Star Cluster** at 253 — two of the most obvious things in the sky. Defaulting
     * them to some faint number would have hidden both until the field was under twenty-four degrees.
     * Where brightness cannot be judged, the only other thing known about the object is how big it
     * is, so that is what is judged: [CONSPICUOUS_FRACTION] of the field across.
     *
     * 501 objects have neither, and they appear once the field is narrow enough
     * ([UNMEASURED_FIELD_DEG]) that essentially nothing competes for the screen — 501 objects over
     * the whole sky is 0.6 of them in an eight-degree field. That completes the catalogue at a cost
     * of nothing.
     */
    fun visible(entry: Entry, limit: Double, fovDeg: Double): Boolean {
        val mag = entry.magnitude
        if (mag != null) return mag <= limit
        val major = entry.majorAxisArcmin
        if (major != null) return major >= fovDeg * 60.0 * CONSPICUOUS_FRACTION
        return fovDeg <= UNMEASURED_FIELD_DEG
    }

    /**
     * How much of the field an unmeasured object must span to be worth drawing.
     *
     * A sixtieth of the field is about eighteen pixels across on a 1080-pixel screen — conspicuous
     * rather than merely present. Over the real catalogue it admits five objects at the widest field
     * and thirteen at sixty degrees, so it rescues the obvious ones and adds nothing else.
     */
    const val CONSPICUOUS_FRACTION = 1.0 / 60.0

    /** Below this field, objects with neither a brightness nor a size are drawn as bare markers. */
    const val UNMEASURED_FIELD_DEG = 8.0

    // ---- how brightly to draw it -----------------------------------------------------------

    /**
     * Brightness per unit area, in magnitudes per square arcsecond, or null if it cannot be derived.
     *
     * This is what decides how strongly an extended object is drawn: a third-magnitude galaxy spread
     * over three square degrees is not a third-magnitude anything to look at. A round object is
     * treated as round when it has no minor axis.
     *
     * ⚠️ **This is DERIVED here and is not OpenNGC's own `SurfBr` column.** Measured over the 10,262
     * rows where both exist, the two differ by a median of 0.57 and a ninetieth percentile of 1.46
     * magnitudes per square arcsecond, because the catalogue's comes from LEDA's isophotal
     * measurement rather than from the magnitude and axes stored beside it. Deriving it is the more
     * useful of the two here precisely because it is consistent with those: an object whose stored
     * magnitude is B would otherwise be drawn at a strength that disagreed with the number next to it.
     *
     * ⚠️ **It decides HOW an object is drawn and never WHETHER.** The two orderings are nearly
     * disjoint — the top two hundred by magnitude and the top two hundred by surface brightness share
     * eight members. Cutting on surface brightness leads with anonymous thirteenth-magnitude galaxies
     * and leaves out Andromeda.
     */
    fun surfaceBrightness(entry: Entry): Double? {
        val mag = entry.magnitude ?: return null
        val major = entry.majorAxisArcmin ?: return null
        if (major <= 0.0) return null
        val minor = entry.minorAxisArcmin?.takeIf { it > 0.0 } ?: major
        // Area of the ellipse in square arcseconds: pi * (a/2) * (b/2) arcmin^2, and 3600 square
        // arcseconds to the square arcminute.
        val areaArcsec2 = Math.PI * major * minor / 4.0 * 3600.0
        return mag + 2.5 * log10(areaArcsec2)
    }

    /**
     * Surface brightness to a drawing strength in 0..1.
     *
     * The ends come from the real catalogue: the first percentile of derived surface brightness is
     * 18.3 and the ninety-ninth is 24.5, so [BRIGHTEST_SURFACE] and [FAINTEST_SURFACE] span very
     * nearly the whole population.
     *
     * ⚠️ **The floor is not zero.** An object drawn at no opacity is an object not drawn, and whether
     * to draw it was already decided by [visible]; a surface-brightness scale that can erase things
     * would be that decision made twice, in two places, on two different criteria.
     */
    fun opacity(surfaceBrightness: Double?): Double {
        if (surfaceBrightness == null) return FAINT_FLOOR
        val t = (surfaceBrightness - BRIGHTEST_SURFACE) / (FAINTEST_SURFACE - BRIGHTEST_SURFACE)
        return (1.0 - t).coerceIn(FAINT_FLOOR, 1.0)
    }

    /** Where [opacity] reaches full strength — around the first percentile of the catalogue. */
    const val BRIGHTEST_SURFACE = 20.0

    /** Where [opacity] bottoms out — around the ninety-ninth percentile. */
    const val FAINTEST_SURFACE = 24.5

    /** The dimmest anything is ever drawn. See [opacity] for why this is not zero. */
    const val FAINT_FLOOR = 0.18

    // ---- where and how big it lands on screen ----------------------------------------------

    /**
     * An object placed and oriented on screen.
     *
     * @param x screen position in projection units, as [SkyProjection.Screen] gives them.
     * @param semiMajorUnits half the long axis, in the same units.
     * @param angleRad the screen-space direction of the long axis, measured the way `atan2` measures.
     */
    class Shape(
        val x: Double,
        val y: Double,
        val semiMajorUnits: Double,
        val semiMinorUnits: Double,
        val angleRad: Double,
    )

    /**
     * Place an object on screen at its true size and orientation, or null if it cannot be drawn.
     *
     * ⚠️ **The orientation is MEASURED from the projection, not reasoned about, and that is the whole
     * design of this function.** A position angle is defined on the sky, from celestial north through
     * east; the map draws in a frame that has been rotated by the Earth's turn, the observer's
     * latitude, wherever the view is pointing and possibly the phone's roll. The angle between "north
     * on the sky here" and "up on the screen" is different for every object in the field. Deriving it
     * analytically means a parallactic angle plus a handedness convention, and getting the handedness
     * wrong mirrors every galaxy in the sky while looking perfectly plausible.
     *
     * So this projects the object and two neighbours a hundredth of a degree away — one due north,
     * one due east — and reads the answer off the screen. North and east come out as screen vectors,
     * the long axis is `north·cos(PA) + east·sin(PA)` by the definition of a position angle, and no
     * sign convention is assumed anywhere.
     *
     * ⚠️ **The scale comes from the same two probes, and it is better than the obvious `fovDeg / 2`.**
     * The projection is stereographic, so it is conformal — a small circle stays a circle — but its
     * scale grows toward the edge of the screen. The length of the projected north step divided by
     * the step itself IS the local scale, so an object near the edge is drawn at the size the
     * projection actually gives it rather than the size it would have at the centre.
     *
     * @param majorAxisArcmin the long axis. A missing minor axis is treated as round.
     * @param posAngleDeg a missing position angle is treated as zero, which points the long axis
     *   north — an arbitrary choice, but the object is round often enough that it rarely shows, and
     *   the alternative is refusing to draw a size that is genuinely known.
     */
    fun shapeOf(
        raDeg: Double,
        decDeg: Double,
        majorAxisArcmin: Double,
        minorAxisArcmin: Double?,
        posAngleDeg: Double?,
        basis: SkyProjection.Basis,
    ): Shape? {
        val centreVec = SkyProjection.equatorialVector(raDeg, decDeg)
        val centre = SkyProjection.projectUnit(centreVec[0], centreVec[1], centreVec[2], basis)
        if (!centre.visible) return null

        val r = raDeg * DEG
        val d = decDeg * DEG
        val sr = sin(r)
        val cr = cos(r)
        val sd = sin(d)
        val cd = cos(d)
        // The unit tangents of the equatorial frame. Both are exact everywhere, including at the
        // pole, where "north" correctly becomes the direction of the opposite meridian — which is
        // why these are written out rather than taken as a finite difference in right ascension,
        // whose cos(dec) divisor blows up there.
        val nx = -sd * cr
        val ny = -sd * sr
        val nz = cd
        val ex = -sr
        val ey = cr
        val ez = 0.0

        val cs = cos(PROBE_RAD)
        val sn = sin(PROBE_RAD)
        val north = SkyProjection.projectUnit(
            centreVec[0] * cs + nx * sn, centreVec[1] * cs + ny * sn, centreVec[2] * cs + nz * sn,
            basis,
        )
        val east = SkyProjection.projectUnit(
            centreVec[0] * cs + ex * sn, centreVec[1] * cs + ey * sn, centreVec[2] * cs + ez * sn,
            basis,
        )
        if (!north.visible || !east.visible) return null

        val nvx = north.x - centre.x
        val nvy = north.y - centre.y
        val evx = east.x - centre.x
        val evy = east.y - centre.y
        val unitsPerRadian = hypot(nvx, nvy) / PROBE_RAD
        if (!(unitsPerRadian > 0.0) || !unitsPerRadian.isFinite()) return null

        val pa = (posAngleDeg ?: 0.0) * DEG
        val cp = cos(pa)
        val sp = sin(pa)
        // A position angle is measured from north toward east, so this is that rotation carried out
        // in whatever frame the screen turned out to be.
        val dirX = nvx * cp + evx * sp
        val dirY = nvy * cp + evy * sp

        val semiMajor = (majorAxisArcmin / 2.0) * ARCMIN_RAD * unitsPerRadian
        val minor = minorAxisArcmin?.takeIf { it > 0.0 } ?: majorAxisArcmin
        val semiMinor = (minor / 2.0) * ARCMIN_RAD * unitsPerRadian
        return Shape(
            x = centre.x,
            y = centre.y,
            semiMajorUnits = semiMajor,
            semiMinorUnits = semiMinor.coerceAtMost(semiMajor),
            angleRad = atan2(dirY, dirX),
        )
    }

    /**
     * How far the north and east probes step, in radians.
     *
     * A hundredth of a degree: small enough to be local even at the narrowest field the map allows
     * (a twenty-fifth of it), and large enough that the difference of two projected positions does
     * not lose its precision to cancellation.
     */
    private const val PROBE_RAD = 0.01 * DEG

    private const val ARCMIN_RAD = DEG / 60.0

    /**
     * Whether an object is big enough on screen to be worth drawing as a shape.
     *
     * ⚠️ **The median object in this catalogue is 1.20 arcminutes across**, which at a sixty-degree
     * field on a 1080-pixel screen is well under one pixel. So the great majority of it can only ever
     * be a marker, and a shape is for the few hundred that are genuinely large at the field being
     * drawn. Below [SHAPE_MIN_PX] an ellipse is a smudge that reads as a rendering fault.
     */
    fun drawsShape(semiMajorUnits: Double, halfPx: Double): Boolean =
        semiMajorUnits * halfPx >= SHAPE_MIN_PX / 2.0

    /** The smallest long axis, in pixels, that is drawn as a shape rather than as a marker. */
    const val SHAPE_MIN_PX = 7.0

    /**
     * Whether this object's name is worth the room, shaped after [StarGlyph.labels].
     *
     * Two guards, and the first does most of the work: **227 of 12,579 objects have a name at all**,
     * so the screen cannot fill with catalogue numbers however deep the cut goes. [LABEL_HEADROOM]
     * is the second, and it exists for the wide field, where sixty objects are drawn and a dozen of
     * them are famous enough to be named — a dozen words over a star chart is a page of text.
     *
     * ⚠️ **An object with no magnitude is labelled if it is drawn at all**, which is the opposite of
     * what a headroom rule would do to it. The only way such an object reaches the screen is by being
     * conspicuously large ([visible]), and the two that matter most are the Hyades and the Coma Star
     * Cluster. Refusing to name the biggest things in the sky because nobody measured their
     * brightness would be the rule defeating its own purpose.
     */
    fun labels(entry: Entry, limit: Double): Boolean {
        if (entry.label.isEmpty()) return false
        val mag = entry.magnitude ?: return true
        return limit - mag >= LABEL_HEADROOM
    }

    /**
     * How much brighter than the cut an object must be before its name is drawn.
     *
     * Swept over the real catalogue, names on screen at a circular field of that width:
     *
     *     headroom   150°    90°    60°    30°    15°
     *          0.0  28.54  20.06  12.06   3.59   0.92
     *          2.0  11.12  12.01   8.71   3.48   0.92
     *          4.0   3.71   4.83   5.02   2.79   0.90
     *
     * Four is where both ends are right: three to five names at the fields anybody looks at, which is
     * a chart rather than a page, and it never collapses to nothing. ⚠️ **My first guess here was two,
     * and the numbers above are why it is not** — I wrote a table from expectation before measuring
     * and every figure in it was wrong. At zero the widest view carries twenty-eight names over a
     * star chart.
     */
    const val LABEL_HEADROOM = 4.0
}
