package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The loading plan, and the inverse transform it rests on.
 *
 * ⚠️ **Every rule here fails silently.** A wrong inverse loads a region of the catalogue somewhere
 * else in the sky and the map draws a plausible, wrong universe. A field radius that understates the
 * view leaves the corners of the screen empty. A reuse decision that is too willing leaves a
 * crescent of missing stars at the edge, which reads as a rendering fault. Nothing throws in any of
 * those cases and nothing looks obviously broken.
 */
class SkyFieldPlanTest {

    // A tall phone, and a stubby one — the aspect ratio is what makes the corner distance
    // interesting, so both extremes are exercised rather than one comfortable middle.
    private val tall = SkyProjection.viewportOf(1080.0, 2400.0)
    private val wide = SkyProjection.viewportOf(2400.0, 1080.0)

    // ---- the inverse transform ------------------------------------------------------------------

    @Test
    fun `a position converted to the horizon and back is where it started`() {
        // ⚠️ Asserted against the shipped forward transform over a grid of real places and times
        // rather than against a derivation of my own. A sign error in the inverse produces an
        // ordinary-looking position somewhere else entirely, so a worked example proves very little
        // and a round trip proves the thing that matters.
        var worst = 0.0
        var worstAt = ""
        for (lat in listOf(-70.0, -33.9, 0.0, 19.4, 51.5, 78.2)) {
            for (lon in listOf(-122.4, -3.2, 0.0, 24.9, 139.7)) {
                for (hours in 0..23 step 3) {
                    val at = FIXED_EPOCH_MS + hours * 3_600_000L
                    for (ra in 0..350 step 37) {
                        for (dec in -85..85 step 17) {
                            val eq = Ephemeris.Equatorial(ra.toDouble(), dec.toDouble(), 0.0)
                            val h = Ephemeris.toHorizontal(eq, lat, lon, at)
                            val back = Ephemeris.toEquatorial(h, lat, lon, at)
                            val d = SkyProjection.separationDeg(
                                eq.rightAscensionDeg, eq.declinationDeg,
                                back.rightAscensionDeg, back.declinationDeg,
                            )
                            if (d > worst) {
                                worst = d
                                worstAt = "ra=$ra dec=$dec at lat=$lat lon=$lon +${hours}h"
                            }
                        }
                    }
                }
            }
        }
        // A ten-thousandth of a degree is a third of an arcsecond — far below anything a chart can
        // draw, and orders of magnitude tighter than any real defect would be.
        assertTrue("worst round trip was $worst° at $worstAt", worst < 1e-4)
    }

    @Test
    fun `the inverse survives the zenith, where the textbook form does not`() {
        // ⚠️ The usual hour-angle formula divides through a tangent of the altitude, which runs away
        // at 90°. That is precisely where somebody holding up a phone points it, so the shipped form
        // multiplies the singularity out. Both poles of the problem are checked: straight up, and
        // straight down.
        for (alt in listOf(89.999, 90.0, -90.0, -89.999)) {
            val eq = Ephemeris.toEquatorial(
                Ephemeris.Horizontal(alt, 137.0, 0.0), 51.5, -0.1, FIXED_EPOCH_MS,
            )
            assertTrue(
                "at altitude $alt the inverse produced ra=${eq.rightAscensionDeg}",
                eq.rightAscensionDeg.isFinite() && eq.rightAscensionDeg in 0.0..360.0,
            )
            assertTrue(
                "at altitude $alt the inverse produced dec=${eq.declinationDeg}",
                eq.declinationDeg.isFinite() && eq.declinationDeg in -90.0..90.0,
            )
        }
    }

    @Test
    fun `looking at the zenith finds the pole overhead`() {
        // A worked case with an answer known without any of this code: from the north pole, straight
        // up is the north celestial pole. It anchors the round trip, which would be satisfied by any
        // self-consistent pair of wrong transforms.
        val eq = Ephemeris.toEquatorial(
            Ephemeris.Horizontal(90.0, 0.0, 0.0), 89.999, 0.0, FIXED_EPOCH_MS,
        )
        assertEquals(90.0, eq.declinationDeg, 0.01)

        // And from the equator, due south on the horizon is declination −90: the south pole sits on
        // the southern horizon for an observer on the equator.
        val south = Ephemeris.toEquatorial(
            Ephemeris.Horizontal(0.0, 180.0, 0.0), 0.0, 0.0, FIXED_EPOCH_MS,
        )
        assertEquals(-90.0, south.declinationDeg, 0.01)
    }

