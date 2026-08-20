package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * ⚠️ Every fixture here is SYNTHESISED AUDIO with a known shape, not a hand-picked number. A tone at
 * a stated amplitude has a computable RMS and a computable zero-crossing rate, so the expectations
 * below are derived from the signal rather than from what the code happened to return — which is the
 * habit that has caught roughly a dozen wrong assertions in this repo already.
 */
class VoiceActivityTest {

    private val frameLen = 320 // 20 ms at 16 kHz, the rate the capture layer uses

    /** A sine wave: RMS is amplitude / sqrt(2), and it crosses zero twice per cycle. */
    private fun tone(amplitude: Float, hz: Double = 150.0, n: Int = frameLen): FloatArray =
        FloatArray(n) { i -> (amplitude * sin(2 * PI * hz * i / 16_000.0)).toFloat() }

    /** Broadband noise: high zero-crossing rate, which is what separates a bang from a voice. */
    private fun noise(amplitude: Float, seed: Int = 1, n: Int = frameLen): FloatArray {
        val r = Random(seed)
        return FloatArray(n) { (r.nextDouble(-1.0, 1.0) * amplitude).toFloat() }
    }

    private fun silence(level: Float = 0.001f) = tone(level, hz = 60.0)

    /** Feed a run of identical frames, collecting the events. */
    private fun run(d: VoiceActivity.Detector, frame: FloatArray, times: Int):
        Pair<VoiceActivity.Detector, List<VoiceActivity.Event>> {
        var cur = d
        val events = mutableListOf<VoiceActivity.Event>()
        repeat(times) {
            val step = VoiceActivity.feed(cur, frame)
            cur = step.detector
            if (step.event != VoiceActivity.Event.NONE) events += step.event
        }
        return cur to events
    }

    // ---- the measures themselves ------------------------------------------------------------

    @Test
    fun rmsOfAToneIsAmplitudeOverRootTwo() {
        // Derived, not recalled: for a full-cycle sine, RMS = A / sqrt(2) = 0.5 * 0.7071 = 0.3536.
        assertEquals(0.5 / kotlin.math.sqrt(2.0), VoiceActivity.rms(tone(0.5f)), 0.01)
        assertEquals(0.0, VoiceActivity.rms(FloatArray(0)), 1e-9)
        assertEquals(0.0, VoiceActivity.rms(FloatArray(frameLen)), 1e-9)
    }

    /**
     * ⚠️ THE RULE THAT SEPARATES A VOICE FROM A DOOR SLAM. A 150 Hz tone crosses zero 300 times a
     * second — about 0.019 of a 16 kHz frame — while broadband noise crosses on roughly half its
     * samples. Energy alone cannot tell them apart, and would wake whisper for every dropped pan.
     */
    @Test
    fun zeroCrossingSeparatesVoicedToneFromBroadbandNoise() {
        val voiced = VoiceActivity.zeroCrossingRate(tone(0.5f, hz = 150.0))
        val bang = VoiceActivity.zeroCrossingRate(noise(0.5f))
        assertTrue("a 150 Hz tone should cross rarely, got $voiced", voiced < 0.05)
        assertTrue("broadband noise should cross often, got $bang", bang > 0.35)
        assertTrue(voiced < VoiceActivity.MAX_SPEECH_ZCR)
        assertTrue(bang > VoiceActivity.MAX_SPEECH_ZCR)
    }

    @Test
    fun aClippedFrameIsRecognisedAsMeaningless() {
        assertFalse(VoiceActivity.saturated(tone(0.5f)))
        assertTrue(VoiceActivity.saturated(FloatArray(frameLen) { 1.0f }))
        assertFalse("a couple of clipped samples is not saturation",
            VoiceActivity.saturated(FloatArray(frameLen) { if (it < 2) 1.0f else 0.1f }))
    }

    // ---- the state machine -------------------------------------------------------------------

