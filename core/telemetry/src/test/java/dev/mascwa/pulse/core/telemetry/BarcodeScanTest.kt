package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Real product codes, from the fixtures the Open Food Facts parser is tested against. */
private const val NUTELLA = "3017624010701"   // EAN-13
private const val PRINGLES_UPC = "038000138416" // UPC-A, the same product as 0038000138416

class BarcodeScanTest {

    private fun run(vararg codes: String): BarcodeScan.Progress =
        codes.fold(BarcodeScan.Progress()) { p, c -> BarcodeScan.see(p, c) }

    /**
     * The rule the whole thing exists for: one decode is not a scan.
     *
     * ⚠️ A shelf, a multipack and the packet in your hand are all in frame, and the first decode is
     * whichever the optics resolved first. Firing on it logs the wrong product's calories, and the
     * name that comes back is a real food with real numbers — just not the one that was eaten.
     */
    @Test
    fun oneDecodeIsNotEnoughAndThreeAre() {
        assertFalse(run(NUTELLA).confirmed)
        assertFalse(run(NUTELLA, NUTELLA).confirmed)
        assertTrue(run(NUTELLA, NUTELLA, NUTELLA).confirmed)
        assertEquals(NUTELLA, run(NUTELLA, NUTELLA, NUTELLA).candidate)
    }

    /** A different code starts over rather than counting toward the one before it. */
    @Test
    fun sweepingAcrossAShelfConfirmsNothing() {
        val p = run(NUTELLA, PRINGLES_UPC, NUTELLA, PRINGLES_UPC)
        assertFalse(p.confirmed)
        assertEquals(1, p.seen)
        assertEquals(PRINGLES_UPC, p.candidate)
    }

    /**
     * ⚠️ A frame that decodes nothing does NOT reset the count.
     *
     * Decodes are intermittent in ordinary light. A barcode that reads on frames 1, 3 and 5 is one
     * somebody is holding perfectly still, and starting over on every blank frame between them would
     * mean nothing ever confirms.
     */
    @Test
    fun aBlankFrameBetweenReadsDoesNotStartOver() {
        var p = BarcodeScan.see(BarcodeScan.Progress(), NUTELLA)
        p = BarcodeScan.nothing(p)
        p = BarcodeScan.see(p, NUTELLA)
        p = BarcodeScan.nothing(p)
        p = BarcodeScan.see(p, NUTELLA)
        assertTrue(p.confirmed)
    }

    /**
     * Something that is not a product code is ignored, and ignoring is not resetting.
     *
     * A QR code or a shipping label drifting through the frame must not undo the packet being held
     * steady — so an implausible decode leaves the progress exactly as it was.
     */
    @Test
    fun aNonProductCodeIsIgnoredWithoutDisturbingTheScan() {
        val p = run(NUTELLA, "https://example.com", NUTELLA, "ABC-123-XYZ", NUTELLA)
        assertTrue(p.confirmed)
        assertEquals(NUTELLA, p.candidate)
    }

    /**
     * Plausibility is length and digits only — the decoder has already checked the check digit.
     *
     * ⚠️ The lengths are the real symbologies: EAN-8/UPC-E 8, UPC-A 12, EAN-13 13, ITF-14 14.
     */
    @Test
    fun onlyRealProductCodeShapesArePlausible() {
        assertTrue(BarcodeScan.plausible(NUTELLA))
        assertTrue(BarcodeScan.plausible(PRINGLES_UPC))
        assertTrue("EAN-8", BarcodeScan.plausible("40170725"))
        assertTrue("ITF-14", BarcodeScan.plausible("10038000138413"))
        assertFalse("nine digits is no symbology", BarcodeScan.plausible("123456789"))
        assertFalse("a letter is not a product code", BarcodeScan.plausible("30176240107O1"))
        assertFalse(BarcodeScan.plausible(""))
        assertFalse(BarcodeScan.plausible("   "))
    }

    /**
     * ⚠️ Nothing here converts UPC-A to EAN-13, and that is measured rather than forgotten.
     *
     * Probed against the live Open Food Facts API: `0038000138416`, `038000138416` and `38000138416`
     * all return the same product, because the source normalises leading zeros at its own end. A
     * conversion here would be code solving a problem that does not exist.
     */
    @Test
    fun bothLengthsOfOneProductAreEquallyPlausibleAndNeitherIsRewritten() {
        assertTrue(BarcodeScan.plausible("0038000138416"))
        assertTrue(BarcodeScan.plausible("038000138416"))
        assertEquals("038000138416", run("038000138416").candidate)
        // ...and the two forms are still treated as different candidates, because this cannot know
        // they are one product and guessing would be the conversion it is deliberately not doing.
        assertEquals(1, run("0038000138416", "038000138416").seen)
    }

    /**
     * The count stops at the threshold.
     *
     * Left unbounded, a scanner held on one packet for a minute counts into the thousands and
     * `fraction` — which drives a progress indicator — stops meaning anything.
     */
    @Test
    fun theCountAndTheFractionAreBothBounded() {
        val many = Array(500) { NUTELLA }
        val p = run(*many)
        assertEquals(BarcodeScan.CONFIRMATIONS, p.seen)
        assertEquals(1f, p.fraction, 1e-6f)
        assertEquals(0f, BarcodeScan.Progress().fraction, 1e-6f)
        assertEquals(1f / 3f, run(NUTELLA).fraction, 1e-6f)
    }

    /** Nothing scanned is not a confirmation, whatever the count says. */
    @Test
    fun aBlankCandidateIsNeverConfirmed() {
        assertFalse(BarcodeScan.Progress(candidate = "", seen = 99).confirmed)
        assertFalse(BarcodeScan.Progress().confirmed)
    }

    /** Surrounding whitespace is trimmed rather than making a code implausible. */
    @Test
    fun whitespaceAroundACodeIsTrimmed() {
        assertEquals(NUTELLA, run(" $NUTELLA ").candidate)
        assertTrue(run(" $NUTELLA ", NUTELLA, "$NUTELLA\n").confirmed)
    }
}
