package dev.mascwa.pulse.feature.phone

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * The call card's DECLINE and HANG UP buttons. ANSWER is not here: it opens the call screen directly
 * (see [CallNotices]), because a screen started from a receiver a notification started is blocked.
 *
 * Not exported — only LCARS's own PendingIntents reach it — and it acts on the call the button was
 * built for, by id: a button tapped after its call has gone does nothing, rather than acting on
 * whichever call came next.
 */
class CallActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(EXTRA_CALL) ?: return
        when (intent.action) {
            ACTION_DECLINE -> CallRegistry.decline(id)
            ACTION_HANG_UP -> CallRegistry.end(id)
        }
    }

    companion object {
        const val ACTION_DECLINE = "dev.mascwa.pulse.phone.DECLINE"
        const val ACTION_HANG_UP = "dev.mascwa.pulse.phone.HANG_UP"
        const val EXTRA_CALL = "call"
    }
}
