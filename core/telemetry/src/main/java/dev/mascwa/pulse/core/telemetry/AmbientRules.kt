package dev.mascwa.pulse.core.telemetry

// ⚠️ Top level rather than on [AmbientAction]'s companion, because enum entries are constructed
// before their companion exists. A `const val` would very probably be inlined and work anyway, and
// "very probably" is not a thing to find out from a CI round.
private const val MINUTE = 60_000L
private const val HOUR = 60 * MINUTE

/**
 * How far into the phone an action reaches. The tiers exist so the acting layer can be switched on
 * one step at a time, and so a surface can say "this rule wants to do X and is not allowed to".
 */
enum class ActionTier {
    /** Nothing outside this app changes. Worst case, Pulse behaves oddly until it is released. */
    APP,

    /** Settings a user could flip themselves — ringer, Do Not Disturb, the torch. */
    PHONE,

    /** Device-owner powers. Off by default, per-action opt-in, and the loudest tier by far. */
    OWNER,
}

/**
 * Everything the acting layer is allowed to do. **A closed enum, and that is the safety model.**
 *
 * ⚠️ The decision layer *cannot express* arbitrary execution — there is no "run this", no free-text
 * command, no package name from a rule. Whatever a rule decides, the worst it can ask for is one of
 * these, so widening the blast radius means adding a member here and tripping
 * `AmbientRulesTest.the closed list cannot express anything destructive`. That is the same property
 * `RemoteProtocol`'s allowlist has, for the same reason: a list you have to edit is a list somebody
 * reviews.
 *
 * ⚠️ **Every member is a HOLD, not a deed.** Each one is a state that is asserted, and then released
 * — by the rule stopping, by the user, or by [maxHoldMs] at the latest. Announcing, speaking and
 * raising an alert are NOT in here: they are not reversible and do not need to be, and mixing a
 * one-way announcement in among things that must be undone is how a release loop ends up with
 * nothing to undo. Urgency travels on [AmbientIntent] instead.
 *
 * ⚠️ **[maxHoldMs] is not a target, it is the longest this may go wrong for.** Every value here was
 * chosen by asking "if the releasing rule never fires again because of a bug, how long before the
 * person is badly served?" — which is why silencing a ringer expires in hours rather than overnight
 * (you want your alarm) and the torch expires in minutes (you want your battery).
 *
 * ⚠️ **THERE IS NO ACTION THAT SWITCHES THE EARS OFF, and the absence is the point.** An earlier
 * draft had a STAND_SENSING_DOWN and had [Situation.ASLEEP] ask for it — which reads as thrift and
 * is not. `Sensorium.cadenceFor(STANDDOWN)` sets `micIntervalSec = 0`, documented as OFF, and a
 * smoke alarm is something this app hears with the microphone. It would have deafened the phone
 * every night for hours, and the hours somebody is asleep are exactly the hours nobody else is
 * listening either. Slowing is in reach and stopping the CAMERA is in reach; stopping the ears is
 * not, because there is no member here to ask for it. Guarded by
 * `AmbientRulesTest.nothing in the list can switch the ears off`.
 *
 * ⚠️ The existing battery ladder can still reach STANDDOWN at 9%, and that is a different trade with
 * a different justification — preserving a dying phone, not a choice this layer makes about sleep.
 * Nothing here touches it.
 */
