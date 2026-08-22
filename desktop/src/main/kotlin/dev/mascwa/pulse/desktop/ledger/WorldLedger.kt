package dev.mascwa.pulse.desktop.ledger

import dev.mascwa.pulse.core.telemetry.Novelty
import dev.mascwa.pulse.desktop.AppPaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Everything this machine has ever watched the world do.
 *
 * The app fetches markets, weather, air quality, space weather, seismic and aviation data every day and
 * throws every value away the moment its cache expires. This keeps them. A tower PC is always on, mains
 * powered, unmetered, and has a disk that does not care about tens of megabytes — which is exactly why
 * this is a desktop feature and not a phone one.
 *
 * ## The storage shape, and why
 *
 * One file per metric per month: `ledger/<metric-id>/2026-08.csv`, two columns, epoch **seconds** and
 * value, with an optional third field marking a backfilled row.
 *
 * ⚠️ **The metric id lives in the path and is never repeated on a line.** That is most of the saving —
 * a record costs about twenty bytes rather than sixty. It also makes pruning a file delete and reading
 * a series a matter of opening a handful of files rather than scanning everything.
 *
 * Seconds rather than milliseconds because nothing here samples faster than once a minute, and three
 * characters a row over millions of rows is real. ⚠️ The ledger's resolution is therefore one second;
 * two observations of one metric inside the same second are two rows with the same stamp, which the
 * scoring treats as two samples. No collector cadence comes close to that.
 *
 * Month buckets are cut on **UTC**, deliberately: the bucket is a storage detail rather than a date
 * anyone reads, and cutting on local time would move rows between files if the machine's zone changed.
 *
 * ## Retention — measured, not guessed
 *
 * At the collector's per-domain cadences the real volume is about 2,800 records a day, or 56 KB, or
 * 20 MB a year at full resolution. So the plan's original three-tier scheme was wrong: **an hourly
 * aggregate would have made most metrics BIGGER**, because most are sampled less often than hourly.
 *
 * What ships instead is full resolution for [FULL_RESOLUTION_DAYS], then one row a day forever. Steady
 * state is roughly 20 MB rolling plus 1.6 MB a year, comfortably inside the owner's 100 MB ceiling.
 *
 * ⚠️ **The daily tier is deliberately NOT fed into the distribution.** A downsampled tail cannot serve
 * both purposes: emitting daily means erases the extremes that records are made of, and emitting daily
 * min-and-max over-weights the tails and biases the spread upward, which would make everything look
 * *less* surprising than it is. So [read] returns only full-resolution observations — a year of them,
 * which is ample — and [dailyExtremes] serves the multi-year record and the long chart. Each tier is
 * used for the one thing it can answer honestly.
 */
class WorldLedger(private val root: Path = AppPaths.dataDir.resolve(DIR_NAME)) {

    /** One day of a metric, once the full-resolution rows have been folded away. */
    data class DailyRow(val epochDay: Long, val min: Double, val max: Double, val mean: Double, val count: Int)

    private val mutex = Mutex()

    // ---------------------------------------------------------------- writing

    /** Record one observation. Cheap enough to call a hundred times a pass. */
    suspend fun append(metricId: String, o: Novelty.Observation) = appendAll(metricId, listOf(o))

    /**
     * Record many at once — one file open per month touched rather than one per row, which is what
     * makes backfilling a year of hourly weather (8,784 rows) a single pass rather than a stutter.
     */
    suspend fun appendAll(metricId: String, observations: List<Novelty.Observation>) {
        if (observations.isEmpty()) return
        val id = requireSafeId(metricId)
        mutex.withLock {
            withContext(Dispatchers.IO) {
                val dir = root.resolve(id)
                Files.createDirectories(dir)
                observations
                    .filter { it.value.isFinite() }
                    .groupBy { monthOf(it.atMs) }
                    .forEach { (month, rows) ->
                        val text = buildString {
                            rows.sortedBy { it.atMs }.forEach { o ->
                                append(o.atMs / 1000L)
                                append(',')
                                append(o.value.toString())
                                if (o.backfilled) append(",b")
                                append('\n')
                            }
                        }
                        Files.writeString(
                            dir.resolve("$month$FULL_SUFFIX"),
                            text,
                            java.nio.file.StandardOpenOption.CREATE,
                            java.nio.file.StandardOpenOption.APPEND,
                        )
                    }
            }
        }
    }

