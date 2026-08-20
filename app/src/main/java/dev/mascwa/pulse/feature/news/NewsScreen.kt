package dev.mascwa.pulse.feature.news

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mascwa.pulse.core.telemetry.Readability
import dev.mascwa.pulse.core.util.openUrl
import dev.mascwa.pulse.feature.common.EmptyState
import dev.mascwa.pulse.feature.common.ErrorState
import dev.mascwa.pulse.feature.common.LcarsButton
import dev.mascwa.pulse.feature.common.LcarsField
import dev.mascwa.pulse.feature.common.LcarsIcons
import dev.mascwa.pulse.feature.common.LcarsTabRow
import dev.mascwa.pulse.feature.common.LoadingState
import dev.mascwa.pulse.feature.common.PulseScaffold
import dev.mascwa.pulse.feature.common.StaleBanner
import dev.mascwa.pulse.feature.live.LiveVideoPlayer
import dev.mascwa.pulse.ui.theme.JetBrainsMono
import dev.mascwa.pulse.ui.theme.Pulse

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewsScreen(vm: NewsViewModel, onRead: ((title: String, url: String) -> Unit)? = null) {
    val state by vm.state.collectAsStateWithLifecycle()
    val analyses by vm.analyses.collectAsStateWithLifecycle()
    val coverageByUrl by vm.coverageByUrl.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // ⚠️ THE DURABLE HALF OF SEARCH LIVES IN THE VM. These two are keyed on state.searchMode so a
    // TRANSITION re-derives them: navigate to the reader mid-search (or rotate) and this
    // composition dies while the VM — scoped to the NavBackStackEntry — keeps searchMode=true and
    // the results as content. Plain remember{false} here made the rail reappear highlighting
    // BREAKING over a list still showing the search hits, with the RESULTS line unreachable.
    // The key only changes on mode transitions, so typing never re-initialises these.
    var searchOpen by remember(state.searchMode) { mutableStateOf(state.searchMode) }
    var searchText by remember(state.searchMode) {
        mutableStateOf(if (state.searchMode) state.query else "")
    }

    // ⚠️ System back leaves SEARCH MODE, not the News tab — gated on the sub-state so it swallows
    // nothing when search is closed.
    androidx.activity.compose.BackHandler(enabled = searchOpen) {
        searchOpen = false
        searchText = ""
        vm.clearSearch()
    }

    // News was the LAST screen on stock Material chrome — its own TopAppBar + ScrollableTabRow
    // behind a topBarOverride. It now takes the same LCARS frame as every other screen (only Home
    // still draws its own masthead), the tab rail is the shared LcarsTabRow, and the search box is
    // the one LcarsField. CANCEL is its own labelled button rather than a ✕ that meant a different
    // verb from the ✕ beside it.
    PulseScaffold(
        title = "News",
        actions = {
            // Gated: with the field already open the magnifier would be a live-looking control
            // that fires the tap cue and changes nothing.
            if (!searchOpen) {
                IconButton(onClick = { searchOpen = true }) {
                    Icon(LcarsIcons.Search, "Search")
                }
            }
        },
    ) { innerPadding ->
        Column(Modifier.padding(innerPadding)) {
        if (searchOpen) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(Modifier.weight(1f)) {
                    LcarsField(
                        value = searchText,
                        onValueChange = { searchText = it },
                        placeholder = "Search news…",
                        leadingIcon = LcarsIcons.Search,
                        imeAction = ImeAction.Search,
                        onImeAction = { vm.search(searchText) },
                    )
                }
                LcarsButton("CANCEL", onClick = {
                    searchOpen = false
                    searchText = ""
                    vm.clearSearch()
                })
            }
            if (state.searchMode) {
                Text(
                    "RESULTS · ${state.query.uppercase()}",
                    fontFamily = JetBrainsMono, fontSize = 10.sp, color = Pulse.colors.muted,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 2.dp),
                )
            }
        } else if (state.tabs.isNotEmpty()) {
            LcarsTabRow(
                tabs = state.tabs.map { it.title },
                selected = state.selectedIndex.coerceIn(0, state.tabs.lastIndex),
                onSelect = vm::selectTab,
            )
        }
        val content = state.content
        // Live television is not a list of articles and has nothing to pull down for, so it sits
        // outside the refresh box entirely rather than inside it doing nothing.
        val liveTab = !state.searchMode && state.tabs.getOrNull(state.selectedIndex)?.live == true
        if (liveTab) {
            LiveVideoPlayer(
                modifier = Modifier.padding(12.dp),
            )
        } else PullToRefreshBox(
            isRefreshing = content.loading && content.data != null,
            onRefresh = { vm.refresh() },
        ) {
            when {
                content.isInitialLoading -> LoadingState()
                content.isError -> ErrorState(content.error ?: "Error", onRetry = { vm.refresh() })
                content.data.isNullOrEmpty() -> EmptyState("No articles found.", onRetry = { vm.refresh() })
                else -> {
                    val distinctArticles = content.data!!.distinctBy { it.url }
                    LazyColumn(
                        contentPadding = PaddingValues(12.dp),
                        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp),
                    ) {
                        item { StaleBanner(content) }
                        items(distinctArticles, key = { it.url }) { article ->
                            ArticleCard(
                                article,
                                pulse = state.marketPulse,
                                allArticles = distinctArticles,
                                // ⚠️ ROUTED, NOT ALWAYS THE READER. Most of this feed is Google
                                // News links, which are redirect stubs the decimator can never read
                                // — sending those to the reader would replace a browser that works
                                // with a polite refusal. The rule lives in Readability so the
                                // screen and the extraction cannot disagree about it.
                                onClick = {
                                    if (onRead != null && Readability.canRead(article.url)) {
                                        onRead(article.title, article.url)
                                    } else {
                                        openUrl(context, article.url)
                                    }
                                },
                                analysis = analyses[article.url],
                                onNeedsAnalysis = vm::ensureAnalyzed,
                                coverage = coverageByUrl[article.url],
                                onNeedsCoverage = vm::ensureCoverage,
                                socialTitles = state.socialTitles,
                                trendTagNames = state.trendTagNames,
                            )
                        }
                    }
                }
            }
        }
        }
    }
}