enum class AmbientAction(
    val tier: ActionTier,
    /** Past tense, for the board line: "held back non-urgent notices". */
    val label: String,
    /** What pressing RELEASE will do, in the same voice. */
    val undo: String,
    val maxHoldMs: Long,
) {
    // ── APP ──────────────────────────────────────────────────────────────────────────────────
    HOLD_NON_URGENT(
        ActionTier.APP, "held back non-urgent notices", "let notices through again", 4 * HOUR,
    ),
    SPEAK_DONT_BUZZ(
        ActionTier.APP, "read notices aloud instead of buzzing", "go back to buzzing", 4 * HOUR,
    ),
    PAUSE_VIDEO(ActionTier.APP, "paused video", "leave video alone", 4 * HOUR),
    STOP_CAMERA_SIPS(
        ActionTier.APP, "stopped looking through the camera", "start looking again", 8 * HOUR,
    ),
    /**
     * Slow the sampling ladder by a step or two.
     *
     * ⚠️ **This must never be implemented as [Sensorium.SenseLevel.STANDDOWN], whatever a future
     * actuator finds convenient.** `cadenceFor(STANDDOWN)` sets `micIntervalSec = 0`, and that
     * type's own KDoc documents 0 as OFF — so reaching for it here would switch the ears off under a
     * name that says it slowed them down. CONSERVE still sips every five minutes, which is what
     * keeps the reading below true. See the note on this enum about what is deliberately absent.
     */
    LOWER_SENSE_RATE(ActionTier.APP, "slowed the sensing rate", "sense at the usual rate", 8 * HOUR),
    STAY_SILENT(ActionTier.APP, "stopped speaking aloud", "speak up again", 4 * HOUR),

    // ── PHONE ────────────────────────────────────────────────────────────────────────────────
    // ⚠️ None of these can stop an emergency call being made or an emergency broadcast arriving.
    // Silencing a ringer does not disconnect a radio, and Android lets an emergency alert through
    // Do Not Disturb by design. That is safety rule 5, and it holds because of what is ABSENT here.
    // ⚠️ **It says vibrate because it sets vibrate.** Going all the way to silent needs the
    // notification-policy grant, which this app does not ask for and which lives behind a system
    // screen somebody would have to be sent hunting through — so the actuator stops at VIBRATE and
    // the label has to stop there too. "Silenced the ringer" on a phone that is still buzzing is the
    // app claiming more than it did, which is the shape this repository keeps having to correct.
    SILENCE_RINGER(ActionTier.PHONE, "set the ringer to vibrate", "let it ring out loud", 4 * HOUR),
    // ⚠️ **There is deliberately no REQUEST_DND, and its absence is the rule.** It was declared here
    // and asked for by NOTHING — the declared-and-never-constructed shape this whole arc exists to
    // remove, shipped in this very enum a slice ago. The meeting rule had already decided against it
    // in writing (Do Not Disturb is a mode people set deliberately and notice the loss of) and no
    // other situation wants it, because HOLD_NON_URGENT already stops OUR notifications where Do Not
    // Disturb stops everyone's — a far larger claim on somebody's phone for the same benefit.
    //
    // Removed rather than left unused for the same reason STAND_SENSING_DOWN was: a member no rule
    // asks for widens what this list can express and buys nothing, and the closed list's whole value
    // is that the worst a rule can reach for is something in it. Putting it back should be a
    // deliberate, reviewed act, which is exactly what adding an enum member is.
    TORCH_ON(ActionTier.PHONE, "turned the torch on", "turn the torch off", 10 * MINUTE),
    // ⚠️ **There is deliberately no RAISE_ALARM_VOLUME either, and the reason is stronger than the
    // one for Do Not Disturb above: as wired it could not have worked.** It raised STREAM_ALARM, and
    // nothing in this subsystem's alert path plays on that stream — the urgent board line goes out
    // on the notification stream, which is a different volume entirely. So it would have moved a
    // number nobody was listening to.
    //
    // And it could not safely have been made to work here. `EmergencyKlaxon` already captures and
    // restores that exact stream, per instance, and two independent capture-and-restores interleave
    // into a phone whose alarm volume is stuck at maximum for ever — the defect this repository
    // has already shipped once on that very class and had to fix. If a physical alarm response is
    // ever wanted it belongs with the component that already owns the stream, not beside it.
    //
    // Losing it costs the safety rule nothing it was actually delivering: the torch is the genuinely
    // additive physical response, and every ALERT is already announced by an urgent board line.

    // ── OWNER ────────────────────────────────────────────────────────────────────────────────
    SUSPEND_DISTRACTING_APPS(
        ActionTier.OWNER, "paused distracting apps", "let those apps open again", 2 * HOUR,
    ),
    DISABLE_CAMERA(ActionTier.OWNER, "switched the camera off", "switch the camera back on", 2 * HOUR),
    HIDE_STATUS_BAR(ActionTier.OWNER, "switched the status bar off", "put the status bar back", 2 * HOUR),
    BLOCK_SCREENSHOTS(ActionTier.OWNER, "blocked screenshots", "allow screenshots again", 2 * HOUR),
    SHORTEN_SCREEN_TIMEOUT(
        ActionTier.OWNER, "shortened the screen timeout", "put the timeout back", 8 * HOUR,
    ),
}

