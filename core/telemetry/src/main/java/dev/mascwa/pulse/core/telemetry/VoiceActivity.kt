package dev.mascwa.pulse.core.telemetry

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * The acoustic interrogator's stage 0 — deciding which audio is worth transcribing.
 *
 * ⚠️ **Without this, whisper runs continuously and the feature is a battery fire.** Transcribing
 * every window regardless of content is the single most expensive thing the interrogator could do,
 * and in a normal room most windows contain no speech at all. Everything downstream is a filter on
 * what somebody said; this is the filter on whether anybody said anything.
 *
 * ⚠️ **The noise floor is LEARNED, not fixed.** A quiet bedroom and a moving car differ by more than
 * an order of magnitude in ambient level, so any absolute threshold is wrong in one of them — too
 * high and speech in the quiet room is never heard, too low and road noise is transcribed all the
 * way home. The floor tracks the ambient level and speech is what rises well above it.
 *
 * ⚠️ **The floor only rises quickly.** It adapts fast to a room getting louder and slowly to it
 * getting quieter, which is deliberately asymmetric: adapting down fast means a long sentence
 * gradually pulls the floor up to its own level and the speaker is cut off mid-word. Being slow to
 * believe the room got quiet costs a little sensitivity and buys never truncating anybody.
 *
 * Pure and frame-driven so CI holds every rule: the caller feeds fixed-size frames and gets back
 * state transitions, with no audio API and no clock anywhere in this file.
 */
object VoiceActivity {

    /** What the detector is doing. */
    enum class State {
        /** Listening to the room; nothing worth keeping. */
        SILENCE,

        /** Speech is being captured. */
        SPEECH,
    }

    /** What changed when a frame was fed in. */
    enum class Event {
        NONE,

        /** Speech began. The caller should start buffering, including the pre-roll. */
        SPEECH_START,

        /** Speech ended cleanly. The buffered audio is a segment worth transcribing. */
        SPEECH_END,

        /**
         * The segment hit [MAX_SEGMENT_FRAMES] and was cut. The caller should transcribe what it has
         * and immediately continue capturing, because the speaker has not stopped.
         *
         * ⚠️ Distinct from [SPEECH_END] on purpose. A forced cut lands mid-sentence, so a caller that
         * treated it as a clean end would hand the cascade half a clause and let [Discourse] judge a
         * fragment as if it were the whole argument.
         */
        SPEECH_CUT,
    }

    /**
     * The detector's carried state. Immutable, returned from [feed], so the whole thing stays pure.
     *
     * @param floor the learned ambient level.
     * @param speechFrames how many frames the current utterance has run for.
     * @param silenceFrames consecutive quiet frames since the last loud one.
     * @param calibrated true once enough frames have been seen for the floor to mean anything.
     */
    data class Detector(
        val state: State = State.SILENCE,
        val floor: Double = 0.0,
        val speechFrames: Int = 0,
        val silenceFrames: Int = 0,
        val framesSeen: Int = 0,
        val calibrated: Boolean = false,
    )

    data class Step(val detector: Detector, val event: Event, val energy: Double)

    /** Root-mean-square of one frame. The cheapest measure that tracks loudness rather than peaks. */
    fun rms(frame: FloatArray): Double {
        if (frame.isEmpty()) return 0.0
        var sum = 0.0
        for (s in frame) sum += s.toDouble() * s.toDouble()
        return sqrt(sum / frame.size)
    }

    /**
     * Zero-crossing rate: how often the waveform changes sign.
     *
     * ⚠️ This is what separates speech from a door slam. A bang is loud and would clear any energy
     * threshold, but it is broadband and crosses zero far more often than voiced speech does, which
     * carries most of its energy at the pitch of somebody's vocal folds. Energy alone would wake
     * whisper for every dropped saucepan.
     */
    fun zeroCrossingRate(frame: FloatArray): Double {
        if (frame.size < 2) return 0.0
        var crossings = 0
        for (i in 1 until frame.size) {
            if ((frame[i] >= 0f) != (frame[i - 1] >= 0f)) crossings++
        }
        return crossings.toDouble() / (frame.size - 1)
    }

    /** True when the frame is clipping badly enough that its energy says nothing useful. */
    fun saturated(frame: FloatArray): Boolean =
        frame.isNotEmpty() && frame.count { abs(it) >= CLIP_LEVEL } > frame.size * CLIP_SHARE

