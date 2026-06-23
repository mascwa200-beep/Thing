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
 * using a hardware-backed AES-256-GCM key in the Android Keystore. The key is created on first use and
 * never leaves the Keystore. Output is `enc1:` + base64(iv ‖ ciphertext+tag).
 *
 * Fully defensive: if the Keystore is unavailable or a ciphertext can't be read, the methods return null
 * so callers fall back to plaintext rather than ever losing the user's settings.
 */
object SecretCrypto {

    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "pulse_settings_secret_v1"
    private const val PREFIX = "enc1:"
    private const val GCM_TAG_BITS = 128
    private const val IV_BYTES = 12

    /** Whether [raw] is one of our encrypted blobs (vs. legacy/plaintext JSON). */
    fun isEncrypted(raw: String): Boolean = raw.startsWith(PREFIX)

    /** Encrypt [plaintext]; null if the Keystore is unavailable (caller stores plaintext instead). */
    fun encrypt(plaintext: String): String? = runCatching {
        val key = secretKey() ?: return null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val ct = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        PREFIX + Base64.encodeToString(iv + ct, Base64.NO_WRAP)
    }.getOrNull()

    /** Decrypt a blob produced by [encrypt]; null if it isn't ours or can't be read. */
    fun decrypt(blob: String): String? = runCatching {
        if (!blob.startsWith(PREFIX)) return null
        val key = secretKey() ?: return null
        val packed = Base64.decode(blob.removePrefix(PREFIX), Base64.NO_WRAP)
        if (packed.size <= IV_BYTES) return null
        val iv = packed.copyOfRange(0, IV_BYTES)
        val ct = packed.copyOfRange(IV_BYTES, packed.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        String(cipher.doFinal(ct), Charsets.UTF_8)
    }.getOrNull()

    private fun secretKey(): SecretKey? {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }
}