/**
 * How loudly the announcement layer should say what was done.
 *
 * ⚠️ **Nothing reads this yet** — said plainly, because a carried-and-never-read field is the defect
 * class this whole arc exists to remove and it would be poor form to add one quietly while removing
 * five. It is set here rather than later because the rule is the only thing that knows how urgent
 * its own reason is: by the time an announcer sees a hold, "the ringer was silenced" looks the same
 * whether a diary entry or a smoke alarm asked for it. Its consumer is the announcement slice.
 */
enum class ActionUrgency {
    /** A line on the board, no buzz. */
    ROUTINE,

    /** Worth interrupting for — the board alerts. */
    IMPORTANT,

    /** The screen, the klaxon, the lot. Reserved for safety. */
    RED,
}

/**
 * One thing a rule wants done, with the sentence explaining it.
 *
 * ⚠️ [reason] is mandatory for the reason [SituationRead.evidence] is: this ends up changing what
 * somebody's phone does, and "Pulse silenced your ringer" with no because is indistinguishable from
 * a fault. Every board line and every row of the scanner's HOLDING NOW list is built from it.
 */
data class AmbientIntent(
    val action: AmbientAction,
    val reason: String,
    val urgency: ActionUrgency = ActionUrgency.ROUTINE,
    /** When this hold was asserted, on the caller's clock. */
    val fromMs: Long,
) {
    /**
     * The instant this hold must be released by, whatever else happens.
     *
     * ⚠️ **Derived, never given, and that is the point.** A rule has nowhere to ask for a longer
     * hold than its action allows, so the ceiling lives with the capability rather than with whoever
     * most recently edited a rule. The first draft of this stored the instant and the KDoc claimed a
     * clamp the data class did not actually apply — an overstated comment, which in this tree is a
     * defect. Making it underivable is better than making it checked.
     *
     * ⚠️ There is deliberately no way to ask for SHORTER either, and nothing is lost by that: a hold
     * ends the moment its rule stops wanting it, which is the ordinary path. This is the backstop
     * for when that never happens.
     */
    val untilMs: Long get() = fromMs + action.maxHoldMs
}

/** What a set of intents is allowed to do right now, and what it is not. */
data class AmbientDecision(
    val allowed: List<AmbientIntent> = emptyList(),
    /**
     * Wanted but not allowed, each with the sentence a surface should show.
     *
     * ⚠️ [permit] fills this with what was out of TIER. The app layer adds what this
     * particular handset cannot physically do — a phone with no flash asked to light a torch —
     * because both are the same fact arriving from different directions, and a reader wants one
     * list of "asked for, not allowed" rather than two.
     */
    val refused: List<AmbientRefusal> = emptyList(),
)

data class AmbientRefusal(val intent: AmbientIntent, val why: String)

