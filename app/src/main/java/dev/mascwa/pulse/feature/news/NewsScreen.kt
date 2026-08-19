package dev.mascwa.pulse.feature.news

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import dev.mascwa.pulse.feature.common.LcarsIcons
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mascwa.pulse.core.telemetry.Readability
import dev.mascwa.pulse.core.util.openUrl
import dev.mascwa.pulse.feature.common.EmptyState
import dev.mascwa.pulse.feature.common.ErrorState
import dev.mascwa.pulse.feature.common.LoadingState
import dev.mascwa.pulse.feature.live.LiveVideoPlayer
import dev.mascwa.pulse.feature.common.PulseScaffold
import dev.mascwa.pulse.feature.common.StaleBanner

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewsScreen(vm: NewsViewModel, onRead: ((title: String, url: String) -> Unit)? = null) {
    val state by vm.state.collectAsStateWithLifecycle()
    val analyses by vm.analyses.collectAsStateWithLifecycle()
    val coverageByUrl by vm.coverageByUrl.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var searchActive by remember { mutableStateOf(false) }
    var searchText by remember { mutableStateOf("") }

    PulseScaffold(
        title = "News",
        topBarOverride = {
            Column {
                if (searchActive) {
                    TextField(
                        value = searchText,
                        onValueChange = { searchText = it },
                        placeholder = { Text("Search news…") },
                        singleLine = true,
                        leadingIcon = {
                            IconButton(onClick = {
                                searchActive = false
                                searchText = ""
                                vm.clearSearch()
                            }) { Icon(LcarsIcons.ArrowBack, "Back") }
                        },
                        trailingIcon = {
                            if (searchText.isNotEmpty()) {
                                IconButton(onClick = { searchText = "" }) {
                                    Icon(LcarsIcons.Close, "Clear")
                                }
                            }
                        },
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                            onSearch = { vm.search(searchText) },
                        ),
                        colors = TextFieldDefaults.colors(
                            unfocusedIndicatorColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .windowInsetsPadding(WindowInsets.statusBars),
                    )
                } else {
                    TopAppBar(
                        title = { Text(if (state.searchMode) "Results: ${state.query}" else "News") },
                        actions = {
                            IconButton(onClick = { searchActive = true }) {
                                Icon(LcarsIcons.Search, "Search")
                            }
                        },
                    )
                }
                if (!state.searchMode && state.tabs.isNotEmpty()) {
                    ScrollableTabRow(
                        selectedTabIndex = state.selectedIndex.coerceIn(0, state.tabs.lastIndex),
                        edgePadding = 8.dp,
                    ) {
                        state.tabs.forEachIndexed { i, tab ->
                            Tab(
                                selected = i == state.selectedIndex,
                                onClick = { vm.selectTab(i) },
                                text = { Text(tab.title) },
                            )
                        }
                    }
                }
            }
        },
    ) { innerPadding ->
        val content = state.content
        // Live television is not a list of articles and has nothing to pull down for, so it sits
        // outside the refresh box entirely rather than inside it doing nothing.
        val liveTab = !state.searchMode && state.tabs.getOrNull(state.selectedIndex)?.live == true
        if (liveTab) {
            LiveVideoPlayer(
                modifier = Modifier
                    .padding(innerPadding)
                    .padding(12.dp),
            )
        } else PullToRefreshBox(
            isRefreshing = content.loading && content.data != null,
            onRefresh = { vm.refresh() },
            modifier = Modifier.padding(innerPadding),
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
