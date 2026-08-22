package dev.mascwa.pulse.data.survival

import android.content.Context
import dev.mascwa.pulse.core.telemetry.ContentPack
import dev.mascwa.pulse.core.telemetry.Guide
import dev.mascwa.pulse.core.telemetry.GuideBook
import dev.mascwa.pulse.core.telemetry.GuideIndex
import dev.mascwa.pulse.core.telemetry.GuideIndexEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Loads the bundled, always-offline Knowledge Base from assets. Storage is one JSON shard per category
 * (`guides_<category>[_N].json`, written by `tools/kb/kb_pipeline.py`, <= 25 guides per file) plus a small
 * `guide_index.json` catalog index — so as the library grows toward its 10,000-page target, no code path
 * ever pays for the whole corpus at once:
 *
 * - [index] parses only the small catalog index (id/title/category/summary/headings/file per guide) and
 *   holds it resident — enough for browse rails, lists and field-level search.
 * - [guide] parses only the one shard that holds the requested guide (tiny LRU of recently-opened shards).
 * - [searchBodies] streams a full-text scan shard by shard (raw-text reject first, parse only on a hit,
 *   discard after) so body-level search stays O(one shard) in memory at any corpus size.
 *
 * The old parse-everything-and-hold-resident `guides()` method is gone — at 10,000+ pages it would have
 * been an ANR on screen open, not a convenience.
 */
class SurvivalContentRepository(
    private val context: Context,
    private val json: Json,
    /**
     * Installed expansion packs, or null for a bundle-only library.
     *
     * ⚠️ The merge happens **here**, at the one place that answers "what is in the library", so the
     * reader, search, study and the assistant's tools all see one corpus and none of them needed
     * changing. A pack that had to be plumbed through every consumer would be a pack that is only
     * half installed.
     */
    private val packs: PackStore? = null,
) {
    @Volatile private var cachedIndex: List<GuideIndexEntry>? = null
    @Volatile private var cachedFiles: List<String>? = null

    /** Which pack revision the caches were built from, so installing one invalidates them. */
    @Volatile private var cachedAtPackRevision: Int = -1

    private val shardMutex = Mutex()
    private val shardLru = LinkedHashMap<String, List<Guide>>(8, 0.75f, true)

    /** The lightweight catalog index — enough for browse rails, lists and heading-level search. */
    suspend fun index(): List<GuideIndexEntry> {
        val revision = packs?.revision ?: 0
        cachedIndex?.let { if (revision == cachedAtPackRevision) return it }
        val bundled = bundledIndex()
        // Bundled first and bundled wins: a pack can add to the library and can never shadow it.
        val merged = ContentPack.merge(bundled, packs?.indexEntries().orEmpty()) { it.id }
        cachedAtPackRevision = revision
        cachedIndex = merged
        return merged
    }

    private suspend fun bundledIndex(): List<GuideIndexEntry> {
        return withContext(Dispatchers.IO) {
            runCatching {
                json.decodeFromString(GuideIndex.serializer(), readAsset(INDEX_FILE)).entries
            }.getOrElse {
                // A missing/stale index never breaks the app — derive one by streaming every shard once
                // (parse, summarize, discard; the shard name is known here, so lazy [guide] still works).
                // CI keeps the real index in lockstep with the shards, so this is a last resort.
                // ⚠️ Bundled shards only. Packs derive their own rows and are merged by the caller;
                // deriving them here as well would list every pack guide twice.
                bundledShardFiles().flatMap { name ->
                    runCatching {
                        json.decodeFromString(GuideBook.serializer(), readAsset(name)).guides.map { g ->
                            GuideIndexEntry(g.id, g.title, g.category, g.summary, g.sections.map { s -> s.heading }, name)
                        }
                    }.getOrDefault(emptyList())
                }
            }
        }
    }

    /** Full guide by id — parses only its own category shard. */
    suspend fun guide(id: String): Guide? {
        val entry = index().firstOrNull { it.id == id } ?: return null
        if (entry.file.isNotBlank()) return shard(entry.file).firstOrNull { it.id == id }
        // Defensive: an index entry with no file (shouldn't happen — both index paths set it) → scan.
        for (name in shardFiles()) shard(name).firstOrNull { it.id == id }?.let { return it }
        return null
    }

    /**
     * Body-level full-text search, streamed shard by shard: each shard's raw JSON text is scanned first
     * (a cheap reject — most shards won't contain the query), parsed only on a hit to identify WHICH
     * guides match, then discarded. Emits matching guide ids per shard as the scan progresses, so results
     * appear incrementally and memory stays bounded by one shard regardless of corpus size. Callers pass
     * a trimmed, non-blank query and combine these ids with their own index-field matching.
     */
    fun searchBodies(query: String): Flow<List<String>> = flow {
        val needle = query.trim()
        if (needle.isBlank()) return@flow
        for (name in shardFiles()) {
            val text = runCatching { readShard(name) }.getOrNull() ?: continue
            if (!text.contains(needle, ignoreCase = true)) continue
            val ids = runCatching { json.decodeFromString(GuideBook.serializer(), text).guides }
                .getOrDefault(emptyList())
                .filter { guideContains(it, needle) }
                .map { it.id }
            if (ids.isNotEmpty()) emit(ids)
        }
    }.flowOn(Dispatchers.IO)

    private fun guideContains(g: Guide, needle: String): Boolean =
        g.title.contains(needle, true) || g.category.contains(needle, true) ||
            g.summary.contains(needle, true) || g.safetyNote?.contains(needle, true) == true ||
            g.sections.any { s ->
                s.heading.contains(needle, true) || s.body.contains(needle, true) ||
                    s.ingredients?.any { it.contains(needle, true) } == true ||
                    s.steps?.any { it.contains(needle, true) } == true
            }

    /** Every guides*.json under assets/survival/ (the per-category shards), sorted for determinism.
     *  guide_index.json deliberately does NOT match this prefix+suffix filter. */
    private suspend fun bundledShardFiles(): List<String> {
        cachedFiles?.let { return it }
        return withContext(Dispatchers.IO) {
            (context.assets.list("survival")
                ?.filter { it.startsWith("guides") && it.endsWith(".json") && it != INDEX_FILE }
                ?.sorted().orEmpty()).also { cachedFiles = it }
        }
    }

    /** Everything the library can read, bundled first — what a full-text scan has to walk. */
    private suspend fun shardFiles(): List<String> =
        bundledShardFiles() + packs?.installed().orEmpty().flatMap { it.files }

    private suspend fun shard(file: String): List<Guide> = withContext(Dispatchers.IO) {
        shardMutex.withLock {
            shardLru[file] ?: run {
                val parsed = json.decodeFromString(GuideBook.serializer(), readShard(file)).guides
                shardLru[file] = parsed
                while (shardLru.size > MAX_RESIDENT_SHARDS) shardLru.remove(shardLru.keys.first())
                parsed
            }
        }
    }

    /**
     * A shard's text, from wherever it lives.
     *
     * A pack file is recognised by its name rather than by a lookup — the qualification scheme exists
     * precisely so this routing needs no table and cannot get out of step with what is installed.
     */
    private suspend fun readShard(name: String): String =
        if (ContentPack.isPackFile(name)) {
            packs?.read(name) ?: error("Expansion pack resource missing: $name")
        } else {
            readAsset(name)
        }

    private fun readAsset(name: String): String =
        context.assets.open("survival/$name").bufferedReader().use { it.readText() }

    private companion object {
        const val INDEX_FILE = "guide_index.json"

        /** Recently-opened category shards kept parsed (access-ordered LRU). */
        const val MAX_RESIDENT_SHARDS = 3
    }
}