/**
 * Situation in, holds out. Pure, deterministic and clock-free — the instant is a parameter — so CI
 * holds every rule, exactly as it does for [AmbientSituation] one layer below.
 *
 * ⚠️ **The rules say what they WANT; [permit] decides what they may have.** Keeping those apart is
 * what lets the scanner show "would pause your distracting apps · the device-owner tier is off"
 * rather than silently doing nothing, and it means turning a tier on changes one boolean rather
 * than reopening every rule.
 *
 * ⚠️ **Nothing here acts on a situation that has only just appeared.** A rule needs [SETTLE_MS] of
 * the same answer and [MIN_CONFIDENCE] behind it, because every input to [AmbientSituation] flickers
 * and a detector that muted a phone on one stray accelerometer sample would be worse than no
 * detector. The one carve-out is safety, below, and it is deliberate.
 */
object AmbientRules {

    /** How long a situation must have held before any rule will act on it. */
    const val SETTLE_MS = 90_000L

    /** How sure [AmbientSituation] has to be. Below this the evidence is one signal deep. */
    const val MIN_CONFIDENCE = 0.5f

    /**
     * Decide everything the rules want, given the world.
     *
     * Returns an empty list when nothing applies, which is the overwhelmingly common case and not a
     * failure — see [Situation.UNKNOWN]'s own note.
     */
    fun decide(s: AmbientSignals): List<AmbientIntent> =
        // ⚠️ Safety first and OUTSIDE the settle gate: see [safety].
        safety(s) + if (!settled(s)) emptyList() else when (s.situation.situation) {
            Situation.DRIVING -> driving(s)
            Situation.POCKETED -> pocketed(s)
            Situation.ASLEEP -> asleep(s)
            Situation.IN_A_MEETING -> meeting(s)
            Situation.IN_COMPANY -> company(s)
            else -> emptyList()
        }

    /**
     * Split what the rules want into what they may have and what they may not.
     *
     * @param maxTier the deepest tier switched on. [ActionTier.APP] is the floor and cannot be
     *   turned off — an app declining to buzz you is not a power that needs a permission.
     */
    fun permit(intents: List<AmbientIntent>, maxTier: ActionTier): AmbientDecision {
        val allowed = mutableListOf<AmbientIntent>()
        val refused = mutableListOf<AmbientRefusal>()
        for (i in intents) {
            if (i.action.tier.ordinal <= maxTier.ordinal) allowed += i
            else refused += AmbientRefusal(i, whyRefused(i.action.tier))
        }
        return AmbientDecision(allowed, refused)
    }

    /**
     * Why nothing is being held, in the words a person would use. Null when something IS.
     *
     * ⚠️ **A layer that silently does nothing is indistinguishable from a broken one**, and this
     * repository has corrected that shape repeatedly — the scanner's own "▸ LOOK NOW" was a button
     * that did precisely nothing on a phone low on battery until it was made to say so. Quiet is the
     * overwhelmingly common and correct state here; what it must not be is unexplained.
     *
     * ⚠️ The order matters and is worst-first: a situation nobody is sure of cannot also be waiting
     * to settle, so reporting the settle timer for an UNKNOWN reading would send somebody away to
     * wait for something that is never going to arrive.
     */
    fun whyQuiet(s: AmbientSignals): String? {
        if (decide(s).isNotEmpty()) return null
        val r = s.situation
        return when {
            r.situation == Situation.UNKNOWN ->
                "it is not sure what you are doing — most of what a phone can sense fits several lives"
            r.confidence < MIN_CONFIDENCE ->
                "the evidence for ${r.situation.label.lowercase()} is one signal deep"
            r.sinceMs <= 0L -> "it has only just worked this out"
            s.nowMs - r.sinceMs < SETTLE_MS ->
                "waiting to see whether ${r.situation.label.lowercase()} holds — " +
                    "another ${((SETTLE_MS - (s.nowMs - r.sinceMs)) / 1000L).coerceAtLeast(1L)}s"
            // ⚠️ Settled, believed, and still nothing: there is genuinely no rule for this one, which
            // is true of half the situations and is a fact about the rules rather than a fault.
            else -> "nothing is set up to act on ${r.situation.label.lowercase()}"
        }
    }

