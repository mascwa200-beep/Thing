package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI

/**
 * The deep sky.
 *
 * ⚠️ **Every expected value here was computed by running the shipped function over real data, not
 * recalled.** That is the habit this project keeps having to relearn, and it caught one wrong table
 * in this very file: the label-headroom sweep was written from expectation first and every figure in
 * it was wrong by a factor of three.
 */
class DeepSkyTest {

    // ---- what an object is -----------------------------------------------------------------

    @Test
    fun `every type the builder allows becomes a shape, and only the unclassified falls through`() {
        // ⚠️ This list is the builder's own KNOWN_TYPES, which it refuses to build without. If
        // upstream grows a type the build fails there; this is the other half — that everything in
        // the asset today has somewhere to go.
        val known = listOf(
            "G", "GPair", "GTrpl", "GGroup", "GCl", "OCl", "Cl+N", "*Ass",
            "PN", "Neb", "RfN", "EmN", "HII", "SNR", "DrkN", "Other",
        )
        for (type in known) {
            val kind = DeepSky.kindOf(type)
            if (type == "Other") {
                assertEquals(DeepSky.Kind.OTHER, kind)
            } else {
                assertTrue("$type fell through to OTHER", kind != DeepSky.Kind.OTHER)
            }
        }
    }

    @Test
    fun `a dark nebula is its own kind, because drawing one as a glow would be a lie`() {
        assertEquals(DeepSky.Kind.DARK_NEBULA, DeepSky.kindOf("DrkN"))
        assertEquals(DeepSky.Kind.NEBULA, DeepSky.kindOf("EmN"))
    }

    @Test
    fun `a type nobody has mapped costs one marker, not a blank sky`() {
        assertEquals(DeepSky.Kind.OTHER, DeepSky.kindOf("QuasarPair"))
        assertEquals(DeepSky.Kind.OTHER, DeepSky.kindOf(""))
    }

    // ---- reading the asset -----------------------------------------------------------------

    private val m31Row =
        "NGC0224\tG\t10.68479\t41.26906\t3.44\tV\t177.83\t69.66\t35\tAndromeda Galaxy"

    @Test
    fun `a row parses into every field`() {
        val e = DeepSky.parse(m31Row).single()
        assertEquals("NGC0224", e.id)
        assertEquals(DeepSky.Kind.GALAXY, e.kind)
        assertEquals(10.68479, e.rightAscensionDeg, 1e-9)
        assertEquals(41.26906, e.declinationDeg, 1e-9)
        assertEquals(3.44, e.magnitude!!, 1e-9)
        assertEquals('V', e.band)
        assertEquals(177.83, e.majorAxisArcmin!!, 1e-9)
        assertEquals(69.66, e.minorAxisArcmin!!, 1e-9)
        assertEquals(35.0, e.positionAngleDeg!!, 1e-9)
        assertEquals("Andromeda Galaxy", e.label)
    }

    @Test
    fun `an absent measurement is null and never zero`() {
        val e = DeepSky.parse("IC0100\tG\t1.0\t2.0\t\t\t\t\t\t").single()
        assertNull(e.magnitude)
        assertNull(e.majorAxisArcmin)
        assertNull(e.minorAxisArcmin)
        assertNull(e.positionAngleDeg)
        // ⚠️ The band is null rather than a blank sentinel: a band with no magnitude beside it
        // would be a claim about which photometric system a number that does not exist was measured
        // in. It is nullable so `(magnitude == null) == (band == null)` is checkable, which is the
        // assertion below.
        assertNull(e.band)
        assertEquals("", e.label)
    }

