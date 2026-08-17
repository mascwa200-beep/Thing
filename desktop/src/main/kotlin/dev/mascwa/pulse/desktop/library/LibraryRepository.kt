package dev.mascwa.pulse.desktop.library

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * The bundled Knowledge Base, read off the classpath.
 *
 * The desktop counterpart of the Android `SurvivalContentRepository`, and the same three-method shape
 * for the same reason: the corpus is 581 guides across 50 category shards, and no code path may ever
 * pay for the whole of it at once.
 *
 * - [index] parses only the small catalog (id/title/category/summary/headings/file per guide) and holds
 *   it resident — enough for browse rails, lists and every ranking pass.
 * - [guide] parses only the one shard holding the requested guide, behind a tiny LRU.
 * - [searchBodies] streams a full-text scan shard by shard: reject on the raw text first, parse only on
 *   a hit, discard after — so body search stays O(one shard) at any corpus size.
 *
 * ⚠️ **The shard list comes from the index, not from a directory listing.** A jar has no directory to
 * list, so the Android version's `assets.list("survival")` has no equivalent here; every index row names
 * its own shard file, which is a more exact answer anyway. The consequence is that the index is
 * *required* rather than a convenience — and `LibraryBundleTest` turns a missing or stale one into a
 * build failure instead of an empty library discovered on a Windows box.
 */
class LibraryRepository(private val json: Json = Json { ignoreUnknownKeys = true }) {

    @Volatile private var cachedIndex: List<GuideIndexEntry>? = null

    private val shardMutex = Mutex()
    private val shardLru = LinkedHashMap<String, List<Guide>>(8, 0.75f, true)

    /** The lightweight catalog — enough for browse rails, lists and heading-level search. */
    suspend fun index(): List<GuideIndexEntry> {
        cachedIndex?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching {
                json.decodeFromString(GuideIndex.serializer(), read(INDEX_FILE)).entries
            }.getOrDefault(emptyList()).also { cachedIndex = it }
        }
    }

    /** Full guide by id — parses only its own category shard. */
    suspend fun guide(id: String): Guide? {
        val entry = index().firstOrNull { it.id == id } ?: return null
        if (entry.file.isBlank()) return null
        return shard(entry.file).firstOrNull { it.id == id }
    }

    /** Every category, in display order, with how many guides each holds. */
    suspend fun categories(): List<Pair<String, Int>> =
        index().groupingBy { it.category }.eachCount().toList().sortedBy { it.first }

    /**
     * Body-level full-text search, streamed shard by shard.
     *
     * Emits matching ids per shard as the scan progresses, so results appear incrementally and memory
     * stays bounded by one shard. Callers pass a trimmed, non-blank query and combine these ids with
     * their own index-field matching.
     */
    fun searchBodies(query: String): Flow<List<String>> = flow {
        val needle = query.trim()
        if (needle.isBlank()) return@flow
        for (name in shardFiles()) {
            val text = runCatching { read(name) }.getOrNull() ?: continue
            // The cheap reject: most shards will not contain the query at all, and skipping the parse
            // is the whole reason this scales.
            if (!text.contains(needle, ignoreCase = true)) continue
            val ids = runCatching { json.decodeFromString(GuideBook.serializer(), text).guides }
                .getOrDefault(emptyList())
                .filter { guideContains(it, needle) }
                .map { it.id }
            if (ids.isNotEmpty()) emit(ids)
        }
    }.flowOn(Dispatchers.IO)

    /** A bundled diagram's bytes, or null when the guide names a file that is not packaged. */
    suspend fun imageBytes(name: String): ByteArray? = withContext(Dispatchers.IO) {
        runCatching {
            javaClass.getResourceAsStream("$ROOT/images/$name")?.use { it.readBytes() }
        }.getOrNull()
    }

    /** The bundled provenance/licensing note for the diagrams, shown in the reader. */
    suspend fun imageNotice(): String = withContext(Dispatchers.IO) {
        runCatching { read("images/NOTICE.txt") }.getOrDefault("")
    }

    private fun guideContains(g: Guide, needle: String): Boolean =
        g.title.contains(needle, true) || g.category.contains(needle, true) ||
            g.summary.contains(needle, true) || g.safetyNote?.contains(needle, true) == true ||
            g.sections.any { s ->
                s.heading.contains(needle, true) || s.body.contains(needle, true) ||
                    s.ingredients?.any { it.contains(needle, true) } == true ||
                    s.steps?.any { it.contains(needle, true) } == true
            }

    /** The shards, taken from the index and sorted for determinism. */
    private suspend fun shardFiles(): List<String> =
        index().map { it.file }.filter { it.isNotBlank() }.distinct().sorted()

    private suspend fun shard(file: String): List<Guide> = withContext(Dispatchers.IO) {
        shardMutex.withLock {
            shardLru[file] ?: run {
                val parsed = runCatching {
                    json.decodeFromString(GuideBook.serializer(), read(file)).guides
                }.getOrDefault(emptyList())
                shardLru[file] = parsed
                while (shardLru.size > MAX_RESIDENT_SHARDS) shardLru.remove(shardLru.keys.first())
                parsed
            }
        }
    }

    private fun read(name: String): String =
        javaClass.getResourceAsStream("$ROOT/$name")?.bufferedReader()?.use { it.readText() }
            ?: error("Bundled library resource missing: $ROOT/$name")

    companion object {
        /** Where `processResources` puts the copied asset tree. */
        const val ROOT = "/survival"

        const val INDEX_FILE = "guide_index.json"

        /** Recently-opened category shards kept parsed (access-ordered LRU). */
        const val MAX_RESIDENT_SHARDS = 3
    }
}
