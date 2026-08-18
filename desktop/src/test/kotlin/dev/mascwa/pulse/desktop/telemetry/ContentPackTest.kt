// MIRROR OF core/telemetry/src/test/java/dev/mascwa/pulse/core/telemetry/ContentPackTest.kt — regenerate with tools/mirror_desktop_cores.py; MirrorDriftTest holds it
package dev.mascwa.pulse.desktop.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentPackTest {

    private fun pack(
        id: String = "cooking",
        version: Int = 2,
        size: Long = 4_404_019L,
        sha: String = "",
        url: String = "https://example.invalid/cooking.zip",
    ) = ContentPack.Pack(
        id = id, title = "Cooking", summary = "s", version = version,
        sizeBytes = size, guideCount = 12, url = url, sha256 = sha,
    )

    private fun installed(version: Int) = ContentPack.Installed("cooking", version, listOf("cooking__a.json"))

    // ---- what to fetch ------------------------------------------------------------------------------

    @Test
    fun aPackIsFetchedWhenItIsAbsentOrTheCatalogHasSomethingNewer() {
        assertTrue(ContentPack.shouldInstall(pack(version = 2), installed = null))
        assertTrue(ContentPack.shouldInstall(pack(version = 3), installed(2)))
        assertFalse("already current", ContentPack.shouldInstall(pack(version = 2), installed(2)))
    }

    /**
     * ⚠️ Strictly newer, never merely different.
     *
     * A catalog that has been rolled back — a bad pack pulled and the previous one restored under a
     * lower number — must not make the app replace the newer content it already holds with older
     * content, and then do it again on every launch for as long as the rollback stands.
     */
    @Test
    fun aRolledBackCatalogDoesNotDowngradeWhatIsAlreadyHeld() {
        assertFalse(ContentPack.shouldInstall(pack(version = 1), installed(4)))
        assertEquals(ContentPack.State.INSTALLED, ContentPack.stateOf(pack(version = 1), installed(4)))
    }

    @Test
    fun anUnusablePackIsNeverFetched() {
        assertFalse("no url", ContentPack.shouldInstall(pack(url = ""), installed = null))
        assertFalse("no id", ContentPack.shouldInstall(pack(id = ""), installed = null))
        assertFalse("no version", ContentPack.shouldInstall(pack(version = 0), installed = null))
    }

    @Test
    fun stateSaysWhatThePackIsDoing() {
        assertEquals(ContentPack.State.AVAILABLE, ContentPack.stateOf(pack(), null))
        assertEquals(ContentPack.State.INSTALLED, ContentPack.stateOf(pack(version = 2), installed(2)))
        assertEquals(ContentPack.State.UPDATABLE, ContentPack.stateOf(pack(version = 3), installed(2)))
    }

    // ---- names --------------------------------------------------------------------------------------

    /**
     * ⚠️ The storage name is DERIVED, never taken from the archive.
     *
     * An archive is remote content. A file called `../guide_index.json` inside one, written where it
     * asked to be written, lands straight on top of the bundled catalog — which is the whole library.
     */
    @Test
    fun anArchiveCannotNameItsWayOutOfItsOwnFolder() {
        assertEquals("cooking__guide_index.json", ContentPack.qualify("cooking", "../guide_index.json"))
        assertEquals("cooking__passwd", ContentPack.qualify("cooking", "../../../../etc/passwd"))
        assertEquals("cooking__x.json", ContentPack.qualify("cooking", "sub\\dir\\x.json"))
        // And a hostile pack ID cannot escape either.
        assertEquals("evil__x.json", ContentPack.qualify("../evil", "x.json"))
        assertEquals("pack__x.json", ContentPack.qualify("", "x.json"))
    }

    /**
     * Two packs offering the same file name must not be able to overwrite one another.
     *
     * ⚠️ Nor can either collide with the bundle: verified against the real corpus, **no** bundled
     * shard name contains a double underscore (they are `guides_<slug>[_N].json`), which is exactly
     * what [ContentPack.isPackFile] keys on.
     */
    @Test
    fun packFilesCannotCollideWithEachOtherOrWithTheBundle() {
        val a = ContentPack.qualify("cooking", "guides_1.json")
        val b = ContentPack.qualify("gardening", "guides_1.json")
        assertTrue(a != b)
        assertEquals("cooking", ContentPack.packIdOf(a))
        assertEquals("gardening", ContentPack.packIdOf(b))

        assertFalse(ContentPack.isPackFile("guides_agriculture_and_gardening.json"))
        assertFalse(ContentPack.isPackFile("guide_index.json"))
        assertNull(ContentPack.packIdOf("guides_astronomy.json"))
        assertFalse("not even a json file", ContentPack.isPackFile("cooking__notes.txt"))
    }

    // ---- one library --------------------------------------------------------------------------------

    private data class E(val id: String)

    /**
     * ⚠️ The bundle wins every collision, and this is a safety property rather than a preference.
     *
     * The bundled corpus is the one CI validates and the one the emergency protocols live in. A pack
     * able to shadow `first-aid` could replace what somebody reads while performing CPR.
     */
    @Test
    fun aPackCanAddToTheLibraryButNeverShadowIt() {
        val bundled = listOf(E("first-aid"), E("water"))
        val packs = listOf(
            "cooking" to listOf(E("first-aid"), E("braising")),
            "garden" to listOf(E("braising"), E("compost")),
        )
        val merged = ContentPack.merge(bundled, packs) { it.id }

        assertEquals(listOf("first-aid", "water", "braising", "compost"), merged.map { it.id })
        // The pack's "first-aid" is gone, and the bundled one is the one that survived.
        assertEquals(1, merged.count { it.id == "first-aid" })
        assertTrue(merged[0] === bundled[0])
        // And the second pack did not get to overwrite the first pack's braising either.
        assertTrue(merged.first { it.id == "braising" } === packs[0].second[1])
    }

    @Test
    fun theLibraryHoldsItsOrderSoBrowseRailsDoNotReshuffle() {
        val bundled = listOf(E("a"), E("b"))
        val packs = listOf("p1" to listOf(E("c")), "p2" to listOf(E("d")))
        assertEquals(listOf("a", "b", "c", "d"), ContentPack.merge(bundled, packs) { it.id }.map { it.id })
        assertEquals(
            listOf("a", "b", "d", "c"),
            ContentPack.merge(bundled, packs.reversed()) { it.id }.map { it.id },
        )
    }

    /** What a pack really adds, once collisions are taken off — the honest number for a button. */
    @Test
    fun theAddedCountIsWhatSurvivesTheMergeRatherThanWhatWasClaimed() {
        val bundled = listOf(E("first-aid"), E("water"))
        val packs = listOf("cooking" to listOf(E("first-aid"), E("braising"), E("stock")))
        assertEquals(2, ContentPack.newCount(bundled, packs) { it.id })
        assertEquals(0, ContentPack.newCount(bundled, emptyList<Pair<String, List<E>>>()) { it.id })
        // A bundle that already repeats itself must not make the count go negative.
        assertEquals(0, ContentPack.newCount(listOf(E("a"), E("a")), emptyList<Pair<String, List<E>>>()) { it.id })
    }

    // ---- is it the pack we asked for? ---------------------------------------------------------------

    @Test
    fun anArchiveThatIsNotThePackIsRejected() {
        val p = pack(size = 1_000L, sha = "ABCD")
        assertTrue(ContentPack.verifies(p, 1_000L, "abcd"))
        assertFalse("truncated download", ContentPack.verifies(p, 900L, "abcd"))
        assertFalse("a portal's login page", ContentPack.verifies(p, 1_000L, "beef"))
        // ⚠️ A stated digest with nothing to compare it to is the hashing having failed, not a pass.
        assertFalse(ContentPack.verifies(p, 1_000L, ""))
    }

    @Test
    fun aCatalogThatStatesNoDigestIsStillCheckedOnSize() {
        val p = pack(size = 1_000L, sha = "")
        assertTrue(ContentPack.verifies(p, 1_000L, ""))
        assertFalse(ContentPack.verifies(p, 999L, ""))
        // Nothing stated at all: nothing to check against, so nothing is claimed.
        assertTrue(ContentPack.verifies(pack(size = 0L, sha = ""), 12L, ""))
    }

    // ---- saying it to a person ----------------------------------------------------------------------

    /** Arithmetic computed from the shipped rounding, not recalled. */
    @Test
    fun sizesReadTheWayADownloadPromptShouldSayThem() {
        assertEquals("unknown size", ContentPack.describeSize(0))
        assertEquals("500 B", ContentPack.describeSize(500))
        // (1024 + 512) / 1024 = 1
        assertEquals("1 KB", ContentPack.describeSize(1024))
        // (1536 + 512) / 1024 = 2
        assertEquals("2 KB", ContentPack.describeSize(1536))
        // (4404019*10 + 524288) / 1048576 = 42  ->  4.2
        assertEquals("4.2 MB", ContentPack.describeSize(4_404_019))
        // At and above ten megabytes the tenth stops earning its place.
        assertEquals("10 MB", ContentPack.describeSize(10L * 1024 * 1024))
        assertEquals("1.0 GB", ContentPack.describeSize(1024L * 1024 * 1024))
    }

    @Test
    fun aPackSaysWhatItIsBeforeYouAgreeToDownloadIt() {
        assertEquals("12 guides · 4.2 MB", pack().describe())
        assertEquals(
            "1 guide",
            ContentPack.Pack("x", "X", "s", 1, 0L, 1, "https://example.invalid/x.zip").describe(),
        )
    }
}
