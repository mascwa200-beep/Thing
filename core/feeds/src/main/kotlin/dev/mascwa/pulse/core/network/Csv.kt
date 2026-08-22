package dev.mascwa.pulse.core.network

/** Tiny CSV reader for the simple, well-formed CSV that Stooq returns. */
object Csv {

    /** Returns rows as maps keyed by the (lower-cased) header columns. */
    fun parseWithHeader(text: String): List<Map<String, String>> {
        val lines = text.trim().split('\n').map { it.trim('\r', ' ') }.filter { it.isNotEmpty() }
        if (lines.size < 2) return emptyList()
        val header = splitLine(lines.first()).map { it.lowercase() }
        return lines.drop(1).map { line ->
            val cells = splitLine(line)
            header.mapIndexed { i, key -> key to (cells.getOrNull(i) ?: "") }.toMap()
        }
    }

    fun rows(text: String): List<List<String>> {
        return text.trim().split('\n')
            .map { it.trim('\r', ' ') }
            .filter { it.isNotEmpty() }
            .map { splitLine(it) }
    }

    private fun splitLine(line: String): List<String> {
        // Stooq does not quote fields, but be defensive about quoted commas.
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuotes = false
        for (c in line) {
            when {
                c == '"' -> inQuotes = !inQuotes
                c == ',' && !inQuotes -> { out += sb.toString().trim(); sb.clear() }
                else -> sb.append(c)
            }
        }
        out += sb.toString().trim()
        return out
    }
}
