package dev.mascwa.pulse.sky

import dev.mascwa.pulse.core.telemetry.Ephemeris
import dev.mascwa.pulse.core.telemetry.ReferenceCircles
import dev.mascwa.pulse.core.telemetry.SkyProjection

/** Every expression the app-module call sites use, typed against the real declarations. */
fun main() {
    val at = 1781481600000L
    val lat = 51.5074
    val lon = -0.1278

    // SkyMapViewModel.identify
    val eq: Ephemeris.Equatorial = SkyFrame.catalogueOf(45.0, 180.0, lat, lon, at)
    val t: DoubleArray = SkyProjection.equatorialVector(eq.rightAscensionDeg, eq.declinationDeg)

    // SkyMapViewModel.horizonOf
    val h: Ephemeris.Horizontal = SkyFrame.horizonOf(101.287, -16.716, lat, lon, at)

    // SkyMapViewModel.init
    val equatorLine = SkyLines(ReferenceCircles.ARCS * ReferenceCircles.PER_ARC, ReferenceCircles.ARCS)
    val eclipticLine = SkyLines(ReferenceCircles.ARCS * ReferenceCircles.PER_ARC, ReferenceCircles.ARCS)
    ReferenceLines.fill(equatorLine, null, at)
    ReferenceLines.fill(eclipticLine, Ephemeris.trueObliquityDeg(at), at)

    // SkyMapScreen / refreshDeep
    val view = SkyProjection.View(azimuthDeg = 180.0, altitudeDeg = 45.0, fovDeg = 60.0)
    val f: SkyFrame = SkyFrame.of(view, lat, lon, at)
    val fwd = doubleArrayOf(0.0, 1.0, 0.0)
    val up = doubleArrayOf(0.0, 0.0, 1.0)
    val g: SkyFrame = SkyFrame.ofPointing(fwd, up, 60.0, lat, lon, at)
    val c: Ephemeris.Equatorial = SkyFrame.centreOf(view, lat, lon, at)
    val d: Ephemeris.Equatorial = SkyFrame.centreOfPointing(fwd, lat, lon, at)

    println("catalogueOf   ra %.4f dec %.4f".format(eq.rightAscensionDeg, eq.declinationDeg))
    println("horizonOf     alt %.4f az %.4f".format(h.altitudeDeg, h.azimuthDeg))
    println("frame forward %.6f %.6f %.6f".format(f.forwardX, f.forwardY, f.forwardZ))
    println("point forward %.6f %.6f %.6f".format(g.forwardX, g.forwardY, g.forwardZ))
    println("centreOf      ra %.4f  centreOfPointing ra %.4f  vector x %.4f"
        .format(c.rightAscensionDeg, d.rightAscensionDeg, t[0]))
    // The round trip a tap depends on: catalogueOf then horizonOf must give the input back.
    val back = SkyFrame.horizonOf(eq.rightAscensionDeg, eq.declinationDeg, lat, lon, at)
    println("round trip alt %.9f (want 45)  az %.9f (want 180)".format(back.altitudeDeg, back.azimuthDeg))
    // And sinAltitude on the frame still reports the true altitude of the direction it looks at.
    println("sinAltitude at the centre %.9f (want sin 45 = %.9f)"
        .format(f.sinAltitude(f.forwardX, f.forwardY, f.forwardZ), Math.sin(Math.toRadians(45.0))))
}
