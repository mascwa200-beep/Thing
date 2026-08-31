package dev.mascwa.pulse.data.update

import dev.mascwa.pulse.core.network.HttpClient
import dev.mascwa.pulse.core.telemetry.FoodPack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

/**
 * Fetching the food database, instead of building it into the application.
 *
 * ⚠️ **Measured, and it is the whole reason this exists.** The nutrition APK was 189,972,281 bytes,
 * of which the overwhelming majority was one 425 MB SQLite asset holding 4,524,449 products. The
 * in-app updater downloads the **entire APK** on every published build — so "keep adding barcodes"
 * meant re-downloading the whole corpus every time a line of interface code changed. Separated, the
 * application is small and the corpus is fetched once.
 *
 * ⚠️ **It lives here rather than in `:core:database` on purpose.** This module already reads GitHub
 * releases with the stored token, already streams a download with progress, and already knows how to
 * decide whether a published artifact is safe to take. A second copy of any of that would be a second
 * chance for the two to disagree about what "published" means, which is the exact reason `:core:update`
 * was carved out in the first place. What it does NOT do is depend on `:core:database`: the
 * destination is passed in as a path, so the database module keeps no knowledge of GitHub.
 *
 * ⚠️ **The decision is not made here.** `FoodPack.plan` decides — pure, tested, no network — and this
 * carries it out. That split is what lets "never downgrade", "never apply a pack this build cannot
 * read" and "take the full download when a chain is not worth it" be held by CI rather than by a
 * device somebody has to be holding.
 */
