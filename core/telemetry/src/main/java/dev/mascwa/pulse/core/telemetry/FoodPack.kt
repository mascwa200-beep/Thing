package dev.mascwa.pulse.core.telemetry

import kotlinx.serialization.Serializable

/**
 * Deciding what a phone has to fetch to hold every food barcode there is.
 *
 * ## Why the database stopped being part of the application
 *
 * ⚠️ **Measured, and it is the whole reason this file exists.** The nutrition APK was
 * **189,972,281 bytes**, of which the overwhelming majority was one asset: a 425 MB SQLite file of
 * 4,524,449 products, deflated. The in-app updater downloads the **entire APK** on every published
 * build — so "keep adding barcodes" meant re-downloading the whole corpus every time the *code*
 * changed, which is what made continuous updating untenable rather than merely expensive.
 *
 * Split apart, the application is small and the corpus is fetched once and then kept current in
 * pieces. The two move at completely different rates and had been welded together.
 *
 * ## What a pack is
 *
 * A gzipped SQLite database, published as a release asset, plus a [Manifest] describing it. After the
 * first fetch, a rebuild publishes a **delta** — the rows that are new or changed since the previous
 * version — which is a few megabytes where the full pack is a few hundred.
 *
 * ⚠️ **This file decides and does not act.** No I/O, no clock, no Android: given a manifest and what
 * is on the phone, it returns a [Plan]. That is what makes the interesting rules — never downgrade,
 * never apply a pack this build cannot read, take the full download when a delta chain is not worth
 * it — testable without a network or a 425 MB file.
 */
object FoodPack {

    /**
     * The shape of the data inside a pack, bumped when the SQLite schema changes.
     *
     * ⚠️ **This is NOT the pack version.** A pack version counts publications; this counts
     * incompatible changes to the tables, and it is what stops a build from applying a pack whose
     * columns it cannot read. `FoodDatabase`'s Room version is the same number and must move with it.
     */
    const val SCHEMA: Int = 2

    /**
     * The most deltas that will be chained rather than taking the full pack.
     *
     * ⚠️ Every delta applied is a chance to be interrupted with a partly-updated database, and the
     * chain has to be applied in order. Past a handful the arithmetic stops favouring them anyway:
     * eight deltas of the size a monthly rebuild produces are already a substantial fraction of one
     * full download, and the full one is a single atomic replace.
     */
    const val MAX_CHAIN: Int = 8

    /**
     * How much smaller a delta chain has to be before it is preferred to the full pack.
     *
     * ⚠️ **Not "any saving at all", deliberately.** A full pack lands as one file that replaces the
     * old one in a single rename; a chain mutates the database the person is already using. Paying
     * a bit more for the atomic path is the right trade unless the saving is real — and at 0.6 the
     * saving is at least 40% of a few hundred megabytes, which is not marginal.
     */
    const val DELTA_WORTH_IT: Double = 0.6

    /** One downloadable file in a pack, whole or delta. */
    @Serializable
    data class Piece(
        /** The release asset's file name. */
        val name: String = "",
        /** Compressed size, which is what the phone actually downloads. */
        val bytes: Long = 0,
        /** Lower-case hex SHA-256 of the compressed file, or blank if the builder did not record one. */
        val sha256: String = "",
        /**
         * Uncompressed size, which is what the phone needs room for.
         *
         * ⚠️ The two differ by roughly a factor of three here, and confusing them is how a free-space
         * guard passes and the write then fails halfway. Named separately for that reason.
         */
        val unpackedBytes: Long = 0,
        /** For a delta: the version it applies to. Zero for a full pack. */
        val from: Int = 0,
        /** The version this piece produces. */
        val to: Int = 0,
        /** For a delta: how many rows it carries, so the surface can say what is happening. */
        val rows: Long = 0,
    )

    /** What the publisher says is available. */
    @Serializable
    data class Manifest(
        /** [SCHEMA] at the time the pack was built. */
        val schema: Int = 0,
        /** Which publication this is; increases, never reused. */
        val version: Int = 0,
        /** When it was built, as the builder wrote it — displayed, never parsed. */
        val builtAt: String = "",
        /** Products in the whole corpus, for the surface to state. */
        val rows: Long = 0,
        /** The whole database. */
        val full: Piece = Piece(),
        /** Every delta the publisher still keeps, in no particular order. */
        val deltas: List<Piece> = emptyList(),
    )

    /** What is on the phone already. */
    @Serializable
    data class Installed(
        val schema: Int = 0,
        val version: Int = 0,
        val builtAt: String = "",
        val rows: Long = 0,
    )

    /** What to do about it. */
    sealed interface Plan {
        /** Nothing to fetch. */
        data object UpToDate : Plan

        /** Fetch the whole thing and replace whatever is there. */
        data class Full(val piece: Piece) : Plan

        /** Fetch these, in this order, and apply each to the database in place. */
        data class Deltas(val chain: List<Piece>) : Plan

        /**
         * The published pack cannot be used by this build, and saying so beats failing later.
         *
         * ⚠️ **The two directions are different situations and must not be collapsed.** A pack
         * NEWER than the app means updating the app fixes it. A pack OLDER means the app is ahead of
         * the publisher and there is nothing the person can do but wait — telling them to update
         * would be advice that cannot work.
         */
        data class Incompatible(val appIsBehind: Boolean) : Plan
    }

    /** Total compressed bytes a plan will download. */
    fun bytesToFetch(plan: Plan): Long = when (plan) {
        is Plan.Full -> plan.piece.bytes
        is Plan.Deltas -> plan.chain.sumOf { it.bytes }
        else -> 0
    }

