package dev.mascwa.pulse.core.telemetry

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.abs
import kotlin.math.hypot

/**
 * S9b measurement: does applying proper motion to the DEEP catalogue alone split bright stars in
 * two on screen?
 *
 * Both catalogues are drawn whole and their overlap is allowed — `StarLayer` measured the two
 * records for one star at a median of one arcsecond apart. The deep set is Gaia at epoch J2016 and
 * the bright set is the Bright Star Catalogue at J2000 **with no proper-motion columns at all**, so
 * carrying the deep one forward and leaving the bright one where it is would WIDEN that gap. This
 * walks every record in the real bundled catalogue and reports how many are bright enough to be in
 * both, and how fast they move.
 */
fun main() {
    val f = File("core/sky/src/main/assets/sky/stars.skycat")
    println("catalogue: ${f.length() / 1_000_000} MB")
    val raf = RandomAccessFile(f, "r")
    val buf = raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, f.length())
        .order(ByteOrder.LITTLE_ENDIAN)

    val starCount = buf.getInt(StarCatalogFormat.OFF_STAR_COUNT)
    val tileCount = buf.getInt(StarCatalogFormat.OFF_TILE_COUNT)
    val epochYear = buf.getInt(StarCatalogFormat.OFF_EPOCH_MILLIYEAR) / 1000.0
    println("stars $starCount  tiles $tileCount  epoch J$epochYear")

    // Years the app would carry them: J2016.0 to now.
    val years = 2026.66 - epochYear
    println("years the app would carry them: %.2f".format(years))

    val base = StarCatalogFormat.recordsOffset(tileCount)
    // Buckets of total proper motion in mas/yr.
    val edges = doubleArrayOf(0.0, 10.0, 50.0, 100.0, 200.0, 500.0, 1000.0, 2000.0, 1e9)
    val allBuckets = LongArray(edges.size)
    val brightBuckets = LongArray(edges.size)   // mag <= 6.5, the bright catalogue's own limit
    var bright = 0L
    var worstAll = 0.0
    var worstBright = 0.0
    var noPm = 0L

    for (i in 0 until starCount) {
        val at = (base + i.toLong() * StarCatalogFormat.RECORD_BYTES).toInt()
        val mag = StarCatalogFormat.decodeMagnitude(buf.get(at + 4).toInt())
        val pmRa = StarCatalogFormat.decodeProperMotion(buf.get(at + 6).toInt())
        val pmDec = StarCatalogFormat.decodeProperMotion(buf.get(at + 7).toInt())
        val pm = hypot(pmRa, pmDec)
        if (pm == 0.0) noPm++
        var b = 0
        while (b < edges.size - 1 && pm >= edges[b + 1]) b++
        allBuckets[b]++
        worstAll = maxOf(worstAll, pm)
        if (mag <= 6.5) {
            bright++
            brightBuckets[b]++
            worstBright = maxOf(worstBright, pm)
        }
    }

    println()
    println("total proper motion, mas/yr           all stars        of which mag <= 6.5")
    for (b in 0 until edges.size - 1) {
        val lo = edges[b]
        val hi = if (b == edges.size - 2) Double.POSITIVE_INFINITY else edges[b + 1]
        println(
            "  %8.0f .. %-10s %12d  %6.3f%%   %8d".format(
                lo, if (hi.isInfinite()) "inf" else "%.0f".format(hi),
                allBuckets[b], 100.0 * allBuckets[b] / starCount, brightBuckets[b],
            ),
        )
    }
    println("  no measured proper motion at all: $noPm (%.2f%%)".format(100.0 * noPm / starCount))
    println()
    println("stars at mag <= 6.5 in the deep catalogue: $bright")
    println("worst proper motion overall: %.0f mas/yr -> %.1f arcsec over %.1f yr"
        .format(worstAll, worstAll * years / 1000.0, years))
    println("worst among mag <= 6.5:      %.0f mas/yr -> %.1f arcsec over %.1f yr"
        .format(worstBright, worstBright * years / 1000.0, years))

    println()
    println("=== what that is on screen, for a star in BOTH catalogues ===")
    println("(the bright copy stays at J2000, so the split becomes the full 26.7 years)")
    val split = 2026.66 - 2000.0
    for (pm in doubleArrayOf(worstBright, 500.0, 200.0, 100.0, 50.0)) {
        val arcsec = pm * split / 1000.0
        print("  %7.0f mas/yr -> %7.1f arcsec:".format(pm, arcsec))
        for (fov in doubleArrayOf(60.0, 5.0, 1.0, 0.25)) {
            print("  fov %5.2f = %6.1f px".format(fov, 1080.0 * arcsec / (fov * 3600.0)))
        }
        println()
    }

    // How many bright-catalogue-range stars move enough to be a visible split at the 0.25 floor,
    // where one pixel is 0.833 arcsec?
    var visible = 0L
    for (i in 0 until starCount) {
        val at = (base + i.toLong() * StarCatalogFormat.RECORD_BYTES).toInt()
        if (StarCatalogFormat.decodeMagnitude(buf.get(at + 4).toInt()) > 6.5) continue
        val pm = hypot(
            StarCatalogFormat.decodeProperMotion(buf.get(at + 6).toInt()),
            StarCatalogFormat.decodeProperMotion(buf.get(at + 7).toInt()),
        )
        if (pm * split / 1000.0 > 4.0 * 0.833) visible++   // more than 4 px apart at the floor
    }
    println()
    println("bright-range stars whose two copies would sit more than 4 px apart at the 0.25 field: $visible")
    if (abs(0.0) > 1.0) println("unreachable")
}