    // ---------------------------------------------------------------- reading

    /**
     * Full-resolution observations from [sinceMs] onwards, oldest first.
     *
     * ⚠️ A row that will not parse is skipped rather than throwing. A process killed mid-append leaves
     * a partial final line, and a ledger that refuses to open because of one truncated row would lose
     * everything before it to protect nothing.
     */
    suspend fun read(metricId: String, sinceMs: Long = 0L): List<Novelty.Observation> {
        val id = requireSafeId(metricId)
        return withContext(Dispatchers.IO) {
            val dir = root.resolve(id)
            if (!Files.isDirectory(dir)) return@withContext emptyList()
            val sinceSec = sinceMs / 1000L
            monthFiles(dir)
                .flatMap { f -> Files.readAllLines(f).mapNotNull(::parseRow) }
                .filter { it.first >= sinceSec }
                .sortedBy { it.first }
                .map { (sec, v, back) -> Novelty.Observation(sec * 1000L, v, back) }
        }
    }

    /** The folded daily tier, oldest first — for records older than the full-resolution window. */
    suspend fun dailyExtremes(metricId: String): List<DailyRow> {
        val id = requireSafeId(metricId)
        return withContext(Dispatchers.IO) {
            val f = root.resolve(id).resolve(DAILY_FILE)
            if (!Files.exists(f)) return@withContext emptyList()
            Files.readAllLines(f).mapNotNull(::parseDaily).sortedBy { it.epochDay }
        }
    }

    /**
     * When this metric was last recorded, or null if never — the question the collector's per-domain
     * cadence asks about every metric on every pass.
     *
     * ⚠️ Reads the **last line only**, by seeking to the end and scanning back. Parsing a whole month
     * file for its final row, a hundred times a pass, would be the single most wasteful thing here.
     */
    suspend fun lastAt(metricId: String): Long? {
        val id = requireSafeId(metricId)
        return withContext(Dispatchers.IO) {
            val dir = root.resolve(id)
            if (!Files.isDirectory(dir)) return@withContext null
            val newest = monthFiles(dir).lastOrNull() ?: return@withContext null
            lastLineOf(newest)?.let(::parseRow)?.first?.times(1000L)
        }
    }

    suspend fun metricIds(): List<String> = withContext(Dispatchers.IO) {
        if (!Files.isDirectory(root)) return@withContext emptyList()
        Files.list(root).use { s -> s.filter { Files.isDirectory(it) }.map { it.fileName.toString() }.sorted().toList() }
    }

    /**
     * Whether [metricId] has already had a provider's history poured into it.
     *
     * ⚠️ A marker file inside the metric's own directory rather than a preference, so it travels with
     * the data it describes: clearing the ledger clears the memory of having filled it, which is the
     * only behaviour that leaves a wiped ledger able to fill itself again. A preference would remember
     * a backfill whose rows no longer exist and refuse to do it a second time.
     */
    suspend fun isBackfilled(metricId: String): Boolean = withContext(Dispatchers.IO) {
        Files.exists(root.resolve(requireSafeId(metricId)).resolve(BACKFILL_MARKER))
    }

    /** Record that [metricId] has been backfilled, whether or not any rows survived the judging. */
    suspend fun markBackfilled(metricId: String) {
        val id = requireSafeId(metricId)
        withContext(Dispatchers.IO) {
            val dir = root.resolve(id)
            Files.createDirectories(dir)
            runCatching { Files.write(dir.resolve(BACKFILL_MARKER), ByteArray(0)) }
        }
    }

    suspend fun sizeBytes(): Long = withContext(Dispatchers.IO) {
        if (!Files.exists(root)) return@withContext 0L
        Files.walk(root).use { s -> s.filter { Files.isRegularFile(it) }.mapToLong { Files.size(it) }.sum() }
    }

