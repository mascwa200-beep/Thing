package dev.mascwa.pulse.core.telemetry

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ⚠️ **Every expectation here was computed by running the shipped functions first**
 * (`scratchpad/sky/PointingProbe.kt`), and the roll sign was measured against the real Android
 * sensor maths before that (`scratchpad/sky/PointProbe.kt`). Nothing below is a recollection.
 */
class SkyPointingTest {

    private val fov = 60.0

    /** London, and three fixed instants — a clock read at test time would make a failure unrepeatable. */
    private val LAT = 51.5074
    private val LON = -0.1278
    private val EPOCHS = longArrayOf(1_700_000_000_000L, 1_711_000_000_000L, 1_735_689_600_000L)

    private fun unit(azDeg: Double, altDeg: Double): DoubleArray {
        val a = Math.toRadians(azDeg)
        val h = Math.toRadians(altDeg)
        return doubleArrayOf(cos(h) * sin(a), cos(h) * cos(a), sin(h))
    }

    private fun len(v: DoubleArray) = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])

    private fun angleBetween(a: DoubleArray, b: DoubleArray): Double =
        Math.toDegrees(acos((a[0] * b[0] + a[1] * b[1] + a[2] * b[2]).coerceIn(-1.0, 1.0)))

    // ------------------------------------------------------------------ the attitude

    @Test
    fun `the two directions are always a unit pair at right angles`() {
        // Measured over 5,616 attitudes covering every azimuth, every altitude from pole to pole and
        // six rolls including a half-turn: worst |f dot u| 1.8e-16, worst |len - 1| 3.3e-16.
        val f = DoubleArray(3)
        val u = DoubleArray(3)
        for (az in 0 until 360 step 7) {
            for (alt in -90..90 step 5) {
                for (roll in intArrayOf(0, 17, -33, 90, -90, 179)) {
                    val a = SkyPointing.Attitude(az.toDouble(), alt.toDouble(), roll.toDouble())
                    SkyPointing.forward(a, f)
                    SkyPointing.screenUp(a, u)
                    val label = "az $az alt $alt roll $roll"
                    assertEquals("forward length, $label", 1.0, len(f), 1e-12)
                    assertEquals("up length, $label", 1.0, len(u), 1e-12)
                    assertEquals(
                        "perpendicular, $label",
                        0.0,
                        f[0] * u[0] + f[1] * u[1] + f[2] * u[2],
                        1e-12,
                    )
                }
            }
        }
    }

    @Test
    fun `with no roll the screen up is the look direction a quarter turn higher`() {
        // The claim the class note makes, and the reason there is no singularity: measured worst
        // component difference 2.8e-16 over the whole sweep.
        val u = DoubleArray(3)
        for (az in 0 until 360 step 7) {
            for (alt in -90..90 step 5) {
                SkyPointing.screenUp(SkyPointing.Attitude(az.toDouble(), alt.toDouble(), 0.0), u)
                val q = unit(az.toDouble(), alt + 90.0)
                for (i in 0..2) assertEquals("component $i at az $az alt $alt", q[i], u[i], 1e-12)
            }
        }
    }

    @Test
    fun `tipping the top of the handset right turns the screen up toward the screen right`() {
        // Aimed north and upright, screen-up is straight up and screen-right is east. A quarter turn
        // clockwise should therefore put screen-up due east.
        val u = DoubleArray(3)
        SkyPointing.screenUp(SkyPointing.Attitude(0.0, 0.0, 90.0), u)
        assertEquals("east", 1.0, u[0], 1e-12)
        assertEquals("north", 0.0, u[1], 1e-12)
        assertEquals("up", 0.0, u[2], 1e-12)
    }

    @Test
    fun `the pitch is turned round and the roll is not`() {
        // ⚠️ The roll used to be negated here as well, which put the negation in twice — see
        // `fromDeviceOrientation`'s own note and the round-trip test below, which is what holds it.
        val a = SkyPointing.fromDeviceOrientation(123.0, -45.0, 30.0)
        assertEquals(123.0, a.azimuthDeg, 0.0)
        assertEquals(45.0, a.altitudeDeg, 0.0)
        assertEquals(30.0, a.rollDeg, 0.0)
    }

    /**
     * What `SensorManager.getOrientation` answers for an attitude, after the camera-upright remap.
     *
     * ⚠️ **These three lines are the ONLY thing here taken on trust, and they are not taken on trust
     * from me.** `scratchpad/sky/PoleProbe.kt` drives the real `remapCoordinateSystem` and
     * `getOrientation` out of `org.robolectric:android-all` over the same attitudes and agrees with
     * the composition to 7.0e-06° at aims out to 89.99°. That jar is 186 MB and has no business on a
     * pure JVM module's test classpath, so the probe validates and this carries.
     *
     * The remap makes the matrix's second column the camera's forward direction and its third the
     * screen-up, which is where each formula comes from: the azimuth and pitch read the aim, and the
     * roll compares the up-components of screen-right and screen-up.
     */
    private fun reportedFor(f: DoubleArray, u: DoubleArray): Triple<Double, Double, Double> {
        val right = cross(f, u)
        return Triple(
            Math.toDegrees(Math.atan2(f[0], f[1])),
            Math.toDegrees(Math.asin((-f[2]).coerceIn(-1.0, 1.0))),
            Math.toDegrees(Math.atan2(-right[2], u[2])),
        )
    }

    private fun cross(a: DoubleArray, b: DoubleArray) = doubleArrayOf(
        a[1] * b[2] - a[2] * b[1],
        a[2] * b[0] - a[0] * b[2],
        a[0] * b[1] - a[1] * b[0],
    )

    /** [p] turned [deg] about [f] — a full circle of valid screen-up directions at any aim. */
    private fun turnAbout(f: DoubleArray, p: DoubleArray, deg: Double): DoubleArray {
        val q = cross(f, p)
        val c = cos(Math.toRadians(deg))
        val s = sin(Math.toRadians(deg))
        val v = doubleArrayOf(p[0] * c + q[0] * s, p[1] * c + q[1] * s, p[2] * c + q[2] * s)
        val n = len(v)
        return doubleArrayOf(v[0] / n, v[1] / n, v[2] / n)
    }

    @Test
    fun `the sensor's roll is read back the way round the handset is really held`() {
        // ⚠️ **A ROUND TRIP against ground truth, which is what the older tests were missing.** They
        // compared the vector path against the angle path — both built from the same Attitude — so
        // they agreed perfectly while the sign that reached them was turned round twice. Here the
        // attitude is chosen first, the sensor's numbers are derived from it, and the reconstruction
        // has to come back to the attitude we started with.
        //
        // ⚠️ The aims go to 89.99°, where the older cases stopped at 70°. The failure this catches
        // is at its worst exactly where nothing used to look.
        val f = DoubleArray(3)
        val u = DoubleArray(3)
        var worst = 0.0
        for (az in intArrayOf(0, 47, 123, 231, 315)) {
            for (alt in doubleArrayOf(0.0, 30.0, 70.0, 85.0, 89.0, 89.99, -45.0, -89.0, -89.9)) {
                val aim = unit(az.toDouble(), alt)
                // A perpendicular that is defined at the pole too, where "the vertical with the look
                // direction taken off" is the zero vector.
                val seed = if (abs(aim[0]) < 0.9) doubleArrayOf(1.0, 0.0, 0.0) else doubleArrayOf(0.0, 1.0, 0.0)
                val perp = cross(aim, seed).let { val n = len(it); doubleArrayOf(it[0] / n, it[1] / n, it[2] / n) }
                for (turn in doubleArrayOf(0.0, 37.0, 90.0, -115.0, 179.0)) {
                    val up = turnAbout(aim, perp, turn)
                    val (azDeg, pitchDeg, rollDeg) = reportedFor(aim, up)
                    val a = SkyPointing.fromDeviceOrientation(azDeg, pitchDeg, rollDeg)
                    SkyPointing.forward(a, f)
                    SkyPointing.screenUp(a, u)
                    val where = "az $az alt $alt turn $turn"
                    // ⚠️ The tolerance is 1e-4 and not 1e-6 because `angleBetween` is an `acos` of a
                    // dot product, and near 1 that CANNOT resolve better than sqrt(eps) — about
                    // 8.5e-07 degrees. My first version asserted 1e-6 and one case came in at
                    // 1.2e-6, which is the measurement's own floor rather than a defect. The bound
                    // still separates right from wrong by six orders of magnitude: with the roll
                    // negated these come in at 180 degrees.
                    assertEquals("aim at $where", 0.0, angleBetween(aim, f), 1e-4)
                    val off = angleBetween(up, u)
                    worst = maxOf(worst, off)
                    assertEquals("screen-up at $where", 0.0, off, 1e-4)
                }
            }
        }
        assertTrue("worst $worst", worst < 1e-4)
    }

    // ------------------------------------------------------------------ the two paths agree

    @Test
    fun `the vector path draws exactly what the angle path draws`() {
        // ⚠️ **This proves the two paths agree with EACH OTHER and nothing more, which is exactly
        // how a doubled sign survived in both.** Both bases below are built from the same
        // `Attitude`, so whatever reaches it reaches them equally and they cannot disagree however
        // wrong it is. It is still worth having — `equivalentView` negates the roll on its way to a
        // `View` and the vector path needs no sign at all, so the two conventions really do have to
        // be held together — but the statement about the SENSOR is
        // `the sensor's roll is read back the way round the handset is really held`, above.
        // Measured worst here: 9.0e-16.
        val targets = ArrayList<DoubleArray>()
        for (az in 0 until 360 step 10) {
            for (alt in -80..80 step 10) targets.add(unit(az.toDouble(), alt.toDouble()))
        }
        val f = DoubleArray(3)
        val u = DoubleArray(3)
        for (az in 0 until 360 step 30) {
            for (alt in intArrayOf(-89, -60, -20, 0, 20, 60, 89)) {
                for (roll in intArrayOf(0, 17, -33, 90, -90, 179)) {
                    val a = SkyPointing.Attitude(az.toDouble(), alt.toDouble(), roll.toDouble())
                    SkyPointing.forward(a, f)
                    SkyPointing.screenUp(a, u)
                    val vector = SkyProjection.basisOf(f, u[0], u[1], u[2], fov, 0.0)
                    val angles = SkyProjection.basisOf(SkyPointing.equivalentView(a, fov))
                    for (t in targets) {
                        val p = SkyProjection.projectUnit(t[0], t[1], t[2], vector)
                        val q = SkyProjection.projectUnit(t[0], t[1], t[2], angles)
                        if (!p.visible || !q.visible) continue
                        if (hypot(p.x, p.y) > 1.5) continue
                        assertEquals("x at az $az alt $alt roll $roll", q.x, p.x, 1e-9)
                        assertEquals("y at az $az alt $alt roll $roll", q.y, p.y, 1e-9)
                    }
                }
            }
        }
    }

    @Test
    fun `aimed at the zenith only the screen up gives a usable basis`() {
        // ⚠️ THE WHOLE ARGUMENT FOR THE VECTOR PATH, asserted rather than left as a remark. A basis
        // is `forward x up`; with up = the observer's zenith and the look direction AT the zenith
        // that cross product is the zero vector, `usable` goes false, and a map built that way draws
        // nothing at all. The screen-up is perpendicular to the look direction by construction, so
        // it cannot happen.
        val f = DoubleArray(3)
        val u = DoubleArray(3)
        val straightUp = SkyPointing.Attitude(37.0, 90.0, 0.0)
        SkyPointing.forward(straightUp, f)
        SkyPointing.screenUp(straightUp, u)
        assertTrue("screen-up basis", SkyProjection.basisOf(f, u[0], u[1], u[2], fov, 0.0).usable)
        assertFalse("zenith basis", SkyProjection.basisOf(f, 0.0, 0.0, 1.0, fov, 0.0).usable)
    }

    @Test
    fun `the angle path clamps at the pole and the vector path does not`() {
        // Documents the one place the two deliberately differ, so nobody "fixes" it into agreement.
        val a = SkyPointing.Attitude(0.0, 90.0, 0.0)
        assertEquals(SkyProjection.MAX_ALTITUDE_DEG, SkyPointing.equivalentView(a, fov).altitudeDeg, 0.0)
        val f = DoubleArray(3)
        SkyPointing.forward(a, f)
        assertEquals(90.0, SkyPointing.altitudeOf(f), 1e-9)
    }

    // ------------------------------------------------- into the stars' own frame

    @Test
    fun `turning the pair into the stars frame keeps it a unit pair at right angles`() {
        // ⚠️ THE PROPERTY THE POINTED BASIS RESTS ON. `SkyFrame` builds `forward x up`, and both of
        // those reach it in east/north/up. `Ephemeris.toEquatorial` is a pure rotation with no
        // refraction in it, so the pair has to survive as a unit pair at right angles — and if it did
        // not, nothing would fail: the map would draw very slightly sheared and no error would ever
        // be reported. Measured over 2,592 (attitude, epoch) pairs spanning three epochs, every
        // azimuth, altitudes from -80 to the zenith and eight rolls: worst |f dot u| 2.4e-15, worst
        // |len - 1| 2.2e-16.
        val f = DoubleArray(3)
        val u = DoubleArray(3)
        val ef = DoubleArray(3)
        val eu = DoubleArray(3)
        for (epoch in EPOCHS) {
            var az = 0
            while (az < 360) {
                var alt = -80
                while (alt <= 90) {
                    var roll = -180
                    while (roll < 180) {
                        val a = SkyPointing.Attitude(az.toDouble(), alt.toDouble(), roll.toDouble())
                        SkyPointing.forward(a, f)
                        SkyPointing.screenUp(a, u)
                        SkyPointing.toEquatorialVector(f, LAT, LON, epoch, ef)
                        SkyPointing.toEquatorialVector(u, LAT, LON, epoch, eu)
                        val dot = ef[0] * eu[0] + ef[1] * eu[1] + ef[2] * eu[2]
                        assertEquals("perpendicular at $a", 0.0, dot, 1e-12)
                        assertEquals("look is a unit vector at $a", 1.0, len(ef), 1e-12)
                        assertEquals("up is a unit vector at $a", 1.0, len(eu), 1e-12)
                        roll += 47
                    }
                    alt += 17
                }
                az += 37
            }
        }
    }

    @Test
    fun `straight up is the observer's own latitude, and due north at that altitude is the pole`() {
        // Two facts nothing here computes and every textbook states, so they pin the rotation against
        // something outside this code: what is overhead has a declination equal to the latitude, and
        // the celestial pole stands due north at an altitude equal to it.
        //
        // ⚠️ The second is here because I first wrote it as "due north at 90 minus the latitude",
        // which is out by a long way — the probe answers 76.9852 for that, not 90, since the sine
        // doubles the angle. Nineteenth time in this project that an expectation of mine was wrong
        // where the code was right.
        val up = DoubleArray(3)
        val v = DoubleArray(3)
        for (epoch in EPOCHS) {
            SkyPointing.forward(SkyPointing.Attitude(0.0, 90.0, 0.0), up)
            SkyPointing.toEquatorialVector(up, LAT, LON, epoch, v)
            assertEquals("overhead", LAT, Math.toDegrees(Math.asin(v[2].coerceIn(-1.0, 1.0))), 1e-9)

            SkyPointing.forward(SkyPointing.Attitude(0.0, LAT, 0.0), up)
            SkyPointing.toEquatorialVector(up, LAT, LON, epoch, v)
            assertEquals("the pole", 90.0, Math.toDegrees(Math.asin(v[2].coerceIn(-1.0, 1.0))), 1e-9)
        }
    }

    @Test
    fun `the altitude and the azimuth do not get handed over the wrong way round`() {
        // `Ephemeris.Horizontal(altitudeDeg, azimuthDeg, distanceKm)` takes two angles of the same
        // type in a fixed order, so swapping them compiles, runs, and points somewhere else entirely.
        // Measured: 60 degrees away for this attitude, against 0.00e+00 for the order that ships.
        val f = DoubleArray(3)
        val v = DoubleArray(3)
        SkyPointing.forward(SkyPointing.Attitude(120.0, 30.0, 0.0), f)
        SkyPointing.toEquatorialVector(f, LAT, LON, EPOCHS[0], v)

        val right = Ephemeris.toEquatorial(Ephemeris.Horizontal(30.0, 120.0, 0.0), LAT, LON, EPOCHS[0])
        val swapped = Ephemeris.toEquatorial(Ephemeris.Horizontal(120.0, 30.0, 0.0), LAT, LON, EPOCHS[0])
        val rv = SkyProjection.equatorialVector(right.rightAscensionDeg, right.declinationDeg)
        val sv = SkyProjection.equatorialVector(swapped.rightAscensionDeg, swapped.declinationDeg)
        assertEquals("as shipped", 0.0, angleBetween(v, rv), 1e-9)
        assertEquals("the swap this guards against", 60.0, angleBetween(v, sv), 1e-6)
    }

    @Test
    fun `the seam at the zenith is gone in the stars frame too, not only in the handset's`() {
        // The ENU half of this is asserted above; this is the half that matters, because the basis
        // the renderer actually builds is the equatorial one. Aimed straight up, the pair rotated out
        // of the handset's frame still gives a usable basis, and the observer's zenith — which is
        // what `SkyFrame.of` passes — still does not.
        val f = DoubleArray(3)
        val u = DoubleArray(3)
        val ef = DoubleArray(3)
        val eu = DoubleArray(3)
        val epoch = EPOCHS[0]
        val straightUp = SkyPointing.Attitude(37.0, 90.0, 0.0)
        SkyPointing.forward(straightUp, f)
        SkyPointing.screenUp(straightUp, u)
        SkyPointing.toEquatorialVector(f, LAT, LON, epoch, ef)
        SkyPointing.toEquatorialVector(u, LAT, LON, epoch, eu)

        val zenith = Ephemeris.toEquatorial(Ephemeris.Horizontal(90.0, 0.0, 0.0), LAT, LON, epoch)
        val zv = SkyProjection.equatorialVector(zenith.rightAscensionDeg, zenith.declinationDeg)

        assertTrue("screen-up basis", SkyProjection.basisOf(ef, eu[0], eu[1], eu[2], fov, 0.0).usable)
        assertFalse("zenith basis", SkyProjection.basisOf(ef, zv[0], zv[1], zv[2], fov, 0.0).usable)
    }

    // ------------------------------------------------------------------ smoothing

    @Test
    fun `a blended attitude is still a valid attitude`() {
        // Measured over 16 blends: worst |f dot u| 1.1e-16, worst |len - 1| 1.1e-16.
        val prevF = DoubleArray(3)
        val prevU = DoubleArray(3)
        val f = DoubleArray(3)
        val u = DoubleArray(3)
        for (az in 0 until 360 step 23) {
            val p = SkyPointing.Attitude(az.toDouble(), 20.0, 10.0)
            SkyPointing.forward(p, prevF)
            SkyPointing.screenUp(p, prevU)
            SkyPointing.smooth(prevF, prevU, SkyPointing.Attitude(az + 8.0, 25.0, -14.0), 0.25, f, u)
            assertEquals("forward length at $az", 1.0, len(f), 1e-12)
            assertEquals("up length at $az", 1.0, len(u), 1e-12)
            assertEquals("perpendicular at $az", 0.0, f[0] * u[0] + f[1] * u[1] + f[2] * u[2], 1e-12)
        }
    }

    @Test
    fun `blending directions does not whip the picture round near the zenith`() {
        // ⚠️ THE REASON THIS SMOOTHS VECTORS RATHER THAN ANGLES. At altitude 89.9 the azimuths 0 and
        // 180 are two readings only 0.2 degrees apart in aim — a centimetre of hand movement — yet
        // their azimuths are a half-turn apart. A smoother averaging the azimuth lands on 90, which
        // turns the screen-up by a measured 90 degrees for that 0.2 degree change. Blending the
        // directions cannot do that: the look direction stays within the 0.2 degrees the two
        // readings actually span.
        val a0 = SkyPointing.Attitude(0.0, 89.9, 0.0)
        val a1 = SkyPointing.Attitude(180.0, 89.9, 0.0)
        val f0 = DoubleArray(3)
        val u0 = DoubleArray(3)
        val f1 = DoubleArray(3)
        SkyPointing.forward(a0, f0)
        SkyPointing.screenUp(a0, u0)
        SkyPointing.forward(a1, f1)
        assertEquals("the two readings really are this close", 0.2, angleBetween(f0, f1), 1e-6)

        // What the rejected design does, stated as a number so the comparison is not rhetorical.
        // ⚠️ 89.99983, not 90 — and my first assertion here said 90 because I read it off a probe
        // that printed one decimal place. It is a hair under a right angle because the altitude is
        // 89.9 rather than the pole itself, which leaves the two screen-ups a little out of the
        // horizontal plane. Pinned at the measured value rather than at the round one.
        val perAngleUp = DoubleArray(3)
        SkyPointing.screenUp(SkyPointing.Attitude(90.0, 89.9, 0.0), perAngleUp)
        assertEquals("averaging the azimuth turns the picture", 89.99983, angleBetween(u0, perAngleUp), 1e-4)

        val f = DoubleArray(3)
        val u = DoubleArray(3)
        SkyPointing.smooth(f0, u0, a1, 0.5, f, u)
        assertTrue(
            "blended aim ${angleBetween(f, f0)} left the span the readings cover",
            angleBetween(f, f0) <= 0.2 + 1e-6 && angleBetween(f, f1) <= 0.2 + 1e-6,
        )
    }

    @Test
    fun `a half turn between frames keeps the newest reading rather than nothing`() {
        // ⚠️ Two exactly opposite readings blend to a zero vector, and a normalised zero vector is an
        // unusable basis — a BLANK MAP. At any real frame rate that is a sensor glitch, so the fresh
        // reading stands. Asserted on both vectors because they degenerate independently.
        val prevF = DoubleArray(3)
        val prevU = DoubleArray(3)
        val f = DoubleArray(3)
        val u = DoubleArray(3)
        val freshF = DoubleArray(3)
        val freshU = DoubleArray(3)
        val north = SkyPointing.Attitude(0.0, 0.0, 0.0)
        val south = SkyPointing.Attitude(180.0, 0.0, 0.0)
        SkyPointing.forward(north, prevF)
        SkyPointing.screenUp(north, prevU)
        SkyPointing.forward(south, freshF)
        SkyPointing.screenUp(south, freshU)
        SkyPointing.smooth(prevF, prevU, south, 0.5, f, u)
        for (i in 0..2) {
            assertEquals("forward $i", freshF[i], f[i], 1e-12)
            assertEquals("up $i", freshU[i], u[i], 1e-12)
        }
        assertEquals("and it is still usable", 1.0, len(f), 1e-12)
    }

    @Test
    fun `the handset spun in its own plane keeps the newest reading too`() {
        // ⚠️ THE CASE THE TEST ABOVE CANNOT REACH, and a perturbation run found that out: with the
        // look directions also opposite, the FORWARD guard returns first and the screen-up guard is
        // never exercised. Deleting it failed nothing. Here the aim does not move at all — the
        // handset is spun a half-turn in its own plane between two frames — so the forward blend is
        // perfectly healthy and only the up vector cancels.
        val prevF = DoubleArray(3)
        val prevU = DoubleArray(3)
        val f = DoubleArray(3)
        val u = DoubleArray(3)
        val freshU = DoubleArray(3)
        val upright = SkyPointing.Attitude(0.0, 0.0, 0.0)
        val inverted = SkyPointing.Attitude(0.0, 0.0, 180.0)
        SkyPointing.forward(upright, prevF)
        SkyPointing.screenUp(upright, prevU)
        SkyPointing.screenUp(inverted, freshU)
        assertEquals("the two screen-ups really are opposite", 180.0, angleBetween(prevU, freshU), 1e-6)

        SkyPointing.smooth(prevF, prevU, inverted, 0.5, f, u)
        for (i in 0..2) assertEquals("up $i", freshU[i], u[i], 1e-12)
        assertEquals("still a unit vector", 1.0, len(u), 1e-12)
        assertEquals("still square to the aim", 0.0, f[0] * u[0] + f[1] * u[1] + f[2] * u[2], 1e-12)
    }

    @Test
    fun `the two ends of the smoothing range do what they say`() {
        val prevF = DoubleArray(3)
        val prevU = DoubleArray(3)
        val f = DoubleArray(3)
        val u = DoubleArray(3)
        val freshF = DoubleArray(3)
        val here = SkyPointing.Attitude(0.0, 0.0, 0.0)
        val there = SkyPointing.Attitude(90.0, 30.0, 12.0)
        SkyPointing.forward(here, prevF)
        SkyPointing.screenUp(here, prevU)
        SkyPointing.forward(there, freshF)

        SkyPointing.smooth(prevF, prevU, there, 1.0, f, u)
        for (i in 0..2) assertEquals("alpha 1 takes the reading, $i", freshF[i], f[i], 0.0)

        SkyPointing.smooth(prevF, prevU, there, 0.0, f, u)
        assertEquals("alpha 0 stays put", 0.0, angleBetween(f, prevF), 1e-9)
    }

    @Test
    fun `a blend really blends when the caller aliases its arrays`() {
        // ⚠️ **THE CALL SITE'S OWN PATTERN, which is the one no other test here uses.** The view
        // model keeps a single pair of arrays and passes them as both `prev` and `out`, because that
        // is what the signature invites and it allocates nothing. Written the obvious way, `smooth`
        // wrote the fresh reading into `out` before reading `prev` — so every blend became
        // `new·(1−w) + new·w`, the weight did nothing at all, and the map followed raw sensor
        // jitter. Every other test below passes distinct arrays and could never have seen it.
        val f = DoubleArray(3)
        val u = DoubleArray(3)
        val here = SkyPointing.Attitude(0.0, 0.0, 0.0)
        val there = SkyPointing.Attitude(40.0, 0.0, 0.0)
        SkyPointing.forward(here, f)
        SkyPointing.screenUp(here, u)

        SkyPointing.smooth(f, u, there, 0.25, f, u)

        val fresh = DoubleArray(3)
        SkyPointing.forward(there, fresh)
        val movedTowardTheReading = angleBetween(f, unit(0.0, 0.0))
        assertTrue("it has to move at all: $movedTowardTheReading", movedTowardTheReading > 1.0)
        assertTrue(
            "a quarter weight must not arrive: ${angleBetween(f, fresh)}",
            angleBetween(f, fresh) > 1.0,
        )
        // ⚠️ A quarter of the way round a 40 degree turn is NOT 10 degrees, and asserting that is
        // how this test first failed. The blend is of the CHORD and then normalised, so the angle
        // is atan(0.25 sin40 / (0.75 + 0.25 cos40)) = 9.6859 degrees. Computed from the shipped
        // arithmetic, not from the shape of the number.
        assertEquals("a quarter of the way", 9.6859, movedTowardTheReading, 1e-3)
    }

    // ------------------------------------------------------------------ the pole damping

    @Test
    fun `the up weight is untouched away from the pole and stiffer at it`() {
        // Below the ramp nothing changes at all, which is what makes this safe to ship blind.
        for (alt in doubleArrayOf(0.0, 30.0, 60.0, 74.9, -74.9)) {
            assertEquals("alt $alt", 0.25, SkyPointing.upAlpha(0.25, alt), 0.0)
        }
        // At the pole the filter runs POLE_MAX_STRETCH times longer, which is a smaller weight:
        // 1 - 0.75^(1/8) = 0.03532137. Computed rather than eyeballed — my first value was 0.035426,
        // which is simply wrong arithmetic and the code was right.
        assertEquals(0.03532137, SkyPointing.upAlpha(0.25, 90.0), 1e-8)
        assertEquals("straight down is the same", 0.03532137, SkyPointing.upAlpha(0.25, -90.0), 1e-8)
        // ⚠️ Both ends answer unchanged. 1 is what the caller passes for the very first reading and
        // stiffening it would blend that against the arrays' starting values — the swept-in-from-north
        // defect the first-reading branch exists to prevent.
        assertEquals(1.0, SkyPointing.upAlpha(1.0, 90.0), 0.0)
        assertEquals(0.0, SkyPointing.upAlpha(0.0, 90.0), 0.0)
    }

    @Test
    fun `the stiffening ramps smoothly rather than stepping`() {
        // ⚠️ **What smoothstep buys over a plain ramp is a slope that starts at zero, and only the
        // SECOND difference can see that.** My first version asserted the largest single step, which
        // separates the two by accident and by almost nothing — measured, smoothstep 0.00418 against
        // linear 0.00968, so a threshold between them is luck rather than a statement. The slope
        // jump separates them by a factor of twenty-four: 0.00040 against 0.00968. A discontinuity
        // in slope at the threshold is a visible kink in how far the picture lags as you tilt across
        // it, which is the thing worth forbidding.
        val samples = ArrayList<Double>()
        var alt = 90.0 - SkyPointing.POLE_RAMP_DEG - 1.0
        while (alt <= 90.0) {
            samples.add(SkyPointing.upAlpha(0.25, alt))
            alt += 0.1
        }
        var largestJump = 0.0
        for (i in 0 until samples.size - 1) {
            assertTrue("never rises as the pole nears, at sample $i", samples[i + 1] <= samples[i] + 1e-12)
        }
        for (i in 0 until samples.size - 2) {
            val d0 = samples[i + 1] - samples[i]
            val d1 = samples[i + 2] - samples[i + 1]
            largestJump = maxOf(largestJump, abs(d1 - d0))
        }
        assertTrue("the slope must not jump: $largestJump", largestJump < 0.002)
    }

    @Test
    fun `a stiff screen up is still blended when the aim is taken whole`() {
        // ⚠️ The early return has to require BOTH weights. Checking only `alpha` would mean a caller
        // taking the aim whole while damping the picture got no damping at all — and it would look
        // right, because the aim would be exactly where it should be.
        val f = DoubleArray(3)
        val u = DoubleArray(3)
        val here = SkyPointing.Attitude(0.0, 0.0, 0.0)
        SkyPointing.forward(here, f)
        SkyPointing.screenUp(here, u)
        val next = SkyPointing.Attitude(0.0, 0.0, 60.0)
        val fresh = DoubleArray(3)
        SkyPointing.screenUp(next, fresh)

        SkyPointing.smooth(f, u, next, 1.0, f, u, upAlpha = 0.2)

        assertTrue("the picture must not arrive whole", angleBetween(u, fresh) > 1.0)
        assertTrue("but it must move", angleBetween(u, DoubleArray(3).also { SkyPointing.screenUp(here, it) }) > 1.0)
    }

    @Test
    fun `a stiffer screen up still leaves a valid attitude`() {
        // ⚠️ The pair is blended with two different weights, so it is NOT square afterwards — the
        // re-orthogonalisation is what makes two weights safe rather than merely convenient. A
        // sheared basis is not a crash; it is a picture very slightly skewed that nothing reports.
        val f = DoubleArray(3)
        val u = DoubleArray(3)
        val here = SkyPointing.Attitude(10.0, 88.0, 0.0)
        SkyPointing.forward(here, f)
        SkyPointing.screenUp(here, u)
        val next = SkyPointing.Attitude(64.0, 87.0, 25.0)

        SkyPointing.smooth(f, u, next, 0.5, f, u, upAlpha = SkyPointing.upAlpha(0.5, next.altitudeDeg))

        assertEquals("unit aim", 1.0, len(f), 1e-12)
        assertEquals("unit up", 1.0, len(u), 1e-12)
        assertEquals("square", 0.0, f[0] * u[0] + f[1] * u[1] + f[2] * u[2], 1e-12)
    }

    // ------------------------------------------------------------------ trim and read-out

    @Test
    fun `the hand set correction wraps rather than running off the end`() {
        assertEquals(10.0, SkyPointing.trimmed(SkyPointing.Attitude(350.0, 0.0, 0.0), 20.0).azimuthDeg, 1e-9)
        assertEquals(345.0, SkyPointing.addTrim(-5.0, -10.0), 1e-9)
        assertEquals(5.0, SkyPointing.addTrim(355.0, 10.0), 1e-9)
        // The trim moves only the bearing; nothing else about the attitude may change.
        val a = SkyPointing.Attitude(10.0, 42.0, -7.0)
        val t = SkyPointing.trimmed(a, 33.0)
        assertEquals(42.0, t.altitudeDeg, 0.0)
        assertEquals(-7.0, t.rollDeg, 0.0)
    }

    @Test
    fun `reading angles back out of a direction round trips`() {
        val f = DoubleArray(3)
        for (az in 0 until 360 step 11) {
            for (alt in -85..85 step 5) {
                val a = SkyPointing.Attitude(az.toDouble(), alt.toDouble(), 0.0)
                SkyPointing.forward(a, f)
                assertEquals("altitude at az $az alt $alt", alt.toDouble(), SkyPointing.altitudeOf(f), 1e-9)
                assertEquals("azimuth at az $az alt $alt", az.toDouble(), SkyPointing.azimuthOf(f), 1e-9)
            }
        }
    }

    @Test
    fun `straight up has no bearing and says so rather than inventing one`() {
        assertEquals(90.0, SkyPointing.altitudeOf(doubleArrayOf(0.0, 0.0, 1.0)), 1e-12)
        assertEquals(-90.0, SkyPointing.altitudeOf(doubleArrayOf(0.0, 0.0, -1.0)), 1e-12)

        // ⚠️ The exact zeros do NOT exercise the guard and an earlier version of this test used only
        // those: `atan2(0.0, 0.0)` is 0.0, so deleting the guard changes nothing for that input. The
        // cases that reach it are a NEGATIVE zero, where `atan2(0.0, -0.0)` is a confident 180, and
        // the residue of a cancellation, where 1e-15 against -1e-15 is a confident 135. Both are
        // bearings invented out of nothing, at the one place a bearing does not exist.
        assertEquals("exact zeros", 0.0, SkyPointing.azimuthOf(doubleArrayOf(0.0, 0.0, 1.0)), 0.0)
        assertEquals("a negative zero", 0.0, SkyPointing.azimuthOf(doubleArrayOf(0.0, -0.0, 1.0)), 0.0)
        assertEquals("cancellation residue", 0.0, SkyPointing.azimuthOf(doubleArrayOf(1e-15, -1e-15, 1.0)), 0.0)
        assertEquals("and pointing down", 0.0, SkyPointing.azimuthOf(doubleArrayOf(-1e-16, -1e-16, -1.0)), 0.0)
    }

    @Test
    fun `how far apart two aims are ignores the roll`() {
        val level = SkyPointing.Attitude(0.0, 0.0, 0.0)
        val rolled = SkyPointing.Attitude(0.0, 0.0, 47.0)
        assertEquals("turning the handset is not looking elsewhere", 0.0, SkyPointing.separationDeg(level, rolled), 1e-9)
        assertEquals(90.0, SkyPointing.separationDeg(level, SkyPointing.Attitude(90.0, 0.0, 0.0)), 1e-9)
        assertEquals(30.0, SkyPointing.separationDeg(level, SkyPointing.Attitude(0.0, 30.0, 0.0)), 1e-9)
        assertTrue(abs(SkyPointing.separationDeg(level, level)) < 1e-9)
    }
}
