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
import dev.mascwa.pulse.feature.common.LcarsIcons
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mascwa.pulse.core.util.Formatters
import dev.mascwa.pulse.core.util.openUrl
import dev.mascwa.pulse.core.telemetry.TheaterModel
import dev.mascwa.pulse.data.social.SocialItem
import dev.mascwa.pulse.feature.common.EmptyState
import dev.mascwa.pulse.feature.common.ErrorState
import dev.mascwa.pulse.feature.common.LoadingState
import dev.mascwa.pulse.feature.common.LcarsChip
import dev.mascwa.pulse.feature.common.LcarsFrame
import dev.mascwa.pulse.feature.common.LcarsHeaderBar
import dev.mascwa.pulse.feature.common.PulseScaffold
import dev.mascwa.pulse.feature.common.StaleBanner
import dev.mascwa.pulse.ui.theme.ChakraPetch
import dev.mascwa.pulse.ui.theme.JetBrainsMono
import dev.mascwa.pulse.ui.theme.Pulse

@Composable
fun SocialScreen(
    vm: SocialViewModel,
    onBack: (() -> Unit)? = null,
    onWatch: ((String) -> Unit)? = null,
) {
    PulseScaffold(
        title = "Social",
        onBack = onBack,
    ) { innerPadding ->
        SocialBody(vm, Modifier.padding(innerPadding), onWatch = onWatch)
    }
}

/** The scaffold-free SOCIAL feed (tab rail + feed list) — hosted standalone in [SocialScreen] and as the
 *  SOCIAL sub-tab inside the LCARS COMMS section. */
@Composable
fun SocialBody(vm: SocialViewModel, modifier: Modifier = Modifier, onWatch: ((String) -> Unit)? = null) {
    val tab by vm.tab.collectAsStateWithLifecycle()
    val context = LocalContext.current
    Column(modifier) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SocialTab.entries.forEach { t ->
                LcarsChip(t.label, selected = t == tab, onClick = { vm.select(t) })
            }
        }
        PullToRefreshBox(isRefreshing = false, onRefresh = { vm.refresh() }) {
            when (tab) {
                SocialTab.LEMMY -> FeedList(vm.lemmy.collectAsStateWithLifecycle().value.let { it },
                    context, onRetry = { vm.refresh() }, onWatch = onWatch)
                SocialTab.HN -> FeedList(vm.hn.collectAsStateWithLifecycle().value,
                    context, onRetry = { vm.refresh() }, onWatch = onWatch)
                SocialTab.MASTODON -> MastodonContent(vm, context, onWatch = onWatch)
            }
        }
    }
}

@Composable
private fun FeedList(
    async: dev.mascwa.pulse.core.util.Async<dev.mascwa.pulse.data.social.SocialFeed>,
    context: android.content.Context,
    onRetry: () -> Unit,
    onWatch: ((String) -> Unit)? = null,
) {
    when {
        async.isInitialLoading -> LoadingState()
        async.isError -> ErrorState(async.error ?: "Error", onRetry)
        async.data?.items.isNullOrEmpty() -> EmptyState("Nothing trending right now.", onRetry = onRetry)
        else -> LazyColumn(
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { StaleBanner(async) }
            items(async.data!!.items.distinctBy { it.url }, key = { it.url }) { item ->
                ItemRow(
                    item,
                    onClick = { openUrl(context, item.url) },
                    onWatch = onWatch?.takeIf { TheaterModel.videoId(item.url).isNotBlank() }?.let { w -> { w(item.url) } },
                )
            }
        }
    }
}

@Composable
private fun MastodonContent(
    vm: SocialViewModel,
    context: android.content.Context,
    onWatch: ((String) -> Unit)? = null,
) {
    val async by vm.mastodon.collectAsStateWithLifecycle()
    when {
        async.isInitialLoading -> LoadingState()
        async.isError -> ErrorState(async.error ?: "Error", onRetry = { vm.refresh() })
        async.data == null -> EmptyState("No trends.", onRetry = { vm.refresh() })
        else -> {
            val data = async.data!!
            LazyColumn(
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item { StaleBanner(async) }
                if (data.tags.isNotEmpty()) {
                    item { LcarsHeaderBar("Trending tags") }
                    item {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            data.tags.forEach { tag ->
                                LcarsChip("#${tag.name}", selected = false, onClick = { openUrl(context, tag.url) },
                                    modifier = Modifier.padding(bottom = 8.dp))
                            }
                        }
                    }
                    item { LcarsHeaderBar("Trending posts") }
                }
                items(data.statuses.distinctBy { it.url }, key = { it.url }) { item ->
                    ItemRow(
                        item,
                        onClick = { openUrl(context, item.url) },
                        onWatch = onWatch?.takeIf { TheaterModel.videoId(item.url).isNotBlank() }?.let { w -> { w(item.url) } },
                    )
                }
            }
        }
    }
}

@Composable
private fun ItemRow(item: SocialItem, onClick: () -> Unit, onWatch: (() -> Unit)? = null) {
    val c = Pulse.colors
    LcarsFrame(Modifier.fillMaxWidth().clickable { onClick() }) {
        Column {
            Text(item.title, fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = c.ink)
            // A self-post IS its text. Without this an Ask HN row was a headline and a score.
            item.body?.let { text ->
                Text(
                    text, fontFamily = ChakraPetch, fontSize = 12.sp, lineHeight = 17.sp,
                    color = c.ink2, maxLines = 4, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            // ⚠️ The age was on the model all along and only the News surface ever drew it. A fifth
            // of a Hacker News top page is routinely several days old, which "trending" does not
            // suggest — and here the two surfaces rendered the same object differently.
            val age = item.publishedEpochMs.takeIf { it > 0L }?.let { Formatters.relativeTime(it) }
            Text(
                listOfNotNull(item.source, item.meta, age).joinToString(" · "),
                fontFamily = JetBrainsMono, fontSize = 10.sp,
                color = c.accent, modifier = Modifier.padding(top = 4.dp),
            )
            // A row whose link is a video gets an in-app path: the Theater plays it right here,
            // instead of the plain tap's browser hand-off. Additive — nothing regresses.
            if (onWatch != null) {
                Text(
                    "▶ WATCH IN THEATER",
                    fontFamily = JetBrainsMono, fontSize = 10.sp, color = c.amber,
                    modifier = Modifier.clickable { onWatch() }.padding(top = 6.dp, bottom = 2.dp),
                )
            }
        }
    }
}
