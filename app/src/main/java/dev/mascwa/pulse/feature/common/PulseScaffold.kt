package dev.mascwa.pulse.feature.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.mascwa.pulse.ui.theme.Pulse

/**
 * Standard per-screen chrome — now the LCARS frame rather than a Material top bar.
 *
 * This is the highest-leverage file in the app's appearance: thirty-five screens route through it,
 * and until now every one of them was topped by a stock `TopAppBar`. The palette had been authentic
 * for a while and the app still read as a dark application with square corners, because nothing
 * drew the L-shaped chrome that makes LCARS recognisable at arm's length.
 *
 * **The signature is unchanged on purpose.** No call site moves, which is what makes a change this
 * wide verifiable: a compile error or a missing screen is the entire failure surface.
 *
 * The rail earns its width rather than merely decorating. Its top block holds [navigationIcon], so
 * the back control grows from a 24dp glyph to a 56×54 corner — and the thirty-odd screens that each
 * hand-roll their own back arrow can be swept later without touching this file again.
 */
@Composable
fun PulseScaffold(
    title: String,
    modifier: Modifier = Modifier,
    /**
     * Accepted and ignored.
     *
     * Kept only so the thirty-five call sites stay untouched. A repo-wide search found **no screen
     * that passes it** — the collapsing-toolbar behaviour it configures has never been used, so
     * there is nothing here to reproduce. Delete the parameter once the call sites are swept.
     */
    @Suppress("UNUSED_PARAMETER") scrollBehavior: TopAppBarScrollBehavior? = null,
    /**
     * The legacy slot, now NULLABLE with a null default — the shim that keeps every un-swept call
     * site compiling while [onBack] becomes the one idiom. ⚠️ The nullability is load-bearing: the
     * old non-null `{}` default meant a screen that passed nothing still painted the corner accent,
     * a dead block that looked tappable on every tab screen. Null now means "no control here" and
     * the frame paints the corner as plain header chrome.
     */
    navigationIcon: (@Composable () -> Unit)? = null,
    /**
     * THE back control. Set it and the whole 56×54 corner block becomes the back affordance —
     * accent, tappable, cue and haptics from the kit — one idiom instead of the six this app grew.
     */
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    topBarOverride: (@Composable () -> Unit)? = null,
    /** False for a full-bleed screen — a map — where the horizontal room genuinely cannot be spared. */
    rail: Boolean = true,
    content: @Composable (PaddingValues) -> Unit,
) {
    if (topBarOverride != null) {
        // ONE screen draws its own masthead (Home — News rejoined the standard frame in the
        // consistency arc). It short-circuits the frame exactly as it short-circuited the
        // TopAppBar, including owning its own status-bar inset.
        LcarsBareFrame(modifier, topBarOverride, content)
        return
    }
    LcarsScreenFrame(
        title = title,
        modifier = modifier,
        // The route-stable seed for the rail's colours and codes. Using the title means a screen's
        // rail is fixed for as long as its name is, which is the stability those codes need.
        seed = title,
        navigationIcon = navigationIcon,
        onBack = onBack,
        actions = actions,
        rail = rail,
    ) {
        // The frame owns its own insets and the host Scaffold already pads above the bottom nav, so
        // there is no inner padding left to hand down.
        content(PaddingValues(0.dp))
    }
}

/**
 * The escape hatch: a screen that draws its own masthead.
 *
 * Deliberately does **not** apply the status-bar inset. Both overriding screens already apply it
 * themselves — `HomeScreen` calls `windowInsetsPadding(WindowInsets.statusBars)` on its own bar —
 * and padding it twice would leave a visible gap under the clock.
 */
@Composable
private fun LcarsBareFrame(
    modifier: Modifier,
    topBar: @Composable () -> Unit,
    content: @Composable (PaddingValues) -> Unit,
) {
    Column(modifier.fillMaxSize().background(Pulse.colors.void)) {
        topBar()
        Box(Modifier.weight(1f)) { content(PaddingValues(0.dp)) }
    }
}
