package dev.mascwa.pulse.feature.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mascwa.pulse.ui.theme.ChakraPetch
import dev.mascwa.pulse.ui.theme.JetBrainsMono
import dev.mascwa.pulse.ui.theme.Pulse

// The shared loading / error / empty / stale states, in the app's terminal idiom. They read the active
// [Pulse.colors] palette, so they render green under the TOOLS Pip-Boy theme and in the accent elsewhere.

@Composable
fun LoadingState(modifier: Modifier = Modifier) {
    val c = Pulse.colors
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = c.accent, strokeWidth = 2.dp)
    }
}

@Composable
fun ErrorState(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Pulse.colors
    Box(modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Filled.WarningAmber, null, modifier = Modifier.size(44.dp), tint = c.negative)
            Text(
                "COULDN'T LOAD",
                fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 15.sp, letterSpacing = 1.sp,
                color = c.ink, modifier = Modifier.padding(top = 12.dp),
            )
            Text(
                message,
                fontFamily = JetBrainsMono, fontSize = 11.sp, textAlign = TextAlign.Center, color = c.muted,
                modifier = Modifier.padding(top = 4.dp),
            )
            Text(
                "▸ RETRY",
                fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 1.sp,
                color = c.accent,
                modifier = Modifier.padding(top = 16.dp).border(1.dp, c.accent).clickable { onRetry() }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
fun EmptyState(message: String, modifier: Modifier = Modifier) {
    val c = Pulse.colors
    Box(modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Filled.Inbox, null, modifier = Modifier.size(38.dp), tint = c.muted)
            Text(
                message,
                fontFamily = JetBrainsMono, fontSize = 11.sp, color = c.muted, textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}

@Composable
fun StaleBanner(visible: Boolean, modifier: Modifier = Modifier) {
    // Only surface the "cached data" notice when the device is actually offline.
    // A fresh disk-cache hit while online is normal and must not trigger this.
    if (!visible || dev.mascwa.pulse.core.connectivity.LocalIsOnline.current) return
    val c = Pulse.colors
    Row(
        modifier = modifier.fillMaxWidth().background(c.panel).padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.Filled.CloudOff, null, modifier = Modifier.size(16.dp), tint = c.amber)
        Text(
            "OFFLINE — SHOWING CACHED DATA",
            fontFamily = JetBrainsMono, fontSize = 10.sp, letterSpacing = 0.5.sp, color = c.amber,
        )
    }
}

@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        if (actionLabel != null && onAction != null) {
            TextButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}

/** Standard outer padding combining a parent inset with content insets. */
fun contentPadding(extra: PaddingValues): PaddingValues = extra
