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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mascwa.pulse.core.telemetry.Freshness
import dev.mascwa.pulse.core.util.Async
import dev.mascwa.pulse.ui.theme.ChakraPetch
import dev.mascwa.pulse.ui.theme.JetBrainsMono
import dev.mascwa.pulse.ui.theme.Pulse

// The shared loading / error / empty / stale states, in the app's terminal idiom. They read the active
// [Pulse.colors] palette, so they render LCARS-orange under the TOOLS theme and in the accent elsewhere.

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
            Icon(LcarsIcons.Warning, null, modifier = Modifier.size(44.dp), tint = c.negative)
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

/**
 * Nothing to show — and, when the caller can offer one, a way to ask again.
 *
 * ⚠️ The retry is not decoration. Most of these sit inside a `PullToRefreshBox`, whose gesture is
 * driven entirely by nested scroll — `PullToRefreshModifierNode` implements `NestedScrollConnection`
 * and nothing else, checked against the shipped material3 1.3.1 classes. This is a plain `Box` with
 * nothing scrollable in it, so it produces no scroll deltas and **the pull cannot fire at all**. A
 * screen that ends up empty for a reason the user could clear had no way out of it.
 */
@Composable
fun EmptyState(message: String, modifier: Modifier = Modifier, onRetry: (() -> Unit)? = null) {
    val c = Pulse.colors
    Box(modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(LcarsIcons.Inbox, null, modifier = Modifier.size(38.dp), tint = c.muted)
            Text(
                message,
                fontFamily = JetBrainsMono, fontSize = 11.sp, color = c.muted, textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 12.dp),
            )
            if (onRetry != null) {
                Text(
                    "▸ CHECK AGAIN",
                    fontFamily = ChakraPetch, fontWeight = FontWeight.Bold, fontSize = 12.sp,
                    letterSpacing = 1.sp, color = c.accent,
                    modifier = Modifier.padding(top = 16.dp).border(1.dp, c.accent).clickable { onRetry() }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
    }
}

/**
 * The banner for one or more feeds, taken straight off their [Async] states.
 *
 * Preferred over the primitive below at every call site: the state already knows whether it is stored,
 * when it arrived and whether the last refresh threw, so passing it whole removes the chance of pairing
 * one feed's staleness with another's timestamp.
 *
 * With several feeds the **oldest** timestamp is reported — a screen is only as current as its stalest
 * part — and a failure in any of them counts as a failure.
 */
@Composable
fun StaleBanner(vararg states: Async<*>, modifier: Modifier = Modifier) = StaleBanner(
    visible = states.any { it.stale },
    lastUpdatedEpochMs = Freshness.oldestOf(*states.map { it.lastUpdatedEpochMs }.toLongArray()),
    refreshFailed = states.any { it.error != null },
    modifier = modifier,
)

/**
 * Says how far the data on screen can be trusted, and how old it is.
 *
 * ⚠️ [lastUpdatedEpochMs] is required rather than defaulted. Every `Async` already carries it and it
 * was being read at exactly one call site in the whole app; a default of zero here would let a site I
 * missed silently go on saying nothing, which is the bug this is fixing. Making it required means the
 * compiler enumerates the call sites instead.
 *
 * For a screen fed by several sources, pass `Freshness.oldestOf(a, b)` — a screen is only as current as
 * its stalest part.
 *
 * This no longer bails out merely because the device believes it is online: online-and-failing is
 * precisely the case that used to be invisible.
 */
@Composable
fun StaleBanner(
    visible: Boolean,
    lastUpdatedEpochMs: Long,
    refreshFailed: Boolean = false,
    modifier: Modifier = Modifier,
) {
    if (!visible) return
    val reading = Freshness.assess(
        lastUpdatedMs = lastUpdatedEpochMs,
        nowMs = System.currentTimeMillis(),
        online = dev.mascwa.pulse.core.connectivity.LocalIsOnline.current,
        servingStored = true,
        refreshFailed = refreshFailed,
    )
    if (!reading.worthShowing) return
    // ⚠️ Online-and-serving-storage is deliberately NOT announced. Every repository caches with its own
    // max age — half an hour for weather, hours for the economic series — and within it the app treats
    // that data as current by its own contract. Firing here would put a notice on nearly every screen
    // open that hit a warm cache, which is how a warning stops being read.
    //
    // The honest limit: a repository that internally swallows a failure and falls back to storage past
    // its own max age looks identical to a warm hit from here — same flags, no error. Distinguishing
    // them needs the repositories to report it, which is a larger change than this one.
    if (reading.state == Freshness.State.STORED) return
    val c = Pulse.colors
    val tint = c.amber
    Row(
        modifier = modifier.fillMaxWidth().background(c.panel).padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(LcarsIcons.CloudOff, null, modifier = Modifier.size(16.dp), tint = tint)
        Text(
            reading.label.uppercase(),
            fontFamily = JetBrainsMono, fontSize = 10.sp, letterSpacing = 0.5.sp, color = tint,
        )
    }
}

/** Standard outer padding combining a parent inset with content insets. */
fun contentPadding(extra: PaddingValues): PaddingValues = extra
