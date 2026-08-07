package dev.mascwa.pulse.desktop.remote

import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Adversarial tests for the remote-control protocol core. The happy path is the easy part; what matters
 * here is that every hostile or malformed input is *rejected* rather than misinterpreted, because this
 * code parses bytes from an open socket.
 */
class RemoteProtocolTest {

    private fun ecKeyPair() = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"))
    }.generateKeyPair()

    // ---- field codec ----

    @Test fun fieldCodecRoundTrips() {
        val fields = listOf("", "a", "hello world", "with|pipe", "with:colon", "12:not-a-field|")
        assertEquals(fields, RemoteWire.decode(RemoteWire.encode(fields)))
    }

    /** The whole point of length-prefixing: no two distinct tuples may share an encoding. */
    @Test fun fieldCodecIsUnambiguous() {
        assertFalse(
            RemoteWire.encode(listOf("ab", "c")).contentEquals(RemoteWire.encode(listOf("a", "bc"))),
        )
    }

    /** A field body containing the delimiters must survive — the length, not the delimiter, defines it. */
    @Test fun fieldCodecSurvivesDelimiterInjection() {
        val hostile = listOf("6:evil|", "|||", "0:|")
        assertEquals(hostile, RemoteWire.decode(RemoteWire.encode(hostile)))
    }

    @Test fun fieldCodecRejectsMalformed() {
        assertNull(RemoteWire.decode("garbage".toByteArray()))
        assertNull(RemoteWire.decode("5:abc|".toByteArray()))          // length overruns the buffer
        assertNull(RemoteWire.decode("2:ab".toByteArray()))            // missing terminator
        assertNull(RemoteWire.decode("2:abX".toByteArray()))           // wrong terminator
        assertNull(RemoteWire.decode("-1:x|".toByteArray()))           // negative length
        assertNull(RemoteWire.decode("x:ab|".toByteArray()))           // non-numeric length
    }

    @Test fun blobPackingRoundTrips() {
        val blobs = listOf(byteArrayOf(1, 2, 3), ByteArray(0), byteArrayOf(-1, 127))
        val out = RemoteWire.unpackBlobs(RemoteWire.packBlobs(blobs), 3)
        assertNotNull(out)
        for (i in blobs.indices) assertArrayEquals(blobs[i], out!![i])
    }

    @Test fun blobUnpackingRejectsTruncationAndWrongCount() {
        val packed = RemoteWire.packBlobs(listOf(byteArrayOf(1, 2, 3), byteArrayOf(4)))
        assertNull(RemoteWire.unpackBlobs(packed, 3))                             // wrong expected count
        assertNull(RemoteWire.unpackBlobs(packed.copyOf(packed.size - 1), 2))     // truncated payload
        // A length header claiming more than the buffer holds must not over-read.
        val lying = RemoteWire.intToBe(9999) + byteArrayOf(1, 2)
        assertNull(RemoteWire.unpackBlobs(lying, 1))
    }

    @Test fun bigEndianLengthRoundTrips() {
        for (v in listOf(0, 1, 255, 256, 65535, RemoteWire.MAX_FRAME_BYTES)) {
            assertEquals(v, RemoteWire.beToInt(RemoteWire.intToBe(v), 0))
        }
    }

    // ---- command allowlist ----

    @Test fun onlyAllowlistedCommandsParse() {
        assertEquals(RemoteCommand.PING, RemoteCommand.fromWire("ping"))
        assertNull(RemoteCommand.fromWire("rm -rf /"))
        assertNull(RemoteCommand.fromWire("set.wipe"))
        assertNull(RemoteCommand.fromWire(""))
        assertNull(RemoteCommand.fromWire("PING"))   // exact match only, no case folding
    }

    /** Nothing that can strand the device or the link may ever be expressible on the wire. */
    @Test fun destructiveCapabilitiesAreNotInTheAllowlist() {
        val wire = RemoteCommand.entries.map { it.wire.lowercase() }
        for (banned in listOf("wipe", "suspend", "usb", "lock", "selfcode", "token", "key", "wifi")) {
            assertTrue(
                "Command allowlist must not expose '$banned'",
                wire.none { it.contains(banned) },
            )
        }
    }

    @Test fun commandWireNamesAreUnique() {
        val wire = RemoteCommand.entries.map { it.wire }
        assertEquals(wire.size, wire.toSet().size)
    }

    @Test fun requestRoundTripsAndRejectsForeignVersions() {
        val req = RemoteRequest(RemoteCommand.SET_NOTIFICATIONS, "true")
        assertEquals(req, RemoteRequest.parse(req.toBytes()))

        val wrongVersion = RemoteWire.encode(listOf("lcars-remote-999", "ping", ""))
        assertNull(RemoteRequest.parse(wrongVersion))

        val unknownCommand = RemoteWire.encode(listOf(PROTOCOL_VERSION, "do.evil", ""))
        assertNull(RemoteRequest.parse(unknownCommand))

        assertNull(RemoteRequest.parse(RemoteWire.encode(listOf(PROTOCOL_VERSION))))   // wrong arity
        assertNull(RemoteRequest.parse("not a frame".toByteArray()))
    }

    @Test fun replyRoundTrips() {
        val reply = RemoteReply(true, "battery=88|charging=1")
        assertEquals(reply, RemoteReply.parse(reply.toBytes()))
        assertEquals(RemoteReply(false, ""), RemoteReply.parse(RemoteReply(false).toBytes()))
    }

    // ---- replay / sequencing ----

    @Test fun sequenceGuardRejectsReplayAndReordering() {
        val g = SequenceGuard()
        assertTrue(g.accept(0))
        assertTrue(g.accept(1))
        assertFalse(g.accept(1))    // exact replay
        assertFalse(g.accept(0))    // older
        assertTrue(g.accept(5))     // gaps are fine; only monotonicity matters
        assertFalse(g.accept(4))
        assertFalse(g.accept(-1))
        assertEquals(5L, g.highest)
    }

    // ---- crypto ----

    @Test fun sealOpenRoundTripsAndDetectsTampering() {
        val key = RemoteCrypto.randomBytes(32)
        val plaintext = "set.notifications=false".toByteArray()
        val sealed = RemoteCrypto.seal(key, 7, plaintext)!!
        assertArrayEquals(plaintext, RemoteCrypto.open(key, 7, sealed))

        // Flipping any ciphertext bit must fail the GCM tag, not silently decrypt.
        val tampered = sealed.copyOf().also { it[0] = (it[0] + 1).toByte() }
        assertNull(RemoteCrypto.open(key, 7, tampered))

        // Right key, wrong sequence -> wrong nonce -> rejected. This is what stops record reordering.
        assertNull(RemoteCrypto.open(key, 8, sealed))

        // Wrong key entirely.
        assertNull(RemoteCrypto.open(RemoteCrypto.randomBytes(32), 7, sealed))

        // Garbage input must not throw.
        assertNull(RemoteCrypto.open(key, 7, byteArrayOf(1, 2, 3)))
    }

    @Test fun nonceIsUniquePerSequence() {
        val seen = (0L until 500L).map { RemoteCrypto.nonceFor(it).toList() }.toSet()
        assertEquals(500, seen.size)
        assertEquals(12, RemoteCrypto.nonceFor(0).size)
    }

    @Test fun hkdfIsDeterministicAndSeparatesDirections() {
        val secret = RemoteCrypto.randomBytes(32)
        val salt = RemoteCrypto.randomBytes(32)
        val a = RemoteCrypto.hkdf(secret, salt, RemoteCrypto.INFO_C2S)
        val b = RemoteCrypto.hkdf(secret, salt, RemoteCrypto.INFO_C2S)
        val c = RemoteCrypto.hkdf(secret, salt, RemoteCrypto.INFO_S2C)
        assertArrayEquals(a, b)
        assertEquals(32, a.size)
        assertFalse("each direction must get an independent key", a.contentEquals(c))
    }

    // ---- handshake ----

    @Test fun handshakeDerivesTheSameKeysOnBothSidesAndDiffersPerSession() {
        val clientEph = ecKeyPair()
        val serverEph = ecKeyPair()
        val clientId = ecKeyPair()
        val serverId = ecKeyPair()

        val transcript = Handshake.transcript(
            clientEph.public.encoded, serverEph.public.encoded,
            clientId.public.encoded, serverId.public.encoded,
        )

        val clientSecret = RemoteCrypto.sharedSecret(clientEph.private, serverEph.public.encoded)!!
        val serverSecret = RemoteCrypto.sharedSecret(serverEph.private, clientEph.public.encoded)!!
        assertArrayEquals("ECDH must agree", clientSecret, serverSecret)

        val (c2sA, s2cA) = Handshake.deriveKeys(clientSecret, transcript)
        val (c2sB, s2cB) = Handshake.deriveKeys(serverSecret, transcript)
        assertArrayEquals(c2sA, c2sB)
        assertArrayEquals(s2cA, s2cB)
        assertFalse(c2sA.contentEquals(s2cA))

        // A record sealed by the client opens for the server under the shared c2s key.
        val sealed = RemoteCrypto.seal(c2sA, 0, "ping".toByteArray())!!
        assertArrayEquals("ping".toByteArray(), RemoteCrypto.open(c2sB, 0, sealed))

        // A fresh session (new ephemeral keys) must yield different keys, so an old recording is useless.
        val newEph = ecKeyPair()
        val newTranscript = Handshake.transcript(
            newEph.public.encoded, serverEph.public.encoded,
            clientId.public.encoded, serverId.public.encoded,
        )
        val newSecret = RemoteCrypto.sharedSecret(newEph.private, serverEph.public.encoded)!!
        val (c2sNew, _) = Handshake.deriveKeys(newSecret, newTranscript)
        assertFalse(c2sA.contentEquals(c2sNew))
        assertNull("a record from the old session must not open in the new one",
            RemoteCrypto.open(c2sNew, 0, sealed))
    }

    /** Swapping any public value changes the transcript, which is what breaks a man-in-the-middle. */
    @Test fun transcriptBindsEveryPublicValue() {
        val a = ecKeyPair().public.encoded
        val b = ecKeyPair().public.encoded
        val c = ecKeyPair().public.encoded
        val d = ecKeyPair().public.encoded
        val base = Handshake.transcript(a, b, c, d)
        assertFalse(base.contentEquals(Handshake.transcript(d, b, c, d)))
        assertFalse(base.contentEquals(Handshake.transcript(a, d, c, d)))
        assertFalse(base.contentEquals(Handshake.transcript(a, b, d, d)))
        assertFalse(base.contentEquals(Handshake.transcript(a, b, c, a)))
        // ...and the client/server slots are not interchangeable.
        assertFalse(base.contentEquals(Handshake.transcript(b, a, c, d)))
    }

    @Test fun sharedSecretRejectsGarbagePeerKey() {
        assertNull(RemoteCrypto.sharedSecret(ecKeyPair().private, byteArrayOf(1, 2, 3)))
        assertNull(RemoteCrypto.sharedSecret(ecKeyPair().private, ByteArray(0)))
    }

    @Test fun base64RoundTripsAndRejectsGarbage() {
        val raw = RemoteCrypto.randomBytes(40)
        assertArrayEquals(raw, Handshake.unb64(Handshake.b64(raw)))
        assertNull(Handshake.unb64("!!!not base64!!!"))
    }

    // ---- pairing ----

    @Test fun pairingProofAcceptsRightCodeAndRejectsWrong() {
        val transcript = Handshake.transcript(
            ecKeyPair().public.encoded, ecKeyPair().public.encoded,
            ecKeyPair().public.encoded, ecKeyPair().public.encoded,
        )
        val code = "048213"
        val proof = PairingProof.proof(code, transcript)
        assertTrue(PairingProof.verify(code, transcript, proof))
        assertFalse(PairingProof.verify("048214", transcript, proof))
        assertFalse(PairingProof.verify("", transcript, proof))
        assertFalse(PairingProof.verify(code, transcript, ByteArray(proof.size)))
        assertFalse("a truncated proof must not pass", PairingProof.verify(code, transcript, proof.copyOf(8)))
    }

    /** Binding the proof to the transcript is what stops it being replayed into a different handshake. */
    @Test fun pairingProofIsBoundToItsTranscript() {
        val t1 = Handshake.transcript(
            ecKeyPair().public.encoded, ecKeyPair().public.encoded,
            ecKeyPair().public.encoded, ecKeyPair().public.encoded,
        )
        val t2 = Handshake.transcript(
            ecKeyPair().public.encoded, ecKeyPair().public.encoded,
            ecKeyPair().public.encoded, ecKeyPair().public.encoded,
        )
        val code = "111111"
        assertFalse(PairingProof.verify(code, t2, PairingProof.proof(code, t1)))
    }

    @Test fun generatedCodesAreSixDigits() {
        repeat(50) {
            val c = PairingProof.newCode()
            assertEquals(PairingProof.CODE_LENGTH, c.length)
            assertTrue("code must be digits only: $c", c.all { ch -> ch.isDigit() })
        }
        // Sanity: not a constant.
        assertTrue(( 1..40).map { PairingProof.newCode() }.toSet().size > 1)
    }

    // ---- network scoping ----

    @Test fun onlyLocalAddressesAreAccepted() {
        for (ok in listOf("192.168.1.10", "10.0.0.5", "172.16.4.4", "172.31.255.254",
                          "169.254.1.1", "127.0.0.1", "::1", "fe80::1%wlan0",
                          // A dual-stack socket reports IPv4 peers in mapped form on many networks.
                          "::ffff:192.168.1.5", "::FFFF:10.1.2.3")) {
            assertTrue("$ok should be local", LocalNetwork.isLocalAddress(ok))
        }
        for (bad in listOf("8.8.8.8", "172.15.0.1", "172.32.0.1", "1.2.3.4",
                           "203.0.113.9", "", "not-an-ip", "2001:4860:4860::8888",
                           // Mapped form must not become a bypass for a public address.
                           "::ffff:8.8.8.8")) {
            assertFalse("$bad should NOT be local", LocalNetwork.isLocalAddress(bad))
        }
    }

    // ---- end-to-end ----

    /** A full session: handshake, pair, encrypt a command, answer it, and reject a replay. */
    @Test fun endToEndSessionWorksAndReplayFails() {
        val clientEph = ecKeyPair(); val serverEph = ecKeyPair()
        val clientId = ecKeyPair(); val serverId = ecKeyPair()
        val transcript = Handshake.transcript(
            clientEph.public.encoded, serverEph.public.encoded,
            clientId.public.encoded, serverId.public.encoded,
        )

        val code = PairingProof.newCode()
        assertTrue(PairingProof.verify(code, transcript, PairingProof.proof(code, transcript)))

        val secret = RemoteCrypto.sharedSecret(clientEph.private, serverEph.public.encoded)!!
        val (c2s, s2c) = Handshake.deriveKeys(secret, transcript)

        val guard = SequenceGuard()
        val request = RemoteRequest(RemoteCommand.SET_NOTIFICATIONS, "false")
        val record = RemoteCrypto.seal(c2s, 0, request.toBytes())!!

        assertTrue(guard.accept(0))
        val decoded = RemoteRequest.parse(RemoteCrypto.open(c2s, 0, record)!!)
        assertEquals(request, decoded)

        val reply = RemoteReply(true, "notifications=false")
        val sealedReply = RemoteCrypto.seal(s2c, 0, reply.toBytes())!!
        assertEquals(reply, RemoteReply.parse(RemoteCrypto.open(s2c, 0, sealedReply)!!))

        // Replaying the very same record is refused by the sequence guard.
        assertFalse(guard.accept(0))
    }

    @Test fun protocolVersionIsStable() {
        assertEquals("lcars-remote-1", PROTOCOL_VERSION)
        assertNotEquals("", PROTOCOL_VERSION)
    }
}
