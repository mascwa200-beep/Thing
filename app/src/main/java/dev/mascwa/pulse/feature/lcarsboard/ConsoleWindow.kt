package dev.mascwa.pulse.feature.lcarsboard

import android.app.Activity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Hide Android's status bar behind an LCARS console, so the console owns the top of the screen.
 *
 * Only the STATUS bar. The gesture bar at the bottom stays, because the owner named only the top, and
 * a home screen with no visible way home is its own problem.
 *
 * ⚠️ **What it costs, said rather than discovered.** Android's rule for a window that hides its bars is
 * that a swipe down from the top edge first brings the bar back for a moment, so the notification shade
 * takes one more pull. No app can change that.
 *
 * Call it after `enableEdgeToEdge`, and again whenever the window regains focus: a bar swiped into
 * view, or another window over this one, can leave it showing. That is the shape `LiveVideoPlayer`
 * already uses for full-screen video.
 */
internal fun Activity.hideStatusBarForConsole() {
    val controller = WindowCompat.getInsetsController(window, window.decorView)
    controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    controller.hide(WindowInsetsCompat.Type.statusBars())
}
