package dev.mascwa.pulse.core.telemetry

/**
 * What a person appears to be DOING, as distinct from what the room is like ([EnvReading]) and what
 * the handset is doing ([SenseContext]).
 *
 * ⚠️ **[UNKNOWN] is a real answer and the commonest correct one.** Everything else in this file is
 * a claim about somebody's life inferred from a microphone, an accelerometer and a lock screen, and
 * the whole value of the layer above depends on it refusing to guess. A detector that always names
 * something is a detector whose name means nothing.
 *
 * ⚠️ These are not mutually exclusive in the world and the enum does not pretend otherwise —
 * somebody walking with the phone in a pocket is genuinely both [WALKING] and [POCKETED]. One is
 * reported, and where the evidence for the other is CLOSE it is named in
 * [SituationRead.contenders]. Where it is not close it is simply not reported, which is the same
 * judgement a person would make: a phone face-down and still is put away, whatever else is true.
 */
enum class Situation {
    /** Moving at vehicle speed, or a cabin that sounds like one. Says nothing about who is driving. */
    DRIVING,

    /** Moving at walking pace. */
    WALKING,

    /**
     * Something is against the front of the phone and it is not being looked at — a pocket, a bag,
     * face-down on a table. ⚠️ No sensor on a phone can tell those apart; see [EnvReading.covered].
     */
    POCKETED,

    /**
     * Put away for the night.
     *
     * ⚠️ **This requires the hour to be night, and that is a real limitation stated rather than
     * papered over.** A dark, still, charging, dozing phone is indistinguishable from one in a
     * drawer, so outside the small hours the same evidence reports [AT_HOME_IDLE] or [POCKETED]
     * instead. A daytime nap will not be recognised, and inventing a rule that claimed to recognise
     * it would be claiming a measurement nothing here makes.
     */
    ASLEEP,

    /**
     * Settled somewhere with the phone in use.
     *
     * ⚠️ "Desk" is shorthand. The evidence is *in use, still, and not covered*, which is equally a
     * sofa, a train seat or a kitchen counter — nothing here knows about furniture.
     */
    AT_DESK,

    /** The calendar says something is on, and the room agrees. */
    IN_A_MEETING,

    /** Other people are around — voices, or a lot of Bluetooth devices. */
    IN_COMPANY,

    /** On the home network, phone put down. */
    AT_HOME_IDLE,

    /** On a network that is not home. */
    OUT_AND_ABOUT,

    /** Nothing reached the bar. Not a failure — usually the honest answer. */
    UNKNOWN,
}

/**
 * One situation read, with its reasons.
 *
 * ⚠️ **[evidence] is mandatory rather than decoration.** Everything downstream of this can change
 * what the phone does, and a person has to be able to see WHY it did. A situation with no reasons
 * attached is unexplainable to its owner and unfixable by whoever gets the bug report.
 */
data class SituationRead(
    val situation: Situation = Situation.UNKNOWN,
    /** Roughly how much evidence there was, 0f..1f. Not a probability — see [AmbientSituation.CONFIDENT]. */
    val confidence: Float = 0f,
    val evidence: List<String> = emptyList(),
    /** What else nearly fitted. Empty when one reading stood clear. */
    val contenders: List<Situation> = emptyList(),
    /**
     * When this situation started, on whatever clock the caller passed in.
     *
     * ⚠️ Carried forward while the situation is unchanged, so a rule can ask how long somebody has
     * been driving rather than only whether they are. Zero means it has never been set.
     */
    val sinceMs: Long = 0L,
) {
    /** "Driving · moving at vehicle speed, audio over Bluetooth" — one line for a surface. */
    fun describe(): String {
        val name = when (situation) {
            Situation.DRIVING -> "Driving"
            Situation.WALKING -> "Walking"
            Situation.POCKETED -> "Put away"
            Situation.ASLEEP -> "Asleep"
            Situation.AT_DESK -> "Using the phone"
            Situation.IN_A_MEETING -> "In a meeting"
            Situation.IN_COMPANY -> "With people"
            Situation.AT_HOME_IDLE -> "At home, phone down"
            Situation.OUT_AND_ABOUT -> "Out and about"
            Situation.UNKNOWN -> "Not sure"
        }
        return if (evidence.isEmpty()) name else "$name · ${evidence.joinToString(", ")}"
    }
}

