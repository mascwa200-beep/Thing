package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a phone fetches to hold every food barcode there is.
 *
 * The rules that matter here are all refusals — never downgrade, never apply a pack this build
 * cannot read, never chain across a gap — and every one of them fails in a way that leaves a
 * database still answering queries, just wrongly. That is what makes them worth pinning.
 */
class FoodPackTest {

    private fun full(bytes: Long = 160_000_000, to: Int = 132) = FoodPack.Piece(
        name = "food-$to.db.gz", bytes = bytes, sha256 = "a".repeat(64),
        unpackedBytes = bytes * 3, to = to,
    )

    private fun delta(from: Int, to: Int, bytes: Long = 4_000_000, rows: Long = 40_000) =
        FoodPack.Piece(
            name = "food-$from-$to.delta.gz", bytes = bytes, sha256 = "b".repeat(64),
            unpackedBytes = bytes * 3, from = from, to = to, rows = rows,
        )

    private fun manifest(
        version: Int = 132,
        schema: Int = FoodPack.SCHEMA,
        fullBytes: Long = 160_000_000,
        deltas: List<FoodPack.Piece> = emptyList(),
    ) = FoodPack.Manifest(
        schema = schema, version = version, builtAt = "2026-08-31", rows = 4_524_449,
        full = full(fullBytes, version), deltas = deltas,
    )

    private fun installed(version: Int, schema: Int = FoodPack.SCHEMA) =
        FoodPack.Installed(schema = schema, version = version, builtAt = "2026-08-01", rows = 4_500_000)

    // ---- the refusals -----------------------------------------------------------------------

    /**
     * ⚠️ **A schema this build cannot read is not "an update available".** Offering it downloads
     * several hundred megabytes to produce a database whose columns the app cannot query — and it
     * would do that every time the check ran.
     */
    @Test
    fun aPackThisBuildCannotReadIsNeverOffered() {
        val newer = FoodPack.plan(manifest(schema = FoodPack.SCHEMA + 1), null)
        assertEquals(FoodPack.Plan.Incompatible(appIsBehind = true), newer)
        val older = FoodPack.plan(manifest(schema = FoodPack.SCHEMA - 1), installed(131))
        assertEquals(FoodPack.Plan.Incompatible(appIsBehind = false), older)
    }

    /**
     * ⚠️ **The two directions of that are different situations and the sentence must differ.**
     * "Update the app" is advice that works in one case and is impossible in the other, and telling
     * somebody to do something that cannot help is worse than saying nothing.
     */
    @Test
    fun theTwoDirectionsOfIncompatibilityAreSaidDifferently() {
        val behind = FoodPack.describe(FoodPack.Plan.Incompatible(true), manifest())
        val ahead = FoodPack.describe(FoodPack.Plan.Incompatible(false), manifest())
        assertTrue(behind, behind.contains("newer version of this app"))
        assertTrue(ahead, !ahead.contains("newer version of this app"))
        assertTrue(ahead, ahead.contains("ahead"))
    }

    /**
     * ⚠️ **Never downgrade.** A published release can be rolled back, and a phone holding a newer
     * corpus than the publisher currently offers is ahead rather than out of date. Replacing 425 MB
     * of data with older data to satisfy an ordering is the wrong answer, and it would repeat on
     * every check.
     */
    @Test
    fun aPhoneAheadOfThePublisherIsLeftAlone() {
        assertEquals(FoodPack.Plan.UpToDate, FoodPack.plan(manifest(version = 130), installed(132)))
        assertEquals(FoodPack.Plan.UpToDate, FoodPack.plan(manifest(version = 132), installed(132)))
    }

    /**
     * ⚠️ **A chain with a gap is not a chain.** Each delta carries only the rows that changed in its
     * own step, so applying 130→131 and then 132→133 leaves everything that changed in 131→132
     * permanently wrong — silently, in a database that still answers every query.
     */
    @Test
    fun aGapInTheChainFallsBackToTheWholeThing() {
        val gapped = manifest(version = 133, deltas = listOf(delta(130, 131), delta(132, 133)))
        assertEquals(FoodPack.Plan.Full(full(160_000_000, 133)), FoodPack.plan(gapped, installed(130)))
        assertEquals(emptyList<FoodPack.Piece>(), FoodPack.chainFrom(130, 133, gapped.deltas))
    }

    /** Nothing installed is the ordinary first run: fetch the whole thing. */
    @Test
    fun aPhoneWithNoDatabaseFetchesTheWholeThing() {
        assertEquals(FoodPack.Plan.Full(full()), FoodPack.plan(manifest(), null))
    }

