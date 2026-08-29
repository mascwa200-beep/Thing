package dev.mascwa.pulse.core.telemetry

import java.io.File

/**
 * Run the SHIPPED reader over the REAL bundled asset, which no unit test does — every fixture in
 * MilkyWayTest is one this file built itself.
 */
object AssetProbe

fun main() {
    val bytes = File("core/sky/src/main/assets/sky/milkyway.bin").readBytes()
    println("file: ${bytes.size} bytes, expected ${MilkyWay.FILE_BYTES}")
    val r = MilkyWay.readRaster(bytes)
    if (r == null) { println("REFUSED — the shipped reader will not read the shipped asset"); return }
    println("accepted. peak %.1f stars/deg^2".format(r.peak))

    // Where the density actually is, read back through the shipped sampler.
    fun d(l: Double, b: Double) = MilkyWay.sample(r.cells, r.peak, l, b)
    println("  galactic centre  l=0   b=0   -> %6.1f /deg^2  opacity %.3f".format(d(0.0, 0.0), MilkyWay.opacity(d(0.0, 0.0))))
    println("  Great Rift       l=35  b=0   -> %6.1f            opacity %.3f".format(d(35.0, 0.0), MilkyWay.opacity(d(35.0, 0.0))))
    println("  Carina           l=290 b=0   -> %6.1f            opacity %.3f".format(d(290.0, 0.0), MilkyWay.opacity(d(290.0, 0.0))))
    println("  anticentre       l=180 b=0   -> %6.1f            opacity %.3f".format(d(180.0, 0.0), MilkyWay.opacity(d(180.0, 0.0))))
    println("  north pole       l=0   b=+90 -> %6.1f            opacity %.3f".format(d(0.0, 90.0), MilkyWay.opacity(d(0.0, 90.0))))
    println("  south pole       l=0   b=-90 -> %6.1f            opacity %.3f".format(d(0.0, -90.0), MilkyWay.opacity(d(0.0, -90.0))))

    // The seam. If the wrap were wrong these two would not agree.
    println("  seam l=359.9 %.1f vs l=0.1 %.1f".format(d(359.9, 0.0), d(0.1, 0.0)))

    // A real place on the sky, through the whole chain the renderer uses.
    val g = DoubleArray(2)
    // Sagittarius A*: RA 17h45m40s, Dec -29d00m28s.
    val v = SkyProjection.equatorialVector(266.4168, -29.0078)
    MilkyWay.galacticOfVector(v[0], v[1], v[2], g)
    println("  Sgr A* -> l=%.3f b=%.3f (should be ~0, ~0)".format(g[0], g[1]))

    // How much of the sky is drawn at all, and how much is at the cap.
    var drawn = 0
    var capped = 0
    for (row in 0 until MilkyWay.ROWS) {
        for (col in 0 until MilkyWay.COLUMNS) {
            val o = MilkyWay.opacity(d(col + 0.5, row - 89.5))
            if (o > 0.0) drawn++
            if (o >= MilkyWay.MAX_OPACITY - 1e-9) capped++
        }
    }
    val total = MilkyWay.CELLS.toDouble()
    println("  %.1f%% of the sky is drawn at all; %.2f%% is at full opacity"
        .format(100.0 * drawn / total, 100.0 * capped / total))
}
