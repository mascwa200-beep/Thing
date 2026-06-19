package dev.mascwa.pulse.feature.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mascwa.pulse.navigation.FEED_TABS
import dev.mascwa.pulse.navigation.FeedTabState
import dev.mascwa.pulse.ui.theme.JetBrainsMono

// Fallout Pip-Boy phosphor green — kept local so the bar reads the same on every feed.
private val PipBg = Color(0xFF04130A)
private val PipGrid = Color(0xFF15462A)
private val PipDim = Color(0xFF2E8F52)
private val PipBright = Color(0xFF5BFF9B)

/**
 * The Pip-Boy feed tab strip rendered under the app bar on every feed screen (via [PulseScaffold]
 * reading `LocalFeedTabs`). Horizontally scrollable; the active tab is bright; tapping another jumps
 * straight to that feed.
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
                Text(
                    label,
                    fontFamily = JetBrainsMono, fontSize = 12.sp, letterSpacing = 1.4.sp,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                    color = if (active) PipBright else PipDim,
                    modifier = Modifier
                        .clickable(enabled = !active) { state.onSelect(route) }
                        .padding(vertical = 9.dp),
                )
            }
        }
        Canvas(Modifier.fillMaxWidth().height(1.5.dp)) {
            drawLine(PipGrid, Offset(0f, size.height / 2f), Offset(size.width, size.height / 2f), 2f)
        }
    }
}