    /**
     * ⚠️ Nothing fires before the floor has been measured. A detector that triggered on its very
     * first frame would fire on whatever the microphone happened to be doing when the service
     * started, which is exactly when a phone is being picked up and handled.
     */
    @Test
    fun nothingFiresBeforeCalibration() {
        // ⚠️ The fixture has to establish a LOW floor first and only then go loud, all inside the
        // calibration window. An earlier version fed loud frames from the very first sample, which
        // could not fail: the floor bootstraps to whatever the first frame measured, so the second
        // frame of loud audio was already below the ratio and nothing fired with or without the
        // gate. The perturbation run reported the guard asleep, which is exactly what it was.
        var d = VoiceActivity.Detector()
        val quiet = 5
        val loud = VoiceActivity.CALIBRATION_FRAMES - quiet - 2
        assertTrue("the fixture must stay inside the calibration window", quiet + loud < VoiceActivity.CALIBRATION_FRAMES)
        assertTrue("and must be long enough to trip the onset", loud > VoiceActivity.ONSET_FRAMES)

        d = run(d, silence(), quiet).first
        val (after, events) = run(d, tone(0.5f), loud)
        assertTrue("fired during calibration: $events", events.isEmpty())
        assertFalse(after.calibrated)
    }

    /**
     * ⚠️ A single loud frame is a transient, not an onset. Without the run requirement the detector
     * starts a segment on one sample of a chair scraping, and every such start costs a whisper
     * invocation. An earlier version of this suite asserted nothing about WHEN speech began, so
     * dropping the requirement entirely broke no test.
     */
    @Test
    fun anIsolatedLoudFrameIsNotAnOnset() {
        val calibrated = run(VoiceActivity.Detector(), silence(), 40).first

        val (afterOne, oneEvents) = run(calibrated, tone(0.5f), 1)
        assertTrue("one frame must not start speech: $oneEvents", oneEvents.isEmpty())
        assertEquals(VoiceActivity.State.SILENCE, afterOne.state)

        val (afterShort, shortEvents) = run(calibrated, tone(0.5f), VoiceActivity.ONSET_FRAMES - 1)
        assertTrue("a run below the threshold must not start speech: $shortEvents", shortEvents.isEmpty())
        assertEquals(VoiceActivity.State.SILENCE, afterShort.state)

        val (afterOnset, onsetEvents) = run(calibrated, tone(0.5f), VoiceActivity.ONSET_FRAMES)
        assertEquals(listOf(VoiceActivity.Event.SPEECH_START), onsetEvents)
        assertEquals(VoiceActivity.State.SPEECH, afterOnset.state)
    }

    @Test
    fun quietRoomThenSpeechThenQuietYieldsExactlyOneSegment() {
        var d = VoiceActivity.Detector()
        d = run(d, silence(), 40).first
        assertTrue("the floor should be learned by now", d.calibrated)

        val (afterSpeech, startEvents) = run(d, tone(0.4f), 40)
        assertEquals(listOf(VoiceActivity.Event.SPEECH_START), startEvents)
        assertEquals(VoiceActivity.State.SPEECH, afterSpeech.state)

        val (afterQuiet, endEvents) = run(afterSpeech, silence(), VoiceActivity.HANGOVER_FRAMES + 1)
        assertEquals(listOf(VoiceActivity.Event.SPEECH_END), endEvents)
        assertEquals(VoiceActivity.State.SILENCE, afterQuiet.state)
    }

    /**
     * ⚠️ THE HANGOVER. A pause between clauses is not the end of a sentence, and a detector without
     * this cuts every speaker into fragments at their own commas — which then reach [Discourse] as
     * separate utterances and get judged as if each were a whole argument.
     */
    @Test
    fun aPauseBetweenClausesDoesNotEndTheUtterance() {
        var d = run(VoiceActivity.Detector(), silence(), 40).first
        d = run(d, tone(0.4f), 30).first
        assertEquals(VoiceActivity.State.SPEECH, d.state)

        val (afterPause, events) = run(d, silence(), VoiceActivity.HANGOVER_FRAMES - 1)
        assertTrue("a short pause must not end the utterance: $events", events.isEmpty())
        assertEquals(VoiceActivity.State.SPEECH, afterPause.state)

        // And speech resuming clears the count, so the next pause starts over.
        val resumed = run(afterPause, tone(0.4f), 5).first
        assertEquals(0, resumed.silenceFrames)
    }