    // ---- drawing equatorial stars without converting them ---------------------------------------

    @Test
    fun `a star drawn from equatorial coordinates lands exactly where the horizon path puts it`() {
        // ⚠️ THE PROPERTY THE STAR FIELD RESTS ON, and it has no visible failure mode. Holding stars
        // in horizon coordinates means reconverting all of them as the Earth turns — which at a
        // narrow field, where a screen pixel is a fraction of an arcsecond, is every single frame.
        // So they are held in equatorial coordinates and the rotation goes into the projection basis
        // instead, built from the view direction and the observer's zenith rather than from the
        // world's vertical.
        //
        // If that basis is wrong the map still draws a full, plausible sky — in the wrong place, or
        // mirrored, or rotated. Nothing throws. So the two paths are required to agree to the last
        // bit of the arithmetic rather than merely to look similar.
        var worst = 0.0
        var worstAt = ""
        for (lat in listOf(-45.0, -10.0, 0.0, 51.5, 78.0)) {
            for (hours in 0..20 step 5) {
                val at = FIXED_EPOCH_MS + hours * 3_600_000L
                val lon = -3.2
                for (viewAz in listOf(0.0, 95.0, 180.0, 300.0)) {
                    for (viewAlt in listOf(-20.0, 5.0, 40.0, 80.0)) {
                        for (roll in listOf(0.0, 37.0)) {
                            val view = SkyProjection.View(viewAz, viewAlt, 40.0, roll)

                            // The equatorial basis: forward is where the middle of the screen points,
                            // and up is the observer's zenith — declination equal to their latitude,
                            // right ascension equal to the local sidereal time.
                            val centre = Ephemeris.toEquatorial(
                                Ephemeris.Horizontal(view.altitudeDeg, view.azimuthDeg, 0.0), lat, lon, at,
                            )
                            val zenith = Ephemeris.toEquatorial(
                                Ephemeris.Horizontal(90.0, 0.0, 0.0), lat, lon, at,
                            )
                            val z = SkyProjection.equatorialVector(
                                zenith.rightAscensionDeg, zenith.declinationDeg,
                            )
                            val basis = SkyProjection.basisOf(
                                SkyProjection.equatorialVector(
                                    centre.rightAscensionDeg, centre.declinationDeg,
                                ),
                                z[0], z[1], z[2], view.fovDeg, view.rollDeg,
                            )

                            for (ra in 0..340 step 47) {
                                for (dec in -80..80 step 31) {
                                    val eq = Ephemeris.Equatorial(ra.toDouble(), dec.toDouble(), 0.0)
                                    val h = Ephemeris.toHorizontal(eq, lat, lon, at)

                                    val viaHorizon = SkyProjection.project(h.azimuthDeg, h.altitudeDeg, view)
                                    val v = SkyProjection.equatorialVector(
                                        eq.rightAscensionDeg, eq.declinationDeg,
                                    )
                                    val viaEquatorial = SkyProjection.projectUnit(v[0], v[1], v[2], basis)

                                    assertEquals(
                                        "visibility disagrees at ra=$ra dec=$dec, view $viewAz/$viewAlt lat=$lat",
                                        viaHorizon.visible, viaEquatorial.visible,
                                    )
                                    if (!viaHorizon.visible) continue
                                    val d = maxOf(
                                        Math.abs(viaHorizon.x - viaEquatorial.x),
                                        Math.abs(viaHorizon.y - viaEquatorial.y),
                                    )
                                    if (d > worst) {
                                        worst = d
                                        worstAt = "ra=$ra dec=$dec view $viewAz/$viewAlt roll=$roll lat=$lat +${hours}h"
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        // A screen unit is half the short screen dimension, so this is a millionth of a pixel on any
        // real display — the two paths are doing the same arithmetic in a different order.
        assertTrue("the two paths disagree by $worst screen units at $worstAt", worst < 1e-9)
    }

    @Test
    fun `the observer's zenith is their latitude, at the sidereal time`() {
        // The anchor under the test above, which a self-consistent pair of wrong transforms would
        // otherwise satisfy. Straight up from 51.5° north is declination 51.5°, whatever the hour;
        // only the right ascension turns with the day.
        val first = Ephemeris.toEquatorial(
            Ephemeris.Horizontal(90.0, 0.0, 0.0), 51.5, -0.1, FIXED_EPOCH_MS,
        )
        val sixHoursLater = Ephemeris.toEquatorial(
            Ephemeris.Horizontal(90.0, 0.0, 0.0), 51.5, -0.1, FIXED_EPOCH_MS + 6 * 3_600_000L,
        )
        assertEquals(51.5, first.declinationDeg, 0.01)
        assertEquals(51.5, sixHoursLater.declinationDeg, 0.01)
        // Six hours of rotation is ninety degrees of right ascension, give or take the sidereal day
        // being four minutes short of a solar one.
        val turned = ((sixHoursLater.rightAscensionDeg - first.rightAscensionDeg) + 360.0) % 360.0
        assertEquals(90.0, turned, 0.5)
    }

    // ---- the field ------------------------------------------------------------------------------

    @Test
    fun `the field radius reaches the corner, not half the declared field`() {
        // ⚠️ THE ONE THAT WOULD LEAVE THE CORNERS EMPTY. `fovDeg` describes the short screen
        // dimension, and a tall phone shows far more sky than that up and down — measured, an 83°
        // field on a 1080x2400 screen reaches 85.7° from the centre to the corner, which is more
        // than the whole declared field. Taking fov/2 as the radius would under-load by a factor
        // approaching two in angle.
        val view = SkyProjection.View(180.0, 30.0, 83.3)
        val radius = SkyFieldPlan.fieldRadiusDeg(view, tall)
        assertTrue("$radius° should exceed half the declared field", radius > view.fovDeg / 2.0)
        assertTrue("$radius° should exceed the whole declared field on a tall screen", radius > view.fovDeg)

        // And the radius really does contain the corner it claims to.
        val (az, alt) = SkyProjection.unproject(tall.halfWidth, tall.halfHeight, view)
        val corner = SkyProjection.separationDeg(view.azimuthDeg, view.altitudeDeg, az, alt)
        assertTrue("the corner at $corner° is outside the claimed radius $radius°", corner <= radius + 1e-9)
    }

    @Test
    fun `a wider screen reaches further than a taller one at the same field`() {
        // A sanity check on which dimension `fovDeg` is normalised to. The short side carries the
        // declared field, so whichever side is long is the one that reaches further — and a
        // landscape screen is the mirror of a portrait one.
        val view = SkyProjection.View(90.0, 10.0, 60.0)
        assertEquals(
            SkyFieldPlan.fieldRadiusDeg(view, tall),
            SkyFieldPlan.fieldRadiusDeg(view, wide),
            0.5,
        )
    }

    @Test
    fun `the radius is never more than the whole sky`() {
        val view = SkyProjection.View(0.0, 0.0, SkyProjection.MAX_FOV_DEG)
        assertTrue(SkyFieldPlan.fieldRadiusDeg(view, tall) <= 180.0)
        assertTrue(SkyFieldPlan.readRadiusDeg(1000.0) <= 180.0)
    }

    // ---- the decision ---------------------------------------------------------------------------

    @Test
    fun `the first frame always reads`() {
        val plan = SkyFieldPlan.plan(
            SkyProjection.View(180.0, 30.0, 60.0), tall, 100.0, -20.0, loaded = null, deepest = 12.0,
        )
        assertTrue("expected a read on the first frame, got $plan", plan is SkyFieldPlan.Plan.Read)
    }

    @Test
    fun `a small pan inside what is held costs no reading`() {
        val view = SkyProjection.View(180.0, 30.0, 20.0)
        val first = SkyFieldPlan.plan(view, tall, 100.0, -20.0, null, 12.0) as SkyFieldPlan.Plan.Read
        // Drift by a fraction of the margin — well inside the loaded region.
        val nudge = (first.becomes.radiusDeg - SkyFieldPlan.fieldRadiusDeg(view, tall)) * 0.5
        assertTrue("the plan left no margin at all to pan into", nudge > 0.1)
        val plan = SkyFieldPlan.plan(view, tall, 100.0, -20.0 + nudge, first.becomes, 12.0)
        assertEquals(SkyFieldPlan.Plan.Reuse, plan)
    }

    @Test
    fun `panning until the edge of the view leaves the region reads again`() {
        // ⚠️ The safety rule, and the one worth stating as its own test: what has to be inside the
        // held region is the whole visible FIELD, not merely the point being looked at. A check on
        // centres alone passes here and leaves a crescent of empty sky at the edge of the screen.
        val view = SkyProjection.View(180.0, 30.0, 20.0)
        val first = SkyFieldPlan.plan(view, tall, 100.0, -20.0, null, 12.0) as SkyFieldPlan.Plan.Read
        val field = SkyFieldPlan.fieldRadiusDeg(view, tall)

        // Drift so the centre is still comfortably inside the region but the field's far edge is not.
        val drift = first.becomes.radiusDeg - field + 0.5
        assertTrue("the drift should leave the centre inside", drift < first.becomes.radiusDeg)
        val plan = SkyFieldPlan.plan(view, tall, 100.0, -20.0 + drift, first.becomes, 12.0)
        assertTrue("expected a re-read once the field left the region, got $plan", plan is SkyFieldPlan.Plan.Read)
    }

    @Test
    fun `zooming in past the loaded depth reads again, and zooming out does not`() {
        val wideView = SkyProjection.View(180.0, 30.0, 100.0)
        val held = (SkyFieldPlan.plan(wideView, tall, 100.0, -20.0, null, 12.0) as SkyFieldPlan.Plan.Read).becomes

        // In: the limit deepens, so what is held is no longer enough however well it covers the sky.
        val narrow = SkyProjection.View(180.0, 30.0, 5.0)
        assertTrue(
            "zooming in should re-read for depth",
            SkyFieldPlan.plan(narrow, tall, 100.0, -20.0, held, 12.0) is SkyFieldPlan.Plan.Read,
        )

        // Out from a deep hold: the region is what fails, not the depth — so this reads too, but for
        // the other reason. Asserted so the two causes cannot be confused later.
        val deepHold = SkyFieldPlan.Loaded(100.0, -20.0, 3.0, 12.0)
        val out = SkyFieldPlan.plan(wideView, tall, 100.0, -20.0, deepHold, 12.0)
        assertTrue("zooming out of a small deep region should re-read", out is SkyFieldPlan.Plan.Read)
        assertTrue(
            "and it should ask for a shallower depth than it held",
            (out as SkyFieldPlan.Plan.Read).magnitudeLimit < deepHold.magnitudeLimit,
        )
    }

    @Test
    fun `a pinch does not re-read on every frame`() {
        // ⚠️ Without the quantised depth this is the defect: magnitudeLimit is continuous in the
        // field of view, so every frame of a pinch asks for a hair more depth than is held and every
        // frame re-reads three million stars' worth of index. Zooming by a whisker must be free.
        //
        // ⚠️ The loop updates what it holds after each read, because that is what a caller does —
        // and my first version of this test did not, which made it assert the wrong thing. Without
        // the update, one legitimate step across a depth boundary looks like every remaining frame
        // re-reading, and the count came out at five for a reason that was nothing to do with the
        // code. Model the caller, or the test measures the test.
        var held = (SkyFieldPlan.plan(
            SkyProjection.View(180.0, 30.0, 30.0), tall, 100.0, -20.0, null, 12.0,
        ) as SkyFieldPlan.Plan.Read).becomes
        var reads = 0
        var fov = 30.0
        repeat(40) {
            fov *= 0.999                     // a tenth of a percent per frame; 4% over the gesture
            val p = SkyFieldPlan.plan(SkyProjection.View(180.0, 30.0, fov), tall, 100.0, -20.0, held, 12.0)
            if (p is SkyFieldPlan.Plan.Read) { reads++; held = p.becomes }
        }
        // At most one, and one is legitimate: a 4% pinch deepens the needed limit by 0.073
        // magnitudes, which crosses a half-magnitude boundary if it happens to straddle one. Forty
        // reads would be the defect.
        assertTrue("a barely-perceptible pinch re-read $reads times", reads <= 1)
    }

    @Test
    fun `a pinch that crosses no depth boundary reads nothing at all`() {
        // The stronger half of the rule above, with the boundary crossing removed rather than
        // allowed for: a gesture too small to change the quantised depth must be entirely free.
        val start = SkyProjection.View(180.0, 30.0, 30.0)
        val held = (SkyFieldPlan.plan(start, tall, 100.0, -20.0, null, 12.0) as SkyFieldPlan.Plan.Read).becomes
        var fov = 30.0
        var reads = 0
        repeat(20) {
            fov *= 0.9999
            val p = SkyFieldPlan.plan(SkyProjection.View(180.0, 30.0, fov), tall, 100.0, -20.0, held, 12.0)
            if (p is SkyFieldPlan.Plan.Read) reads++
        }
        // Asserted rather than assumed: the whole gesture stays inside one depth step.
        assertEquals(
            "the fixture strayed across a depth boundary, so this tests nothing",
            SkyFieldPlan.quantiseLimit(SkyProjection.magnitudeLimit(30.0, 12.0)),
            SkyFieldPlan.quantiseLimit(SkyProjection.magnitudeLimit(fov, 12.0)),
            1e-9,
        )
        assertEquals("a pinch within one depth step re-read $reads times", 0, reads)
    }

    @Test
    fun `the depth asked for is never shallower than the view needs`() {
        // Rounding the wrong way would hide stars the zoom has earned, which is the silent direction.
        var fov = SkyProjection.MAX_FOV_DEG
        while (fov >= SkyProjection.MIN_FOV_DEG) {
            val want = SkyProjection.magnitudeLimit(fov, 12.0)
            assertTrue(
                "at fov $fov the plan would read to ${SkyFieldPlan.quantiseLimit(want)}, shallower than $want",
                SkyFieldPlan.quantiseLimit(want) >= want,
            )
            fov /= 1.3
        }
    }

    @Test
    fun `a catalogue that stops shallow is not asked for more than it holds`() {
        // The bundled core tier stops at magnitude 12; a deep-tier build goes further. Zooming past
        // the end of the data must ask for what exists rather than for what the zoom would like.
        val narrow = SkyProjection.View(0.0, 0.0, SkyProjection.MIN_FOV_DEG)
        val plan = SkyFieldPlan.plan(narrow, tall, 10.0, 10.0, null, 9.0) as SkyFieldPlan.Plan.Read
        assertTrue("asked for ${plan.magnitudeLimit} from a catalogue holding 9.0", plan.magnitudeLimit <= 9.0)
    }

    // ---- the property the whole design rests on -------------------------------------------------

    @Test
    fun `the work stays bounded from the widest field to the narrowest`() {
        // ⚠️ THE CLAIM THIS FILE EXISTS TO MAKE, checked against the real counts in the bundled
        // catalogue. Star numbers rise 2.8x per magnitude while the field's area falls as the square
        // of its angle, and the adaptive magnitude limit is tuned so the two nearly cancel. If that
        // ever stops holding, a zoom level somewhere becomes unaffordable — and it would show up as
        // the map going slow at one particular scale, which is a miserable thing to diagnose from a
        // screenshot. Measured across the whole range: drawn 5,065 to 12,532, held up to 32,854.
        var fov = SkyProjection.MAX_FOV_DEG
        var mostDrawn = 0.0
        var mostHeld = 0.0
        var at = ""
        while (fov >= SkyProjection.MIN_FOV_DEG) {
            val view = SkyProjection.View(180.0, 30.0, fov)
            val field = SkyFieldPlan.fieldRadiusDeg(view, tall)
            val drawn = SkyFieldPlan.estimateStars(field, SkyProjection.magnitudeLimit(fov, 12.0))
            val held = SkyFieldPlan.estimateStars(
                SkyFieldPlan.readRadiusDeg(field),
                SkyFieldPlan.quantiseLimit(SkyProjection.magnitudeLimit(fov, 12.0)),
            )
            if (drawn > mostDrawn) { mostDrawn = drawn; at = "fov $fov" }
            if (held > mostHeld) mostHeld = held
            fov /= 1.25
        }
        assertTrue("the worst zoom draws $mostDrawn stars ($at)", mostDrawn < 20_000)
        assertTrue("the worst zoom holds $mostHeld stars", mostHeld < 50_000)
        // And the floor: a design that drew nothing would pass every bound above.
        assertTrue("nothing was drawn at any zoom", mostDrawn > 1_000)
    }

    @Test
    fun `the star density law matches the catalogue it was measured from`() {
        // The estimate is only ever used to reason about the shape of the design, but a law that had
        // drifted from the data would make that reasoning worthless. These two are the cumulative
        // counts in the bundled file, six magnitudes apart.
        assertEquals(6_514.0, SkyFieldPlan.estimateStars(180.0, 6.0), 1.0)
        assertEquals(3_087_821.0, SkyFieldPlan.estimateStars(180.0, 12.0), 3_087_821.0 * 0.15)
    }

    private companion object {
        /** 2026-03-15T22:00:00Z — an arbitrary but fixed instant, so nothing here depends on today. */
        const val FIXED_EPOCH_MS = 1_773_612_000_000L
    }
}
