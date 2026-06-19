package dev.mascwa.pulse.navigation

import androidx.compose.runtime.compositionLocalOf

/**
 * The Fallout Pip-Boy feed tabs that replace the old Tools grid: a single horizontally-scrolling
 * tab row (route → label, in display order). The TOOLS bottom-nav opens straight into the first
 * feed and each tab jumps directly to its feed — no in-between launcher.
 *
 * (J.A.R.V.I.S. is deliberately not a tab — it has its own Stark-HUD area and is reached from Home.
 *  OBJECTIVES is no longer a tab either — it's folded into the NAV map as a MAP|OBJECTIVES sub-tab.)
 */
val FEED_TABS: List<Pair<String, String>> = listOf(
    Routes.TACNET to "PIP-BOY",
    Routes.NAV to "NAV",
    Routes.SURVIVE to "SURVIVE",
    Routes.SOCIAL to "SOCIAL",
    Routes.SEARCH to "SEARCH",
)

/** Routes that count as "feeds" — the TOOLS tab highlights on any of them and the tab bar shows. */
val FEED_ROUTES: Set<String> = FEED_TABS.map { it.first }.toSet()

/** The feed the TOOLS bottom-nav lands on by default. */
const val FEED_HOME: String = Routes.TACNET

/** The active feed-tab context, provided once around the NavHost; null on non-feed screens. */
class FeedTabState(val current: String, val onSelect: (String) -> Unit)

val LocalFeedTabs = compositionLocalOf<FeedTabState?> { null }
