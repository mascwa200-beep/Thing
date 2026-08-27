package dev.mascwa.pulse.data.health

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Getting the sheets out of a file somebody picked, without trusting how big it is.
 *
 * ⚠️ **No Android import in this file, deliberately** — the same reasoning as [FoodLogFiling]. Inside
 * [HealthImporter] this needs a `Context` and a `Uri` to reach, so it could only be checked by reading
 * it; here a JVM test can build a real archive, and a real bomb, and watch what happens.
 *
 * ## Why there are caps at all
 *
 * The import path was `openInputStream(uri).readBytes()` followed by `zip.readBytes()` per entry, with
 * no bound anywhere. The file comes from a system picker, so it is whatever the user tapped — and a
 * few megabytes of zeros in a zip decompresses to gigabytes. **Reading it all and then judging it is
 * not a check**: the process is dead before the judging happens. So every limit below is tested INSIDE
 * the loop that reads, which is the shape `PackArchive` settled on for the same reason.
 *
 * Even the honest case is heavier than it looks. Nothing here is streamed — the compressed bytes,
 * every decompressed byte and every sheet as a `String` are live at once, and a `String` of text with
 * an accent in it is two bytes a character.
 *
 * ## Where the numbers come from
 *
 * ⚠️ **Sized against what this app itself writes, measured through the shipped exporter rather than
 * estimated.** A food-log row is 418 bytes carrying its micronutrients and its 29 further nutrients
 * (228 without them), so five entries a day is 3.81 MB after five years and 15.26 MB after twenty;
 * the other three sheets are one row per day or per reading and are far smaller. [MAX_TOTAL_BYTES] of
 * 32 MB is about twice a twenty-year export and eight times a five-year one — it cannot refuse a real
 * file, and it stops a bomb long before the heap does. This importer only understands columns
 * `HealthExport` writes, so "our own export, generously" is the right thing to size against.
 */
internal object HealthArchive {

    /**
     * The most that will be read out of the picked file itself.
     *
     * The same figure as [MAX_TOTAL_BYTES] on purpose: a real export compresses, so a compressed file
     * this large has nothing plausible in it, and one number is easier to hold than two.
     */
    const val MAX_FILE_BYTES = 32L * 1_000_000

    /**
     * The most that will be read out of the archive, ACROSS every entry.
     *
     * ⚠️ Across, not per entry. A bomb is as easily a thousand small entries as one enormous one, and
     * a per-entry limit on its own lets that straight through.
     */
    const val MAX_TOTAL_BYTES = 32L * 1_000_000

    /** How many sheets an archive may hold. `HealthExporter` writes four. */
    const val MAX_ENTRIES = 64

    /** What came out of the file, or why nothing did. */
    sealed interface Read {
        data class Ok(val sheets: List<String>) : Read
        data object Unopenable : Read
        data class TooBig(val what: String) : Read
    }

    /**
     * Read at most [limit] bytes, or null the moment there is more than that.
     *
     * ⚠️ **Null rather than a truncated array, and that is not fussiness.** Half a CSV parses
     * perfectly — the header is at the top — so truncating would put part of somebody's record back
     * and report success, which is worse than refusing outright.
     */
    fun readBounded(input: InputStream, limit: Long): ByteArray? {
        if (limit <= 0) return null
        val out = ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            // ⚠️ Checked before the write, so nothing past the limit is ever held.
            if (total > limit) return null
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    /**
     * The sheets inside [bytes], which may be a zip or may be one CSV.
     *
     * ⚠️ A zip is recognised by whether any entry actually appears, not by the file name — a `.zip`
     * renamed to `.csv` by a mail client still opens.
     */
    fun sheetsFrom(bytes: ByteArray): Read {
        if (bytes.isEmpty()) return Read.Ok(emptyList())

        var bomb = false
        val fromZip = runCatching {
            val out = mutableListOf<String>()
            var total = 0L
            ZipInputStream(bytes.inputStream()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (out.size >= MAX_ENTRIES) { bomb = true; break }
                    if (!entry.isDirectory) {
                        // ⚠️ Accumulated as BYTES and decoded ONCE at the end, never block by block.
                        // A multi-byte character split across two reads decodes to rubbish, and this
                        // corpus is full of accented food names — the mistake `PackArchive` made, and
                        // whose first test then passed against the broken reader.
                        val body = readBounded(zip, MAX_TOTAL_BYTES - total)
                        if (body == null) { bomb = true; break }
                        total += body.size
                        out += body.toString(Charsets.UTF_8)
                    }
                    zip.closeEntry()
                }
            }
            out
        }.getOrDefault(emptyList())

        if (bomb) return Read.TooBig("What is inside that file")
        return Read.Ok(if (fromZip.isNotEmpty()) fromZip else listOf(bytes.toString(Charsets.UTF_8)))
    }

    /** The sentence a refusal prints. */
    fun tooBig(what: String) =
        "$what is larger than this can safely read — ${MAX_TOTAL_BYTES / 1_000_000} MB is the limit, " +
            "which is several times the biggest record this app can write."
}
