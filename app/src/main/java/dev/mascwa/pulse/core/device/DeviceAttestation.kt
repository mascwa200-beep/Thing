package dev.mascwa.pulse.core.device

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import dev.mascwa.pulse.core.telemetry.HardwareAttestation
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec

/**
 * Runs an Android Keystore **key-attestation** probe: generates a throwaway EC key in the device's secure
 * hardware (Titan M2 / StrongBox on a Pixel) with a random challenge, reads the leaf certificate's
 * attestation extension, and hands the record to the pure [HardwareAttestation] parser. The result proves —
 * cryptographically, not heuristically — the security level, bootloader-lock state, verified-boot state and
 * the verified-boot key (which identifies GrapheneOS).
 *
 * Needs no Context — `AndroidKeyStore` is process-global. Fully defensive: any failure (no hardware
 * attestation, parse error) returns a [Report] with an `error` rather than throwing. The probe key is
 * deleted afterwards. This is read-only: it establishes identity, it changes nothing.
 */
class DeviceAttestation {

    data class Report(
        val available: Boolean,
        val info: HardwareAttestation.Info?,
        val verdict: HardwareAttestation.Verdict?,
        val error: String?,
    )

    fun run(): Report = try {
        val challenge = ByteArray(24).also { SecureRandom().nextBytes(it) }
        // Prefer a StrongBox-backed attestation (secure element); fall back to TEE if unavailable.
        val leaf = generateAndGetLeaf(challenge, strongBox = true)
            ?: generateAndGetLeaf(challenge, strongBox = false)
        when {
            leaf == null ->
                Report(false, null, null, "No attestation certificate — this device may not support key attestation.")
            else -> {
                val extRaw = leaf.getExtensionValue(ATTESTATION_OID)
                if (extRaw == null) {
                    Report(false, null, null, "No attestation extension in the certificate (hardware attestation unavailable).")
                } else {
                    val keyDescription = unwrapOctetString(extRaw) ?: extRaw
                    val info = HardwareAttestation.parse(keyDescription)
                    if (info == null) Report(false, null, null, "Couldn't parse the attestation record.")
                    else Report(true, info, HardwareAttestation.verdict(info), null)
                }
            }
        }
    } catch (e: Exception) {
        Report(false, null, null, e.message ?: "Attestation failed.")
    } finally {
        runCatching { KeyStore.getInstance(KEYSTORE).apply { load(null) }.deleteEntry(ALIAS) }
    }

    private fun generateAndGetLeaf(challenge: ByteArray, strongBox: Boolean): X509Certificate? = try {
        val builder = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setAttestationChallenge(challenge)
        if (strongBox) builder.setIsStrongBoxBacked(true)
        val kpg = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, KEYSTORE)
        kpg.initialize(builder.build())
        kpg.generateKeyPair()
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        ks.getCertificateChain(ALIAS)?.firstOrNull() as? X509Certificate
    } catch (e: StrongBoxUnavailableException) {
        null // caller retries without StrongBox
    } catch (e: Exception) {
        if (strongBox) null else throw e // a non-StrongBox failure is a real error
    }

    /** Strip the outer DER OCTET STRING that [X509Certificate.getExtensionValue] wraps the value in. */
    private fun unwrapOctetString(der: ByteArray): ByteArray? {
        if (der.size < 2 || (der[0].toInt() and 0xFF) != 0x04) return null
        var p = 1
        val lenFirst = der[p].toInt() and 0xFF; p++
        val length: Int
        if (lenFirst < 0x80) {
            length = lenFirst
        } else {
            val n = lenFirst and 0x7F
            if (n == 0 || n > 4) return null
            var acc = 0
            for (i in 0 until n) {
                if (p >= der.size) return null
                acc = (acc shl 8) or (der[p].toInt() and 0xFF); p++
            }
            length = acc
        }
        if (length < 0 || p + length > der.size) return null
        return der.copyOfRange(p, p + length)
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "pulse_attestation_probe"
        const val ATTESTATION_OID = "1.3.6.1.4.1.11129.2.1.17"
    }
}
