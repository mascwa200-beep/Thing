package dev.mascwa.pulse.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import dev.mascwa.pulse.core.telemetry.LedgerSigner
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/**
 * On-device [LedgerSigner]: an EC P-256 key in the AndroidKeyStore — **StrongBox / secure element
 * preferred, TEE fallback** (mirrors [SecretCrypto]'s alias + fallback pattern) — that signs the audit
 * ledger's head hash with `SHA256withECDSA`. The private key never leaves the secure element, so the
 * chain's tip is non-repudiable: only this device's key can produce a head signature that
 * [dev.mascwa.pulse.core.telemetry.LedgerSignature.verify] accepts.
 *
 * Fully defensive: any Keystore failure yields `null`, and the store simply persists the chain unsigned
 * (the hash chain still proves internal integrity). The signature adds *origin* proof on top.
 */
class KeystoreLedgerSigner : LedgerSigner {

    override fun sign(data: ByteArray): ByteArray? = runCatching {
        val key = privateKey() ?: return null
        Signature.getInstance(SIGN_ALGORITHM).run {
            initSign(key)
            update(data)
            sign()
        }
    }.getOrNull()

    override fun publicKeySpki(): ByteArray? = runCatching { publicKey()?.encoded }.getOrNull()

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

    /** Alias of an existing key, or a freshly-generated one (StrongBox first, then TEE), or null. */
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
        val gen = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .apply { if (strongBox) setIsStrongBoxBacked(true) }
            .build()
        gen.initialize(spec)
        gen.generateKeyPair()
        alias
    }.getOrNull()

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ALIAS_STRONGBOX = "pulse_ledger_sign_sb1"
        const val ALIAS_TEE = "pulse_ledger_sign_v1"
        const val SIGN_ALGORITHM = "SHA256withECDSA"
    }
}
