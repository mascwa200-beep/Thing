package dev.mascwa.pulse.feature.social

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mascwa.pulse.core.util.openUrl
import dev.mascwa.pulse.data.social.SocialItem
import dev.mascwa.pulse.feature.common.EmptyState
import dev.mascwa.pulse.feature.common.ErrorState
import dev.mascwa.pulse.feature.common.LoadingState
import dev.mascwa.pulse.feature.common.NeonChip
import dev.mascwa.pulse.feature.common.NeonPanel
import dev.mascwa.pulse.feature.common.PulseScaffold
import dev.mascwa.pulse.feature.common.StaleBanner
import dev.mascwa.pulse.ui.theme.JetBrainsMono
import dev.mascwa.pulse.ui.theme.Pulse

@Composable
fun SocialScreen(vm: SocialViewModel, onBack: (() -> Unit)? = null) {
    val tab by vm.tab.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val c = Pulse.colors

    PulseScaffold(
        title = "Social",
        navigationIcon = {
            if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
        },
    ) { innerPadding ->
        Column(Modifier.padding(innerPadding)) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SocialTab.entries.forEach { t ->
                    NeonChip(t.label, selected = t == tab, onClick = { vm.select(t) })
                }
            }
            PullToRefreshBox(isRefreshing = false, onRefresh = { vm.refresh() }) {
                when (tab) {
                    SocialTab.LEMMY -> FeedList(vm.lemmy.collectAsStateWithLifecycle().value.let { it },
                        context, onRetry = { vm.refresh() })
                    SocialTab.HN -> FeedList(vm.hn.collectAsStateWithLifecycle().value,
                        context, onRetry = { vm.refresh() })
                    SocialTab.MASTODON -> MastodonContent(vm, context)
                }
            }
        }
    }
}

@Composable
private fun FeedList(
    async: dev.mascwa.pulse.core.util.Async<dev.mascwa.pulse.data.social.SocialFeed>,
    context: android.content.Context,
    onRetry: () -> Unit,
) {
    when {
        async.isInitialLoading -> LoadingState()
        async.isError -> ErrorState(async.error ?: "Error", onRetry)
        async.data?.items.isNullOrEmpty() -> EmptyState("Nothing trending right now.")
        else -> LazyColumn(
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (async.stale) item { StaleBanner(true) }
            items(async.data!!.items, key = { it.url }) { item -> ItemRow(item) { openUrl(context, item.url) } }
        }
    }
}

@Composable
private fun MastodonContent(vm: SocialViewModel, context: android.content.Context) {
    val async by vm.mastodon.collectAsStateWithLifecycle()
    val c = Pulse.colors
    when {
        async.isInitialLoading -> LoadingState()
        async.isError -> ErrorState(async.error ?: "Error", onRetry = { vm.refresh() })
        async.data == null -> EmptyState("No trends.")
        else -> {
            val data = async.data!!
            LazyColumn(
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (async.stale) item { StaleBanner(true) }
                if (data.tags.isNotEmpty()) {
                    item {
                        Text("TRENDING TAGS", fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.muted,
                            modifier = Modifier.padding(top = 6.dp, bottom = 2.dp))
                    }
                    item {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            data.tags.forEach { tag ->
                                NeonChip("#${tag.name}", selected = false, onClick = { openUrl(context, tag.url) },
                                    modifier = Modifier.padding(bottom = 8.dp))
                            }
                        }
                    }
                    item {
                        Text("TRENDING POSTS", fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.muted,
                            modifier = Modifier.padding(top = 6.dp, bottom = 2.dp))
                    }
                }
                items(data.statuses, key = { it.url }) { item -> ItemRow(item) { openUrl(context, item.url) } }
            }
        }
    }
}

@Composable
private fun ItemRow(item: SocialItem, onClick: () -> Unit) {
    val c = Pulse.colors
    NeonPanel(Modifier.fillMaxWidth().clickable { onClick() }) {
        Column {
            Text(item.title, style = MaterialTheme.typography.bodyLarge, color = c.ink,
                fontWeight = FontWeight.Medium)
            Text("${item.source} · ${item.meta}", fontFamily = JetBrainsMono, fontSize = 10.sp,
                color = c.accent, modifier = Modifier.padding(top = 4.dp))
        }
    }
}