/**
 * Turns the two readings plus the clock into a [SituationRead].
 *
 * Pure and deterministic: the clock, the previous read and every signal are parameters, so CI holds
 * every rule in here. Scoring rather than a decision tree, for two reasons — a tree has to commit to
 * an order of questions that the evidence does not justify, and a score falls naturally out as
 * [SituationRead.confidence] and [SituationRead.contenders] without a second mechanism.
 */
object AmbientSituation {

    /** Below this, nothing is claimed. The single most important number in the file. */
    const val MIN_SCORE = 4

    /** The score at which [SituationRead.confidence] reaches 1. Not a probability, a scale. */
    const val CONFIDENT = 6f

    /**
     * What the incumbent gets for being the incumbent.
     *
     * ⚠️ **Hysteresis, and it is not cosmetic.** Every input here flickers — a sip that heard no
     * voices, one accelerometer sample over a threshold, a Wi-Fi scan that came back thin — and a
     * detector that changed its mind on each of those would hand the layer above a stream of
     * transitions to act on and undo. One point is enough to require a real change of evidence and
     * small enough that it cannot hold a situation whose reasons have genuinely gone.
     */
    const val INCUMBENT_BONUS = 1

    /** Scores within this of the winner are worth naming as alternatives. */
    const val CONTENDER_MARGIN = 1

    /** Hours at which [Situation.ASLEEP] is considered at all — see that value's own note. */
    val NIGHT_HOURS = 22..23
    val SMALL_HOURS = 0..5

    private data class Candidate(val situation: Situation, val score: Int, val evidence: List<String>)

    /**
     * @param hourOfDay 0..23 local, passed in rather than read — the clock is the caller's, exactly
     *   as it is for [SensoriumBaseline.update]. A core that read a clock could not be tested.
     * @param prev the previous read, for hysteresis and for carrying [SituationRead.sinceMs].
     */
    fun read(
        env: EnvReading,
        phone: SenseContext,
        hourOfDay: Int,
        nowMs: Long,
        prev: SituationRead? = null,
    ): SituationRead {
        val candidates = listOfNotNull(
            driving(env, phone),
            walking(env, phone),
            pocketed(env, phone),
            asleep(env, phone, hourOfDay),
            atDesk(env, phone),
            inAMeeting(env, phone),
            inCompany(env, phone),
            atHomeIdle(env, phone),
            outAndAbout(env, phone),
        )
        if (candidates.isEmpty()) return unknown(prev, nowMs)

        // ⚠️ The bonus is applied for RANKING only and never reaches the reported score, so a
        // situation cannot creep up in confidence simply by persisting.
        val incumbent = prev?.situation
        val ranked = candidates.sortedByDescending {
            it.score + if (it.situation == incumbent) INCUMBENT_BONUS else 0
        }
        val best = ranked.first()
        if (best.score < MIN_SCORE) return unknown(prev, nowMs)

        val bestRank = best.score + if (best.situation == incumbent) INCUMBENT_BONUS else 0
        val contenders = ranked.drop(1)
            .filter { it.score >= MIN_SCORE }
            .filter { bestRank - (it.score + if (it.situation == incumbent) INCUMBENT_BONUS else 0) <= CONTENDER_MARGIN }
            .map { it.situation }

        return SituationRead(
            situation = best.situation,
            confidence = (best.score / CONFIDENT).coerceIn(0f, 1f),
            evidence = best.evidence,
            contenders = contenders,
            sinceMs = if (prev?.situation == best.situation && prev.sinceMs > 0L) prev.sinceMs else nowMs,
        )
    }

