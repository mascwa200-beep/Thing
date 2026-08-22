package dev.mascwa.pulse.desktop.remote

import dev.mascwa.pulse.core.telemetry.RemoteCrypto
import java.io.File
import java.nio.file.Files
import java.security.Signature
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The desktop identity hand-assembles a self-signed X.509 certificate in DER (the JDK exposes no public
 * certificate builder, and BouncyCastle would be a heavy dependency for a wrapper nothing validates).
 * Hand-rolled DER is exactly the kind of code that looks right and isn't, so these tests run it for real:
 * generate, persist, reload, and verify a signature actually validates against the stored public key.
 */
class DesktopIdentityTest {

    private val tempDir: File = Files.createTempDirectory("lcars-identity-test").toFile()
    private val keystore = File(tempDir, "remote-identity.p12")

    @After fun cleanup() {
        keystore.delete()
        tempDir.delete()
    }

    @Test fun generatesAPersistableIdentityAndSignsVerifiably() {
        val identity = DesktopIdentity(keystore)

        val spki = identity.publicKeySpki()
        assertNotNull("a P-256 identity must be generated on first use", spki)
        assertTrue("the keystore must be written to disk", keystore.isFile)

        val data = "handshake transcript".toByteArray()
        val signature = identity.sign(data)
        assertNotNull("signing must succeed", signature)

        // The real check: the signature validates against the exported public key, which is exactly what
        // the phone will do with the SPKI it stored at pairing time.
        val publicKey = java.security.KeyFactory.getInstance("EC")
            .generatePublic(java.security.spec.X509EncodedKeySpec(spki!!))
        val ok = Signature.getInstance("SHA256withECDSA").run {
            initVerify(publicKey)
            update(data)
            verify(signature!!)
        }
        assertTrue("signature must verify against the published SPKI", ok)
    }

    @Test fun identityIsStableAcrossRestarts() {
        val first = DesktopIdentity(keystore).publicKeySpki()
        assertNotNull(first)
        // A brand-new instance over the same file models restarting the app: pairing must survive it.
        val second = DesktopIdentity(keystore).publicKeySpki()
        assertArrayEquals("a restart must not change identity, or every device would unpair", first, second)
    }

    @Test fun privateKeyIsUsableForEcdh() {
        val identity = DesktopIdentity(keystore)
        val priv = identity.privateKey()
        assertNotNull(priv)
        val peer = java.security.KeyPairGenerator.getInstance("EC").apply {
            initialize(java.security.spec.ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        val secret = RemoteCrypto.sharedSecret(priv!!, peer.public.encoded)
        assertNotNull("the stored key must work for the handshake's ECDH agreement", secret)
        assertTrue(secret!!.isNotEmpty())
    }

    @Test fun fingerprintIsStableAndShort() {
        val identity = DesktopIdentity(keystore)
        val fp = identity.fingerprint()
        assertEquals("fingerprint should be 4 colon-grouped hex bytes", 4, fp.split(":").size)
        assertEquals(fp, identity.fingerprint())
        assertFalse(fp == "unavailable")
    }
}