    /**
     * Decide what this phone should fetch.
     *
     * @param manifest what the publisher says is available.
     * @param installed what is on the phone, or null if nothing is.
     * @param appSchema this build's [SCHEMA]; a parameter rather than the constant so a test can
     *   put the two out of step, which is the case the guard exists for.
     */
    fun plan(manifest: Manifest, installed: Installed?, appSchema: Int = SCHEMA): Plan {
        // ⚠️ Schema first, before anything else is considered. A pack whose columns this build cannot
        // read is not "an update available" however new it is, and offering it would download several
        // hundred megabytes to produce a database that cannot be queried.
        if (manifest.schema != appSchema) return Plan.Incompatible(appIsBehind = manifest.schema > appSchema)
        if (manifest.version <= 0 || manifest.full.name.isBlank()) return Plan.UpToDate

        // ⚠️ An installed pack of a different schema is treated as absent rather than as something to
        // update from. Its rows are the wrong shape, so no delta can be applied to it — the only
        // honest move is to fetch the whole thing again.
        val local = installed?.takeIf { it.schema == appSchema && it.version > 0 }
            ?: return Plan.Full(manifest.full)

        // ⚠️ Never downgrade, and `<=` rather than `==`. A published release can be rolled back, and a
        // phone holding a newer corpus than the publisher currently offers is not out of date — it is
        // ahead, and replacing 425 MB with older data to satisfy an ordering is the wrong answer.
        if (local.version >= manifest.version) return Plan.UpToDate

        val chain = chainFrom(local.version, manifest.version, manifest.deltas)
        if (chain.isEmpty()) return Plan.Full(manifest.full)
        val chainBytes = chain.sumOf { it.bytes }
        // ⚠️ A full pack whose size the manifest never stated cannot be compared against, and
        // guessing would pick whichever branch the zero happened to favour. Take the deltas: they are
        // the ones whose sizes are known.
        if (manifest.full.bytes <= 0) return Plan.Deltas(chain)
        return if (chainBytes < manifest.full.bytes * DELTA_WORTH_IT) {
            Plan.Deltas(chain)
        } else {
            Plan.Full(manifest.full)
        }
    }

    /**
     * The ordered run of deltas from [from] to [to], or empty if there is no complete one.
     *
     * ⚠️ **Contiguous or nothing.** A gap cannot be bridged by skipping it: each delta carries only
     * the rows that changed in its own step, so applying 130→131 and then 132→133 leaves whatever
     * changed in 131→132 permanently wrong, silently, with a database that still answers queries.
     */
    fun chainFrom(from: Int, to: Int, deltas: List<Piece>): List<Piece> {
        if (from >= to) return emptyList()
        // ⚠️ Keyed by `from`, and the LAST writer for a key would win in a plain `associateBy` — so a
        // publisher that offers both 130→131 and 130→133 would be resolved by list order rather than
        // by reach. Prefer the longest hop, which is fewer files and fewer chances to be interrupted.
        val byFrom = HashMap<Int, Piece>()
        for (d in deltas) {
            if (d.name.isBlank() || d.to <= d.from) continue
            val best = byFrom[d.from]
            if (best == null || d.to > best.to) byFrom[d.from] = d
        }
        val chain = ArrayList<Piece>()
        var at = from
        while (at < to) {
            val next = byFrom[at] ?: return emptyList()
            // A delta reaching past the version being published is not part of a chain to it.
            if (next.to > to) return emptyList()
            chain.add(next)
            at = next.to
            if (chain.size > MAX_CHAIN) return emptyList()
        }
        return if (at == to) chain else emptyList()
    }

    /**
     * A size, as a person reads it.
     *
     * ⚠️ Mebibytes, matching every file manager somebody could compare this against, and matching
     * `Formatters.megabytes` in the shared feeds module. A "megabyte" of 1,000,000 bytes would put
     * this 5% out against the number their phone shows them.
     */
    fun describeBytes(bytes: Long): String = when {
        bytes <= 0 -> "unknown size"
        bytes < 1024L * 1024L -> "${(bytes + 1023) / 1024} kB"
        bytes < 1024L * 1024L * 1024L -> "${(bytes + (1024L * 1024L / 2)) / (1024L * 1024L)} MB"
        else -> String.format(java.util.Locale.US, "%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0))
    }

    /**
     * One sentence for what a plan is about to do.
     *
     * ⚠️ **A delta chain names the number of products, not the number of files.** "3 updates" is a
     * statement about our publishing schedule; "51,000 new and changed products" is a statement about
     * what the person gets, and it is the one that explains why the download is worth allowing.
     */
    fun describe(plan: Plan, manifest: Manifest): String = when (plan) {
        Plan.UpToDate -> "Every barcode is up to date."
        is Plan.Full ->
            "Download the food database — ${describeBytes(plan.piece.bytes)}, " +
                "${products(manifest.rows)} products."
        is Plan.Deltas -> {
            val rows = plan.chain.sumOf { it.rows }
            "Update the food database — ${describeBytes(bytesToFetch(plan))}" +
                if (rows > 0) ", ${products(rows)} new and changed products." else "."
        }
        is Plan.Incompatible ->
            if (plan.appIsBehind) {
                "The published food database needs a newer version of this app."
            } else {
                "This app is ahead of the published food database; it will update itself when the " +
                    "next one is built."
            }
    }

    /** A count, grouped, so seven digits are readable at a glance. */
    fun products(n: Long): String {
        if (n <= 0) return "no"
        val s = n.toString()
        val out = StringBuilder()
        for ((i, ch) in s.withIndex()) {
            if (i > 0 && (s.length - i) % 3 == 0) out.append(',')
            out.append(ch)
        }
        return out.toString()
    }
}
