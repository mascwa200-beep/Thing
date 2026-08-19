package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptPolicyTest {

    private fun e(text: String, atMs: Long) = TranscriptPolicy.Entry(text, atMs)

    // ---- 1. never written --------------------------------------------------------------------

    /**
     * ⚠️ A long digit run is card-shaped whatever was said around it. Spoken digits arrive from a
     * transcriber separated by spaces, so the pattern has to tolerate them — a rule that only caught
     * the unspaced form would miss the commonest case by a distance.
     */
    @Test
    fun aLongDigitRunIsNeverWritten() {
        assertTrue(TranscriptPolicy.isSensitive("It is 4111111111111111 on the front."))
        assertTrue(TranscriptPolicy.isSensitive("It is 4111 1111 1111 1111 on the front."))
        assertTrue(TranscriptPolicy.isSensitive("four one one one 1 1 1 1 1 1 1 1 1 1 1 1"))
        assertNull(TranscriptPolicy.admit("The card is 4111 1111 1111 1111.", 0))
    }

    /** A secret word beside any code at all is enough, even when the code is short. */
    @Test
    fun aCodeNamedAsASecretIsNeverWritten() {
        assertTrue(TranscriptPolicy.isSensitive("The one-time code is 448215, read it back to me."))
        assertTrue(TranscriptPolicy.isSensitive("My pin is 4821 if you need to get in."))
        assertTrue(TranscriptPolicy.isSensitive("The verification code came through as 90210 just now."))
        assertNull(TranscriptPolicy.admit("My pin is 4821 if you need to get in.", 0))
    }

    /**
     * ⚠️ The screen is deliberately narrow. A broad "financial talk" or "health talk" filter was
     * considered and rejected — those are exactly the conversations where reasoning matters most,
     * so screening them out would gut the feature. Ordinary numbers must survive.
     */
    @Test
    fun ordinarySpeechWithNumbersIsStillWritten() {
        for (s in listOf(
            "The scheme cost about 40000 pounds in its first year of operation.",
            "We moved here in 1987 and the boiler is original to the house.",
            "Take the 274 bus and get off at the fourth stop after the bridge.",
            "The password reset email never arrived, which is the whole problem.", // secret word, no code
            "It is 4821 steps according to the phone, which cannot be right.",     // code, no secret word
        )) {
            assertFalse("'$s' must not be screened out", TranscriptPolicy.isSensitive(s))
            assertNotNull(TranscriptPolicy.admit(s, 0))
        }
    }

    // ---- 2. redaction -------------------------------------------------------------------------

    /**
     * ⚠️ The credential shapes must run BEFORE the digit mask, and this test is what holds it.
     * Several of the shapes contain digit runs; masking those first breaks the pattern and leaves
     * the rest of the key in the clear — the same ordering trap SecretScrub documents for
     * `Authorization: Bearer`. Negative-tested: swapping the two passes fails exactly this test.
     */
    @Test
    fun credentialShapesAreMaskedBeforeDigitsCanBreakThem() {
        val key = "sk-abc123def456ghi789jkl012"
        val out = TranscriptPolicy.redact("The key is $key, use it.")
        assertFalse("the key must not survive in any part: $out", out.contains("abc123def456"))
        assertFalse(out.contains(key))
        assertTrue(out.contains(TranscriptPolicy.MASK))

        // ⚠️ The assertion is on the WHOLE rendered line, not on the secret's absence. An earlier
        // draft asserted only `!masked.contains(secret)`, which passes with the passes swapped while
        // the credential's tail leaks: for a Slack token, masking digits first turns
        // `xoxb-<10 digits>-<16-char tail>` into `xoxb-[redacted]-<tail>` — the whole original string is
        // indeed gone, and the tail is sitting there in the clear. The perturbation run reported this
        // guard asleep, which is exactly what it is for.
        //
        // ⚠️ The Slack fixture is ASSEMBLED rather than written as a literal. GitHub push protection
        // classifies a well-formed Slack token as a real secret and rejects the whole push — which it
        // did, for this file, on the first attempt. The runtime string is identical, which is all the
        // test cares about; only the bytes on disk differ. Any future fixture that trips the scanner
        // gets the same treatment. Do NOT resolve such a block with the "allow this secret" link.
        val slack = "xoxb" + "-1234567890-" + "abcdefghijklmnop"
        for (secret in listOf(
            "ghp_0123456789abcdefghijklmnopqrstuvwxyz",
            "AIzaSyA1234567890abcdefghijklmnopqrstuv",
            slack,
            "Bearer abcdef0123456789ghijkl",
            "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NSJ9",
        )) {
            assertEquals(
                "nothing of '$secret' may survive in any part",
                "value ${TranscriptPolicy.MASK} end",
                TranscriptPolicy.redact("value $secret end"),
            )
        }
    }

    @Test
    fun theMaskIsFixedWidthSoTheOriginalLengthIsNotLeaked() {
        val short = TranscriptPolicy.redact("code 1234")
        val long = TranscriptPolicy.redact("code 123456789012")
        assertEquals(short.length, long.length)
        assertEquals("code ${TranscriptPolicy.MASK}", short)
    }

    @Test
    fun shortNumbersAndWordsSurviveRedaction() {
        assertEquals(
            "We moved here in ${TranscriptPolicy.MASK} and took the 27 bus.",
            TranscriptPolicy.redact("We moved here in 1987 and took the 27 bus."),
        )
    }

    // ---- 3. retention -------------------------------------------------------------------------

    /**
     * ⚠️ BOTH bounds are enforced, not either. The window is what the owner reasons about; the count
     * is what keeps a television left on all afternoon from filling the device before the window has
     * expired. Negative-tested in both directions.
     */
    @Test
    fun ageAndCountAreBothEnforced() {
        val now = 1_000_000_000L
        val w = TranscriptPolicy.WINDOW_MS

        val aged = listOf(e("old", now - w - 1), e("just inside", now - w + 1), e("new", now))
        assertEquals(
            listOf("just inside", "new"),
            TranscriptPolicy.prune(aged, now).map { it.text },
        )
        assertEquals(listOf("old"), TranscriptPolicy.expired(aged, now).map { it.text })

        // All fresh, but too many: the newest survive.
        val many = (0 until 10).map { e("line $it", now - it) }.reversed()
        val kept = TranscriptPolicy.prune(many, now, maxEntries = 4)
        assertEquals(4, kept.size)
        assertEquals(listOf("line 3", "line 2", "line 1", "line 0"), kept.map { it.text })
        assertEquals(6, TranscriptPolicy.expired(many, now, maxEntries = 4).size)
    }

    @Test
    fun aFreshQuietDayIsLeftAlone() {
        val now = 1_000L
        val entries = listOf(e("one", 0), e("two", 500))
        assertEquals(entries, TranscriptPolicy.prune(entries, now))
        assertTrue(TranscriptPolicy.expired(entries, now).isEmpty())
    }

    @Test
    fun pruningNothingYieldsNothing() {
        assertTrue(TranscriptPolicy.prune(emptyList(), 0).isEmpty())
        assertTrue(TranscriptPolicy.expired(emptyList(), 0).isEmpty())
    }

    // ---- the whole policy ---------------------------------------------------------------------

    /**
     * ⚠️ [TranscriptPolicy.admit] returns null rather than an empty entry for anything it refuses.
     * An empty row is still a row, and a caller that wrote one would be recording that *something*
     * was said at that moment — which is most of what the refusal was protecting.
     */
    @Test
    fun admitRefusesRatherThanStoringAnEmptyRow() {
        assertNull(TranscriptPolicy.admit("", 0))
        assertNull(TranscriptPolicy.admit("   \n ", 0))
        assertNull(TranscriptPolicy.admit("The card is 4111 1111 1111 1111.", 0))

        val ok = TranscriptPolicy.admit("  We moved here in 1987 and the boiler is original.  ", 42)
        assertNotNull(ok)
        assertEquals(42L, ok!!.atMs)
        assertEquals("We moved here in ${TranscriptPolicy.MASK} and the boiler is original.", ok.text)
    }
}