    /**
     * ⚠️ An UNKNOWN read restarts the clock rather than inheriting the previous one. "We have not
     * known what you were doing since 9am" is a true statement and a useless one; what a caller
     * wants from [SituationRead.sinceMs] is how long the CURRENT answer has held.
     */
    private fun unknown(prev: SituationRead?, nowMs: Long) = SituationRead(
        situation = Situation.UNKNOWN,
        sinceMs = if (prev?.situation == Situation.UNKNOWN && prev.sinceMs > 0L) prev.sinceMs else nowMs,
    )

    // ---- the rules ----

    /**
     * ⚠️ A Bluetooth route is corroboration and never the reason on its own: a car stereo, earbuds
     * and a kitchen speaker are all the same device type to Android, so on its own it would call a
     * washing-up session a drive.
     *
     * ⚠️ **The `score == 0` line is belt-and-braces and is NOT what enforces that today** — said
     * plainly because a negative test found it, and an overstated comment is a defect here. The
     * route is worth 1 and [MIN_SCORE] is 4, so a candidate built from corroboration alone could
     * never be reported anyway: deleting the line changes no output. What it does buy is that this
     * function's own contract is true where it is written rather than by arithmetic three screens
     * away — it cannot hand back a DRIVING candidate whose only evidence is "audio over Bluetooth",
     * whatever a later edit does to the bar or the bonus.
     */
    private fun driving(env: EnvReading, phone: SenseContext): Candidate? {
        var score = 0
        val why = mutableListOf<String>()
        if (env.motion == MotionState.DRIVING) {
            score += 4; why += "moving at vehicle speed"
        }
        if (env.setting == EnvSetting.VEHICLE) {
            score += 3; why += "it sounds like a vehicle"
        }
        if (score == 0) return null
        if (phone.route == AudioRoute.BLUETOOTH) {
            score += 1; why += "audio over Bluetooth"
        }
        return Candidate(Situation.DRIVING, score, why)
    }

    private fun walking(env: EnvReading, phone: SenseContext): Candidate? {
        if (env.motion != MotionState.WALKING) return null
        var score = 4
        val why = mutableListOf("walking pace")
        if (phone.awayFromHome == true) {
            score += 1; why += "away from home"
        }
        if (env.setting == EnvSetting.OUTDOOR) {
            score += 1; why += "outdoors"
        }
        return Candidate(Situation.WALKING, score, why)
    }

    /**
     * ⚠️ A covered phone whose screen is ON is somebody holding it against their ear, not a pocket.
     * The screen-off term carries most of the weight for exactly that reason.
     */
    private fun pocketed(env: EnvReading, phone: SenseContext): Candidate? {
        if (!env.covered) return null
        var score = 2
        val why = mutableListOf("something against the front of the phone")
        if (phone.screenOn == false) {
            score += 3; why += "screen off"
        }
        if (env.motion == MotionState.WALKING || env.motion == MotionState.HANDLING) {
            score += 1; why += "moving with you"
        }
        return Candidate(Situation.POCKETED, score, why)
    }

    private fun asleep(env: EnvReading, phone: SenseContext, hourOfDay: Int): Candidate? {
        if (hourOfDay !in NIGHT_HOURS && hourOfDay !in SMALL_HOURS) return null
        if (phone.attention() != Attention.AWAY) return null
        var score = 2
        val why = mutableListOf("the small hours, phone untouched")
        if (env.light == LightState.DARK) {
            score += 2; why += "dark"
        }
        if (phone.idle == true) {
            score += 2; why += "the phone has gone into Doze"
        }
        if (env.motion == MotionState.STILL) {
            score += 1; why += "still"
        }
        if (phone.charging == true) {
            score += 1; why += "charging"
        }
        return Candidate(Situation.ASLEEP, score, why)
    }

