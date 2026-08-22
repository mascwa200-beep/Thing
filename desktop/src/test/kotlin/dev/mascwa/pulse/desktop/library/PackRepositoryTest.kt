package dev.mascwa.pulse.desktop.library

import dev.mascwa.pulse.core.telemetry.ContentPack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Turning a published catalog into packs the reader can actually be offered.
 *
 * The fetch itself needs a network and a token; this is the decision inside it, which is where the
 * mistakes live.
 */
class PackRepositoryTest {

    private fun entry(
        id: String = "cooking",
        asset: String = "pack-cooking-v2.zip",
        version: Int = 2,
    ) = PackCatalogEntry(
        id = id, title = "Cooking", summary = "s", version = version,
        sizeBytes = 4_404_019L, guideCount = 12, asset = asset, sha256 = "abcd",
    )

    /**
     * ⚠️ A row whose asset was never uploaded is dropped rather than offered.
     *
     * A catalog is published by hand as often as not, and a row naming a missing asset is the ordinary
     * mistake. Offering it produces a download button that always fails, which costs more trust than
     * a pack quietly not being there yet.
     */
    @Test
    fun aRowNamingAnAssetThatIsNotThereIsNotOffered() {
        val assets = mapOf("pack-cooking-v2.zip" to "https://api.example.invalid/assets/1")
        val resolved = PackRepository.resolve(
            listOf(entry(), entry(id = "garden", asset = "pack-garden-v1.zip")),
            assets,
        )
        assertEquals(listOf("cooking"), resolved.map { it.id })
        assertEquals("https://api.example.invalid/assets/1", resolved.single().url)
    }

    /**
     * ⚠️ The catalog names an asset; the app decides the URL.
     *
     * The catalog is content and the URL is infrastructure. A row cannot carry a URL of its own, so a
     * published content file can never point the app's authenticated requests somewhere else.
     */
    @Test
    fun everyResolvedUrlComesFromTheReleaseRatherThanTheCatalog() {
        val assets = mapOf("p.zip" to "https://api.github.com/repos/x/y/releases/assets/9")
        val resolved = PackRepository.resolve(listOf(entry(asset = "p.zip")), assets)
        assertEquals("https://api.github.com/repos/x/y/releases/assets/9", resolved.single().url)
        // Nothing a catalog row can say produces a different host.
        assertTrue(resolved.all { it.url in assets.values })
    }

    @Test
    fun anUnusableRowIsDroppedEvenWhenItsAssetExists() {
        val assets = mapOf("p.zip" to "https://api.example.invalid/a")
        assertTrue(PackRepository.resolve(listOf(entry(id = "", asset = "p.zip")), assets).isEmpty())
        assertTrue(PackRepository.resolve(listOf(entry(version = 0, asset = "p.zip")), assets).isEmpty())
    }

    @Test
    fun aBlankTitleFallsBackToTheIdRatherThanRenderingEmpty() {
        val assets = mapOf("p.zip" to "https://api.example.invalid/a")
        val resolved = PackRepository.resolve(
            listOf(PackCatalogEntry(id = "field-medicine", version = 1, asset = "p.zip", guideCount = 3)),
            assets,
        )
        assertEquals("field-medicine", resolved.single().title)
        assertEquals("3 guides", resolved.single().describe())
    }

    @Test
    fun whatIsInstalledDecidesWhatTheOfferSays() {
        val pack = PackRepository.resolve(
            listOf(entry(version = 3)),
            mapOf("pack-cooking-v2.zip" to "https://api.example.invalid/a"),
        ).single()
        assertEquals(ContentPack.State.AVAILABLE, ContentPack.stateOf(pack, null))
        assertEquals(
            ContentPack.State.UPDATABLE,
            ContentPack.stateOf(pack, ContentPack.Installed("cooking", 2, listOf("cooking__a.json"))),
        )
        assertEquals(
            ContentPack.State.INSTALLED,
            ContentPack.stateOf(pack, ContentPack.Installed("cooking", 3, listOf("cooking__a.json"))),
        )
    }
}
