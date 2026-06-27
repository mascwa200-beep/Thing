package dev.mascwa.pulse.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts/decrypts small strings (the settings blob, which carries the cloud + GitHub tokens) **at rest**
 * using a hardware AES-256-GCM key in the Android Keystore. The key never leaves the Keystore.
 *
 * Two backings, chosen automatically:
 *  - `enc2:` — a **StrongBox** (secure-element / Titan M2) key, used when this device has StrongBox AND a
 *    full encrypt→decrypt round-trip succeeds (a one-time self-test). This binds the key to the strongest
 *    available hardware.
 *  - `enc1:` — a TEE-backed key (the original), used as the fallback and to read any pre-existing blobs.
 *
 * The self-test is load-bearing: we only ever WRITE StrongBox ciphertext after proving we can read it back
 * on this device, so a device-specific StrongBox quirk can never strand the settings as undecryptable.
 *
 * Fully defensive: if the Keystore is unavailable or a ciphertext can't be read, the methods return null so
 * callers fall back appropriately rather than losing the user's settings.
 */
object SecretCrypto {

    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val ALIAS_TEE = "pulse_settings_secret_v1"
    private const val ALIAS_SB = "pulse_settings_secret_sb1"
    private const val PREFIX_TEE = "enc1:"
    private const val PREFIX_SB = "enc2:"
    private const val GCM_TAG_BITS = 128
    private const val IV_BYTES = 12

    /** Cached result of the StrongBox round-trip self-test (null = not yet probed). */
    @Volatile private var strongBoxUsable: Boolean? = null

    /** Whether [raw] is one of our encrypted blobs (vs. legacy/plaintext JSON). */
    fun isEncrypted(raw: String): Boolean = raw.startsWith(PREFIX_TEE) || raw.startsWith(PREFIX_SB)

    /** Encrypt [plaintext]; null if no Keystore key is usable (caller stores plaintext instead). Prefers
     *  StrongBox when the self-test passes, else the TEE key. */
    fun encrypt(plaintext: String): String? {
        if (strongBoxAvailable()) {
            encryptWith(plaintext, ALIAS_SB, PREFIX_SB, strongBox = true)?.let { return it }
        }
        return encryptWith(plaintext, ALIAS_TEE, PREFIX_TEE, strongBox = false)
    }

    /** Decrypt a blob produced by [encrypt]; null if it isn't ours or can't be read. */
    fun decrypt(blob: String): String? = when {
        blob.startsWith(PREFIX_SB) -> decryptWith(blob, PREFIX_SB, ALIAS_SB)
        blob.startsWith(PREFIX_TEE) -> decryptWith(blob, PREFIX_TEE, ALIAS_TEE)
        else -> null
    }

    // --- internals -------------------------------------------------------------------------------

    private fun encryptWith(plaintext: String, alias: String, prefix: String, strongBox: Boolean): String? =
        runCatching {
            val key = secretKey(alias, strongBox) ?: return null
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val iv = cipher.iv
            val ct = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
            prefix + Base64.encodeToString(iv + ct, Base64.NO_WRAP)
        }.getOrNull()

    private fun decryptWith(blob: String, prefix: String, alias: String): String? = runCatching {
        val key = existingKey(alias) ?: return null
        val packed = Base64.decode(blob.removePrefix(prefix), Base64.NO_WRAP)
        if (packed.size <= IV_BYTES) return null
        val iv = packed.copyOfRange(0, IV_BYTES)
        val ct = packed.copyOfRange(IV_BYTES, packed.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        String(cipher.doFinal(ct), Charsets.UTF_8)
    }.getOrNull()

    /** Is a StrongBox-backed key usable here? Probed once via a real encrypt→decrypt round-trip; cached. */
    private fun strongBoxAvailable(): Boolean {
        strongBoxUsable?.let { return it }
        val ok = runCatching {
            val probe = "pulse-strongbox-probe"
            val enc = encryptWith(probe, ALIAS_SB, PREFIX_SB, strongBox = true)
            enc != null && decryptWith(enc, PREFIX_SB, ALIAS_SB) == probe
        }.getOrDefault(false)
        strongBoxUsable = ok
        return ok
    }

    private fun existingKey(alias: String): SecretKey? = runCatching {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.secretKey
    }.getOrNull()

    private fun secretKey(alias: String, strongBox: Boolean): SecretKey? {
        existingKey(alias)?.let { return it }
        return runCatching {
            val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            val spec = KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .apply { if (strongBox) setIsStrongBoxBacked(true) }
                .build()
            gen.init(spec)
            gen.generateKey()
        }.getOrNull()
    }
}
