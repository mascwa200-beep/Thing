package dev.mascwa.pulse.desktop.library

import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/**
 * Reading a downloaded pack.
 *
 * A pack archive is a plain zip of guide shards. Nothing else is taken out of it — not images, not
 * an index, not a README — because everything this app does with a pack it does with the shards, and
 * an unpacker that writes whatever it finds is a much larger surface than one that takes what it
 * came for.
 *
 * ⚠️ **An archive is remote content, so every limit here exists to be hit.** A zip that decompresses
 * to gigabytes is a few kilobytes on the wire, and an unbounded reader turns that into an
 * out-of-memory kill on somebody's phone. The caps are deliberately generous for real content and
 * ruinous for a bomb, and reading stops at the first breach rather than finishing and judging after.
 */
object PackArchive {

    /**
     * The shards inside [bytes], keyed by their name in the archive.
     *
     * @return the entries, or a failure naming what was wrong with the archive. Never a partial map:
     *   a pack that is half read is exactly the invisible half-install the store refuses.
     */
    fun read(bytes: ByteArray): Result<Map<String, String>> = runCatching {
        val out = LinkedHashMap<String, String>()
        var total = 0L
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                val name = entry.name.substringAfterLast('/').substringAfterLast('\\')
                if (!name.endsWith(".json", ignoreCase = true)) continue
                require(out.size < MAX_ENTRIES) { "That pack holds too many files." }

                val buffer = ByteArray(8 * 1024)
                // ⚠️ BYTES accumulated and decoded once, never text decoded per chunk. A UTF-8
                // character can straddle a read boundary, and decoding each 8 KB block on its own
                // splits it — which in this corpus means every em dash, degree sign and middot in a
                // shard unlucky enough to sit on the seam. Silent, and only visible in the reader.
                val sink = java.io.ByteArrayOutputStream()
                var read = zip.read(buffer)
                var entryBytes = 0L
                while (read > 0) {
                    entryBytes += read
                    total += read
                    // Checked inside the loop, not after: the point is to stop reading, and a check
                    // that runs once the whole entry is in memory has already lost.
                    require(entryBytes <= MAX_ENTRY_BYTES) { "$name is too large to be a guide shard." }
                    require(total <= MAX_TOTAL_BYTES) { "That pack expands to more than it should." }
                    sink.write(buffer, 0, read)
                    read = zip.read(buffer)
                }
                require(sink.size() > 0) { "$name is empty." }
                // Last one wins on a duplicated name; the store qualifies these anyway, and refusing
                // the whole pack over a repeated entry name helps nobody.
                out[name] = sink.toString(Charsets.UTF_8)
            }
        }
        require(out.isNotEmpty()) { "That archive holds no guide shards." }
        out
    }

    /** More files than any real pack, few enough that a malicious one cannot exhaust anything. */
    const val MAX_ENTRIES = 512

    /** A bundled shard holds 25 guides; the largest is well under this. */
    const val MAX_ENTRY_BYTES = 32L * 1024 * 1024

    /** Roughly the whole bundled corpus, as a ceiling for one pack. */
    const val MAX_TOTAL_BYTES = 256L * 1024 * 1024
}