    /**
     * ⚠️ **An installed pack of the wrong schema is treated as ABSENT, not as something to update
     * from.** Its rows are the wrong shape, so no delta can be applied to it; the only honest move is
     * to fetch the whole thing. Reading its version and offering a delta would apply new-shaped rows
     * to an old-shaped table.
     */
    @Test
    fun anInstalledPackOfTheWrongShapeIsTreatedAsNothingAtAll() {
        val m = manifest(version = 132, deltas = listOf(delta(131, 132)))
        assertEquals(FoodPack.Plan.Full(full()), FoodPack.plan(m, installed(131, schema = FoodPack.SCHEMA - 1)))
        // ...and with the right schema the very same version DOES take the delta, so the test above
        // is about the schema and not about the version.
        assertEquals(FoodPack.Plan.Deltas(listOf(delta(131, 132))), FoodPack.plan(m, installed(131)))
    }

    // ---- choosing between a chain and the whole thing ----------------------------------------

    /**
     * The boundary, computed rather than eyeballed: the full pack is 100,000,000 bytes and
     * [FoodPack.DELTA_WORTH_IT] is 0.6, so the threshold is exactly 60,000,000 and the comparison is
     * strict. A chain one byte under takes the deltas; a chain exactly on it takes the full pack.
     */
    @Test
    fun aChainIsOnlyWorthItWhenTheSavingIsReal() {
        fun planFor(chainBytes: Long) = FoodPack.plan(
            manifest(version = 132, fullBytes = 100_000_000, deltas = listOf(delta(131, 132, chainBytes))),
            installed(131),
        )
        assertTrue(planFor(59_999_999) is FoodPack.Plan.Deltas)
        assertTrue(planFor(60_000_000) is FoodPack.Plan.Full)
        assertTrue(planFor(60_000_001) is FoodPack.Plan.Full)
        // A realistic monthly delta is nowhere near the boundary, which is the point of having one.
        assertTrue(planFor(4_000_000) is FoodPack.Plan.Deltas)
    }

    /**
     * ⚠️ **A full pack whose size the manifest never stated cannot be compared against.** Guessing
     * would silently pick whichever branch the zero happened to favour — and `0 * 0.6` is zero, so a
     * naive comparison sends every phone down the full-download path for ever.
     */
    @Test
    fun anUnstatedFullSizeTakesTheDeltasRatherThanGuessing() {
        val m = manifest(version = 132, fullBytes = 0, deltas = listOf(delta(131, 132)))
        assertEquals(FoodPack.Plan.Deltas(listOf(delta(131, 132))), FoodPack.plan(m, installed(131)))
    }

    /**
     * ⚠️ **A run longer than [FoodPack.MAX_CHAIN] takes the full pack.** Every delta applied is a
     * chance to be interrupted with a half-updated database, and they have to go in order.
     */
    @Test
    fun aVeryLongRunOfDeltasTakesTheFullPackInstead() {
        val many = (100 until 120).map { delta(it, it + 1, bytes = 1_000) }
        val m = manifest(version = 120, deltas = many)
        assertTrue(FoodPack.plan(m, installed(100)) is FoodPack.Plan.Full)
        assertEquals(emptyList<FoodPack.Piece>(), FoodPack.chainFrom(100, 120, many))
        // ...and a run exactly at the cap is still taken, or the cap would be off by one.
        val eight = (100 until 108).map { delta(it, it + 1, bytes = 1_000) }
        assertEquals(FoodPack.MAX_CHAIN, FoodPack.chainFrom(100, 108, eight).size)
    }

    // ---- the chain itself --------------------------------------------------------------------

    /** The ordinary multi-step case, in order. */
    @Test
    fun aContiguousRunIsReturnedInOrder() {
        val ds = listOf(delta(132, 133), delta(130, 131), delta(131, 132))
        assertEquals(listOf(130 to 131, 131 to 132, 132 to 133),
            FoodPack.chainFrom(130, 133, ds).map { it.from to it.to })
    }

    /**
     * ⚠️ **Where two deltas start at the same version, take the one that reaches furthest.** A plain
     * `associateBy` resolves that by list order, so a publisher offering both 130→131 and 130→133
     * would be chained by whichever it happened to write second — fewer files is strictly better, and
     * it should not depend on the order of a JSON array.
     */
    @Test
    fun theLongestHopWinsRatherThanWhicheverWasListedLast() {
        val ds = listOf(delta(130, 131), delta(130, 133), delta(131, 132), delta(132, 133))
        assertEquals(listOf(130 to 133), FoodPack.chainFrom(130, 133, ds).map { it.from to it.to })
        // ...and with the long hop listed first, the answer is the same, which is what makes this
        // about reach rather than about order.
        assertEquals(listOf(130 to 133), FoodPack.chainFrom(130, 133, ds.reversed()).map { it.from to it.to })
    }

