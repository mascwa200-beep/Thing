package dev.mascwa.pulse.desktop.remote

import dev.mascwa.pulse.desktop.AppPaths
import java.io.File
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Date

/**
 * This desktop's long-term identity: an EC P-256 key pair persisted in a PKCS12 keystore under
 * [AppPaths.dataDir], used to authenticate the ephemeral key during the [Handshake] so the phone can prove
 * it is talking to the machine it paired with.
 *
 * **Honest limitation, stated rather than glossed over:** the phone keeps its identity key inside the
 * Android Keystore, ideally StrongBox, where the private key physically cannot be exported. A desktop has
 * no equivalent, so this key is only as protected as the file system it sits on — anyone who can read the
 * user's profile directory can lift it. That is inherent to the platform, not a shortcut here, and it is
 * why the phone (the device actually being controlled) is the side that holds the hardware-backed key.
 *
 * Generated lazily on first use and reused thereafter, so a device stays paired across restarts.
 */
class DesktopIdentity(
    private val keystoreFile: File = AppPaths.dataDir.resolve(KEYSTORE_NAME).toFile(),
) {

    private var cached: KeyPair? = null

    /** The X.509/SPKI encoding of this machine's public key — what the phone stores when pairing. */
    fun publicKeySpki(): ByteArray? = keyPair()?.public?.encoded

    /** DER ECDSA signature over [data], or null if the key is unavailable. Never throws. */
    fun sign(data: ByteArray): ByteArray? = runCatching {
        val kp = keyPair() ?: return null
        Signature.getInstance(SIGN_ALGORITHM).run {
            initSign(kp.private)
            update(data)
            sign()
        }
    }.getOrNull()

    /** The private key, for the ECDH agreement in the handshake. */
    fun privateKey(): PrivateKey? = keyPair()?.private

    /** A short, human-comparable fingerprint of the public key, so the pairing screens can show the same. */
    fun fingerprint(): String = publicKeySpki()?.let { fingerprintOf(it) } ?: "unavailable"

    @Synchronized
    private fun keyPair(): KeyPair? {
        cached?.let { return it }
        return (load() ?: generateAndStore()).also { cached = it }
    }

    private fun load(): KeyPair? = runCatching {
        if (!keystoreFile.isFile) return null
        val ks = KeyStore.getInstance("PKCS12")
        keystoreFile.inputStream().use { ks.load(it, PASSWORD) }
        val key = ks.getKey(ALIAS, PASSWORD) as? PrivateKey ?: return null
        val cert = ks.getCertificate(ALIAS) ?: return null
        KeyPair(cert.publicKey, key)
    }.getOrNull()

    private fun generateAndStore(): KeyPair? = runCatching {
        val kp = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"), SecureRandom())
        }.generateKeyPair()

        // PKCS12 will not store a bare private key, so the key needs a certificate to sit under. A
        // self-signed throwaway is correct here: the certificate is never validated by anything — trust
        // comes from the pairing exchange, not from a PKI.
        val cert = selfSigned(kp)
        val ks = KeyStore.getInstance("PKCS12")
        ks.load(null, PASSWORD)
        ks.setKeyEntry(ALIAS, kp.private, PASSWORD, arrayOf(cert))
        keystoreFile.parentFile?.mkdirs()
        keystoreFile.outputStream().use { ks.store(it, PASSWORD) }
        kp
    }.getOrNull()

    /**
     * Builds a minimal self-signed X.509 certificate by hand. The JDK has no public certificate builder
     * (`sun.security.x509` is not exported on a modern JDK), and pulling in BouncyCastle for a wrapper the
     * protocol never validates would be a heavy dependency for nothing — so the DER is assembled directly,
     * the same approach `core:telemetry` already takes for RFC-3161 and Keystore attestation parsing.
     */
    private fun selfSigned(kp: KeyPair): X509Certificate {
        val now = System.currentTimeMillis()
        val tbs = Der.seq(
            Der.explicit(0, Der.int(BigInteger.valueOf(2))),          // version v3
            Der.int(BigInteger.valueOf(now)),                          // serial
            ECDSA_SHA256_ALG_ID,
            NAME,                                                      // issuer
            Der.seq(Der.utcTime(Date(now - 86_400_000L)), Der.utcTime(Date(now + TEN_YEARS_MS))),
            NAME,                                                      // subject == issuer (self-signed)
            kp.public.encoded,                                         // SubjectPublicKeyInfo, already DER
        )
        val sig = Signature.getInstance(SIGN_ALGORITHM).run {
            initSign(kp.private)
            update(tbs)
            sign()
        }
        val cert = Der.seq(tbs, ECDSA_SHA256_ALG_ID, Der.bitString(sig))
        return java.security.cert.CertificateFactory.getInstance("X.509")
            .generateCertificate(cert.inputStream()) as X509Certificate
    }

    companion object {
        const val KEYSTORE_NAME = "remote-identity.p12"
        private const val ALIAS = "lcars-remote-identity"
        private const val SIGN_ALGORITHM = "SHA256withECDSA"
        private const val TEN_YEARS_MS = 10L * 365 * 24 * 60 * 60 * 1000

        /**
         * The PKCS12 password. Deliberately a constant and deliberately not treated as a secret: it
         * protects a file that already sits in the user's own profile, and inventing a "hidden" password
         * stored right next to the file it protects would be security theatre. The real protection is the
         * pairing step — an unpaired key is useless to anyone.
         */
        private val PASSWORD = "lcars-remote".toCharArray()

        /** A stable short fingerprint (8 hex, colon-grouped) of an SPKI, for display on both ends. */
        fun fingerprintOf(spki: ByteArray): String =
            RemoteCrypto.sha256(spki).take(4).joinToString(":") { "%02X".format(it) }

        /** `SEQUENCE { OID ecdsa-with-SHA256 }` — 1.2.840.10045.4.3.2, no parameters (per RFC 5758). */
        private val ECDSA_SHA256_ALG_ID = byteArrayOf(
            0x30, 0x0A, 0x06, 0x08, 0x2A, 0x86.toByte(), 0x48, 0xCE.toByte(), 0x3D, 0x04, 0x03, 0x02,
        )

        /** A fixed one-RDN Name: CN=LCARS Desktop. Never validated; present only because X.509 demands one. */
        private val NAME: ByteArray = Der.seq(
            Der.set(
                Der.seq(
                    byteArrayOf(0x06, 0x03, 0x55, 0x04, 0x03),          // OID 2.5.4.3 (commonName)
                    Der.utf8("LCARS Desktop"),
                ),
            ),
        )
    }
}

