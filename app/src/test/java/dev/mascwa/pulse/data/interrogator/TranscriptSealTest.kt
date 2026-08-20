package dev.mascwa.pulse.data.interrogator

import dev.mascwa.pulse.core.telemetry.TranscriptPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one consequential decision in the interrogator's storage path that can be exercised without a
 * device.
 *
 * Everything else in [TranscriptStore] is Room and Keystore, which CI can only compile. [TranscriptSeal]
 * was factored out into a file with no Android imports precisely so this rule is held by a test that
 * actually runs, rather than by whoever next edits the file.
 */
class TranscriptSealTest {

    private val working: (String) -> String? = { "enc:$it" }
    private val broken: (String) -> String? = { null }

    /**
     * ⚠️ THE RULE. `SecretCrypto.encrypt` returns null when the secure element is unavailable or the
     * key has been invalidated. The tempting reading is "store it anyway, it never leaves the
     * device" — but a device with no working keystore is precisely the device where a plaintext row
     * is most likely to be read by something else, and this store holds verbatim speech from people
     * who never agreed to be recorded. Losing the utterance is the correct outcome.
     *
     * Negative-tested: falling back to the plaintext on a null cipher fails exactly this test.
     */
    @Test
    fun anUtteranceThatCannotBeEncryptedIsNotStored() {
        assertNull(TranscriptSeal.seal("The whole scheme failed and nobody said so.", 0, broken))
    }

    @Test
    fun aSealedRowCarriesCiphertextAndNeverThePlaintext() {
        val plain = "The whole scheme failed and nobody said so."
        val row = TranscriptSeal.seal(plain, 4_242, working)
        assertNotNull(row)
        assertEquals(4_242L, row!!.atMs)
        assertEquals("enc:$plain", row.cipher)
        assertTrue("the row must not carry the plaintext itself", row.cipher != plain)
    }

    /**
     * ⚠️ The policy runs BEFORE the cipher, so a refused utterance is never even offered to the
     * keystore. Encrypting first and screening after would put the card number through the cipher
     * and, more to the point, would mean the screen could be skipped by any future caller that
     * reached for the cipher directly.
     */
    @Test
    fun thePolicyScreensBeforeTheCipherIsTouched() {
        var offered: String? = null
        val watching: (String) -> String? = { offered = it; "enc:$it" }
        assertNull(TranscriptSeal.seal("The card is 4111 1111 1111 1111.", 0, watching))
        assertNull("a refused utterance must never reach the cipher", offered)
    }

    /** What is sealed is the REDACTED text, not what was heard. */
    @Test
    fun whatIsSealedIsAlreadyRedacted() {
        val row = TranscriptSeal.seal("We moved here in 1987 and the boiler is original.", 0, working)
        assertNotNull(row)
        assertEquals("enc:We moved here in ${TranscriptPolicy.MASK} and the boiler is original.", row!!.cipher)
    }

    @Test
    fun emptyAndBlankAreRefusedBeforeAnythingElse() {
        assertNull(TranscriptSeal.seal("", 0, working))
        assertNull(TranscriptSeal.seal("   \n\t ", 0, working))
    }
}