    // ---------------------------------------------------------------- retention

    /**
     * Fold anything older than [FULL_RESOLUTION_DAYS] into one row a day, then delete the month files
     * it came from.
     *
     * ⚠️ Each daily row keeps **min and max**, not just the mean. Collapse to a mean and "highest in
     * three years" starts silently lying the moment a peak leaves the full-resolution window — and it
     * would keep lying, because nothing downstream could tell.
     *
     * ⚠️ The daily rows are written **before** the month files are deleted. A crash between the two
     * costs a duplicate fold next time, which [foldInto] tolerates by keying on the day; a crash the
     * other way round would cost the data itself.
     */
    suspend fun prune(nowMs: Long) {
        mutex.withLock {
            withContext(Dispatchers.IO) {
                if (!Files.isDirectory(root)) return@withContext
                val cutoffSec = (nowMs - FULL_RESOLUTION_DAYS * DAY_MS) / 1000L
                val cutoffMonth = monthOf(nowMs - FULL_RESOLUTION_DAYS * DAY_MS)

                Files.list(root).use { it.filter { p -> Files.isDirectory(p) }.toList() }.forEach { dir ->
                    // Only whole months entirely behind the cutoff are folded — a month still partly
                    // inside the window must keep every row it has.
                    val stale = monthFiles(dir).filter { f -> monthNameOf(f) < cutoffMonth }
                    if (stale.isEmpty()) return@forEach

                    val rows = stale.flatMap { f -> Files.readAllLines(f).mapNotNull(::parseRow) }
                        .filter { it.first < cutoffSec }
                    if (rows.isNotEmpty()) foldInto(dir.resolve(DAILY_FILE), rows)
                    stale.forEach { Files.deleteIfExists(it) }
                }
            }
        }
    }