    /** A cough is loud, brief, and worth nothing. It must not reach whisper. */
    @Test
    fun aBriefNoiseIsNotReportedAsSpeech() {
        var d = run(VoiceActivity.Detector(), silence(), 40).first
        // Long enough to trip the onset, far short of MIN_SPEECH_FRAMES.
        d = run(d, tone(0.4f), VoiceActivity.ONSET_FRAMES + 1).first
        assertEquals(VoiceActivity.State.SPEECH, d.state)
        val (after, events) = run(d, silence(), VoiceActivity.HANGOVER_FRAMES + 1)
        assertTrue("a click must not become a segment: $events", VoiceActivity.Event.SPEECH_END !in events)
        assertEquals(VoiceActivity.State.SILENCE, after.state)
    }

    /**
     * ⚠️ A television left on never goes quiet, so the segment has to be bounded or the buffer grows
     * until the process dies. SPEECH_CUT is a DIFFERENT event from SPEECH_END because the cut lands
     * mid-sentence, and a caller that conflated them would hand the cascade half a clause.
     */
    @Test
    fun anEndlessTalkerIsCutRatherThanBuffered() {
        var d = run(VoiceActivity.Detector(), silence(), 40).first
        val (after, events) = run(d, tone(0.4f), VoiceActivity.MAX_SEGMENT_FRAMES + 10)
        assertTrue("expected a cut, got $events", VoiceActivity.Event.SPEECH_CUT in events)
        assertTrue("a cut is not an end", VoiceActivity.Event.SPEECH_END !in events)
        assertEquals("capture must continue after a cut", VoiceActivity.State.SPEECH, after.state)
    }

    /**
     * ⚠️ THE ASYMMETRIC FLOOR. It rises quickly and falls slowly, so a long sentence cannot pull the
     * floor up to its own level and cut the speaker off. Tested by the property that matters: the
     * floor does not move at all while speech is being captured.
     */
    @Test
    fun theFloorDoesNotLearnFromSpeechItIsCapturing() {
        var d = run(VoiceActivity.Detector(), silence(), 40).first
        val quietFloor = d.floor
        d = run(d, tone(0.6f), 100).first
        assertEquals(VoiceActivity.State.SPEECH, d.state)
        assertEquals("the floor must be frozen during speech", quietFloor, d.floor, 1e-12)
    }

    @Test
    fun theFloorRisesFasterThanItFalls() {
        var d = run(VoiceActivity.Detector(), silence(0.01f), 40).first
        val start = d.floor
        // Louder ambient (still below the speech ratio, so it stays SILENCE and keeps learning).
        val up = run(d, tone(0.02f), 10).first.floor
        val rose = up - start
        val down = run(d.copy(floor = up), silence(0.01f), 10).first.floor
        val fell = up - down
        assertTrue("floor should rise: $start -> $up", rose > 0)
        assertTrue("floor should fall: $up -> $down", fell > 0)
        assertTrue("rising ($rose) must outpace falling ($fell)", rose > fell)
    }

    /**
     * ⚠️ In a genuinely silent room the learned floor tends toward zero, and any multiple of zero is
     * still zero — so without an absolute minimum, electrical hiss would clear the ratio test and
     * the detector would fire on nothing at all.
     */
    @Test
    fun aSilentRoomDoesNotTriggerOnHiss() {
        val hiss = 0.0005f
        var d = run(VoiceActivity.Detector(), tone(hiss), 40).first
        assertTrue(d.calibrated)
        val (_, events) = run(d, tone(hiss * 6), 60) // 6x the floor, still far below ABSOLUTE_FLOOR
        assertTrue("hiss must not read as speech: $events", events.isEmpty())
    }

    /** Broadband noise at speech volume is a slam, not a sentence, however loud it is. */
    @Test
    fun aLoudBangDoesNotStartASegment() {
        var d = run(VoiceActivity.Detector(), silence(), 40).first
        val (_, events) = run(d, noise(0.6f), 60)
        assertTrue("broadband noise must not read as speech: $events", events.isEmpty())
    }
}