/** The few DER constructions the self-signed certificate needs. Length encoding follows X.690 definite form. */
private object Der {

    fun seq(vararg parts: ByteArray): ByteArray = tagged(0x30, parts.reduceOrNull { a, b -> a + b } ?: ByteArray(0))

    fun set(vararg parts: ByteArray): ByteArray = tagged(0x31, parts.reduceOrNull { a, b -> a + b } ?: ByteArray(0))

    fun explicit(tag: Int, body: ByteArray): ByteArray = tagged(0xA0 or tag, body)

    fun int(v: BigInteger): ByteArray = tagged(0x02, v.toByteArray())

    fun utf8(s: String): ByteArray = tagged(0x0C, s.toByteArray(Charsets.UTF_8))

    /** BIT STRING with a leading "0 unused bits" octet — the encoding a signature value always takes. */
    fun bitString(bytes: ByteArray): ByteArray = tagged(0x03, byteArrayOf(0x00) + bytes)

    fun utcTime(date: Date): ByteArray {
        val fmt = java.text.SimpleDateFormat("yyMMddHHmmss'Z'").apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }
        return tagged(0x17, fmt.format(date).toByteArray(Charsets.US_ASCII))
    }

    private fun tagged(tag: Int, body: ByteArray): ByteArray =
        byteArrayOf(tag.toByte()) + length(body.size) + body

    private fun length(n: Int): ByteArray = when {
        n < 0x80 -> byteArrayOf(n.toByte())
        n < 0x100 -> byteArrayOf(0x81.toByte(), n.toByte())
        n < 0x10000 -> byteArrayOf(0x82.toByte(), (n ushr 8).toByte(), n.toByte())
        else -> byteArrayOf(0x83.toByte(), (n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte())
    }
}
