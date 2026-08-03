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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
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
import dev.mascwa.pulse.core.util.openUrl
import dev.mascwa.pulse.feature.common.EmptyState
import dev.mascwa.pulse.feature.common.ErrorState
import dev.mascwa.pulse.feature.common.LoadingState
import dev.mascwa.pulse.feature.common.PulseScaffold
import dev.mascwa.pulse.feature.common.StaleBanner

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewsScreen(vm: NewsViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val analyses by vm.analyses.collectAsStateWithLifecycle()
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
                            }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                        },
                        trailingIcon = {
                            if (searchText.isNotEmpty()) {
                                IconButton(onClick = { searchText = "" }) {
                                    Icon(Icons.Filled.Close, "Clear")
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
                                Icon(Icons.Filled.Search, "Search")
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
        PullToRefreshBox(
            isRefreshing = content.loading && content.data != null,
            onRefresh = { vm.refresh() },
            modifier = Modifier.padding(innerPadding),
        ) {
            when {
                content.isInitialLoading -> LoadingState()
                content.isError -> ErrorState(content.error ?: "Error", onRetry = { vm.refresh() })
                content.data.isNullOrEmpty() -> EmptyState("No articles found.")
                else -> {
                    val distinctArticles = content.data!!.distinctBy { it.url }
                    LazyColumn(
                        contentPadding = PaddingValues(12.dp),
                        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp),
                    ) {
                        if (content.stale) item { StaleBanner(true) }
                        items(distinctArticles, key = { it.url }) { article ->
                            ArticleCard(
                                article,
                                pulse = state.marketPulse,
                                allArticles = distinctArticles,
                                onClick = { openUrl(context, article.url) },
                                analysis = analyses[article.url],
                                onNeedsAnalysis = vm::ensureAnalyzed,
                            )
                        }
                    }
                }
            }
        }
    }
}
