package dev.mascwa.pulse.feature.media

import android.content.Context
import dev.mascwa.pulse.core.telemetry.MediaFloor
import dev.mascwa.pulse.core.telemetry.MediaFloor.Owner
import dev.mascwa.pulse.core.telemetry.MediaFloor.claim
import dev.mascwa.pulse.core.telemetry.MediaFloor.released
import dev.mascwa.pulse.feature.live.LiveVideoController
import dev.mascwa.pulse.feature.tacnet.RadioController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The app's one speaker, arbitrated.
 *
 * [MediaFloor] decides; this performs. The shape is a star: this object knows about both players and
 * **neither player knows about the other**, so adding a third one later is a change here and nowhere
 * else.
 *
 * Without it, two ExoPlayers each built with `handleAudioFocus = true` would simply fight — the
 * newer one wins focus and the older falls silent while its own state flow and its foreground
 * notification both still say it is playing. Audio that stops for no stated reason is the worst
 * version of that, which is why [note] exists.
 */
object AudioFloor {

    private var state = MediaFloor.State()

    /**
     * What to tell the user about the last claim, or null when nothing was displaced.
     *
     * Set on **every** claim, including to null, so a play that displaced nothing clears a stale
     * line on its own rather than relying on each screen to remember to.
     */
    private val _note = MutableStateFlow<String?>(null)
    val note: StateFlow<String?> = _note.asStateFlow()

    /** Who is playing right now, for a surface that wants to show it. */
    private val _owner = MutableStateFlow(Owner.NONE)
    val owner: StateFlow<Owner> = _owner.asStateFlow()

    /**
     * [who] is starting. Whoever had the speaker is stopped first.
     *
     * ⚠️ Synchronised, and the lock is **re-entered on purpose**. Performing `STOP_RADIO` runs the
     * radio's teardown, which reports its own release back into [released] on this same thread — a
     * Java monitor is reentrant, so that is a nested no-op rather than a deadlock, and
     * [MediaFloor.released]'s owner check is what makes it a no-op rather than a corruption.
     */
    @Synchronized
    fun claim(context: Context, who: Owner) {
        val previous = state.owner
        val step = state.claim(who)
        state = step.state
        _owner.value = step.state.owner
        _note.value = MediaFloor.displacedNote(previous, who)
        val app = context.applicationContext
        when (step.action) {
            MediaFloor.Action.NOTHING -> {}
            MediaFloor.Action.STOP_RADIO -> RadioController.stop(app)
            MediaFloor.Action.STOP_VIDEO -> LiveVideoController.stop(app)
            MediaFloor.Action.STOP_ONDEMAND ->
                dev.mascwa.pulse.feature.theater.OnDemandController.stop(app)
        }
    }

    /**
     * [who] reports it has stopped. A report, never a request — it can only free the floor, and a
     * report from a player that was already displaced is ignored.
     */
    @Synchronized
    fun released(who: Owner) {
        state = state.released(who).state
        _owner.value = state.owner
    }

    /** Dismiss the displacement line. */
    fun clearNote() {
        _note.value = null
    }
}
