package dev.mascwa.pulse.data.interrogator

import dev.mascwa.pulse.core.telemetry.TranscriptPolicy

/**
 * The admit-redact-encrypt decision for one utterance, with the cipher injected.
 *
 * ⚠️ **Deliberately a separate file with no Android imports at all.** The rule below is the most
 * consequential line in the interrogator's storage path and it is exactly the sort of thing only
 * ever exercised on a phone whose keystore has gone wrong — so it has to be testable on the JVM,
 * which means it must not depend on `android.*`, on Room, or on the storage schema. Returning a
 * plain [Sealed] rather than the Room entity is what buys that: what gets tested is the decision,
 * not the table it happens to land in, and the schema can change without touching either.
 *
 * See [TranscriptStore] for where it is applied and why the transcript is stored the way it is.
 */
object TranscriptSeal {

    /** Ciphertext and the moment it was said. Nothing else about the speaker is kept. */
    data class Sealed(val cipher: String, val atMs: Long)

    /**
     * @param encrypt the Keystore-bound cipher, which returns null when the secure element is
     *   unavailable or the key has been invalidated.
     * @return null when the utterance must not be stored, for any of three reasons — the policy
     *   refused it, it was empty, or it could not be sealed. The caller does not need to tell them
     *   apart: the correct response to all three is to carry on and not retry.
     */
    fun seal(text: String, atMs: Long, encrypt: (String) -> String?): Sealed? {
        // ⚠️ The policy runs BEFORE the cipher is touched. Encrypting first and screening after
        // would put a card number through the keystore, and — the part that actually matters —
        // would leave the screen skippable by any future caller that reached for the cipher itself.
        val admitted = TranscriptPolicy.admit(text, atMs) ?: return null
        // ⚠️ A row that cannot be encrypted is NOT stored. The tempting reading of a null cipher is
        // "store it anyway, it never leaves the device". It does not leave the device either way —
        // but a device with no working keystore is precisely the device where a plaintext row is
        // most likely to be read by something else, and this is the one store in the app holding
        // verbatim speech from people who never agreed to be recorded. Losing it is correct.
        val cipher = encrypt(admitted.text) ?: return null
        return Sealed(cipher, admitted.atMs)
    }
}