    private fun atDesk(env: EnvReading, phone: SenseContext): Candidate? {
        if (phone.attention() != Attention.IN_USE) return null
        if (env.covered) return null
        var score = 4
        val why = mutableListOf("screen on and unlocked")
        if (env.motion == MotionState.STILL || env.motion == MotionState.HANDLING) {
            score += 2; why += "settled"
        }
        return Candidate(Situation.AT_DESK, score, why)
    }

    /**
     * ⚠️ **The calendar is REQUIRED, and shipping this without it was considered and rejected.**
     * Voices plus a silenced phone plus stillness is also a quiet café, a waiting room and a train,
     * and asserting a meeting wrongly means holding back notifications somebody wanted. A diary
     * entry is the only signal here that is about an intention rather than an acoustic.
     */
    private fun inAMeeting(env: EnvReading, phone: SenseContext): Candidate? {
        if (phone.calendarBusy != true) return null
        var score = 3
        val why = mutableListOf("something on the calendar right now")
        if (env.social != SocialDensity.ALONE) {
            score += 2; why += "people nearby"
        }
        if (phone.wantsQuiet() == true) {
            score += 1; why += "you have silenced the phone"
        }
        if (env.motion == MotionState.STILL) {
            score += 1; why += "still"
        }
        return Candidate(Situation.IN_A_MEETING, score, why)
    }

    private fun inCompany(env: EnvReading, phone: SenseContext): Candidate? {
        var score = when (env.social) {
            SocialDensity.CROWD -> 4
            SocialDensity.FEW -> 3
            SocialDensity.ALONE -> return null
        }
        val why = mutableListOf(
            if (env.social == SocialDensity.CROWD) "it is crowded" else "company nearby",
        )
        if (env.noise == NoiseProfile.LIVELY || env.noise == NoiseProfile.LOUD) {
            score += 1; why += "lively"
        }
        if (phone.awayFromHome == true) {
            score += 1; why += "away from home"
        }
        return Candidate(Situation.IN_COMPANY, score, why)
    }

    /**
     * ⚠️ Requires a POSITIVE "at home". `awayFromHome` is null both when no home network has been
     * configured and when the SSID cannot be read, and treating either as being at home would put
     * somebody's house on a reading taken in a car park.
     */
    private fun atHomeIdle(env: EnvReading, phone: SenseContext): Candidate? {
        if (phone.awayFromHome != false) return null
        var score = 2
        val why = mutableListOf("on the home network")
        if (phone.attention() == Attention.AWAY) {
            score += 2; why += "phone put down"
        }
        if (env.motion == MotionState.STILL) {
            score += 1; why += "still"
        }
        return Candidate(Situation.AT_HOME_IDLE, score, why)
    }

    /**
     * ⚠️ **The base is 3 and not 2, and the difference is whether this situation exists at all.**
     * At 2 it was DOMINATED in every combination that cleared [MIN_SCORE]: reaching 4 needed two
     * corroborations, and each of those pairs handed a higher score to a sharper rule — walking
     * plus outdoors gives [walking] 6, walking plus people gives it 5, and outdoors plus people
     * ties with [inCompany], which is declared first. So [Situation.OUT_AND_ABOUT] could be named
     * as a contender and could never be the answer. That is the computed-and-never-used defect in
     * a brand-new file, and it was found by adding the scores up for a test rather than by reading
     * them. Being somewhere that is not home is a positive, measured fact and is worth as much as
     * being on the home network is to [atHomeIdle]; at 3 one corroboration reaches the bar and the
     * situation is reachable, while a sharper reading of the same evidence still wins.
     */
    private fun outAndAbout(env: EnvReading, phone: SenseContext): Candidate? {
        if (phone.awayFromHome != true) return null
        var score = 3
        val why = mutableListOf("on a network that is not home")
        if (env.motion == MotionState.WALKING) {
            score += 1; why += "walking"
        }
        if (env.setting == EnvSetting.OUTDOOR) {
            score += 1; why += "outdoors"
        }
        if (env.social != SocialDensity.ALONE) {
            score += 1; why += "people around"
        }
        return Candidate(Situation.OUT_AND_ABOUT, score, why)
    }
}
