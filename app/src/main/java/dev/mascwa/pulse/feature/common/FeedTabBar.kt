package dev.mascwa.pulse.feature.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mascwa.pulse.navigation.FEED_TABS
import dev.mascwa.pulse.navigation.FeedTabState
import dev.mascwa.pulse.ui.theme.ChakraPetch
import dev.mascwa.pulse.ui.theme.JetBrainsMono

// Fallout Pip-Boy phosphor green — kept local so the header + bar read the same on every feed.
private val PipBg = Color(0xFF04130A)
private val PipGrid = Color(0xFF15462A)
private val PipDim = Color(0xFF2E8F52)
private val PipBright = Color(0xFF5BFF9B)

/**
 * The Pip-Boy header shown above the feed tabs (replaces the Material app bar on feed screens, wired in
 * [PulseScaffold]). A green CRT bar: the screen title in the display face, phosphor-bright, with the
 * navigation icon / actions tinted into the palette so the whole top chrome reads as one terminal panel.
 */
@Composable
fun PipBoyHeader(
    title: String,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
) {
    Column(Modifier.fillMaxWidth().background(PipBg).windowInsetsPadding(WindowInsets.statusBars)) {
        Row(
            Modifier.fillMaxWidth().height(48.dp).padding(start = 4.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CompositionLocalProvider(LocalContentColor provides PipBright) { navigationIcon() }
            Text(
                title.uppercase(),
                fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 17.sp, letterSpacing = 3.sp,
                color = PipBright, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(start = 6.dp),
            )
            CompositionLocalProvider(LocalContentColor provides PipDim) {
                Row(verticalAlignment = Alignment.CenterVertically, content = actions)
            }
        }
    }
}

/**
 * The Pip-Boy feed tab strip rendered under the header on every feed screen (via [PulseScaffold]
 * reading `LocalFeedTabs`). Horizontally scrollable; the active tab is bright with a phosphor underline
 * (Fallout selected-tab marker); tapping another jumps straight to that feed.
 */
@Composable
fun FeedTabBar(state: FeedTabState) {
    Column(Modifier.fillMaxWidth().background(PipBg)) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            FEED_TABS.forEach { (route, label) ->
                val active = route == state.current
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.clickable(enabled = !active) { state.onSelect(route) },
                ) {
                    Text(
                        label,
                        fontFamily = JetBrainsMono, fontSize = 12.sp, letterSpacing = 1.4.sp,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                        color = if (active) PipBright else PipDim,
                        modifier = Modifier.padding(top = 9.dp, bottom = 5.dp),
                    )
                    // Bright phosphor underline marks the selected tab (Pip-Boy idiom).
                    Box(Modifier.fillMaxWidth().height(2.dp).background(if (active) PipBright else Color.Transparent))
                }
            }
        }
        Canvas(Modifier.fillMaxWidth().height(1.5.dp)) {
            drawLine(PipGrid, Offset(0f, size.height / 2f), Offset(size.width, size.height / 2f), 2f)
        }
    }
}