    /**
     * ⚠️ **A delta reaching PAST the version being published is not part of a chain to it.** Applying
     * it would leave the phone holding rows from a version the manifest does not describe, and the
     * recorded local version would then be a lie about what is in the file.
     */
    @Test
    fun aDeltaThatOvershootsIsNotUsed() {
        val ds = listOf(delta(130, 135))
        assertEquals(emptyList<FoodPack.Piece>(), FoodPack.chainFrom(130, 133, ds))
    }

    /** Nonsense pieces are dropped rather than followed. */
    @Test
    fun malformedDeltasAreIgnored() {
        val ds = listOf(
            delta(130, 131).copy(name = ""),      // no asset to download
            delta(131, 131),                      // reaches nowhere
            delta(132, 130),                      // backwards
        )
        assertEquals(emptyList<FoodPack.Piece>(), FoodPack.chainFrom(130, 133, ds))
        assertEquals(emptyList<FoodPack.Piece>(), FoodPack.chainFrom(133, 130, listOf(delta(130, 133))))
    }

    // ---- what the person is told --------------------------------------------------------------

    /**
     * ⚠️ **A delta chain names PRODUCTS, not files.** "3 updates" is a statement about our publishing
     * schedule; "120,000 new and changed products" is a statement about what they get, and it is the
     * one that explains why a download is worth allowing.
     */
    @Test
    fun theSentenceSaysWhatIsGainedRatherThanHowManyFiles() {
        val chain = listOf(delta(130, 131, rows = 40_000), delta(131, 132, rows = 80_000))
        val said = FoodPack.describe(FoodPack.Plan.Deltas(chain), manifest())
        assertTrue(said, said.contains("120,000 new and changed products"))
        assertTrue(said, !said.contains("2 "))
        val whole = FoodPack.describe(FoodPack.Plan.Full(full()), manifest())
        assertTrue(whole, whole.contains("4,524,449 products"))
    }

    /**
     * Sizes as a person reads them, in mebibytes to match every file manager they could compare
     * against. Each expected value computed: 160,000,000 / 1,048,576 = 152.59 → 153;
     * 4,000,000 / 1,048,576 = 3.81 → 4; 700 kB stays in kilobytes.
     */
    @Test
    fun sizesAreSaidInTheUnitsAPhoneShows() {
        assertEquals("153 MB", FoodPack.describeBytes(160_000_000))
        assertEquals("4 MB", FoodPack.describeBytes(4_000_000))
        assertEquals("684 kB", FoodPack.describeBytes(700_000))
        assertEquals("1.4 GB", FoodPack.describeBytes(1_500_000_000))
        assertEquals("unknown size", FoodPack.describeBytes(0))
        assertEquals("unknown size", FoodPack.describeBytes(-1))
    }

    /** Grouping, because seven digits are unreadable without it. */
    @Test
    fun countsAreGrouped() {
        assertEquals("4,524,449", FoodPack.products(4_524_449))
        assertEquals("1,000", FoodPack.products(1_000))
        assertEquals("999", FoodPack.products(999))
        assertEquals("no", FoodPack.products(0))
    }

    /** How much is actually about to be downloaded, which is what a data warning has to state. */
    @Test
    fun theBytesToFetchAreTheCompressedOnes() {
        assertEquals(160_000_000, FoodPack.bytesToFetch(FoodPack.Plan.Full(full())))
        assertEquals(
            7_000_000,
            FoodPack.bytesToFetch(
                FoodPack.Plan.Deltas(listOf(delta(130, 131, 3_000_000), delta(131, 132, 4_000_000))),
            ),
        )
        assertEquals(0, FoodPack.bytesToFetch(FoodPack.Plan.UpToDate))
        assertEquals(0, FoodPack.bytesToFetch(FoodPack.Plan.Incompatible(true)))
    }

    /**
     * ⚠️ A manifest that says nothing yields "nothing to do" rather than a download of a nameless
     * asset. The publisher's first run, a truncated fetch and a 404 body all land here.
     */
    @Test
    fun anEmptyManifestAsksForNothing() {
        assertEquals(FoodPack.Plan.UpToDate, FoodPack.plan(FoodPack.Manifest(schema = FoodPack.SCHEMA), null))
        assertEquals(
            FoodPack.Plan.UpToDate,
            FoodPack.plan(FoodPack.Manifest(schema = FoodPack.SCHEMA, version = 5), null),
        )
    }
}
