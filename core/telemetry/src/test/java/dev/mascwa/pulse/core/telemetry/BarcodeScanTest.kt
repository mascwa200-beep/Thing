package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
     * ⚠️ **The SCANNER does not convert UPC-A to EAN-13, and [BarcodeScan.normalize] does. Both are
     * right, and the distinction is the point.**
     *
     * This half is about confirmation: `see` counts identical decodes, and it must compare what the
     * camera actually read. Folding two spellings together here would mean one decode of each form
     * counted as two sightings of one code — confirming on evidence the optics never provided.
     *
     * Resolving the two spellings to one product is a *lookup* question, answered once, downstream,
     * by `normalize`. Probed against the live Open Food Facts API, `0038000138416`, `038000138416`
     * and `38000138416` all return the same product because the source is forgiving at its own end;
     * the bundled offline table has no server to be forgiving for it, which is why `normalize`
     * exists at all.
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

    // ---- normalize: the key the offline database is built on -------------------------------

    /**
     * ⚠️ **The property the whole offline design rests on.** A US packet prints UPC-A and a European
     * database stores EAN-13; they differ by one leading zero and nothing else. As text they are two
     * products and half of all US scans miss. As numbers they are one.
     *
     * `PRINGLES_UPC` and its EAN-13 form are the same real product — the fixture at the top of this
     * file already says so, which is why it is the pair used here.
     */
    @Test
    fun upcAAndItsEan13FormAreOneKey() {
        assertEquals(BarcodeScan.normalize("0$PRINGLES_UPC"), BarcodeScan.normalize(PRINGLES_UPC))
        assertEquals(38_000_138_416L, BarcodeScan.normalize(PRINGLES_UPC))
        // A GTIN-14 whose indicator digit is zero is the same number wearing more padding.
        assertEquals(BarcodeScan.normalize(PRINGLES_UPC), BarcodeScan.normalize("00$PRINGLES_UPC"))
    }

    /** Digits are extracted, so a scanner or a paste that carries punctuation still resolves. */
    @Test
    fun nonDigitsAreStrippedBeforeTheKeyIsTaken() {
        assertEquals(BarcodeScan.normalize(NUTELLA), BarcodeScan.normalize(" 3017-624 010701 "))
    }

    /** Anything that is not a product code at all has no key, rather than a misleading one. */
    @Test
    fun somethingThatIsNotAProductCodeHasNoKey() {
        assertNull(BarcodeScan.normalize(""))
        assertNull(BarcodeScan.normalize("12345"))            // no symbology is 5 long
        assertNull(BarcodeScan.normalize("https://example"))  // a QR code that decoded
        assertNull(BarcodeScan.normalize("301762401070123"))  // 15, past ITF-14
    }

    /**
     * ⚠️ **Measured, not assumed, and it is why nothing is rejected on the checksum.** Over the first
     * 600,000 rows of the real Open Food Facts export, **6.44% of product codes fail the GS1 check
     * digit** — 38,613 of them. Those are real products on real shelves whose code was typed in
     * slightly wrong by a contributor. A strict gate at lookup time would make roughly 286,000
     * products across the corpus permanently unfindable, to re-enforce a rule the barcode decoder
     * has already enforced.
     */
    @Test
    fun aCodeThatFailsItsChecksumStillResolves() {
        val broken = "3017624010700" // Nutella's code with the check digit knocked down by one
        assertFalse(BarcodeScan.checkDigitValid(broken))
        assertEquals(3_017_624_010_700L, BarcodeScan.normalize(broken))
    }

    /**
     * One weighting covers every length. Taking the weights from the RIGHT is equivalent to padding
     * to thirteen, so EAN-8, UPC-A, EAN-13 and ITF-14 need no special cases — and the version that
     * switches on length is the version with four places to be wrong.
     *
     * Every code here was verified against the GS1 definition before being written down.
     */
    @Test
    fun theChecksumRuleHoldsAtEveryLength() {
        assertTrue(BarcodeScan.checkDigitValid("96181072"))         // EAN-8
        assertTrue(BarcodeScan.checkDigitValid(PRINGLES_UPC))       // UPC-A, 12
        assertTrue(BarcodeScan.checkDigitValid("5449000000996"))    // EAN-13, Coca-Cola
        assertTrue(BarcodeScan.checkDigitValid("3017620422003"))    // EAN-13, Nutella 400g
        assertTrue(BarcodeScan.checkDigitValid("00012000001291"))   // GTIN-14
        assertFalse(BarcodeScan.checkDigitValid("5449000000995"))
        assertFalse(BarcodeScan.checkDigitValid("nonsense"))
    }

    /**
     * ⚠️ [BarcodeScan.plausible] gates what the camera acts on; [BarcodeScan.normalize] gates what
     * the database is asked for. They must agree on what counts as a barcode, or a code the scanner
     * confirms is one the lookup cannot express — a scan that visibly succeeds and then finds
     * nothing, for no reason the person can see.
     */
    @Test
    fun whatTheScannerConfirmsIsAlwaysSomethingTheDatabaseCanBeAskedFor() {
        for (code in listOf(NUTELLA, PRINGLES_UPC, "96181072", "00012000001291")) {
            assertTrue(code, BarcodeScan.plausible(code))
            assertNotNull(code, BarcodeScan.normalize(code))
        }
        for (code in listOf("12345", "", "abcdefgh")) {
            assertFalse(code, BarcodeScan.plausible(code))
            assertNull(code, BarcodeScan.normalize(code))
        }
    }
}
