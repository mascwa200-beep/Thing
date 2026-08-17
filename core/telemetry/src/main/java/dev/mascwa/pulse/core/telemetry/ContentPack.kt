package dev.mascwa.pulse.core.telemetry

/**
 * Growing the library without growing the download.
 *
 * The bundled corpus is already megabytes of JSON, and the honest arithmetic on "make it vastly
 * bigger" is that the content cannot all ride in the installer — a thousandfold of what ships today
 * is measured in gigabytes. An **expansion pack** is the shape that squares the two asks: the app
 * installs lean, a pack is fetched **once**, and from that moment it is indistinguishable from
 * bundled content. No network is ever consulted to *read* it, which is the whole point of a library
 * you might open in an emergency.
 *
 * This is the decision layer only — what is available, what is installed, what is stale, and how the
 * two corpora combine. Fetching, unpacking, hashing and storage are the platform's, because they are
 * the parts a test cannot hold still.
 *
 * ## The rules that matter
 *
 * - **Bundled content always wins a collision.** A pack must never be able to shadow a guide that
 *   ships in the app: the bundled corpus is the known-good one, it is what CI validates, and the
 *   emergency protocols live in it. See [merge].
 * - **A pack's files are namespaced by its id.** Two packs (or a pack and the bundle) both offering
 *   `guides_cooking_1.json` must not be able to overwrite each other, so a pack's storage name is
 *   derived rather than trusted. See [qualify].
 * - **Anything unreadable is ignored, never fatal.** A half-downloaded pack is the normal failure and
 *   it must cost exactly its own content — not the library.
 */
object ContentPack {

    /** A pack as advertised by the catalog: what it is, how big, and where to get it. */
    data class Pack(
        val id: String,
        val title: String,
        val summary: String,
        /** Bumped whenever the content changes. Monotonic; the catalog is the authority. */
        val version: Int,
        val sizeBytes: Long,
        val guideCount: Int,
        val url: String,
        /** Lowercase hex SHA-256 of the archive, or blank when the catalog does not state one. */
        val sha256: String = "",
    ) {
        val isUsable: Boolean
            get() = id.isNotBlank() && url.isNotBlank() && version > 0

        /** "12 guides · 4.2 MB" — what the reader needs before agreeing to a download. */
        fun describe(): String = buildString {
            append(guideCount).append(if (guideCount == 1) " guide" else " guides")
            if (sizeBytes > 0) append(" · ").append(describeSize(sizeBytes))
        }
    }

    /** A pack that is on disk and readable. */
    data class Installed(
        val id: String,
        val version: Int,
        /** The shard file names it contributes, already qualified — see [qualify]. */
        val files: List<String>,
    )

    /** What a pack is doing right now, from the reader's point of view. */
    enum class State {
        /** Not on disk. */
        AVAILABLE,

        /** On disk and current. */
        INSTALLED,

        /** On disk, but the catalog offers a newer one. */
        UPDATABLE,
    }

    fun stateOf(pack: Pack, installed: Installed?): State = when {
        installed == null -> State.AVAILABLE
        installed.version < pack.version -> State.UPDATABLE
        else -> State.INSTALLED
    }

    /**
     * Whether [pack] is worth fetching given what is already on disk.
     *
     * ⚠️ Strictly newer, never merely different. A catalog that has been rolled back — a bad pack
     * pulled, the previous one restored under a lower number — must not make the app download the
     * older content over the newer content it already has, and then do it again next launch.
     */
    fun shouldInstall(pack: Pack, installed: Installed?): Boolean =
        pack.isUsable && (installed == null || installed.version < pack.version)

    /**
     * The storage name for one of [packId]'s files.
     *
     * ⚠️ Derived from the pack id rather than taken from the archive, because the archive is remote
     * content and a file called `../guide_index.json` inside one would otherwise be written straight
     * over the bundled catalog. The name is reduced to its last path segment first for the same reason.
     */
    fun qualify(packId: String, file: String): String {
        val leaf = file.substringAfterLast('/').substringAfterLast('\\')
        return "${sanitise(packId)}__${sanitise(leaf)}"
    }

