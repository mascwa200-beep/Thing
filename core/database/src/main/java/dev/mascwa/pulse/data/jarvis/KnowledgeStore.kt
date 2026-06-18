package dev.mascwa.pulse.data.jarvis

import dev.mascwa.pulse.data.jarvis.db.JarvisDatabase
import dev.mascwa.pulse.data.jarvis.db.KnowledgeDocEntity

/**
 * The on-device knowledge library ("docs RAG"). Documents the user loads are chunked and stored so
 * the most relevant pieces can be lexically retrieved (FTS4) and injected into the model's prompt at
 * question time. This is **retrieval, not training** — the frozen model is never modified; we just
 * give it relevant context. Retrieval is lexical (no embeddings yet), matching the rest of the
 * on-device memory; semantic/vector search is a future upgrade.
 */
class KnowledgeStore(db: JarvisDatabase) {

    private val dao = db.knowledgeDocDao()

    /** Chunk [text] and store it under [title]; returns the number of chunks added (0 if empty). */
    suspend fun addDocument(title: String, text: String, source: String = ""): Int {
        val chunks = chunk(text)
        if (chunks.isEmpty()) return 0
        val now = System.currentTimeMillis()
        val cleanTitle = title.trim().ifBlank { "Untitled" }
        dao.insertAll(
            chunks.map {
                KnowledgeDocEntity(title = cleanTitle, source = source.trim(), timestamp = now, text = it)
            },
        )
        return chunks.size
    }

    /**
     * Lexically retrieve the chunks most relevant to [query]. The query is sanitized into safe FTS
     * MATCH syntax (quoted alphanumeric terms OR-ed together) so arbitrary text can't break it.
     */
    suspend fun search(query: String, limit: Int = 5): List<KnowledgeDocEntity> {
        val match = query.lowercase()
            .split(Regex("[^a-z0-9]+"))
            .filter { it.length > 2 }
            .distinct()
            .take(8)
            .joinToString(" OR ") { "\"$it\"" }
        if (match.isBlank()) return emptyList()
        return runCatching { dao.search(match, limit) }.getOrDefault(emptyList())
    }

    /** Total stored chunks across all documents. */
    suspend fun chunkCount(): Int = dao.count()

    /** Number of distinct source documents. */
    suspend fun documentCount(): Int = dao.docCount()

    /** Distinct document titles (read-only self-inspection). */
    suspend fun titles(): List<String> = dao.titles()

    /** Reconstruct a document's full text from its chunks (for diff previews + rollback). */
    suspend fun fullText(title: String): String = dao.textByTitle(title.trim()).joinToString("\n\n")

    suspend fun deleteDocument(title: String) = dao.deleteByTitle(title.trim())

    /** Remove only the docs from a given [source] (e.g. "bundled") — leaves user-added docs intact. */
    suspend fun deleteBySource(source: String) = dao.deleteBySource(source)

    suspend fun clear() = dao.clear()

    /**
     * Split [text] into retrieval-sized chunks, preferring paragraph boundaries and hard-splitting
     * any paragraph longer than [target]. Keeps chunks focused so a match returns a tight snippet.
     */
    private fun chunk(text: String, target: Int = 1000): List<String> {
        val clean = text.replace("\r\n", "\n").trim()
        if (clean.isBlank()) return emptyList()
        if (clean.length <= target) return listOf(clean)
        val chunks = mutableListOf<String>()
        val cur = StringBuilder()
        fun flush() {
            if (cur.isNotBlank()) chunks.add(cur.toString().trim())
            cur.setLength(0)
        }
        for (raw in clean.split(Regex("\n{2,}"))) {
            val para = raw.trim()
            if (para.isEmpty()) continue
            when {
                para.length > target -> {
                    flush()
                    var i = 0
                    while (i < para.length) {
                        chunks.add(para.substring(i, minOf(i + target, para.length)))
                        i += target
                    }
                }
                cur.length + para.length + 2 > target -> {
                    flush()
                    cur.append(para)
                }
                else -> {
                    if (cur.isNotEmpty()) cur.append("\n\n")
                    cur.append(para)
                }
            }
        }
        flush()
        return chunks
    }
}
