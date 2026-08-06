package dev.mascwa.pulse.data.survival

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Loads the bundled, always-offline Knowledge Base from assets. Storage is one JSON shard per category
 * (`guides_<category>.json`, written by `tools/kb/kb_pipeline.py`) plus a small `guide_index.json`
 * catalog index — so as the library grows toward its 10,000-page target, [index] and [guide] never pay
 * for the whole corpus: the index parses ~100s of KB, and [guide] parses only the one shard that holds
 * the requested guide (tiny LRU of recently-opened shards).
 *
 * [guides] (the full merged catalog, parsed once and held resident) remains for the browse/full-text
 * search screens, which genuinely read every body today. MIGRATION NOTE: once the corpus passes ~10 MB
 * of JSON, those screens must move onto [index] + a streamed per-shard search and this method must go —
 * a parse-everything call at that scale is an ANR on screen open, not a convenience.
 */
class SurvivalContentRepository(
    private val context: Context,
    private val json: Json,
) {
    @Volatile private var cached: List<Guide>? = null
    @Volatile private var cachedIndex: List<GuideIndexEntry>? = null

    private val shardMutex = Mutex()
    private val shardLru = LinkedHashMap<String, List<Guide>>(8, 0.75f, true)

    suspend fun guides(): List<Guide> {
        cached?.let { return it }
        return withContext(Dispatchers.IO) {
            // Every guides*.json under assets/survival/ is loaded and flattened (the per-category shards).
            // guide_index.json deliberately does NOT match this glob. Sorted so load order is deterministic.
            val files = context.assets.list("survival")
                ?.filter { it.startsWith("guides") && it.endsWith(".json") }
                ?.sorted().orEmpty()
            files.flatMap { name ->
                val text = context.assets.open("survival/$name").bufferedReader().use { it.readText() }
                json.decodeFromString(GuideBook.serializer(), text).guides
            }.also { cached = it }
        }
    }

    /** The lightweight catalog index — enough for browse rails, lists and heading-level search. */
    suspend fun index(): List<GuideIndexEntry> {
        cachedIndex?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching {
                val text = context.assets.open("survival/guide_index.json").bufferedReader().use { it.readText() }
                json.decodeFromString(GuideIndex.serializer(), text).entries
            }.getOrElse {
                // A missing/stale index never breaks the app — derive one from the full catalog instead
                // (heavier, but correct; CI keeps the real index in lockstep so this is a last resort).
                guides().map { g ->
                    GuideIndexEntry(g.id, g.title, g.category, g.summary, g.sections.map { s -> s.heading }, "")
                }
            }.also { cachedIndex = it }
        }
    }

    /** Full guide by id — parses only its own category shard (unless the full catalog is already resident). */
    suspend fun guide(id: String): Guide? {
        cached?.let { full -> return full.firstOrNull { it.id == id } }
        val entry = index().firstOrNull { it.id == id } ?: return null
        if (entry.file.isBlank()) return guides().firstOrNull { it.id == id } // derived-index fallback path
        return shard(entry.file).firstOrNull { it.id == id }
    }

    private suspend fun shard(file: String): List<Guide> = withContext(Dispatchers.IO) {
        shardMutex.withLock {
            shardLru[file] ?: run {
                val text = context.assets.open("survival/$file").bufferedReader().use { it.readText() }
                val parsed = json.decodeFromString(GuideBook.serializer(), text).guides
                shardLru[file] = parsed
                while (shardLru.size > MAX_RESIDENT_SHARDS) shardLru.remove(shardLru.keys.first())
                parsed
            }
        }
    }

    private companion object {
        /** Recently-opened category shards kept parsed (access-ordered LRU). */
        const val MAX_RESIDENT_SHARDS = 3
    }
}
