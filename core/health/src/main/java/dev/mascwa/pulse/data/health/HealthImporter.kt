package dev.mascwa.pulse.data.health

import android.content.Context
import android.net.Uri
import dev.mascwa.pulse.core.telemetry.HealthImport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.ZoneId
import java.util.zip.ZipInputStream

/**
 * Reads back a zip — or a single CSV — that [HealthExporter] wrote.
 *
 * Every parsing rule lives in the tested `HealthImport` core; this opens the file, decides the
 * calendar, and hands what came out to the stores.
 *
 * ⚠️ **A zip OR a bare CSV, and a lone CSV is identified by its HEADER rather than its file name.** A
 * zip entry keeps the name it was written with; a single sheet forwarded through a mail client, saved
 * from a spreadsheet or renamed on a desktop does not. The header is the thing that actually says
 * what the rows are.
 */
class HealthImporter(
    private val context: Context,
    private val foodLog: FoodLogStore,
    private val body: BodyStore,
) {

    /** What happened, in a sentence the surface can print without interpreting anything. */
    data class Outcome(val ok: Boolean, val message: String)

    /**
     * Read [uri] and put back everything it holds.
     *
     * @param zone the calendar the day each entry counts toward is decided in — the device's own. See
     *   `HealthImport.foodLog` for why the file's stored day is kept when it is still a midnight here
     *   and re-derived when it is not.
     */
    suspend fun import(uri: Uri, zone: ZoneId = ZoneId.systemDefault()): Outcome =
        withContext(Dispatchers.IO) {
            runCatching {
                val dayStartFor: (Long) -> Long = { ms -> HealthDays.startOf(ms, zone) }

                val sheets = readSheets(uri)
                    ?: return@runCatching Outcome(false, "Could not open that file.")
                if (sheets.isEmpty()) {
                    return@runCatching Outcome(false, "There was nothing readable in that file.")
                }

                val result = sheets
                    .map { HealthImport.read(it, dayStartFor) }
                    .reduce { a, b -> a + b }

                result.refusal?.let { return@runCatching Outcome(false, it) }

                val addedEntries = foodLog.importEntries(result.entries)

                // ⚠️ `BodyStore.record` REPLACES any weigh-in already on that day, so a second import
                // of the same file cannot double a morning — the same rule that makes weighing twice
                // before breakfast a correction rather than two readings.
                result.weighins.forEach { w ->
                    body.record(atMs = w.atMs, kg = w.kg, dayStartMs = dayStartFor(w.atMs), note = w.note)
                }

                // ⚠️ Measurements are NOT the same shape, which is easy to assume and wrong:
                // `recordMeasurement` APPENDS with no dedupe, so importing twice would genuinely
                // double every reading. Deduped here rather than by changing the manual path, where
                // appending is the correct behaviour. The whole body record is one small blob, so
                // reading it first costs nothing.
                val already = body.allMeasurements().mapTo(HashSet()) { it.atMs to it.kind }
                var addedMeasurements = 0
                result.measurements.forEach { m ->
                    val kind = kindOf(m.site) ?: return@forEach
                    if (!already.add(m.atMs to kind)) return@forEach
                    body.recordMeasurement(atMs = m.atMs, kind = kind, cm = m.cm)
                    addedMeasurements++
                }

                Outcome(true, report(result, addedEntries, addedMeasurements))
            }.getOrElse { e ->
                Outcome(false, "Import failed — ${e.message ?: e::class.java.simpleName}.")
            }
        }

    // ------------------------------------------------------------------------------- internals

    /**
     * Every CSV in the file, or null if it could not be opened at all.
     *
     * Tries the zip first because that is what the exporter writes; a file that is not a zip reads as
     * one sheet of text. ⚠️ A zip is recognised by whether any entry actually appears, not by the file
     * name — a `.zip` renamed to `.csv` by a mail client still opens.
     */
    private fun readSheets(uri: Uri): List<String>? {
        val bytes = runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull() ?: return null
        if (bytes.isEmpty()) return emptyList()

        val fromZip = runCatching {
            val out = mutableListOf<String>()
            ZipInputStream(bytes.inputStream()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (!entry.isDirectory) out += zip.readBytes().toString(Charsets.UTF_8)
                    zip.closeEntry()
                }
            }
            out
        }.getOrDefault(emptyList())

        return if (fromZip.isNotEmpty()) fromZip else listOf(bytes.toString(Charsets.UTF_8))
    }

    /**
     * A measurement site back into the enum.
     *
     * ⚠️ Matched against the LABEL and the enum name, case-insensitively, and **null when neither
     * matches** — unlike meal and source, which fall back. There is no sensible default site: guessing
     * would file a thigh measurement as a waist, and a wrong number on the wrong body part is worse
     * than a reading that did not come across.
     */
    private fun kindOf(site: String): BodyStore.MeasureKind? =
        BodyStore.MeasureKind.entries.firstOrNull {
            it.label.equals(site, ignoreCase = true) || it.name.equals(site, ignoreCase = true)
        }

    /**
     * What actually landed, which is not the same as what was read.
     *
     * ⚠️ The counts are the ADDED ones, not the file's row counts. Importing the same file twice
     * should say "nothing new", and a message that echoed the file would claim to have imported a
     * thousand entries for the second time.
     */
    private fun report(
        r: HealthImport.Result,
        addedEntries: Int,
        addedMeasurements: Int,
    ): String {
        val parts = buildList {
            if (addedEntries > 0) {
                add("$addedEntries ${if (addedEntries == 1) "entry" else "entries"}")
            }
            if (r.weighins.isNotEmpty()) {
                add("${r.weighins.size} ${if (r.weighins.size == 1) "weigh-in" else "weigh-ins"}")
            }
            if (addedMeasurements > 0) add("$addedMeasurements measurements")
        }
        val head = when {
            parts.isNotEmpty() -> "Added " + parts.joinToString(", ") + "."
            !r.isEmpty -> "Nothing new — everything in that file was already here."
            else -> "Nothing to import."
        }
        val skipped = if (r.skippedRows > 0) {
            " Skipped ${r.skippedRows} ${if (r.skippedRows == 1) "row" else "rows"}."
        } else {
            ""
        }
        val why = if (r.reasons.isEmpty()) "" else " " + r.reasons.joinToString(" ")
        return (head + skipped + why).trim()
    }
}
