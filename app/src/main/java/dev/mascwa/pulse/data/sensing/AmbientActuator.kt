package dev.mascwa.pulse.data.sensing

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.mascwa.pulse.core.telemetry.AmbientAction
import dev.mascwa.pulse.core.telemetry.AmbientDecision
import dev.mascwa.pulse.core.telemetry.AmbientIntent
import dev.mascwa.pulse.core.telemetry.AmbientReconcile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val Context.ambientHoldsDataStore: DataStore<Preferences> by
    preferencesDataStore(name = "pulse_ambient_holds")

/**
 * Carry out the half of a hold that is not simply "the set says so".
 *
 * ⚠️ Exactly one APP-tier action needs this today — [AmbientAction.PAUSE_VIDEO], because pausing is
 * a thing that has to be DONE and not merely permitted — and the rest are pure set membership read
 * through [AmbientHolds]. It exists as a seam anyway because the phone and device-owner tiers are
 * nothing but effects, and discovering that on the day they arrive would mean rewriting the
 * reconciler rather than passing it a different lambda.
 */
fun interface HoldEffect {
    /** @param asserting true when the hold is going on, false when it is coming off. */
    suspend fun run(action: AmbientAction, asserting: Boolean)
}

/**
 * The acting layer: what [AmbientRules] wants, made to actually happen, and undone again.
 *
 * ## The six safety rules, and where each one lives
 *
 * Three are arithmetic and live in [AmbientReconcile] where CI can hold them. The other three are
 * about a process and a disk, and live here:
 *
 * - **Survive death.** [reconcileFromDisk] runs before anything else and lets go of whatever a dead
 *   process left behind. See its own note — it is the most dangerous failure in the design.
 * - **Sensing down ⇒ release everything.** [standDown], called when the watch stops or the user
 *   switches sensing off. A layer that cannot sense cannot justify holding.
 * - **Everything visible and undoable.** [holds] is every hold with its reason and its undo;
 *   [history] is every transition with the sentence for it; [releaseByHand] is the button.
 *
 * ## ⚠️ The refusals are kept rather than dropped
 *
 * [apply] takes the whole [AmbientDecision], including everything refused — for being out of tier,
 * or because this particular handset cannot do it at all — so a surface can say "would have paused
 * your distracting apps · device-owner controls are switched off", or "would have turned the torch
 * on · this phone has no torch",
 * instead of silently doing nothing. That sentence is the entire reason [AmbientRules.permit] splits
 * them rather than filtering.
 *
 * ## ⚠️ What is asserted and read by nothing, said plainly
 *
 * [AmbientAction.SPEAK_DONT_BUZZ] is asked for by the driving rule and **has no consumer**. There is
 * no honest one available at this tier: the only thing that volunteers speech is
 * `maybeSpeakProactive`, which is gated on the user's own `speakProactive` setting and on quiet
 * hours, and overriding either of those would be this layer countermanding a preference rather than
 * reading one. Its real consumer is the announcement slice, which decides the board line and the
 * spoken line together.
 *
 * ⚠️ That is deliberately a different judgement from the one that DELETED `STAND_SENSING_DOWN` in
 * the slice below. That action was removed because reaching for it would have been actively harmful
 * — it would have switched the microphone off, and the microphone is how a smoke alarm is heard. An
 * action that is merely inert costs nothing but the note saying so, and removing this one would mean
 * reopening a tested rule to put it back later.
 *
 * ⚠️ The surface half — [holds], [history], [refused], [releaseByHand], [releaseAllByHand] — has no
 * reader yet either, and that is the ordering the plan chose on purpose: the mechanism carries the
 * safety rule, the scanner merely renders it, and building the render first would mean a screen with
 * a RELEASE button and nothing behind it.
 *
 * ## ⚠️ Why this does not write the audit ledger, though the plan said it would
 *
 * `AuditLedgerStore` is a hash-chained, append-only, tamper-evident record, and its four producers
 * are all genuinely security events: a debug report leaving the phone, a human-gated code change, a
 * device-policy toggle, a change in hardware-attestation posture. The attestation producer's own
 * design note says identical verdicts are deduped **specifically so the append-only log is not
 * spammed**. "Stopped speaking aloud because you are in a meeting" is not that kind of event, and a
 * routine app behaviour landing in the blackbox several times a day would drown the four things in
 * it that matter. APP- and PHONE-tier transitions go to [history], which is what the scanner reads.
 *
 * ⚠️ **The device-owner tier DOES write it, and that promise is now kept rather than deferred** —
 * see the effect lambda in `AppContainer`, where the tier is checked. A device-owner power used with
 * no human in the loop is the same class of event as the `devicepolicy.*` entries Settings writes
 * when somebody flips a toggle by hand. Wired there rather than here because this class deliberately
 * knows nothing about which actions are effects, only that some are.
 */