    /**
     * Feed one frame and get the new state.
     *
     * The frame length is the caller's choice and is not checked here — what matters is that it stays
     * constant, because every threshold below counts frames rather than milliseconds.
     */
    fun feed(d: Detector, frame: FloatArray): Step {
        val energy = rms(frame)
        val zcr = zeroCrossingRate(frame)
        val seen = d.framesSeen + 1
        val calibrated = d.calibrated || seen >= CALIBRATION_FRAMES

        // ⚠️ LOUDNESS IS JUDGED AGAINST THE PREVIOUS FLOOR, AND THE FLOOR IS UPDATED AFTERWARDS.
        // The obvious order — update the floor from this frame, then compare against it — does not
        // work, and measurement is the only reason that is known here rather than guessed at. With a
        // steady 0.28 RMS tone against a 0.0007 floor, the first loud frame dragged the floor to
        // 0.057 and the SECOND frame of identical audio fell below the ratio: the floor chased the
        // speech, the onset run reset every frame, and speech could never be declared at all. The
        // detector was silent on a perfect sine wave.
        val loud = calibrated &&
            energy > maxOf(d.floor * SPEECH_RATIO, ABSOLUTE_FLOOR) &&
            zcr < MAX_SPEECH_ZCR &&
            !saturated(frame)

        // ⚠️ And the floor learns only from frames that are neither speech nor a candidate for it.
        // Letting a sentence raise the floor is how a detector cuts a speaker off mid-word; letting
        // its onset raise the floor is how it never starts. The bootstrap case is separate because
        // a floor of zero has no meaning to adapt from.
        val floor = when {
            d.floor == 0.0 -> energy
            d.state == State.SPEECH || loud -> d.floor
            energy > d.floor -> d.floor + (energy - d.floor) * FLOOR_RISE
            else -> d.floor + (energy - d.floor) * FLOOR_FALL
        }

        return when (d.state) {
            State.SILENCE -> {
                val run = if (loud) d.speechFrames + 1 else 0
                if (run >= ONSET_FRAMES) {
                    Step(
                        d.copy(
                            state = State.SPEECH, floor = floor, speechFrames = run,
                            silenceFrames = 0, framesSeen = seen, calibrated = true,
                        ),
                        Event.SPEECH_START, energy,
                    )
                } else {
                    Step(
                        d.copy(
                            floor = floor, speechFrames = run, silenceFrames = 0,
                            framesSeen = seen, calibrated = calibrated,
                        ),
                        Event.NONE, energy,
                    )
                }
            }

            State.SPEECH -> {
                val quiet = if (loud) 0 else d.silenceFrames + 1
                val length = d.speechFrames + 1
                val next = d.copy(
                    floor = floor, speechFrames = length, silenceFrames = quiet,
                    framesSeen = seen, calibrated = true,
                )
                when {
                    // ⚠️ Bounded, for the same reason Discourse.segment is: a room with a television
                    // in it never goes quiet, and an unbounded segment would grow until the buffer
                    // did. Cut and keep going rather than stop.
                    length >= MAX_SEGMENT_FRAMES ->
                        Step(next.copy(speechFrames = 0, silenceFrames = 0), Event.SPEECH_CUT, energy)

                    // The hangover: a pause between clauses is not the end of a sentence.
                    quiet >= HANGOVER_FRAMES -> {
                        val spoken = length - quiet
                        // Too short to be speech at all - a cough, a click, a chair. Drop it rather
                        // than hand whisper a fragment it will hallucinate words into.
                        val event = if (spoken >= MIN_SPEECH_FRAMES) Event.SPEECH_END else Event.NONE
                        Step(
                            next.copy(state = State.SILENCE, speechFrames = 0, silenceFrames = 0),
                            event, energy,
                        )
                    }

                    else -> Step(next, Event.NONE, energy)
                }
            }
        }
    }

    // ---- constants, all owner-tunable and all counted in FRAMES ------------------------------

    /**
     * Frames before the floor is trusted. At the 20 ms frames the capture layer uses this is about
     * half a second — long enough to measure a room, short enough that the first thing said after
     * the service starts is not lost.
     */
    const val CALIBRATION_FRAMES = 25

    /** How much louder than ambient a frame must be. Speech in a normal room clears this easily. */
    const val SPEECH_RATIO = 3.0

    /**
     * ⚠️ A hard minimum regardless of the learned floor. In a genuinely silent room the floor tends
     * toward zero, and *any* ratio above zero is still zero — so faint electrical hiss would clear
     * the ratio test and the detector would fire on nothing at all.
     */
    const val ABSOLUTE_FLOOR = 0.004

    /** Asymmetric on purpose — see the class KDoc. */
    const val FLOOR_RISE = 0.20
    const val FLOOR_FALL = 0.02

    /** Consecutive loud frames before speech is declared. Rejects single-frame transients. */
    const val ONSET_FRAMES = 3

    /** Quiet frames that end an utterance. Long enough to survive a pause between clauses. */
    const val HANGOVER_FRAMES = 30

    /** Below this the segment is a noise, not a sentence, and is discarded. */
    const val MIN_SPEECH_FRAMES = 15

    /** Nothing is captured for longer than this in one go. */
    const val MAX_SEGMENT_FRAMES = 1_500

    /** Above this a frame is broadband noise rather than voiced speech. */
    const val MAX_SPEECH_ZCR = 0.35

    /** Sample magnitude at which a sample counts as clipped. */
    const val CLIP_LEVEL = 0.99f

    /** Share of clipped samples that makes a frame's energy meaningless. */
    const val CLIP_SHARE = 0.02
}
