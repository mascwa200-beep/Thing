package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec

class LedgerSignatureTest {

    private fun p256(): KeyPair = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"))
    }.generateKeyPair()

    private val head = "a".repeat(64) // a plausible hex head hash

    @Test
    fun validSignatureVerifies() {
        val kp = p256()
        val sig = LedgerSignature.signHead(head, kp.private)
        assertTrue(LedgerSignature.verify(head, sig, kp.public.encoded))
    }

    @Test
    fun signatureOverDifferentHeadFails() {
        val kp = p256()
        val sig = LedgerSignature.signHead(head, kp.private)
        val otherHead = "b".repeat(64)
        assertFalse(LedgerSignature.verify(otherHead, sig, kp.public.encoded))
    }

    @Test
    fun signatureFromAnotherKeyFails() {
        val signer = p256()
        val impostor = p256()
        val sig = LedgerSignature.signHead(head, signer.private)
        assertFalse(LedgerSignature.verify(head, sig, impostor.public.encoded))
    }

    @Test
    fun garbageSignatureIsRejectedNotThrown() {
        val kp = p256()
        assertFalse(LedgerSignature.verify(head, byteArrayOf(1, 2, 3, 4), kp.public.encoded))
    }

    @Test
    fun garbagePublicKeyIsRejectedNotThrown() {
        val kp = p256()
        val sig = LedgerSignature.signHead(head, kp.private)
        assertFalse(LedgerSignature.verify(head, sig, byteArrayOf(9, 9, 9)))
    }

    @Test
    fun headBytesAreAsciiOfTheHex() {
        assertTrue(LedgerSignature.headBytes("ff00").contentEquals("ff00".toByteArray(Charsets.US_ASCII)))
    }
}