    @Test
    fun `no magnitude means no band, which is the guarantee the nullable type carries`() {
        // ⚠️ Why `band` is nullable at all. It was a Char with a space for "none", a stray NUL byte
        // landed inside that literal, and the sentinel silently became the NUL character — which
        // Kotlin compiles without a murmur and which makes grep report the file as binary rather
        // than show the line. This asserts the pairing rather than any sentinel's value.
        val none = DeepSky.parse("A\tG\t1\t2\t\t\t\t\t\t").single()
        assertNull(none.magnitude)
        assertNull(none.band)

        val both = DeepSky.parse("B\tG\t1\t2\t9.5\tB\t\t\t\t").single()
        assertEquals(9.5, both.magnitude!!, 1e-9)
        assertEquals('B', both.band)

        // The other direction is NOT guaranteed and the KDoc says so: a row may state a brightness
        // without saying which system measured it. The bundled asset never does, and parse is
        // defensive rather than trusting.
        val unstated = DeepSky.parse("C\tG\t1\t2\t9.5\t\t\t\t\t").single()
        assertEquals(9.5, unstated.magnitude!!, 1e-9)
        assertNull(unstated.band)
    }

    @Test
    fun `comments and unreadable rows are dropped rather than thrown on`() {
        val text = "# header\n$m31Row\nrubbish\nIC1\tG\tnotanumber\t2\t\t\t\t\t\t\n"
        val out = DeepSky.parse(text)
        assertEquals(1, out.size)
        assertEquals("NGC0224", out.single().id)
    }

    // ---- how deep to cut -------------------------------------------------------------------

    @Test
    fun `the limit deepens as the field narrows`() {
        var previous = Double.NEGATIVE_INFINITY
        for (fov in listOf(150.0, 90.0, 60.0, 30.0, 15.0, 8.0, 4.0, 1.0, 0.25)) {
            val limit = DeepSky.magnitudeLimit(fov)
            assertTrue("limit went backwards at $fov", limit > previous)
            previous = limit
        }
    }

    @Test
    fun `the limit is the law it says it is`() {
        // 7.0 + 10 * log10(150 / fov), the two anchors being the widest field and the decade below it.
        assertEquals(7.0, DeepSky.magnitudeLimit(150.0), 1e-9)
        assertEquals(17.0, DeepSky.magnitudeLimit(15.0), 1e-9)
        // 7 + 10 * log10(2.5) = 7 + 3.9794
        assertEquals(10.9794, DeepSky.magnitudeLimit(60.0), 1e-4)
    }

    @Test
    fun `a field outside what the projection allows is clamped, not extrapolated`() {
        assertEquals(DeepSky.magnitudeLimit(SkyProjection.MAX_FOV_DEG), DeepSky.magnitudeLimit(400.0), 1e-9)
        assertEquals(DeepSky.magnitudeLimit(SkyProjection.MIN_FOV_DEG), DeepSky.magnitudeLimit(0.0), 1e-9)
    }

    // ---- whether to draw it ----------------------------------------------------------------

    private fun entry(
        mag: Double? = null,
        major: Double? = null,
        label: String = "",
        ra: Double = 0.0,
        dec: Double = 0.0,
        minor: Double? = null,
    ) = DeepSky.Entry(
        id = "TEST", kind = DeepSky.Kind.GALAXY,
        rightAscensionDeg = ra, declinationDeg = dec,
        magnitude = mag, band = if (mag == null) null else 'V',
        majorAxisArcmin = major, minorAxisArcmin = minor, positionAngleDeg = null,
        label = label,
    )

    @Test
    fun `a measured object is cut on its brightness`() {
        val limit = DeepSky.magnitudeLimit(60.0)
        assertTrue(DeepSky.visible(entry(mag = limit - 0.01), limit, 60.0))
        assertFalse(DeepSky.visible(entry(mag = limit + 0.01), limit, 60.0))
    }

    @Test
    fun `the Hyades is not hidden for want of a brightness nobody recorded`() {
        // ⚠️ The reason the size clause exists. OpenNGC gives the Hyades no magnitude at all, and it
        // is 329 arcminutes across — five and a half degrees, one of the most obvious things in the
        // sky. A faint default would have kept it off the map until the field was under twenty-four
        // degrees. Same for the Coma Star Cluster at 253.
        val hyades = entry(major = 329.0, label = "Hyades")
        val coma = entry(major = 253.5, label = "Coma Star Cluster")
        for (fov in listOf(150.0, 90.0, 60.0, 30.0)) {
            val limit = DeepSky.magnitudeLimit(fov)
            assertTrue("the Hyades vanished at $fov", DeepSky.visible(hyades, limit, fov))
            assertTrue("Coma vanished at $fov", DeepSky.visible(coma, limit, fov))
        }
    }

