package dev.mascwa.pulse.core.telemetry

import org.junit.Test

/**
 * Measurement, not a test: prints the shipped SkyBrightness figures so the assertions in
 * SkyBrightnessTest are computed from the function rather than recalled. Run through
 * scratchpad/coretest/run.sh; nothing here asserts.
 */
class SkyBrightnessMeasure {
    @Test
    fun measure() {
        for (a in listOf(90.0, 60.0, 45.0, 30.0, 20.0, 10.0, 5.0, 2.0, 1.0, 0.5, 0.0, -1.0)) {
            println("MEASURED airmass(apparent %6.2f) = %.9f".format(java.util.Locale.US, a, SkyBrightness.airmass(a)))
        }
        val h = Refraction.TRUE_AT_APPARENT_HORIZON_DEG
        println("MEASURED drawn horizon true alt = %.9f  apparentOf = %.3e".format(java.util.Locale.US, h, Refraction.apparentOf(h)))
        for (t in listOf(90.0, 45.0, 30.0, 20.0, 10.0, 5.0, 2.0, 1.0, 0.5, 0.0, h, h - 1e-9, -1.0)) {
            println("MEASURED extinction(true %12.9f) = %.9f  transmission = %.9f".format(
                java.util.Locale.US, t, SkyBrightness.extinctionMag(t), SkyBrightness.transmission(t)))
        }
        // Table against exact, over a dense sweep in sine.
        var worstE = 0.0; var worstEAt = 0.0
        var worstT = 0.0; var worstTAt = 0.0
        val lo = SkyBrightness.SIN_DRAWN_HORIZON
        val n = 200_000
        for (i in 0..n) {
            val s = lo + (1.0 - lo) * i / n
            val alt = Math.toDegrees(kotlin.math.asin(s.coerceIn(-1.0, 1.0)))
            val e = kotlin.math.abs(SkyBrightness.fastExtinctionMag(s) - SkyBrightness.extinctionMag(alt))
            if (e > worstE) { worstE = e; worstEAt = alt }
            val tt = kotlin.math.abs(SkyBrightness.fastTransmission(s) - SkyBrightness.transmission(alt))
            if (tt > worstT) { worstT = tt; worstTAt = alt }
        }
        println("MEASURED table extinction worst %.3e mag at %.4f deg; transmission worst %.3e at %.4f deg".format(
            java.util.Locale.US, worstE, worstEAt, worstT, worstTAt))
        println("MEASURED SIN_DRAWN_HORIZON = %.12f".format(java.util.Locale.US, lo))
        println("MEASURED fast at sine 0.05 (true %.4f deg): extinction %.9f transmission %.9f".format(
            java.util.Locale.US, Math.toDegrees(kotlin.math.asin(0.05)),
            SkyBrightness.fastExtinctionMag(0.05), SkyBrightness.fastTransmission(0.05)))
        println("MEASURED k = %s; horizon loss k*(40-1) = %.12f".format(
            java.util.Locale.US, SkyBrightness.EXTINCTION_MAG_PER_AIRMASS, SkyBrightness.EXTINCTION_MAG_PER_AIRMASS * 39.0))
        for (sa in listOf(-30.0, -18.0, -17.999, -15.0, -12.0, -9.0, -7.0, -6.0, -3.0, -2.0, 0.0, 3.0, 5.0, 8.0, 10.0, 25.0)) {
            println("MEASURED sun %7.3f: sky %.6f milky %.6f glow %.6f dimming %.6f".format(
                java.util.Locale.US, sa, SkyBrightness.skyFactor(sa), SkyBrightness.milkyWayFactor(sa),
                SkyBrightness.glowStrength(sa), SkyBrightness.dimmingMag(sa)))
        }
        // Monotonicity sweep.
        var prevS = -1.0; var prevD = -1.0; var prevM = 2.0; var okS = true; var okD = true; var okM = true
        var a = -40.0
        while (a <= 40.0) {
            val s = SkyBrightness.skyFactor(a); val d = SkyBrightness.dimmingMag(a); val m = SkyBrightness.milkyWayFactor(a)
            if (s < prevS) okS = false; if (d < prevD) okD = false; if (m > prevM) okM = false
            prevS = s; prevD = d; prevM = m
            a += 0.01
        }
        println("MEASURED monotone sky=$okS dimming=$okD milky=$okM")
        // Extinction monotone in true altitude from the drawn horizon up.
        var prevX = Double.MAX_VALUE; var okX = true; var t = h
        while (t <= 90.0) { val x = SkyBrightness.extinctionMag(t); if (x > prevX + 1e-12) okX = false; prevX = x; t += 0.01 }
        println("MEASURED extinction monotone non-increasing = $okX")
        println("MEASURED limit identity: " + (SkyBrightness.limitingMagnitude(13.37, -12.0).toRawBits() == 13.37.toRawBits()) +
            " " + (SkyBrightness.limitingMagnitude(6.0, -18.0).toRawBits() == 6.0.toRawBits()))
    }
}