class AmbientActuator(
    private val context: Context,
    private val json: Json,
    private val effect: HoldEffect = HoldEffect { _, _ -> },
) {

    @Serializable
    private data class StoredHold(val action: String, val reason: String, val fromMs: Long)

    @Serializable
    private data class StoredLine(val atMs: Long, val text: String, val action: String? = null, val undo: String? = null)

    @Serializable
    private data class Stored(
        val held: List<StoredHold> = emptyList(),
        val barred: List<String> = emptyList(),
        val log: List<StoredLine> = emptyList(),
    )

    /** One row of the scanner's history: what happened, when, and — if it is still on — how to undo it. */
    data class Line(val atMs: Long, val text: String, val action: AmbientAction? = null, val undo: String? = null)

    private val mutex = Mutex()
    private var state = AmbientReconcile.HoldState()
    private var lines = emptyList<Line>()
    private var loaded = false

    private val _holds = MutableStateFlow<List<AmbientIntent>>(emptyList())

    /**
     * Every hold in force, with its reason, its instant and its undo.
     *
     * ⚠️ Beside [AmbientHolds], not instead of it. That one answers "may I?" for four hot paths and
     * carries nothing else on purpose; this carries everything a surface needs and nothing a
     * consumer should be second-guessing its decisions with.
     */
    val holds: StateFlow<List<AmbientIntent>> = _holds.asStateFlow()

    private val _history = MutableStateFlow<List<Line>>(emptyList())

    /** Newest first, bounded. What the scanner shows under HISTORY. */
    val history: StateFlow<List<Line>> = _history.asStateFlow()

    private val _quiet = MutableStateFlow<String?>(null)

    /**
     * Why nothing is being held, when nothing is. Null while something is.
     *
     * ⚠️ Passed in by the caller rather than worked out here, because the caller is the only thing
     * that holds the signals — and computing them a second time would be two answers to one question
     * that can disagree, which is the drift this project has converged seven times.
     */
    val quiet: StateFlow<String?> = _quiet.asStateFlow()

    private val _refused = MutableStateFlow<List<String>>(emptyList())

    /** "would pause your distracting apps · device-owner controls are switched off". */
    val refused: StateFlow<List<String>> = _refused.asStateFlow()

    /**
     * Let go of whatever a dead process left holding, before anything else runs.
     *
     * ⚠️ **This is the single most dangerous failure mode in the design and it is why the held set is
     * persisted at all.** A hold is asserted, the process is killed — by the system, by a crash, by
     * a force-stop — and nothing is left alive to release it. Every APP-tier hold happens to survive
     * that by itself, because the set lives in memory and a fresh process starts empty; the ones
     * that will not are the phone and device-owner tiers, where the hold is a real change to the
     * device that outlives us. This runs now, while there is nothing for it to do, so that it is
     * already load-bearing on the day there is.
     *
     * ⚠️ It **releases** rather than restores. Resuming a hold whose reason nobody can vouch for any
     * more would be the layer acting on a situation it has not observed since before it died.
     */
    suspend fun reconcileFromDisk(nowMs: Long) = mutex.withLock {
        // ⚠️ **Once per process, and the guard is what makes a second caller safe.** This releases
        // whatever is on DISK, so calling it while this process is legitimately holding something
        // would let go of a hold asserted seconds earlier — and there are now two callers racing:
        // the application at startup and the service at loop start. [loaded] means "this process has
        // read the disk", so whichever wins does the reconcile and the other becomes a no-op.
        //
        // ⚠️ It is also correct for a service restarted inside a LIVE process: `onDestroy` stood
        // everything down on the way out, and if it did not, the in-memory state is still true and
        // the loop's own reconcile of wanted-against-held handles it. What must never happen is a
        // blanket release landing on top of live holds, which is exactly what this prevents.
        if (loaded) return@withLock
        val stored = load()
        lines = stored.log.map { it.toLine() }
        val held = stored.held.mapNotNull { it.toIntent() }
        if (held.isEmpty()) {
            publish()
            return@withLock
        }
        val down = AmbientReconcile.releaseAll(
            AmbientReconcile.HoldState(held = held),
            AmbientReconcile.ReleaseReason.STOOD_DOWN,
        )
        for (r in down.released) runEffect(r.intent.action, asserting = false)
        state = down.state
        // ⚠️ Its own sentence rather than [whyLine]'s. "The watch stopped" is what somebody reads
        // after switching sensing off, and this is a different and much rarer thing — the process
        // that was holding it is gone — which is worth being able to tell apart in the history.
        addLines(
            down.released.map {
                Line(nowMs, "let go of ${it.intent.action.label} — it was left holding when the app stopped")
            },
        )
        publish()
        persist()
    }

    /**
     * One heartbeat's worth of acting.
     *
     * ⚠️ **The effects are run either side of the publish, not after it, and the ordering is the
     * point.** Releases are reverted while the hold is still on, and asserts are applied once it is
     * already on — so at every instant "blocked" is a superset of "effect applied". The other
     * ordering leaves a window where a video is un-blocked but not yet resumed, or paused but not
     * yet blocked, and the second of those is long enough for an auto-advance to start the next item
     * under a hold that exists to stop exactly that.
     */
    suspend fun apply(
        decision: AmbientDecision,
        nowMs: Long,
        /** [AmbientRules.whyQuiet]'s answer, for the surface. Null when something is being held. */
        quietBecause: String? = null,
    ) = mutex.withLock {
        if (!loaded) load()
        val r = AmbientReconcile.reconcile(state, decision.allowed, nowMs)
        // ⚠️ "would HAVE", because [AmbientAction.label] is documented past tense — it is written for
        // the board line ("held back non-urgent notices"), and a bare "would" produced "would
        // silenced the ringer" on screen. That was live rather than theoretical: the driving and
        // meeting rules both ask for the ringer, and with the phone switch off it is refused and
        // rendered on every drive. Checked against every label in the enum; all thirteen read
        // correctly this way but one, and that one is noted where it is declared.
        _refused.value = decision.refused.map { "would have ${it.intent.action.label} · ${it.why}" }
        _quiet.value = quietBecause

        // ⚠️ A quiet heartbeat can still have moved the bar — an action whose rule stopped asking
        // drops out of `expiredWhileWanted` with nothing asserted or released to show for it. That
        // is a real state change and has to be written down, or a restart would resurrect a bar the
        // rule has already earned its way out of.
        val barMoved = r.state.expiredWhileWanted != state.expiredWhileWanted
        if (r.quiet && !barMoved) return@withLock

        for (rel in r.released) runEffect(rel.intent.action, asserting = false)
        state = r.state
        note(nowMs, r.asserted, r.released)
        publish()
        for (a in r.asserted) runEffect(a.action, asserting = true)
        // Published again because a failed effect writes itself into the history, and persisted last
        // so that what reaches the disk is what actually happened rather than what was intended.
        publish()
        persist()
    }

    /**
     * Safety rule 4. Everything goes, and nothing is barred from asking again once sensing returns.
     *
     * ⚠️ Deliberately not a no-op when nothing is held: it also clears [AmbientHolds], which is what
     * a consumer reads, and leaving that populated after the watch stopped would have the board stay
     * silent for ever on a phone whose sensing had been switched off.
     */
    suspend fun standDown(nowMs: Long, why: String = "sensing stopped") = mutex.withLock {
        if (!loaded) load()
        val down = AmbientReconcile.releaseAll(state, AmbientReconcile.ReleaseReason.STOOD_DOWN)
        for (r in down.released) runEffect(r.intent.action, asserting = false)
        state = down.state
        _refused.value = emptyList()
        _quiet.value = why
        addLines(down.released.map { Line(nowMs, "let go of ${it.intent.action.label} — $why") })
        publish()
        persist()
    }

    /** The RELEASE button on one row. It is barred from coming straight back — see [AmbientReconcile.release]. */
    suspend fun releaseByHand(action: AmbientAction, nowMs: Long) = mutex.withLock {
        if (!loaded) load()
        val r = AmbientReconcile.release(state, action)
        if (r.quiet) return@withLock
        for (rel in r.released) runEffect(rel.intent.action, asserting = false)
        state = r.state
        note(nowMs, emptyList(), r.released)
        publish()
        persist()
    }

    /** RELEASE ALL, from the scanner or the ongoing notification. */
    suspend fun releaseAllByHand(nowMs: Long) = mutex.withLock {
        if (!loaded) load()
        val down = AmbientReconcile.releaseAll(state, AmbientReconcile.ReleaseReason.BY_HAND)
        if (down.released.isEmpty()) return@withLock
        for (r in down.released) runEffect(r.intent.action, asserting = false)
        state = down.state
        note(nowMs, emptyList(), down.released)
        publish()
        persist()
    }

    // ---- the plumbing ----

    private fun whyLine(why: AmbientReconcile.ReleaseReason) = when (why) {
        AmbientReconcile.ReleaseReason.NO_LONGER_WANTED -> "it no longer applies"
        AmbientReconcile.ReleaseReason.BACKSTOP -> "it had run for as long as it is allowed to"
        AmbientReconcile.ReleaseReason.STOOD_DOWN -> "the watch stopped"
        AmbientReconcile.ReleaseReason.BY_HAND -> "you asked"
    }

    /**
     * ⚠️ An effect that throws must not lose the transition. The hold is the set either way for the
     * app tier, and for the tiers that are nothing but effects a silent failure would be a hold the
     * scanner claims is on and the phone has never applied — so the failure is written into the
     * history where somebody can see it rather than swallowed.
     */
    private suspend fun runEffect(action: AmbientAction, asserting: Boolean) {
        runCatching { effect.run(action, asserting) }.onFailure {
            addLines(
                listOf(
                    Line(
                        atMs = System.currentTimeMillis(),
                        text = "could not ${if (asserting) "apply" else "undo"} ${action.label}",
                    ),
                ),
            )
        }
    }

    /**
     * Write down what changed.
     *
     * ⚠️ Asserts and releases are passed in separately rather than as one list of sentences,
     * because only an assert can carry an undo — and working out which was which by comparing the
     * rendered strings back against the held set (the first version of this did exactly that) is a
     * match on prose that breaks the moment a label is reworded.
     */
    private fun note(
        nowMs: Long,
        asserted: List<AmbientIntent>,
        released: List<AmbientReconcile.Released>,
    ) {
        if (asserted.isEmpty() && released.isEmpty()) return
        val fresh =
            asserted.map { Line(nowMs, AmbientReconcile.describe(it), it.action, it.action.undo) } +
                released.map { Line(nowMs, "let go of ${it.intent.action.label} — ${whyLine(it.why)}") }
        addLines(fresh)
    }

    /** Newest first, bounded. [fresh] arrives oldest-first, as it is written. */
    private fun addLines(fresh: List<Line>) {
        if (fresh.isEmpty()) return
        lines = (fresh.reversed() + lines).take(MAX_LINES)
    }

    private fun publish() {
        _holds.value = state.held
        _history.value = lines
        AmbientHolds.publish(state.held)
    }

    private suspend fun load(): Stored {
        val raw = withContext(Dispatchers.IO) {
            context.ambientHoldsDataStore.data.first()[KEY]
        }
        loaded = true
        val stored = raw?.let { runCatching { json.decodeFromString(Stored.serializer(), it) }.getOrNull() } ?: Stored()
        state = AmbientReconcile.HoldState(
            held = stored.held.mapNotNull { it.toIntent() },
            expiredWhileWanted = stored.barred.mapNotNullTo(mutableSetOf()) { it.toAction() },
        )
        return stored
    }

    private suspend fun persist() {
        val blob = json.encodeToString(
            Stored.serializer(),
            Stored(
                held = state.held.map { StoredHold(it.action.name, it.reason, it.fromMs) },
                barred = state.expiredWhileWanted.map { it.name },
                log = lines.map { StoredLine(it.atMs, it.text, it.action?.name, it.undo) },
            ),
        )
        withContext(Dispatchers.IO) {
            context.ambientHoldsDataStore.edit { it[KEY] = blob }
        }
    }

    /**
     * ⚠️ An action is stored by NAME, so a name that no longer exists has to be dropped rather than
     * thrown on. Removing a member of [AmbientAction] is exactly what the closed-list design invites
     * — that is how `STAND_SENSING_DOWN` left it — and an install carrying a hold for a deleted
     * capability must come up working, not crash on first read.
     */
    private fun String.toAction(): AmbientAction? = AmbientAction.entries.firstOrNull { it.name == this }

    private fun StoredHold.toIntent(): AmbientIntent? =
        action.toAction()?.let { AmbientIntent(action = it, reason = reason, fromMs = fromMs) }

    private fun StoredLine.toLine() = Line(atMs, text, action?.toAction(), undo)

    private companion object {
        val KEY = stringPreferencesKey("holds")

        /** Enough for a day or two of transitions at the rate real situations change. */
        const val MAX_LINES = 40
    }
}