class FoodPackRepository(
    private val http: HttpClient,
    /** Where the database file itself lives — `context.getDatabasePath(FoodDatabase.DB_NAME)`. */
    private val databaseFile: File,
    private val token: suspend () -> String?,
    /**
     * Called immediately before the downloaded file replaces the live one.
     *
     * ⚠️ **The caller has to close whatever has the database open, and nothing here can do it.** This
     * module deliberately does not depend on `:core:database` — the destination is a path, not a
     * Room type — so the close has to come back through a callback. Skipping it does not fail
     * loudly: SQLite holds an open descriptor, deleting and renaming underneath it succeeds on Unix,
     * the old inode stays alive unreferenced, and every query goes on answering from the OLD corpus
     * with a new one on disk until somebody kills the app. The freed space is not freed either.
     */
    private val beforeReplace: () -> Unit = {},
    private val repo: String = REPO,
    private val tag: String = TAG,
) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * What the phone is holding, kept beside the database rather than in shared preferences.
     *
     * ⚠️ Beside it deliberately: a record in preferences survives a "clear data" that removed the
     * database, and would then describe a corpus the phone does not have. Here, the two go together.
     */
    private val record: File get() = File(databaseFile.parentFile, INSTALLED_NAME)

    @Serializable
    private data class GhRelease(val name: String = "", val assets: List<GhAsset> = emptyList())

    @Serializable
    private data class GhAsset(val name: String = "", val url: String = "", val size: Long = 0)

    /** What is installed, or null if nothing is. */
    fun installed(): FoodPack.Installed? {
        // ⚠️ The record and the file have to agree. A record with no database beside it is what a
        // "clear data" leaves, and trusting it would report a corpus the phone does not have and
        // then plan a small delta against nothing.
        if (!databaseFile.exists() || databaseFile.length() <= 0) return null
        return runCatching {
            json.decodeFromString(FoodPack.Installed.serializer(), record.readText())
        }.getOrNull()?.takeIf { it.version > 0 }
    }

    /**
     * Ask the publisher what is available and decide what to do about it.
     *
     * ⚠️ Throws on a network or HTTP failure rather than reporting "up to date", because those are
     * completely different answers and the surface has to be able to say which. A private repository
     * with no token answers 404, and telling somebody their database is current when it could not be
     * checked is the failure this whole file exists to avoid at a larger scale.
     */
    suspend fun check(): Pair<FoodPack.Manifest, FoodPack.Plan> {
        val manifest = manifest()
        return manifest to FoodPack.plan(manifest, installed())
    }

    private suspend fun manifest(): FoodPack.Manifest {
        val headers = buildMap {
            token()?.trim()?.ifBlank { null }?.let { put("Authorization", "Bearer $it") }
            // A pack check must see the live release. GitHub serves authenticated API responses with
            // `Cache-Control: max-age=60` and OkHttp honours it, so without this a freshly published
            // pack is missed and the app reports the corpus current when it is not.
            put("Cache-Control", "no-cache")
        }
        val release = http.getJson("$API/$repo/releases/tags/$tag", GhRelease.serializer(), headers)
        val asset = release.assets.firstOrNull { it.name == MANIFEST_NAME }
            ?: return FoodPack.Manifest()
        val text = http.getString(asset.url, headers + mapOf("Accept" to OCTET))
        return runCatching { json.decodeFromString(FoodPack.Manifest.serializer(), text) }
            .getOrDefault(FoodPack.Manifest())
    }

    /**
     * Carry out [plan], reporting progress 0..100 across the whole of it.
     *
     * @return null on success, or a sentence saying what went wrong.
     */
    suspend fun install(
        manifest: FoodPack.Manifest,
        plan: FoodPack.Plan,
        onProgress: (Int) -> Unit = {},
        onStage: (String) -> Unit = {},
    ): String? = withContext(Dispatchers.IO) {
        when (plan) {
            FoodPack.Plan.UpToDate -> null
            is FoodPack.Plan.Incompatible -> FoodPack.describe(plan, manifest)
            is FoodPack.Plan.Full -> installFull(manifest, plan.piece, onProgress, onStage)
            is FoodPack.Plan.Deltas ->
                // ⚠️ **Not implemented, and it says so rather than silently doing the full download.**
                // The manifest format, `FoodPack.plan` and this branch are all in place; what is
                // missing is the builder emitting deltas at all, so no manifest in existence carries
                // one and this is unreachable today. Claiming otherwise in a comment is how the next
                // person finds out the hard way.
                "This build cannot apply incremental updates yet."
        }
    }

    private suspend fun installFull(
        manifest: FoodPack.Manifest,
        piece: FoodPack.Piece,
        onProgress: (Int) -> Unit,
        onStage: (String) -> Unit,
    ): String? {
        val dir = databaseFile.parentFile ?: return "there is nowhere to put the database"
        runCatching { dir.mkdirs() }

        // ⚠️ Room for the compressed download AND the file it expands to, plus a margin. Checking
        // only one of them is how a download succeeds and the unpack then fails at 90%, having spent
        // the whole transfer. The two differ by about a factor of three here.
        val needed = piece.bytes + maxOf(piece.unpackedBytes, piece.bytes * 3) + SPARE_BYTES
        val free = runCatching { dir.usableSpace }.getOrDefault(-1L)
        if (free in 0 until needed) {
            return "this needs about ${FoodPack.describeBytes(needed)} free and the phone has " +
                FoodPack.describeBytes(free)
        }

        val headers = buildMap {
            token()?.trim()?.ifBlank { null }?.let { put("Authorization", "Bearer $it") }
            put("Accept", OCTET)
        }
        val url = runCatching { assetUrl(piece.name, headers) }.getOrNull()
            ?: return "the published database could not be found"

        val gz = File(dir, "$TEMP_PREFIX${piece.name}")
        val out = File(dir, "$TEMP_PREFIX${FoodPackFiles.PENDING}")
        try {
            onStage("Downloading")
            // ⚠️ Half the bar for the transfer and half for the unpack, because on a phone reading
            // flash the unpack is not instant and a bar that sits at 100% for a minute reads as a
            // hang. `download` never reports at all when the server states no length, which is why
            // the stage line exists beside it.
            runCatching {
                http.download(url, gz, maxBytes = MAX_PACK_BYTES, headers = headers) { pct ->
                    onProgress(pct / 2)
                }
            }.onFailure { return explain(it) }

            if (piece.bytes > 0 && gz.length() != piece.bytes) {
                return "the download stopped early — ${FoodPack.describeBytes(gz.length())} of " +
                    FoodPack.describeBytes(piece.bytes)
            }
            // ⚠️ Verified before it is unpacked, not after. A corrupted 160 MB file expanded over the
            // database in place would replace a working corpus with a broken one; checked first, the
            // worst case is a wasted download.
            if (piece.sha256.isNotBlank()) {
                onStage("Checking")
                val actual = sha256(gz)
                if (!actual.equals(piece.sha256, ignoreCase = true)) {
                    return "the download did not match its checksum and was discarded"
                }
            }

            onStage("Unpacking")
            runCatching { unpack(gz, out) { pct -> onProgress(50 + pct / 2) } }
                .onFailure { return explain(it) }

            // ⚠️ **The replace is the last thing that happens, and it is a rename.** Everything above
            // works on temporary files beside the destination, so an interruption at any point leaves
            // the existing database exactly as it was. Writing over it directly would mean a failed
            // download destroys a working corpus somebody waited ten minutes for.
            //
            // The old file is deleted first because `File.renameTo` will not replace on every
            // filesystem, and Room's journal has to go with it or the next open finds one describing
            // a database that no longer exists.
            runCatching { beforeReplace() }
            runCatching { databaseFile.delete() }
            runCatching { File(databaseFile.path + "-journal").delete() }
            runCatching { File(databaseFile.path + "-wal").delete() }
            runCatching { File(databaseFile.path + "-shm").delete() }
            if (!out.renameTo(databaseFile)) return "the database could not be put into place"

            // Recorded only once the file is really there, so a record can never describe a database
            // the phone does not have.
            runCatching {
                record.writeText(
                    json.encodeToString(
                        FoodPack.Installed.serializer(),
                        FoodPack.Installed(
                            schema = manifest.schema,
                            version = manifest.version,
                            builtAt = manifest.builtAt,
                            rows = manifest.rows,
                        ),
                    ),
                )
            }
            onProgress(100)
            return null
        } finally {
            runCatching { gz.delete() }
            runCatching { out.delete() }
        }
    }

    /**
     * The API asset URL for a named asset in the release.
     *
     * ⚠️ **The API url with `Accept: application/octet-stream`, never `browser_download_url`.** This
     * repository is private, and the browser URL redirects to a signed location that a token cannot
     * authenticate — it answers 404. The same finding is recorded on the desktop updater.
     */
    private suspend fun assetUrl(name: String, headers: Map<String, String>): String {
        val release = http.getJson(
            "$API/$repo/releases/tags/$tag", GhRelease.serializer(),
            headers - "Accept" + mapOf("Cache-Control" to "no-cache"),
        )
        return release.assets.first { it.name == name }.url
    }

    /** Gunzip [gz] into [dest], reporting 0..100 against the expected uncompressed size. */
    private fun unpack(gz: File, dest: File, onProgress: (Int) -> Unit) {
        // ⚠️ The size is read from the SOURCE rather than trusted from the manifest, so a bar cannot
        // run past 100% on a pack whose recorded size is stale. gzip stores only the low 32 bits of
        // the original length, which is useless above 4 GB and fine here — but it is not read at all:
        // the ratio is estimated from the compressed size, which is honest about being an estimate.
        val approx = maxOf(gz.length() * 3, 1L)
        GZIPInputStream(gz.inputStream().buffered(1 shl 16)).use { input ->
            dest.outputStream().buffered(1 shl 16).use { output ->
                val buf = ByteArray(1 shl 16)
                var total = 0L
                var reported = -1
                var n = input.read(buf)
                while (n >= 0) {
                    output.write(buf, 0, n)
                    total += n
                    val pct = ((total * 100) / approx).toInt().coerceIn(0, 100)
                    if (pct != reported) {
                        reported = pct
                        onProgress(pct)
                    }
                    n = input.read(buf)
                }
            }
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered(1 shl 16).use { input ->
            val buf = ByteArray(1 shl 16)
            var n = input.read(buf)
            while (n >= 0) {
                digest.update(buf, 0, n)
                n = input.read(buf)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun explain(err: Throwable): String {
        val message = err.message?.takeIf { it.isNotBlank() }
        return when {
            message == null -> "the download failed"
            message.contains("404") -> "the published database could not be found — check the token"
            else -> "the download failed — $message"
        }
    }

    /** Remove the database and its record, so the next check offers the whole thing again. */
    fun clear() {
        runCatching { beforeReplace() }
        runCatching { databaseFile.delete() }
        runCatching { File(databaseFile.path + "-journal").delete() }
        runCatching { File(databaseFile.path + "-wal").delete() }
        runCatching { File(databaseFile.path + "-shm").delete() }
        runCatching { record.delete() }
    }

    companion object {
        private const val API = "https://api.github.com/repos"
        private const val OCTET = "application/octet-stream"

        /** The repository the pack is published from. */
        const val REPO = "mascwa200-beep/Thing"

        /**
         * ⚠️ **Its OWN tag, and that is not tidiness.** `softprops/action-gh-release` rewrites a
         * release's NAME on every publish, and the name is where each updater reads its build number
         * — so sharing `nutrition-latest` would have each publish overwrite the other's identity. The
         * desktop companion learned this the same way and has `desktop-latest` for the same reason.
         */
        const val TAG = "food-db-latest"

        /** The manifest asset in that release. */
        const val MANIFEST_NAME = "food-pack.json"

        /**
         * What the phone records beside the database about what it holds.
         *
         * ⚠️ A DIFFERENT name from [MANIFEST_NAME]: one is what the publisher offers, the other is
         * what this phone took. They were the same string for about an hour and the two are easy to
         * confuse into one file that answers neither question.
         */
        const val INSTALLED_NAME = "food-pack-installed.json"

        private const val TEMP_PREFIX = "pending-"

        /**
         * A ceiling on what will be accepted, so a mistake at the publishing end cannot fill a phone.
         * Generous: the pack compresses to roughly 160 MB today and this is more than double that.
         */
        private const val MAX_PACK_BYTES = 400L * 1024L * 1024L

        /** Headroom above the download and the file it expands to, so the last write is not the tight one. */
        private const val SPARE_BYTES = 64L * 1024L * 1024L
    }
}

/** Names shared with whatever displays progress; kept out of the class so a test can reach them. */
object FoodPackFiles {
    /** The temporary the pack is unpacked into before it replaces the live database. */
    const val PENDING = "food.db.pending"
}
