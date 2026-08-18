package dev.mascwa.pulse.notifications

import android.content.Context
import dev.mascwa.pulse.feature.breaking.BreakingOverlayService

/**
 * How a breaking story reaches you: a card over whatever you are doing, never a page you did not ask
 * for.
 *
 * ⚠️ **What this used to do, and why it was wrong.** It started `BreakingNewsActivity` with
 * `FLAG_ACTIVITY_NEW_TASK or FLAG_ACTIVITY_CLEAR_TASK` — a full-screen opaque page for one headline,
 * and `CLEAR_TASK` **wipes the back stack**, so dismissing it could not return you to what you were
 * reading. There was nothing left to return to. Reported by the owner as losing your place, and that
 * is exactly what the flag does.
 *
 * The replacement is a floating, partly transparent card carrying that one story
 * ([BreakingOverlayService]): the app behind stays focused, scrollable and touchable, the card can be
 * dragged out of the way, and its ✕ dismisses it. Reading the full coverage is a choice you make by
 * tapping, and it opens as an ordinary destination you can back out of.
 *
 * Two paths, in order:
 *
 * 1. **"Display over other apps" granted** — the card appears wherever you are, in any app.
 * 2. **Not granted** — the full-screen-intent notification, which is the platform ceiling without
 *    that permission: it takes over from the lock screen and appears as a heads-up while the phone
 *    is in use. Settings → Notifications offers the one-tap grant.
 *
 * A failed overlay start falls through to the notification, so a story is never silently dropped.
 */
object TakeoverLauncher {

    fun launch(context: Context, notifier: Notifier, headline: String, query: String, source: String = "") {
        if (BreakingOverlayService.canShow(context)) {
            if (runCatching { BreakingOverlayService.show(context, headline, source, query) }.isSuccess) return
        }
        notifier.notifyBreakingInterrupt(headline, query)
    }
}