    @Test
    fun `an unmeasured object too small to notice is not drawn at a wide field`() {
        val small = entry(major = 1.2) // the median size in this catalogue
        assertFalse(DeepSky.visible(small, DeepSky.magnitudeLimit(150.0), 150.0))
        // ...and it appears once the field is narrow enough for it to span a sixtieth of the screen.
        assertTrue(DeepSky.visible(small, DeepSky.magnitudeLimit(1.0), 1.0))
    }

    @Test
    fun `an object with neither a brightness nor a size waits for a narrow field`() {
        val bare = entry()
        assertFalse(DeepSky.visible(bare, DeepSky.magnitudeLimit(60.0), 60.0))
        assertFalse(
            DeepSky.visible(
                bare,
                DeepSky.magnitudeLimit(DeepSky.UNMEASURED_FIELD_DEG + 0.1),
                DeepSky.UNMEASURED_FIELD_DEG + 0.1,
            ),
        )
        assertTrue(
            DeepSky.visible(
                bare,
                DeepSky.magnitudeLimit(DeepSky.UNMEASURED_FIELD_DEG),
                DeepSky.UNMEASURED_FIELD_DEG,
            ),
        )
    }

    @Test
    fun `Andromeda survives every cut, which is the surface-brightness trap pinned`() {
        // ⚠️ This test exists because of a design that was nearly shipped. Cutting this catalogue on
        // SURFACE BRIGHTNESS rather than magnitude leads with anonymous thirteenth-magnitude
        // galaxies: the top two hundred by each measure share eight members, and Andromeda is not in
        // the surface-brightness list. A deep-sky layer without Andromeda in it is not a deep-sky
        // layer, so the property is asserted rather than remembered.
        val m31 = DeepSky.parse(m31Row).single()
        for (fov in listOf(150.0, 90.0, 60.0, 30.0, 15.0, 8.0, 4.0, 1.0, 0.25)) {
            assertTrue(
                "Andromeda was cut at a $fov degree field",
                DeepSky.visible(m31, DeepSky.magnitudeLimit(fov), fov),
            )
        }
    }

    // ---- how brightly ----------------------------------------------------------------------

    @Test
    fun `surface brightness is the magnitude spread over the area`() {
        val m31 = DeepSky.parse(m31Row).single()
        // 3.44 + 2.5 * log10(pi * 177.83 * 69.66 / 4 * 3600) = 3.44 + 18.861
        assertEquals(22.301, DeepSky.surfaceBrightness(m31)!!, 1e-3)
    }

    @Test
    fun `a round object is treated as round`() {
        val round = entry(mag = 10.0, major = 4.0)
        val explicit = entry(mag = 10.0, major = 4.0, minor = 4.0)
        assertEquals(DeepSky.surfaceBrightness(explicit)!!, DeepSky.surfaceBrightness(round)!!, 1e-12)
    }

    @Test
    fun `surface brightness needs both halves and says so`() {
        assertNull(DeepSky.surfaceBrightness(entry(major = 4.0)))
        assertNull(DeepSky.surfaceBrightness(entry(mag = 10.0)))
        assertNull(DeepSky.surfaceBrightness(entry(mag = 10.0, major = 0.0)))
    }

    @Test
    fun `the same light spread wider is dimmer per unit area`() {
        // The whole reason this quantity exists: a bright magnitude over a large area is not a bright
        // thing to look at. Doubling both axes quarters the surface brightness, which is 1.505 mag.
        val small = entry(mag = 9.0, major = 2.0, minor = 2.0)
        val large = entry(mag = 9.0, major = 4.0, minor = 4.0)
        val diff = DeepSky.surfaceBrightness(large)!! - DeepSky.surfaceBrightness(small)!!
        assertEquals(2.5 * kotlin.math.log10(4.0), diff, 1e-9)
        assertTrue(diff > 0.0)
    }

