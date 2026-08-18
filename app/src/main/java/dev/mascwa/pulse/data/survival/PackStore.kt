package dev.mascwa.pulse.data.survival

import android.content.Context
import dev.mascwa.pulse.core.telemetry.ContentPack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Expansion packs on disk — the Android twin of the desktop `PackStore`, same shape for the same
 * reasons.
 *
 * A pack is **a bag of guide shards**: the same `{"guides":[…]}` files the bundle ships, and nothing
 * else. It carries no index of its own on purpose — an index shipped alongside content is a second
 * copy that can disagree with it, and the disagreement surfaces only as a guide that lists but will
 * not open. Here the index is derived from the shards, so the two cannot drift, and a shard that does
 * not parse is refused on arrival rather than found by somebody looking something up.
 *
 * ⚠️ Installation parses everything, writes to a staging folder, and moves the pack into place only
 * once all of it is good. A half-written pack is the ordinary failure of a download, and the library
 * must never be able to see one.
 */
class PackStore(
    context: Context,
    private val json: Json,
    dir: File = File(context.filesDir, "packs"),
) {
    private val root = dir

    @Serializable
    private data class StoredPack(
        val id: String,
        val title: String,
        val version: Int,
        val files: List<String>,
        val guideCount: Int = 0,
    )

    @Serializable
    private data class Manifest(val packs: List<StoredPack> = emptyList())

    private val mutex = Mutex()

    @Volatile private var cached: Manifest? = null

    /** Bumped on every change, so the library can notice its merged index is out of date. */
    @Volatile var revision: Int = 0
        private set

    /** What is installed and readable, in a stable order so the merged library does not reshuffle. */
    suspend fun installed(): List<ContentPack.Installed> =
        manifest().packs.sortedBy { it.id }.map { ContentPack.Installed(it.id, it.version, it.files) }

    /** Id, title and guide count per installed pack, for a management screen. */
    suspend fun summaries(): List<Triple<String, String, Int>> =
        manifest().packs.sortedBy { it.id }.map { Triple(it.id, it.title, it.guideCount) }

    /** Each installed pack's index rows, keyed by pack id — derived by parsing its own shards. */
    suspend fun indexEntries(): List<Pair<String, List<GuideIndexEntry>>> = withContext(Dispatchers.IO) {
        manifest().packs.sortedBy { it.id }.map { pack ->
            pack.id to pack.files.flatMap { file ->
                runCatching {
                    json.decodeFromString(GuideBook.serializer(), readFile(file)).guides.map { g ->
                        GuideIndexEntry(g.id, g.title, g.category, g.summary, g.sections.map { it.heading }, file)
                    }
                }.getOrDefault(emptyList())
            }
        }
    }

    /** One pack shard's text, or null when it is not there — never an exception into the reader. */
    suspend fun read(file: String): String? = withContext(Dispatchers.IO) {
        runCatching { readFile(file) }.getOrNull()
    }

    /**
     * Put a pack on disk, replacing any earlier version of it.
     *
     * @param shards file name as it appeared in the archive, to its JSON text.
     */
    suspend fun install(
        pack: ContentPack.Pack,
        shards: Map<String, String>,
    ): Result<ContentPack.Installed> = withContext(Dispatchers.IO) {
        mutex.withLock {
            runCatching {
                require(pack.isUsable) { "That pack is not usable." }
                require(shards.isNotEmpty()) { "That pack contains no guides." }

                // Parse EVERYTHING before writing anything. A pack that is half readable is worse than
                // one that never arrived, because the half that failed is invisible.
                val parsed = shards.mapKeys { (name, _) -> ContentPack.qualify(pack.id, name) }
                    .mapValues { (name, text) ->
                        val book = runCatching { json.decodeFromString(GuideBook.serializer(), text) }
                            .getOrElse { throw IllegalArgumentException("$name is not a guide shard.") }
                        require(book.guides.isNotEmpty()) { "$name holds no guides." }
                        text to book.guides.size
                    }

                root.mkdirs()
                val staging = File(root, ".staging-${ContentPack.qualify(pack.id, "d")}")
                staging.deleteRecursively()
                staging.mkdirs()
                for ((name, payload) in parsed) File(staging, name).writeText(payload.first)

                // Only now is the previous version of this pack disturbed.
                removeFilesLocked(pack.id)
                for (name in parsed.keys) {
                    val target = File(root, name)
                    target.delete()
                    if (!File(staging, name).renameTo(target)) {
                        // Rename can fail across some storage backends; a copy is the honest fallback.
                        File(staging, name).copyTo(target, overwrite = true)
                    }
                }
                staging.deleteRecursively()

                val stored = StoredPack(
                    id = pack.id,
                    title = pack.title,
                    version = pack.version,
                    files = parsed.keys.sorted(),
                    guideCount = parsed.values.sumOf { it.second },
                )
                writeManifestLocked(Manifest(manifest().packs.filterNot { it.id == pack.id } + stored))
                ContentPack.Installed(stored.id, stored.version, stored.files)
            }
        }
    }

    /** Take a pack back off. Its guides leave the library; nothing bundled is touched. */
    suspend fun remove(packId: String): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            val current = manifest()
            if (current.packs.none { it.id == packId }) return@withLock false
            removeFilesLocked(packId)
            writeManifestLocked(Manifest(current.packs.filterNot { it.id == packId }))
            true
        }
    }

    private fun removeFilesLocked(packId: String) {
        val existing = cached?.packs?.firstOrNull { it.id == packId } ?: return
        for (name in existing.files) runCatching { File(root, name).delete() }
    }

    private fun writeManifestLocked(next: Manifest) {
        root.mkdirs()
        File(root, MANIFEST).writeText(json.encodeToString(Manifest.serializer(), next))
        cached = next
        revision++
    }

    private suspend fun manifest(): Manifest {
        cached?.let { return it }
        return withContext(Dispatchers.IO) {
            val loaded = runCatching {
                json.decodeFromString(Manifest.serializer(), File(root, MANIFEST).readText())
            }.getOrDefault(Manifest())
            // ⚠️ A pack whose files have gone — an interrupted uninstall, a cleared data directory —
            // is dropped from the view rather than left to fail on every read. Same instinct as
            // ignoring an unreadable pack: it costs its own content and nothing else.
            val live = loaded.packs.filter { p -> p.files.isNotEmpty() && p.files.all { File(root, it).isFile } }
            Manifest(live).also { cached = it }
        }
    }

    private fun readFile(name: String): String {
        // Belt and braces: the manifest holds qualified names, but a name that reached here another
        // way must not be able to walk out of the packs folder.
        require(ContentPack.isPackFile(name)) { "Not a pack file: $name" }
        return File(root, name).readText()
    }

    private companion object {
        const val MANIFEST = "packs.json"
    }
}
