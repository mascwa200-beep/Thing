package dev.mascwa.pulse.data.blackbox

import dev.mascwa.pulse.core.telemetry.AuditEventType
import dev.mascwa.pulse.core.telemetry.HashChain
import dev.mascwa.pulse.core.telemetry.LedgerSignature
import dev.mascwa.pulse.core.telemetry.LedgerSigner
import dev.mascwa.pulse.security.SecretCrypto
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A user-runnable self-test that exercises the blackbox ledger's real machinery **on this device** and
 * reports each check pass/fail — so you can confirm the hash chain, the secure-element signature, at-rest
 * encryption and trusted-time anchoring actually work at runtime, not just that they compiled.
 *
 * Runs against throwaway data and the real crypto/network components, so it never touches or pollutes the
 * live audit ledger. Each check is independently defensive — one failing (e.g. no network) doesn't abort
 * the rest.
 */
class LedgerSelfTest(
    private val signer: LedgerSigner,
    private val tsa: TsaClient,
) {
    data class Check(val name: String, val ok: Boolean, val detail: String)

    data class Report(val checks: List<Check>) {
        val passed: Int get() = checks.count { it.ok }
        val total: Int get() = checks.size
        val allOk: Boolean get() = checks.isNotEmpty() && checks.all { it.ok }
    }

    /** Run every check. [includeNetwork] gates the live TSA fetch (the one slow, online check). */
    suspend fun run(includeNetwork: Boolean = true): Report {
        val checks = mutableListOf<Check>()

        // 1) Hash chain — entries link, and an edit to a past entry is detected.
        checks += runCatching {
            val chain = HashChain()
            chain.append(1L, AuditEventType.NOTE, "selftest.a")
            chain.append(2L, AuditEventType.NOTE, "selftest.b")
            chain.append(3L, AuditEventType.NOTE, "selftest.c")
            val intact = chain.verify().valid
            val tampered = chain.entries().toMutableList().also { it[1] = it[1].copy(detail = "x") }
            val caught = HashChain(tampered).verify().let { !it.valid && it.brokenAtSeq == 1L }
            Check(
                "Hash chain", intact && caught,
                if (intact && caught) "3 entries linked; an edit to a past entry is detected"
                else "chain integrity logic failed",
            )
        }.getOrElse { Check("Hash chain", false, "error: ${it.message}") }

        // 2) Hardware signature — the secure-element EC key signs a head and it verifies (forgery rejected).
        checks += runCatching {
            val head = "a".repeat(64)
            val sig = signer.sign(LedgerSignature.headBytes(head))
            val spki = signer.publicKeySpki()
            if (sig == null || spki == null) {
                Check("Hardware signature", false, "no secure-element signing key available on this device")
            } else {
                val good = LedgerSignature.verify(head, sig, spki)
                val rejectsForgery = !LedgerSignature.verify("b".repeat(64), sig, spki)
                Check(
                    "Hardware signature", good && rejectsForgery,
                    if (good && rejectsForgery) "signed by the device key + verified; a forged head is rejected"
                    else "signature verification failed",
                )
            }
        }.getOrElse { Check("Hardware signature", false, "error: ${it.message}") }

        // 3) Encryption at rest — SecretCrypto (StrongBox/TEE) round-trips.
        checks += runCatching {
            val plain = "ledger-selftest"
            val enc = SecretCrypto.encrypt(plain)
            if (enc == null) {
                Check("Encryption at rest", false, "secure element unavailable (the ledger persists in plaintext)")
            } else {
                val dec = SecretCrypto.decrypt(enc)
                Check(
                    "Encryption at rest", SecretCrypto.isEncrypted(enc) && dec == plain,
                    if (dec == plain) "encrypt → decrypt round-trip OK (hardware-bound key)"
                    else "round-trip mismatch",
                )
            }
        }.getOrElse { Check("Encryption at rest", false, "error: ${it.message}") }

        // 4) Trusted timestamp — fetch a real RFC-3161 token from a public TSA (network; time-bounded).
        if (includeNetwork) {
            checks += runCatching {
                val token = withTimeoutOrNull(20_000L) { tsa.stamp("ledger-selftest".toByteArray()) }
                when {
                    token == null -> Check("Trusted timestamp (TSA)", false, "no TSA reachable (offline, slow, or blocked)")
                    token.granted && token.genTimeMs != null ->
                        Check("Trusted timestamp (TSA)", true, "anchored — a TSA returned a signed time")
                    else -> Check("Trusted timestamp (TSA)", false, "TSA reached but didn't grant a token")
                }
            }.getOrElse { Check("Trusted timestamp (TSA)", false, "error: ${it.message}") }
        }

        return Report(checks)
    }
}