    @Test
    fun `opacity falls with surface brightness and never reaches zero`() {
        assertEquals(1.0, DeepSky.opacity(DeepSky.BRIGHTEST_SURFACE), 1e-9)
        assertEquals(1.0, DeepSky.opacity(DeepSky.BRIGHTEST_SURFACE - 5.0), 1e-9)
        assertEquals(DeepSky.FAINT_FLOOR, DeepSky.opacity(DeepSky.FAINTEST_SURFACE + 5.0), 1e-9)
        val mid = DeepSky.opacity((DeepSky.BRIGHTEST_SURFACE + DeepSky.FAINTEST_SURFACE) / 2.0)
        assertTrue(mid > DeepSky.FAINT_FLOOR && mid < 1.0)
        // ⚠️ Not zero, ever. Whether to draw an object was decided by `visible`; a strength scale
        // that could erase one would be that decision made twice on two different criteria.
        assertTrue(DeepSky.opacity(null) > 0.0)
        assertEquals(DeepSky.FAINT_FLOOR, DeepSky.opacity(null), 1e-9)
    }

    // ---- where and how big on screen --------------------------------------------------------

    /** Looking at a point on the celestial equator with the pole as up, so screen-up IS north. */
    private fun northUpBasis(fovDeg: Double = 10.0, rollDeg: Double = 0.0) =
        SkyProjection.basisOf(
            SkyProjection.equatorialVector(0.0, 0.0), 0.0, 0.0, 1.0, fovDeg, rollDeg,
        )

    @Test
    fun `a position angle of zero points at celestial north`() {
        val s = DeepSky.shapeOf(0.0, 0.0, 10.0, 5.0, 0.0, northUpBasis())!!
        // Screen y grows downward, so "up" is -90 degrees the way atan2 measures.
        assertEquals(-90.0, s.angleRad * 180.0 / PI, 1e-6)
    }

    @Test
    fun `east is to the left, which is what a sky chart means and what a sign error flips`() {
        // ⚠️ The single most valuable assertion here. A position angle runs from north THROUGH EAST,
        // and on a chart of the sky — seen from inside the sphere, not from outside like a map of the
        // ground — east with north up is to the LEFT. Getting the handedness wrong mirrors every
        // galaxy in the catalogue while looking entirely plausible.
        val s = DeepSky.shapeOf(0.0, 0.0, 10.0, 5.0, 90.0, northUpBasis())!!
        assertEquals(180.0, kotlin.math.abs(s.angleRad * 180.0 / PI), 1e-6)
        // Halfway round is halfway between: north-east is up and to the left.
        val ne = DeepSky.shapeOf(0.0, 0.0, 10.0, 5.0, 45.0, northUpBasis())!!
        assertEquals(-135.0, ne.angleRad * 180.0 / PI, 1e-6)
    }

    @Test
    fun `an object is drawn at its true angular size`() {
        // Ten arcminutes at a ten-degree field: the half-axis is 5' = 0.08333 degrees against a
        // half-field of 5, so about 0.016667 units. The projection is stereographic rather than
        // linear, so the measured value is very slightly smaller — and it is the measured one that
        // is correct.
        val s = DeepSky.shapeOf(0.0, 0.0, 10.0, 5.0, 0.0, northUpBasis())!!
        assertEquals(0.016656, s.semiMajorUnits, 1e-6)
        assertEquals(0.008328, s.semiMinorUnits, 1e-6)
    }

    @Test
    fun `the scale is local, so an object near the edge is drawn as the projection stretches it`() {
        // ⚠️ This is why the size comes from the projected probes rather than from fovDeg / 2. A
        // stereographic projection grows toward the edge; at a ten-degree field the growth is small
        // but real, and at a wide one it is not small.
        val centre = DeepSky.shapeOf(0.0, 0.0, 10.0, 10.0, 0.0, northUpBasis())!!
        val edge = DeepSky.shapeOf(4.9, 0.0, 10.0, 10.0, 0.0, northUpBasis())!!
        assertTrue(
            "the edge should be drawn larger, got ${edge.semiMajorUnits} vs ${centre.semiMajorUnits}",
            edge.semiMajorUnits > centre.semiMajorUnits,
        )
        // ...and by a small amount at this field, not a wild one.
        assertTrue(edge.semiMajorUnits < centre.semiMajorUnits * 1.01)
    }