    /** True when [name] is a file this pack system wrote, rather than something else in the folder. */
    fun isPackFile(name: String): Boolean = name.contains("__") && name.endsWith(".json")

    /** Which pack a qualified name belongs to, or null when it is not one of ours. */
    fun packIdOf(name: String): String? =
        if (isPackFile(name)) name.substringBefore("__").takeIf { it.isNotBlank() } else null

    /**
     * One library out of the bundled corpus and every installed pack.
     *
     * Generic over the entry type on purpose: the index entry is a serialized model and lives in each
     * platform's own module, while the *rule* for combining them must not be written twice.
     *
     * Order is bundled first, then packs in the order given — so a caller that sorts its pack list
     * gets a stable library, and browse rails do not reshuffle because a directory listing came back
     * differently.
     *
     * @param packs each pack's entries, keyed by pack id. Entries whose id is already taken are
     *   dropped — by the bundle, or by an earlier pack.
     */
    fun <T> merge(
        bundled: List<T>,
        packs: List<Pair<String, List<T>>>,
        id: (T) -> String,
    ): List<T> {
        val seen = HashSet<String>(bundled.size * 2)
        val out = ArrayList<T>(bundled.size)
        for (entry in bundled) if (seen.add(id(entry))) out += entry
        for ((_, entries) in packs) {
            for (entry in entries) if (seen.add(id(entry))) out += entry
        }
        return out
    }

    /**
     * How many of [packs]' entries a [merge] would actually admit — what a pack really adds once
     * collisions with the bundle are taken off.
     *
     * Worth stating separately because "12 guides" on a download button and 9 new titles afterwards is
     * the kind of small dishonesty that makes a reader stop trusting the counts.
     */
    fun <T> newCount(bundled: List<T>, packs: List<Pair<String, List<T>>>, id: (T) -> String): Int =
        merge(bundled, packs, id).size - bundled.distinctBy(id).size

    /**
     * Whether a downloaded archive is the one the catalog described.
     *
     * A size that does not match, or a digest that does not match, means the bytes are not the pack —
     * a truncated download, a captive portal's login page, a mirror serving something else. Either is
     * a reason to discard rather than unpack.
     *
     * A blank expected digest is accepted, because not every catalog states one; a blank *actual*
     * digest against a stated expectation is not, because that is the hashing having failed.
     */
    fun verifies(pack: Pack, actualSizeBytes: Long, actualSha256: String): Boolean {
        if (pack.sizeBytes > 0 && actualSizeBytes != pack.sizeBytes) return false
        if (pack.sha256.isBlank()) return true
        return actualSha256.isNotBlank() && actualSha256.equals(pack.sha256, ignoreCase = true)
    }

    /** Bytes, the way a download prompt should say them. */
    fun describeSize(bytes: Long): String = when {
        bytes <= 0L -> "unknown size"
        bytes < 1024L -> "$bytes B"
        bytes < 1024L * 1024L -> "${(bytes + 512L) / 1024L} KB"
        // One decimal below ten megabytes, where the difference between 4.2 and 4.9 is worth stating.
        bytes < 10L * 1024L * 1024L -> {
            val tenths = (bytes * 10 + (1024L * 1024L) / 2) / (1024L * 1024L)
            "${tenths / 10}.${tenths % 10} MB"
        }
        bytes < 1024L * 1024L * 1024L -> "${(bytes + (1024L * 1024L) / 2) / (1024L * 1024L)} MB"
        else -> {
            val tenths = (bytes * 10 + (1024L * 1024L * 1024L) / 2) / (1024L * 1024L * 1024L)
            "${tenths / 10}.${tenths % 10} GB"
        }
    }

    /** Anything that is not a plain name becomes an underscore — no separators, no traversal, no spaces. */
    private fun sanitise(raw: String): String =
        raw.map { if (it.isLetterOrDigit() || it == '-' || it == '.') it else '_' }
            .joinToString("")
            .trim('.', '_')
            .ifBlank { "pack" }
}
