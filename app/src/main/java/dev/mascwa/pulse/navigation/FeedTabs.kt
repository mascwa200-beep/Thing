package dev.mascwa.pulse.navigation

import androidx.compose.runtime.compositionLocalOf

/**
 * The LCARS feed tabs that replace the old Tools grid: a single horizontally-scrolling tab row
 * (route → label, in display order). The TOOLS bottom-nav opens straight into the first feed and each
 * tab jumps directly to its feed — no in-between launcher.
 *
 * (J.A.R.V.I.S. is deliberately not a tab — it has its own Stark-HUD area and is reached from Home.
 *  NAV + QUESTS are no longer top-level tabs either — both are folded into the LCARS hub as its MAP
 *  and QUESTS sections; objectives are reached from the MAP. SURVIVE / SOCIAL / SEARCH are now folded
 *  into the LCARS STATS page as sub-tabs — so it's the single self-contained TOOLS console. All their
 *  standalone routes are kept for Home/hub deep-links.)
 */
val FEED_TABS: List<Pair<String, String>> = listOf(
    Routes.TACNET to "LCARS",
)

/** Routes that count as "feeds" — the TOOLS tab highlights on any of them and the tab bar shows. */
val FEED_ROUTES: Set<String> = FEED_TABS.map { it.first }.toSet()

/** The feed the TOOLS bottom-nav lands on by default. */
const val FEED_HOME: String = Routes.TACNET

/** The active feed-tab context, provided once around the NavHost; null on non-feed screens. */
class FeedTabState(val current: String, val onSelect: (String) -> Unit)

val LocalFeedTabs = compositionLocalOf<FeedTabState?> { null }
