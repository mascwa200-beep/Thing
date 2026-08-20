package dev.mascwa.pulse.desktop.library

import dev.mascwa.pulse.desktop.network.HttpClient
import dev.mascwa.pulse.desktop.settings.DesktopSettingsStore
import dev.mascwa.pulse.core.telemetry.ContentPack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.nio.file.Files
import java.security.MessageDigest

/** One row of the published catalog — what a pack is, and which release asset holds it. */
@Serializable
data class PackCatalogEntry(
    val id: String = "",
    val title: String = "",
    val summary: String = "",
    val version: Int = 0,
    val sizeBytes: Long = 0L,
    val guideCount: Int = 0,
    /** The release asset's file name. Resolved to a URL against the release, never trusted as one. */
    val asset: String = "",
    val sha256: String = "",
)

@Serializable
data class PackCatalog(val packs: List<PackCatalogEntry> = emptyList())

/** A pack as offered to the reader: what it is, and what it is currently doing. */
data class PackOffer(
    val pack: ContentPack.Pack,
    val state: ContentPack.State,
    val installedVersion: Int?,
)

/**
 * Fetching expansion packs from where they are published.
 *
 * Packs ride the same machinery as the installers: a rolling GitHub release whose assets are the
 * archives, plus a small `packs.json` catalog beside them. That is deliberate reuse — the repository
 * is private, so an asset needs the same authenticated request the updater already gets right, and
 * rediscovering the private-asset quirk in a second place is how one of them ends up broken.
 *
 * ⚠️ **A catalog entry names an asset, not a URL.** The catalog is content and the URL is
 * infrastructure; letting the catalog dictate where the app makes requests would turn a content file
 * into a way to point the app anywhere. The asset name is resolved against the release's own asset
 * list, and an entry that resolves to nothing is simply not offered — listing a pack that cannot be
 * fetched teaches distrust faster than offering none.
 */
class PackRepository(
    private val http: HttpClient,
    private val settings: DesktopSettingsStore,
    private val packs: PackStore,
) {
    @Serializable
    private data class GhAsset(val name: String = "", val url: String = "")

    @Serializable
    private data class GhRelease(val assets: List<GhAsset> = emptyList())

    private suspend fun headers(): Map<String, String> = buildMap {
        val token = runCatching { settings.current().githubToken }.getOrNull()?.trim().orEmpty()
        if (token.isNotEmpty()) put("Authorization", "Bearer $token")
        // The same lesson the updater learned: GitHub serves authenticated API responses with
        // max-age=60, so a caching client answers from them and a pack published a moment ago is missed.
        put("Cache-Control", "no-cache")
        put("Accept", "application/vnd.github+json")
    }

    /** What is on offer, with what is already installed folded in. */
    suspend fun offers(): Result<List<PackOffer>> = runCatching {
        val h = headers()
        val release = http.getJson("$API/releases/tags/$TAG", GhRelease.serializer(), h)
        val catalogAsset = release.assets.firstOrNull { it.name.equals(CATALOG, ignoreCase = true) }
            ?: error("No pack catalog has been published yet.")
        val catalog = http.getJson(
            catalogAsset.url,
            PackCatalog.serializer(),
            h + mapOf("Accept" to "application/octet-stream"),
        )
        val resolved = resolve(catalog.packs, release.assets.associate { it.name to it.url })
        val installed = packs.installed().associateBy { it.id }
        resolved.map { pack ->
            PackOffer(pack, ContentPack.stateOf(pack, installed[pack.id]), installed[pack.id]?.version)
        }
    }

    /**
     * Fetch a pack and put it in the library.
     *
     * Downloaded to a temporary file first: an archive is remote content, and the point of verifying
     * size and digest is to decide *before* anything is unpacked whether these are the bytes the
     * catalog described.
     */
    suspend fun install(pack: ContentPack.Pack, onProgress: (Int) -> Unit = {}): Result<ContentPack.Installed> =
        withContext(Dispatchers.IO) {
            runCatching {
                val dir = Files.createTempDirectory("lcars-pack")
                val dest = dir.resolve("${ContentPack.qualify(pack.id, "archive.zip")}.bin").toFile()
                try {
                    http.download(
                        url = pack.url,
                        dest = dest,
                        maxBytes = MAX_ARCHIVE_BYTES,
                        headers = headers() + mapOf("Accept" to "application/octet-stream"),
                        onProgress = onProgress,
                    )
                    val bytes = dest.readBytes()
                    require(ContentPack.verifies(pack, bytes.size.toLong(), sha256(bytes))) {
                        "That download is not the pack the catalog described."
                    }
                    val shards = PackArchive.read(bytes).getOrThrow()
                    packs.install(pack, shards).getOrThrow()
                } finally {
                    runCatching { dest.delete() }
                    runCatching { Files.deleteIfExists(dir) }
                }
            }
        }

    suspend fun remove(packId: String): Boolean = packs.remove(packId)

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    companion object {
        /** Packs live on their own tag, for the same reason the desktop installer does. */
        const val TAG = "packs"
        const val CATALOG = "packs.json"
        const val API = "https://api.github.com/repos/mascwa200-beep/Thing"

        /** Far larger than any sensible pack, small enough that a runaway download stops. */
        const val MAX_ARCHIVE_BYTES = 512L * 1024 * 1024

        /**
         * Catalog rows to offerable packs, dropping anything whose asset is not actually there.
         *
         * Pure and separated from the fetch so the rule can be held by a test: a catalog is published
         * by hand as often as not, and a row naming an asset that was never uploaded is the ordinary
         * mistake. Offering it would produce a download button that always fails.
         */
        fun resolve(entries: List<PackCatalogEntry>, assets: Map<String, String>): List<ContentPack.Pack> =
            entries.mapNotNull { e ->
                val url = assets[e.asset] ?: return@mapNotNull null
                ContentPack.Pack(
                    id = e.id,
                    title = e.title.ifBlank { e.id },
                    summary = e.summary,
                    version = e.version,
                    sizeBytes = e.sizeBytes,
                    guideCount = e.guideCount,
                    url = url,
                    sha256 = e.sha256,
                ).takeIf { it.isUsable }
            }
    }
}
