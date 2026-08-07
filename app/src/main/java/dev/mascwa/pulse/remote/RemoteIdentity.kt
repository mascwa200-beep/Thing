package dev.mascwa.pulse.remote

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/**
 * This phone's long-term identity for the remote link: an EC P-256 key in the AndroidKeyStore —
 * **StrongBox / secure element preferred, TEE fallback** — mirroring [dev.mascwa.pulse.security.SecretCrypto]
 * and [dev.mascwa.pulse.security.KeystoreLedgerSigner]'s alias-and-fallback pattern exactly.
 *
 * The private key never leaves the secure element, so a desktop that paired with this phone can prove it is
 * still talking to *this* phone and not something that took over the address. The key is deliberately
 * separate from the ledger signing key: different purpose, different lifetime, and unpairing every device
 * should never mean invalidating the audit ledger's signatures.
 *
 * `PURPOSE_SIGN` only. The handshake's ECDH uses throwaway in-process ephemeral keys, so this key never
 * needs `PURPOSE_AGREE_KEY` — which also means it can stay in StrongBox, where agreement support varies.
 *
 * Fully defensive: any Keystore failure yields `null`, and the link simply refuses to run rather than
 * falling back to something weaker.
 */
class RemoteIdentity {

    /** DER ECDSA signature over [data], or null if the Keystore is unavailable. */
    fun sign(data: ByteArray): ByteArray? = runCatching {
        val key = privateKey() ?: return null
        Signature.getInstance(SIGN_ALGORITHM).run {
            initSign(key)
            update(data)
            sign()
        }
    }.getOrNull()

    /** X.509/SPKI encoding of the public key — what a desktop stores when it pairs with this phone. */
    fun publicKeySpki(): ByteArray? = runCatching { publicKey()?.encoded }.getOrNull()

    /** True when a usable key exists (or could be created) — the link's precondition. */
    fun isAvailable(): Boolean = publicKeySpki() != null

    /** Short, human-comparable fingerprint so both screens can display the same value. */
    fun fingerprint(): String = publicKeySpki()?.let { spki ->
        dev.mascwa.pulse.core.telemetry.RemoteCrypto.sha256(spki)
            .take(4).joinToString(":") { "%02X".format(it) }
    } ?: "unavailable"

    private fun privateKey(): PrivateKey? = ensureKey()?.let { alias ->
        runCatching {
            val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            (ks.getEntry(alias, null) as? KeyStore.PrivateKeyEntry)?.privateKey
        }.getOrNull()
    }

    private fun publicKey(): PublicKey? = ensureKey()?.let { alias ->
        runCatching {
            val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            ks.getCertificate(alias)?.publicKey
        }.getOrNull()
    }

    private fun ensureKey(): String? {
        existingAlias()?.let { return it }
        generate(ALIAS_STRONGBOX, strongBox = true)?.let { return it }
        return generate(ALIAS_TEE, strongBox = false)
    }

    private fun existingAlias(): String? = runCatching {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        when {
            ks.containsAlias(ALIAS_STRONGBOX) -> ALIAS_STRONGBOX
            ks.containsAlias(ALIAS_TEE) -> ALIAS_TEE
            else -> null
        }
    }.getOrNull()

    private fun generate(alias: String, strongBox: Boolean): String? = runCatching {
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .apply { if (strongBox) setIsStrongBoxBacked(true) }
            .build()
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE).run {
            initialize(spec)
            generateKeyPair()
        }
        alias
    }.getOrNull()

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val SIGN_ALGORITHM = "SHA256withECDSA"
        const val ALIAS_STRONGBOX = "pulse_remote_identity_sb1"
        const val ALIAS_TEE = "pulse_remote_identity_v1"
    }
}