    @Test
    fun `roll turns the picture and leaves the size alone`() {
        val plain = DeepSky.shapeOf(0.0, 0.0, 10.0, 5.0, 0.0, northUpBasis())!!
        val rolled = DeepSky.shapeOf(0.0, 0.0, 10.0, 5.0, 0.0, northUpBasis(rollDeg = 30.0))!!
        assertEquals(30.0, (rolled.angleRad - plain.angleRad) * 180.0 / PI, 1e-6)
        assertEquals(plain.semiMajorUnits, rolled.semiMajorUnits, 1e-12)
    }

    @Test
    fun `an object behind the viewer is refused rather than drawn somewhere plausible`() {
        assertNull(DeepSky.shapeOf(180.0, 0.0, 10.0, 5.0, 0.0, northUpBasis()))
    }

    @Test
    fun `the pole is not a special case`() {
        // ⚠️ The reason the north and east tangents are written out rather than taken as a finite
        // difference in right ascension: that difference divides by cos(declination), which is zero
        // here. The tangent vectors are exact everywhere.
        val basis = SkyProjection.basisOf(
            SkyProjection.equatorialVector(0.0, 88.0), 1.0, 0.0, 0.0, 10.0,
        )
        val s = DeepSky.shapeOf(0.0, 90.0, 10.0, 5.0, 45.0, basis)
        assertNotNull("the pole produced no shape", s)
        assertTrue(s!!.semiMajorUnits.isFinite() && s.semiMajorUnits > 0.0)
        assertTrue(s.angleRad.isFinite())
    }

    @Test
    fun `a missing minor axis draws round, and a longer one is clipped to the major`() {
        val round = DeepSky.shapeOf(0.0, 0.0, 10.0, null, null, northUpBasis())!!
        assertEquals(round.semiMajorUnits, round.semiMinorUnits, 1e-12)
        val wrong = DeepSky.shapeOf(0.0, 0.0, 10.0, 40.0, null, northUpBasis())!!
        assertEquals(wrong.semiMajorUnits, wrong.semiMinorUnits, 1e-12)
    }

    @Test
    fun `a shape below a few pixels is a marker instead`() {
        val half = 540.0
        assertTrue(DeepSky.drawsShape(DeepSky.SHAPE_MIN_PX / 2.0 / half, half))
        assertFalse(DeepSky.drawsShape(DeepSky.SHAPE_MIN_PX / 2.0 / half * 0.99, half))
    }

    // ---- labels ------------------------------------------------------------------------------

    @Test
    fun `only a named object is ever labelled`() {
        assertFalse(DeepSky.labels(entry(mag = 1.0), 20.0))
        assertTrue(DeepSky.labels(entry(mag = 1.0, label = "Andromeda Galaxy"), 20.0))
    }

    @Test
    fun `a name needs room, measured against the cut`() {
        val limit = 10.0
        assertTrue(DeepSky.labels(entry(mag = limit - DeepSky.LABEL_HEADROOM, label = "x"), limit))
        assertFalse(
            DeepSky.labels(entry(mag = limit - DeepSky.LABEL_HEADROOM + 0.01, label = "x"), limit),
        )
    }

    @Test
    fun `a named object with no brightness is labelled if it is drawn at all`() {
        // ⚠️ The opposite of what a headroom rule would do, and deliberately. The only way such an
        // object reaches the screen is by being conspicuously large, and the two that matter are the
        // Hyades and the Coma Star Cluster. Refusing to name the biggest things in the sky because
        // nobody measured their brightness would be the rule defeating its own purpose.
        assertTrue(DeepSky.labels(entry(major = 329.0, label = "Hyades"), 7.0))
    }
}
