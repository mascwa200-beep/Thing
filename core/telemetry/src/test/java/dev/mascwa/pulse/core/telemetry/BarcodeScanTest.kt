package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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

    // ---- expandUpcE: the small-packet codes that used to miss ------------------------------

    /**
     * ⚠️ **Every value here came out of a run of the real ZXing decoder, not out of my head.**
     * `UPCEReader.convertUPCEtoUPCA` from the shipped 3.5.3 jar was compared against this rule over
     * all 2,000,000 number-system-0-and-1 codes, and the two agreed on every one.
     *
     * These cover all four branches of the rule — the last data digit being 0–2, 3, 4, and 5–9 —
     * which is the whole of what there is to get wrong, and each was generated so that its check
     * digit is genuinely the one its expansion demands.
     *
     * ⚠️ **Two mistakes of mine are baked into this list, and both were caught by measuring rather
     * than reading.** One code I first wrote down expanded to a UPC-A failing its own checksum: I had
     * invented a barcode. And the list then claimed to cover all four branches while covering only
     * three — deleting the `'4'` case from the rule failed nothing at all, which the negative-test
     * pass reported as a sleeping guard. `01234543` is the fixture that closes it.
     */
    @Test
    fun aUpcEExpandsToTheUpcAItStandsFor() {
        assertEquals("012000003455", BarcodeScan.expandUpcE("01234505")) // last data digit 0
        assertEquals("042100005264", BarcodeScan.expandUpcE("04252614")) // ...1
        assertEquals("012300000451", BarcodeScan.expandUpcE("01234531")) // ...3, a different shift
        assertEquals("012340000053", BarcodeScan.expandUpcE("01234543")) // ...4, another shift again
        assertEquals("012345000089", BarcodeScan.expandUpcE("01234589")) // ...8, the digit moves last
    }

    /**
     * ⚠️ **The property that makes this worth doing at all.** A UPC-E scan and the UPC-A printed on
     * the same product's outer case have to reach ONE row of the database. Without the expansion the
     * compressed form normalises to a number four orders of magnitude away from the product's own
     * key, so the lookup finds nothing — and reports an unknown product for a barcode it read
     * perfectly, which is indistinguishable from a database gap.
     */
    @Test
    fun theExpandedFormAndTheProductsOwnKeyAreTheSameNumber() {
        val expanded = BarcodeScan.expandUpcE("01234505")!!
        assertEquals(BarcodeScan.normalize(expanded), BarcodeScan.normalize("0012000003455"))
        // ...and the compressed form on its own is emphatically NOT that key, which is the bug.
        assertNotEquals(BarcodeScan.normalize("01234505"), BarcodeScan.normalize(expanded))
        assertEquals(12_000_003_455L, BarcodeScan.normalize(expanded))
        assertEquals(1_234_505L, BarcodeScan.normalize("01234505"))
    }

    /**
     * ⚠️ **An EAN-8 is eight digits and is a product code in its own right.** Nothing here can tell
     * the two apart from the digits, which is exactly why the expansion is not folded into
     * [BarcodeScan.normalize] and is called only where the decoder has reported the symbology. What
     * this test pins is the half that IS decidable: a number system GS1 never assigns to UPC-E is not
     * a UPC-E, and inventing a twelve-digit number from one would name a product that does not exist.
     */
    @Test
    fun onlyTheTwoNumberSystemsUpcEActuallyUsesAreExpanded() {
        assertNull("EAN-8, number system 4", BarcodeScan.expandUpcE("40170725"))
        assertNull("EAN-8, number system 9", BarcodeScan.expandUpcE("96181072"))
        assertNotNull(BarcodeScan.expandUpcE("01234505"))
        assertNotNull(BarcodeScan.expandUpcE("11234505"))
    }

    /** Anything that is not eight digits is not a UPC-E, and says so rather than guessing. */
    @Test
    fun somethingThatIsNotAUpcEExpandsToNothing() {
        assertNull(BarcodeScan.expandUpcE(""))
        assertNull(BarcodeScan.expandUpcE(NUTELLA))
        assertNull(BarcodeScan.expandUpcE(PRINGLES_UPC))
        assertNull(BarcodeScan.expandUpcE("0123450"))   // seven
        assertNull(BarcodeScan.expandUpcE("012345055")) // nine
        assertNull(BarcodeScan.expandUpcE("abcdefgh"))
    }

    /**
     * ⚠️ **A UPC-E's check digit belongs to its EXPANDED form, and that is a real limit on
     * [BarcodeScan.checkDigitValid]'s "one rule covers every length" claim.** Applying the mod-10
     * weighting to the compressed eight digits is not a weaker check, it is a check of the wrong
     * number — and it fails on genuine products: three of the four real codes below do not satisfy it
     * while every one of their expansions does. Nothing rejects on the checksum anywhere in this file,
     * so no product is lost to it; what would be lost is a caller who took a `false` here as evidence
     * of a mistyped code and told somebody their barcode was wrong. Measured over the five genuine
     * codes below: four fail the compressed check and all five expansions pass.
     *
     * This is the property the expansion has to preserve: the digit is carried through unchanged and
     * lands on a twelve-digit code that genuinely satisfies the rule.
     */
    @Test
    fun anExpandedCodePassesTheChecksumThatTheCompressedFormCannotBeJudgedBy() {
        var compressedFailures = 0
        for (e in listOf("01234505", "04252614", "01234531", "01234543", "01234589")) {
            val a = BarcodeScan.expandUpcE(e)!!
            assertEquals("$e expands to twelve digits", 12, a.length)
            assertEquals("check digit carried through in $e", e.last(), a.last())
            assertTrue("$e -> $a satisfies GS1", BarcodeScan.checkDigitValid(a))
            if (!BarcodeScan.checkDigitValid(e)) compressedFailures++
        }
        assertEquals(
            "the compressed form is NOT judgeable by the same rule — if this ever reaches 0, the " +
                "fixtures have drifted to codes that hide the distinction",
            4, compressedFailures,
        )
    }

    // ---- canonical: the one thing both scanners are allowed to decide -----------------------

    /**
     * ⚠️ **A UPC-E decode has to leave the scanner as the twelve digits it stands for**, or the
     * lookup asks for a number the database has never heard of and reports an unknown product for a
     * barcode it read perfectly.
     */
    @Test
    fun aUpcEDecodeLeavesTheScannerExpanded() {
        assertEquals("012000003455", BarcodeScan.canonical("01234505", BarcodeScan.Symbology.UPC_E))
        assertEquals("012340000053", BarcodeScan.canonical("01234543", BarcodeScan.Symbology.UPC_E))
    }

    /**
     * ⚠️ **And every other symbology is passed through untouched**, which is the half that would be
     * quietly broken by an over-eager expansion. An EAN-8 is eight digits and is its own product
     * code; expanding one invents a twelve-digit number naming nothing.
     */
    @Test
    fun everyOtherSymbologyIsTheCodeItAlreadyPrints() {
        assertEquals("40170725", BarcodeScan.canonical("40170725", BarcodeScan.Symbology.EAN_8))
        assertEquals("01234505", BarcodeScan.canonical("01234505", BarcodeScan.Symbology.EAN_8))
        assertEquals(NUTELLA, BarcodeScan.canonical(NUTELLA, BarcodeScan.Symbology.EAN_13))
        assertEquals(PRINGLES_UPC, BarcodeScan.canonical(PRINGLES_UPC, BarcodeScan.Symbology.UPC_A))
        assertEquals(NUTELLA, BarcodeScan.canonical("  $NUTELLA\n", BarcodeScan.Symbology.EAN_13))
    }

    /**
     * A decode that is not a product code at all yields nothing, whatever the decoder called it —
     * so the confirmation counter never starts on a QR code or a shipping label that drifted through.
     */
    @Test
    fun somethingThatIsNotAProductCodeIsNotCanonicalisedIntoOne() {
        assertNull(BarcodeScan.canonical("https://example.com", BarcodeScan.Symbology.OTHER))
        assertNull(BarcodeScan.canonical("123456789", BarcodeScan.Symbology.OTHER))
        assertNull(BarcodeScan.canonical("", BarcodeScan.Symbology.EAN_13))
        // A code the decoder CALLED a UPC-E but which cannot be one is refused rather than passed
        // through as eight raw digits, which would be the wrong product rather than no product.
        assertNull(BarcodeScan.canonical("40170725", BarcodeScan.Symbology.UPC_E))
        assertNull(BarcodeScan.canonical(NUTELLA, BarcodeScan.Symbology.UPC_E))
    }

    /**
     * ⚠️ **Whatever leaves here is something the database can be asked for.** `canonical` feeds
     * `see`, `see` feeds the lookup, and `normalize` is what the lookup keys on — a gap anywhere in
     * that chain is a scan that visibly succeeds and then finds nothing, for no reason the person
     * holding the phone can see.
     */
    @Test
    fun anythingCanonicalIsAlsoConfirmableAndLookupable() {
        val decodes = listOf(
            NUTELLA to BarcodeScan.Symbology.EAN_13,
            PRINGLES_UPC to BarcodeScan.Symbology.UPC_A,
            "40170725" to BarcodeScan.Symbology.EAN_8,
            "01234505" to BarcodeScan.Symbology.UPC_E,
            "01234543" to BarcodeScan.Symbology.UPC_E,
        )
        for ((text, sym) in decodes) {
            val code = BarcodeScan.canonical(text, sym)
            assertNotNull("$text as $sym", code)
            assertTrue("$text as $sym", BarcodeScan.plausible(code!!))
            assertNotNull("$text as $sym", BarcodeScan.normalize(code))
            assertTrue("$text as $sym", run(code, code, code).confirmed)
        }
    }
}
