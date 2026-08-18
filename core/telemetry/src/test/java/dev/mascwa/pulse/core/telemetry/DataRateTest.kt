package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Every figure below is arithmetic, worked before it was asserted: kbps × 60 seconds ÷ 8 bits ÷ 10⁶.
 * The three unusual rates are real ones, taken from a 360-station sample of the live directory.
 */
class DataRateTest {

    @Test fun whatAnOrdinaryStreamCosts() {
        // 128 × 60 / 8 / 1000 = 0.96 MB per minute. The commonest rate in the sample by a distance.
        assertEquals("about 1.0 MB a minute", DataRate.describeKilobits(128))
        // 320 -> 2.4
        assertEquals("about 2.4 MB a minute", DataRate.describeKilobits(320))
        // 24 -> 0.18, and the tenth is kept: down here 0.2 and 0.9 are very different allowances.
        assertEquals("about 0.2 MB a minute", DataRate.describeKilobits(24))
    }

    @Test fun losslessIsBelievedBecauseItIsReal() {
        // ⚠️ Radio Paradise publishes 1441 kbps and is telling the truth — FLAC stereo really is
        // about that. Capping at 320 to catch the typo below would have thrown this away.
        // 1441 × 60 / 8 / 1000 = 10.8, and past ten a tenth of a megabyte is noise.
        assertEquals("about 11 MB a minute", DataRate.describeKilobits(1441))
    }

    @Test fun aRateNoStreamCouldHaveIsNotStated() {
        // The real instance: a station named "Groove Salad 320K AAC" publishing 320000 — bits typed
        // into a field that wants kilobits. Null, not clamped: a clamped number is still a number on
        // screen, and this figure is user-submitted.
        assertNull(DataRate.describeKilobits(320_000))
        assertNull(DataRate.fromKilobits(320_000))
        assertNull(DataRate.describeKilobits(0))
        assertNull(DataRate.describeKilobits(-1))
        // Below the floor is the other direction of the same mistake.
        assertNull(DataRate.fromKilobits(1))
    }

    @Test fun theBandEndsWhereRealStreamsEnd() {
        assertEquals(8_000, DataRate.fromKilobits(DataRate.PLAUSIBLE_MIN_KBPS))
        assertEquals(2_000_000, DataRate.fromKilobits(DataRate.PLAUSIBLE_MAX_KBPS))
        assertNull(DataRate.fromKilobits(DataRate.PLAUSIBLE_MIN_KBPS - 1))
        assertNull(DataRate.fromKilobits(DataRate.PLAUSIBLE_MAX_KBPS + 1))
    }

    @Test fun theShortFormSaysOnlyWhatIsKnown() {
        assertEquals("AAC · 128k", DataRate.quality("AAC", 128))
        // Either half may be absent, and what is left stands alone rather than gaining a placeholder.
        assertEquals("MP3", DataRate.quality("mp3", 0))
        assertEquals("192k", DataRate.quality("", 192))
        assertEquals("192k", DataRate.quality(null, 192))
        // The directory's own word for "we do not know" is not a codec. 20 of 360 said it.
        assertEquals("128k", DataRate.quality("UNKNOWN", 128))
        assertNull(DataRate.quality("UNKNOWN", 0))
        assertNull(DataRate.quality(null, 0))
    }

    @Test fun theVideoPanelAndTheRadioAgree() {
        // The live-TV panel measures bits per second from the player; the radio reads kilobits from
        // the directory. The same stream must cost the same either way, or one screen is wrong.
        assertEquals(DataRate.describe(128_000), DataRate.describeKilobits(128))
    }
}
