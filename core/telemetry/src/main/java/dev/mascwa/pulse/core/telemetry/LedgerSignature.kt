package dev.mascwa.pulse.core.telemetry

import java.security.KeyFactory
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

/**
 * Signs and verifies a [HashChain]'s head, making the chain's tip **non-repudiable**: an attacker who
 * rewrites the entire chain (recomputing every entry's hash so [HashChain.verify] passes) still cannot
 * forge a head signature without the private key — which on-device lives in the Android Keystore / secure
 * element. The hash chain proves *internal* integrity; the head signature proves the chain was produced by
 * *this device's key*.
 *
 * This is the pure, CI-tested verification core plus the [LedgerSigner] abstraction. EC P-256 /
 * `SHA256withECDSA` via JCA, available identically on the JVM (unit tests) and Android. The Keystore-backed
 * implementation and the store wiring (persist + check the head signature) live in the app module.
 */

/** Produces a signature over arbitrary bytes and exposes its public key. On-device impl is Keystore-backed. */
interface LedgerSigner {
    /** DER ECDSA signature over [data], or null if signing is unavailable (no key / no secure element). */
    fun sign(data: ByteArray): ByteArray?

    /** The X.509 (SubjectPublicKeyInfo) encoding of the public key, for independent verification + display. */
    fun publicKeySpki(): ByteArray?
}

object LedgerSignature {

    private const val ALGORITHM = "SHA256withECDSA"

    /** The bytes signed for a chain head: the ASCII hex head hash. Stable + reproducible across platforms. */
    fun headBytes(headHashHex: String): ByteArray = headHashHex.toByteArray(Charsets.US_ASCII)

    /**
     * Verify [signature] over the head hash [headHashHex] against an X.509/SPKI-encoded EC public key
     * [publicKeySpki]. Fully defensive — any malformed key/signature yields `false`, never throws.
     */
    fun verify(headHashHex: String, signature: ByteArray, publicKeySpki: ByteArray): Boolean = runCatching {
        val key: PublicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(publicKeySpki))
        Signature.getInstance(ALGORITHM).run {
            initVerify(key)
            update(headBytes(headHashHex))
            verify(signature)
        }
    }.getOrDefault(false)

    /**
     * Sign [headHashHex] with [privateKey]. The on-device [LedgerSigner] does this inside the Keystore so
     * the private key never leaves the secure element; this overload backs tests and any in-process key.
     */
    fun signHead(headHashHex: String, privateKey: PrivateKey): ByteArray =
        Signature.getInstance(ALGORITHM).run {
            initSign(privateKey)
            update(headBytes(headHashHex))
            sign()
        }
}
