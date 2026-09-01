package dev.mascwa.pulse.data.health

import android.content.Context
import android.net.Uri
import dev.mascwa.pulse.core.telemetry.BodyTrend
import dev.mascwa.pulse.core.telemetry.HealthExport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Writes the whole health record out as a zip of four CSVs.
 *
 * The arithmetic and every formatting rule live in [HealthExport], which is tested; this reads the
 * stores, decides the calendar, and does the file I/O.
 *
 * ⚠️ **A zip rather than four pickers.** Choosing where to save is the slow part of this on Android,
 * and asking four times for one action would make an export somebody does once a year feel like a
 * chore. Everything is in one file, so nothing is half-exported either.
 *
 * ⚠️ **A UTF-8 byte-order mark, deliberately, and it is a trade rather than a default.** Excel on
 * Windows reads a BOM-less UTF-8 CSV as Windows-1252, which mangles every accented food name — and
 * this app's companion runs on Windows, where Excel is the natural thing to open a spreadsheet with.
 * The cost is that a naive `open(f, encoding="utf-8")` in Python shows a stray `﻿` on the first
 * header; `utf-8-sig` is the fix and every real CSV library handles it. Correct-by-default on the
 * machine this will actually be opened on beats correct-by-default for a one-line script.
 */
class HealthExporter(
    private val context: Context,
    private val foodLog: FoodLogStore,
    private val body: BodyStore,
) {

    /** What happened, in a sentence the surface can print without interpreting anything. */
    data class Outcome(val ok: Boolean, val message: String)

    /**
     * Gather everything and write it to [uri].
     *
     * @param zone the calendar the readable date columns are written in — the device's, supplied by
     *   the caller for the reason every core here states: a date derived from UTC is a day out for
     *   half the planet.
     */
    suspend fun export(uri: Uri, zone: ZoneId = ZoneId.systemDefault()): Outcome =
        withContext(Dispatchers.IO) {
            runCatching {
                // ⚠️ Locale.US on the formatters as well as on the numbers. A locale with non-Latin
                // digits (Arabic-Indic, say) makes `DateTimeFormatter` emit them, and a date column no
                // spreadsheet can parse is the same defect as a comma decimal, one field over.
                val dateFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US).withZone(zone)
                val timeFmt = DateTimeFormatter.ofPattern("HH:mm", Locale.US).withZone(zone)
                val dayLabel: (Long) -> String = { dateFmt.format(Instant.ofEpochMilli(it)) }
                val timeLabel: (Long) -> String = { timeFmt.format(Instant.ofEpochMilli(it)) }

                val entries = foodLog.allEntries()
                val days = foodLog.days.value
                // ⚠️ Sorted HERE, not left to the core. `trendKgAt` is indexed against the order the
                // core writes rows in, and the core sorts its own copy — so passing an unsorted list
                // would silently pair each reading with somebody else's trend. Sorting first makes
                // the core's own sort a no-op and the indices provably the same list.
                val readings = body.all().sortedBy { it.atMs }
                val notes = body.notes()
                val measures = body.allMeasurements()
                val trends = forwardTrends(readings)

                context.contentResolver.openOutputStream(uri)?.use { raw ->
                    ZipOutputStream(raw.buffered()).use { zip ->
                        zip.put(HealthExport.FOOD_FILE, HealthExport.foodLog(entries, dayLabel, timeLabel))
                        zip.put(HealthExport.DAYS_FILE, HealthExport.dailyTotals(days, dayLabel))
                        zip.put(
                            HealthExport.WEIGHT_FILE,
                            HealthExport.weighins(
                                readings = readings,
                                trendKgAt = { i -> trends.getOrNull(i) },
                                dayLabel = dayLabel,
                                noteFor = { notes[it].orEmpty() },
                            ),
                        )
                        zip.put(
                            HealthExport.MEASURE_FILE,
                            HealthExport.measurements(
                                measures.map { Triple(it.atMs, it.kind.label, it.cm) },
                                dayLabel,
                            ),
                        )
                    }
                } ?: return@runCatching Outcome(
                    false,
                    "Could not open that file for writing. Try somewhere else on the device.",
                )

                Outcome(
                    true,
                    HealthExport.summarise(entries.size, days.size, readings.size, measures.size),
                )
            }.getOrElse { e ->
                Outcome(false, "Export failed — ${e.message ?: e::class.java.simpleName}.")
            }
        }

    /**
     * The trend as it stood at each reading, rather than as the smoother sees it in hindsight.
     *
     * ⚠️ [BodyTrend.estimate] runs a Kalman filter and then an RTS smoother **backwards** over the
     * whole series, so `points[i]` is informed by every later weigh-in. That is the right number for
     * a chart and the wrong one for this column: somebody comparing the export against a screenshot
     * from that morning would find the two disagreeing, with nothing to say which was wrong. Running
     * the estimate over the readings up to each point costs a pass per reading — a decade of daily
     * weighing is a few million filter steps, under a second, on something a person asked for and is
     * waiting on.
     */
    private fun forwardTrends(readings: List<BodyTrend.Weighin>): List<Double?> =
        readings.indices.map { i ->
            when (val t = BodyTrend.estimate(readings.subList(0, i + 1))) {
                is BodyTrend.Trend.Estimated -> t.latest.trendKg
                is BodyTrend.Trend.TooLittle -> null
            }
        }

    private fun ZipOutputStream.put(name: String, csv: String) {
        putNextEntry(ZipEntry(name))
        write(BOM)
        write(csv.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    private companion object {
        val BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
    }
}