    suspend fun clear() {
        mutex.withLock {
            withContext(Dispatchers.IO) {
                if (!Files.exists(root)) return@withContext
                Files.walk(root).use { s -> s.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
            }
        }
    }

    // ---------------------------------------------------------------- internals

    private fun foldInto(dailyFile: Path, rows: List<Triple<Long, Double, Boolean>>) {
        val existing = if (Files.exists(dailyFile)) {
            Files.readAllLines(dailyFile).mapNotNull(::parseDaily).associateBy { it.epochDay }
        } else {
            emptyMap()
        }

        val folded = rows.groupBy { Math.floorDiv(it.first, DAY_SECONDS) }.mapValues { (d, day) ->
            val values = day.map { it.second }
            DailyRow(d, values.min(), values.max(), values.average(), values.size)
        }

        // ⚠️ A day seen twice is MERGED, not replaced and not skipped.
        //
        // A prune interrupted between writing these rows and deleting the month files leaves both on
        // disk, so the next prune folds that day again. Letting the newer fold win loses any readings
        // the collector appended in between; letting the older one win loses them too. Only combining
        // them is right under every interruption ordering — and the mean has to be weighted by count,
        // or averaging two averages quietly mis-weights a partial day against a full one.
        val merged = (existing.keys + folded.keys).sorted().map { d ->
            val a = existing[d]
            val b = folded[d]
            when {
                a == null -> b!!
                b == null -> a
                else -> DailyRow(
                    epochDay = d,
                    min = minOf(a.min, b.min),
                    max = maxOf(a.max, b.max),
                    mean = (a.mean * a.count + b.mean * b.count) / (a.count + b.count).coerceAtLeast(1),
                    count = a.count + b.count,
                )
            }
        }

        Files.writeString(
            dailyFile,
            merged.joinToString("\n", postfix = "\n") { "${it.epochDay},${it.min},${it.max},${it.mean},${it.count}" },
        )
    }

    /**
     * The full-resolution month files, oldest first.
     *
     * ⚠️ Matched **positively** against `yyyy-MM.csv` rather than by "ends with .csv", and that is not
     * fussiness. The first cut used the loose test, which also matched [DAILY_FILE] — so the moment
     * anything was pruned, the daily aggregate was parsed back as full-resolution rows and injected a
     * garbage observation dated 1970 into every distribution. It cost nothing until the first prune
     * and would then have been quietly wrong forever. A positive pattern cannot be defeated by some
     * future sibling file landing in this directory.
     */
    private fun monthFiles(dir: Path): List<Path> =
        Files.list(dir).use { s ->
            s.filter { MONTH_FILE.matches(it.fileName.toString()) }.sorted().toList()
        }

    private fun monthNameOf(f: Path) = f.fileName.toString().removeSuffix(FULL_SUFFIX)

    private fun monthOf(atMs: Long): String =
        MONTH_FORMAT.format(Instant.ofEpochMilli(atMs).atZone(ZoneOffset.UTC))

    /** (epochSeconds, value, backfilled), or null when the line is not a usable row. */
    private fun parseRow(line: String): Triple<Long, Double, Boolean>? {
        val parts = line.split(',')
        if (parts.size < 2) return null
        val sec = parts[0].toLongOrNull() ?: return null
        val v = parts[1].toDoubleOrNull() ?: return null
        if (!v.isFinite()) return null
        return Triple(sec, v, parts.size > 2 && parts[2] == BACKFILL_FLAG)
    }

    private fun parseDaily(line: String): DailyRow? {
        val p = line.split(',')
        if (p.size < 5) return null
        return DailyRow(
            epochDay = p[0].toLongOrNull() ?: return null,
            min = p[1].toDoubleOrNull() ?: return null,
            max = p[2].toDoubleOrNull() ?: return null,
            mean = p[3].toDoubleOrNull() ?: return null,
            count = p[4].toIntOrNull() ?: return null,
        )
    }

    private fun lastLineOf(f: Path): String? {
        RandomAccessFile(f.toFile(), "r").use { raf ->
            var pos = raf.length() - 1
            if (pos < 0) return null
            // Step over a trailing newline, then back to the one before this line.
            while (pos > 0 && raf.run { seek(pos); read() } == '\n'.code) pos--
            val end = pos
            while (pos > 0) {
                raf.seek(pos - 1)
                if (raf.read() == '\n'.code) break
                pos--
            }
            raf.seek(pos)
            val buf = ByteArray((end - pos + 1).toInt().coerceAtLeast(0))
            if (buf.isEmpty()) return null
            raf.readFully(buf)
            return String(buf).trim().ifEmpty { null }
        }
    }

    companion object {
        const val DIR_NAME = "ledger"
        const val DAILY_FILE = "daily.csv"
        const val FULL_SUFFIX = ".csv"
        const val BACKFILL_FLAG = "b"

        /**
         * Empty marker written beside a metric's month files once its history has been fetched.
         *
         * ⚠️ Deliberately not `.csv`-shaped: [MONTH_FILE] matches only `yyyy-MM.csv`, so this is
         * invisible to every reader. Naming it something a reader might parse is how a stray file
         * became a 1970 observation in every distribution once already.
         */
        const val BACKFILL_MARKER = ".backfilled"

        /** How long observations are kept at the cadence they were taken. See the retention note above. */
        const val FULL_RESOLUTION_DAYS = 365L

        private const val DAY_MS = 24L * 60L * 60L * 1000L
        private const val DAY_SECONDS = 24L * 60L * 60L
        private val MONTH_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM")
        private val MONTH_FILE = Regex("""\d{4}-\d{2}\.csv""")

        /**
         * ⚠️ An unsafe id is **rejected, never sanitised**. Two ids that sanitised to the same directory
         * would silently merge into one series, and a merged series is worse than a missing one: it
         * still scores, and it scores nonsense. `MetricRegistryTest` holds the registry to this
         * alphabet so this can never fire in the shipped app.
         */
        fun requireSafeId(id: String): String {
            require(id.isNotBlank()) { "metric id must not be blank" }
            require(id.all { it.isLowerCase() || it.isDigit() || it == '.' || it == '_' || it == '-' }) {
                "metric id '$id' must be lowercase letters, digits, dot, underscore or hyphen — " +
                    "sanitising it could merge two metrics into one series"
            }
            return id
        }
    }
}
