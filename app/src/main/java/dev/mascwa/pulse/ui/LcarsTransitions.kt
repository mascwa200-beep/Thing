package dev.mascwa.pulse.ui

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.navigation.NavBackStackEntry
import dev.mascwa.pulse.navigation.Routes

/**
 * How one panel gives way to the next.
 *
 * LCARS does not glide. A console panel is replaced, not slid across a desk, so this is deliberately
 * quick and deliberately small: the outgoing panel clears first, then the incoming one seats itself
 * with a short travel from the rail side. The whole exchange is under a third of a second, which is
 * about as long as a transition can be before it starts costing the user time rather than telling
 * them something.
 *
 * ⚠️ **[SURFACE_ROUTES] get no transition at all, and that is not laziness.** A route hosting a
 * native surface — the map's GL view — is not composited like the rest of the tree: it does not
 * alpha-blend with its parent and it does not follow a translation applied to the Compose node above
 * it. Animating one produces a hole, a tear, or a view that arrives before its own contents. The
 * honest options were "no motion on that screen" or "a visible glitch on that screen", and the map
 * simply cutting in is the better of the two.
 */
object LcarsTransitions {

    /** Panels whose content is a native surface. See the class note — animating these tears. */
    val SURFACE_ROUTES = setOf(Routes.NAV)

    /**
     * Whether panels animate at all, decided once from what the device can afford.
     *
     * ⚠️ **A plain flag on the object rather than a composition local, and it has to be.** The four
     * transitions below are `val` lambdas on an object; `NavHost` takes them as parameters and there
     * is no composable scope in which they could read a local. The trade is a mutable global, which is
     * tolerable here for one reason: what it reflects is a property of the hardware, so it is written
     * once at process start from the device budget and never again.
     *
     * ⚠️ This is the one animation in the app that runs for everybody on every navigation, which is
     * exactly why it is the one worth turning off on a phone that cannot spare the frames. It is a
     * hand-off between panels, not a signal — nothing is lost by making it instant, unlike the alert
     * pulse, which stops meaning anything the moment it stops moving.
     */
    @Volatile
    var animate: Boolean = true

    private const val ENTER_MS = 190
    private const val EXIT_MS = 110

    /** How far the incoming panel travels, as a fraction of the screen. Small on purpose. */
    private const val TRAVEL = 14

    private fun NavBackStackEntry?.route(): String? = this?.destination?.route

    private fun AnimatedContentTransitionScope<NavBackStackEntry>.involvesSurface(): Boolean =
        initialState.route() in SURFACE_ROUTES || targetState.route() in SURFACE_ROUTES

    /**
     * Arriving forward: a short travel inward from the rail side, under a fade.
     *
     * Delayed past the outgoing fade so the two do not dissolve through each other — a crossfade
     * reads as a smear, where a clear hand-off reads as a panel being swapped.
     */
    val enter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
        if (!animate || involvesSurface()) {
            EnterTransition.None
        } else {
            fadeIn(tween(ENTER_MS, delayMillis = EXIT_MS, easing = LinearEasing)) +
                slideInHorizontally(tween(ENTER_MS, delayMillis = EXIT_MS)) { -it / TRAVEL }
        }
    }

    /** Leaving forward: fade only. A departing panel that also moved would drag the eye with it. */
    val exit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
        if (!animate || involvesSurface()) ExitTransition.None else fadeOut(tween(EXIT_MS, easing = LinearEasing))
    }

    /** Coming back: the same exchange with the travel reversed, so the direction reads. */
    val popEnter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
        if (!animate || involvesSurface()) {
            EnterTransition.None
        } else {
            fadeIn(tween(ENTER_MS, delayMillis = EXIT_MS, easing = LinearEasing)) +
                slideInHorizontally(tween(ENTER_MS, delayMillis = EXIT_MS)) { it / TRAVEL }
        }
    }

    val popExit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = exit
}
