package dev.mascwa.pulse.feature.survive

import dev.mascwa.pulse.navigation.Routes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The offline takeover's lists are derived from each tile's declared [Need], and this holds them to it.
 *
 * The list used to be curated by hand and got it wrong in both directions: it dropped Wildlife on the
 * stated grounds that it needed "a live GPS+Overpass fetch" — it needs neither — while offering Nearest
 * Help and Nearby Safety as though they worked, when both are network-backed. A screen headed "these
 * tools work with no signal" listing things that cannot is worse than no screen.
 */
class OfflineTilesTest {

    private val ready = offlineReadyTiles()
    private val cached = offlineCachedTiles()

    /**
     * Guards the derivation, not the classification: it fires if [offlineReadyTiles] is ever widened to
     * admit a tile that needs fetching. Whether each tile's [Need] is *correct* is not something a unit
     * test can establish — that came from reading each destination's view model for a repository or an
     * HTTP call, and it is why the two specific cases below are asserted by name.
     */
    @Test
    fun nothingThatNeedsTheNetworkIsOfferedAsWorkingOffline() {
        val wrong = ready.filter { it.needs == Need.NETWORK || it.needs == Need.CACHE }
        assertTrue("offered as working offline but needs a connection: ${wrong.map { it.title }}", wrong.isEmpty())
    }

    @Test
    fun everythingUnderLastReceivedIsThereBecauseItCaches() {
        assertTrue(cached.isNotEmpty())
        assertTrue(cached.all { it.needs == Need.CACHE })
    }

    /** The specific regression: it is offline, and it was hidden for a reason that was never true. */
    @Test
    fun wildlifeIsOfferedOfflineBecauseItIsOffline() {
        assertTrue(
            "Wildlife needs a GPS fix and a lookup table, nothing more",
            ready.any { it.route == Routes.HABITAT },
        )
    }

    /** The mirror image: it hits overpass-api.de, so it cannot sit under "works right now". */
    @Test
    fun nearestHelpIsNotOfferedAsWorkingOffline() {
        assertTrue(ready.none { it.route == Routes.PLACES })
        assertTrue(cached.any { it.route == Routes.PLACES })
    }

    /**
     * The app grew these after the offline screen was written and the screen never learned about them.
     * Each was checked to hold no repository and make no request.
     */
    @Test
    fun theOfflineCapableDestinationsAddedSinceAreAllOffered() {
        val expected = listOf(Routes.STUDY, Routes.SEARCH, Routes.NOTES, Routes.DIARY, Routes.SURVIVAL)
        val missing = expected.filter { route -> ready.none { it.route == route } }
        assertTrue("works offline but not offered: $missing", missing.isEmpty())
    }

    /** A route appearing twice would render two identical cards and collide on the lazy-grid key. */
    @Test
    fun noRouteIsListedTwice() {
        val routes = (ready + cached).map { it.route }
        assertEquals(routes.size, routes.distinct().size)
    }

    /**
     * Catches the real omission: a tile added to the hub but forgotten in `ALL_TILES`, which would make
     * it usable in the app and invisible the moment the connection drops — exactly how the previous
     * list went wrong.
     */
    @Test
    fun everyHubTileIsAccountedFor() {
        val hub = surviveGroups().flatMap { it.tiles }
        val classified = (ready + cached).map { it.route }.toSet()
        val unreachable = hub.filter { it.needs != Need.NETWORK && it.route !in classified }
        assertTrue("classified as usable but on neither list: ${unreachable.map { it.title }}", unreachable.isEmpty())
    }
}