    private fun whyRefused(tier: ActionTier) = when (tier) {
        ActionTier.PHONE -> "phone controls are switched off"
        ActionTier.OWNER -> "device-owner controls are switched off"
        ActionTier.APP -> "" // unreachable: APP is the floor and is never refused
    }

    /** Has the current answer held long enough, and with enough behind it, to act on? */
    internal fun settled(s: AmbientSignals): Boolean {
        val r = s.situation
        if (r.situation == Situation.UNKNOWN) return false
        if (r.confidence < MIN_CONFIDENCE) return false
        if (r.sinceMs <= 0L) return false
        return s.nowMs - r.sinceMs >= SETTLE_MS
    }

    private fun intent(
        s: AmbientSignals,
        action: AmbientAction,
        reason: String,
        urgency: ActionUrgency = ActionUrgency.ROUTINE,
    ) = AmbientIntent(action = action, reason = reason, urgency = urgency, fromMs = s.nowMs)

    // ---- the rules ----

    /**
     * ⚠️ **Safety is exempt from the settle gate and the confidence floor, and that is the single
     * most deliberate decision in this file.** A smoke alarm that has to sound for ninety seconds
     * before the phone reacts is a phone that reacted too late. The bar that protects against a
     * false positive here is not time, it is [SensoriumEvents.ALERT_MIN_CONF] — a strict
     * classification floor applied where the sound is recognised — so waiting again would be paying
     * the cost twice and getting nothing for it.
     *
     * ⚠️ The torch is gated on darkness rather than fired on every alert. A torch in daylight is a
     * flat battery and nothing else; in a dark house with an alarm going off it is the difference
     * between finding the stairs and not.
     *
     * ⚠️ **The response is the torch and nothing else.** It used to raise the alarm volume too, which
     * moved a stream nothing in this path plays on — see the note where that action used to be.
     *
     * ⚠️ **Only a FIRE alarm gets the bright response, and the other two ALERT kinds
     * deliberately get nothing from this rule.** `SensoriumEvents` raises three: a smoke/CO alarm,
     * breaking glass and a gunshot-like sound. For a fire, being loud and lit is how somebody wakes
     * up and finds the door. For the other two the same response could be exactly wrong — a phone
     * that lights a torch while somebody is hiding from whoever broke the window has made their
     * situation worse, not better. Nothing is lost by saying nothing here:
     * `SensoriumEngine` already raises an urgent board line for EVERY alert, so glass and gunshots
     * are still announced. What this rule decides is only the PHYSICAL response, and for those two
     * the safe physical response is none.
     */
    private fun safety(s: AmbientSignals): List<AmbientIntent> {
        val fire = s.events.firstOrNull {
            it.severity == EventSeverity.ALERT && it.key == SensoriumEvents.KEY_SMOKE_ALARM
        } ?: return emptyList()
        val why = fire.detail
        val out = mutableListOf<AmbientIntent>()
        if (s.env.light == LightState.DARK) {
            out += intent(s, AmbientAction.TORCH_ON, "$why, and it is dark", ActionUrgency.RED)
        }
        return out
    }

    /**
     * ⚠️ Audio is deliberately left alone. Somebody driving is listening to something, and a rule
     * that stopped it would be an actively dangerous distraction — the point of the driving rules is
     * to stop the phone demanding attention, not to take away what is already playing safely.
     * [AmbientAction.PAUSE_VIDEO] is the exception because video is the one thing that wants eyes.
     */
    private fun driving(s: AmbientSignals) = listOf(
        intent(s, AmbientAction.HOLD_NON_URGENT, "you are driving", ActionUrgency.IMPORTANT),
        intent(s, AmbientAction.SPEAK_DONT_BUZZ, "you are driving"),
        intent(s, AmbientAction.PAUSE_VIDEO, "you are driving"),
    )

