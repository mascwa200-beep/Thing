package dev.mascwa.pulse.feature.common

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import dev.mascwa.pulse.navigation.LocalFeedTabs

/**
 * Standard per-screen scaffold. The host (PulseApp) owns the bottom navigation
 * bar and pads the NavHost above it, so screens disable their own bottom inset
 * and only the [TopAppBar] consumes the status-bar inset.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PulseScaffold(
    title: String,
    modifier: Modifier = Modifier,
    scrollBehavior: TopAppBarScrollBehavior? = null,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    topBarOverride: (@Composable () -> Unit)? = null,
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        modifier = if (scrollBehavior != null)
            modifier.nestedScroll(scrollBehavior.nestedScrollConnection) else modifier,
        topBar = {
            androidx.compose.foundation.layout.Column {
                val feedTabs = LocalFeedTabs.current
                when {
                    topBarOverride != null -> topBarOverride()
                    // Feed screens get the LCARS header so the whole TOOLS top chrome
                    // (header + tabs) reads as one CRT terminal panel, not a Material bar over a green strip.
                    feedTabs != null -> PipBoyHeader(title, navigationIcon, actions)
                    else -> TopAppBar(
                        title = { Text(title) },
                        navigationIcon = navigationIcon,
                        actions = actions,
                        scrollBehavior = scrollBehavior,
                    )
                }
                // LCARS feed tabs — only on feed screens (LocalFeedTabs set by PulseApp).
                feedTabs?.let { FeedTabBar(it) }
            }
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        content = content,
    )
}