    /**
     * ⚠️ The camera stops rather than slows. A phone in a pocket photographs the inside of a pocket,
     * so every sip is a lens opened, a frame classified and a privacy indicator lit for a reading
     * that could only ever say "dark". Sound still carries information from in there; light does not.
     */
    private fun pocketed(s: AmbientSignals) = listOf(
        intent(s, AmbientAction.STOP_CAMERA_SIPS, "the phone is against something and not in use"),
        intent(s, AmbientAction.LOWER_SENSE_RATE, "the phone is put away"),
    )

    /**
     * ⚠️ **The ears stay on, and that is the whole shape of this rule.** Everything else about being
     * asleep says spend less — but a smoke alarm at three in the morning is the one event where
     * nobody else is listening and the phone's microphone is the only thing that can notice. So the
     * sampling rate drops and the CAMERA stops (a lens in a dark bedroom reads nothing worth the
     * privacy indicator), while sound keeps being sampled every few minutes. A real alarm sounds for
     * minutes, which is what makes a slower sip enough.
     */
    private fun asleep(s: AmbientSignals) = listOf(
        intent(s, AmbientAction.STOP_CAMERA_SIPS, "you appear to be asleep"),
        intent(s, AmbientAction.LOWER_SENSE_RATE, "you appear to be asleep"),
        intent(s, AmbientAction.HOLD_NON_URGENT, "you appear to be asleep"),
        intent(s, AmbientAction.STAY_SILENT, "you appear to be asleep"),
    )

    /**
     * ⚠️ The ringer is silenced and Do Not Disturb is NOT requested, though both are in reach. A
     * meeting is an hour; Do Not Disturb is a mode people set deliberately and notice the loss of,
     * and taking it over for something this app inferred from a diary entry and some voices is a
     * larger liberty than the evidence supports. Silencing a ringer is exactly as reversible and
     * says the same thing.
     *
     * ⚠️ **And the ringer is only silenced when it is measurably ON.** This is the transition-only
     * rule made structural rather than left to whoever writes the actuator. Every member of
     * [AmbientAction] is a hold — asserted, then RELEASED — so asking to silence a ringer somebody
     * had already silenced themselves means the release turns it back on: a phone ringing out loud
     * at the end of a meeting because this layer "undid" something it never did.
     *
     * ⚠️ A null ringer is UNKNOWN and is likewise not acted on. An actuator that cannot read the
     * current state cannot restore it either, so the honest answer is to leave it alone — the same
     * judgement [DndFilter.UNKNOWN] documents for Do Not Disturb, one layer down.
     */
    private fun meeting(s: AmbientSignals) = buildList {
        add(intent(s, AmbientAction.HOLD_NON_URGENT, "your calendar says you are in something"))
        add(intent(s, AmbientAction.STAY_SILENT, "your calendar says you are in something"))
        if (s.phone.ringer == RingerState.NORMAL) {
            add(intent(s, AmbientAction.SILENCE_RINGER, "your calendar says you are in something"))
        }
    }

    /**
     * ⚠️ Being among people earns silence and nothing else. Voices are not a commitment — a pub, a
     * bus and a family kitchen all read the same — so holding somebody's notifications back on that
     * alone would be guessing at what they are doing with their evening. Not speaking aloud is the
     * one thing that is right in all three.
     */
    private fun company(s: AmbientSignals) = listOf(
        intent(s, AmbientAction.STAY_SILENT, "there are people around you"),
    )
}

/**
 * Everything the rules read, flat and defaulted, so a signal nobody has is absent rather than wrong.
 *
 * The same shape as `OracleSignals`, for the same reason: a rule that takes a snapshot can be given
 * one in a test, and a missing input mutes the rules that need it instead of blocking the rest.
 */
data class AmbientSignals(
    val situation: SituationRead = SituationRead(),
    val env: EnvReading = EnvReading(),
    val phone: SenseContext = SenseContext(),
    /** This heartbeat's events. Only [EventSeverity.ALERT] reaches a rule. */
    val events: List<SenseEvent> = emptyList(),
    val nowMs: Long = 0L,
)
