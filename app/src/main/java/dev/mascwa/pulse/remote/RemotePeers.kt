package dev.mascwa.pulse.remote

import dev.mascwa.pulse.core.telemetry.Handshake
import dev.mascwa.pulse.core.telemetry.RemoteCrypto
import dev.mascwa.pulse.data.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The set of computers this phone has paired with, backed by [SettingsRepository].
 *
 * Stores **public** keys only, so this is not a credential store — losing it costs the pairing, not
 * control. An in-memory snapshot backs [isPaired] because it is consulted from inside a socket handler,
 * where suspending on a DataStore read per connection would be both slow and an easy way for a peer to
 * stall the accept loop.
 *
 * Encoding deliberately goes through [Handshake.b64], the same helper the desktop uses, rather than
 * `android.util.Base64`. The two never compare strings across devices today (each side stores the other's
 * key locally), but keeping one encoding for one concept removes a whole category of future bug.
 */
class RemotePeers(
    private val settings: SettingsRepository,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {

    @Volatile private var snapshot: Set<String> = emptySet()

    /** Load the persisted set. Call once when the link starts. */
    suspend fun refresh() {
        snapshot = runCatching { settings.current().remote.pairedKeys.toSet() }.getOrDefault(emptySet())
    }

    fun isPaired(spki: ByteArray): Boolean = Handshake.b64(spki) in snapshot

    /** Add a newly-paired computer. Idempotent — re-pairing the same machine is not an error. */
    fun remember(spki: ByteArray) {
        val encoded = Handshake.b64(spki)
        if (encoded in snapshot) return
        snapshot = snapshot + encoded
        scope.launch {
            runCatching {
                settings.update { s ->
                    if (encoded in s.remote.pairedKeys) s
                    else s.copy(remote = s.remote.copy(pairedKeys = s.remote.pairedKeys + encoded))
                }
            }
        }
    }

    /** Unpair everything. The next connection from any computer is refused until it pairs again. */
    suspend fun forgetAll() {
        snapshot = emptySet()
        runCatching { settings.update { it.copy(remote = it.remote.copy(pairedKeys = emptyList())) } }
    }

    fun count(): Int = snapshot.size

    /** Short display fingerprints of every paired computer, for the settings screen. */
    fun fingerprints(): List<String> = snapshot.mapNotNull(::fingerprintOf)

    companion object {
        /**
         * The short display fingerprint of a stored (base64 SPKI) key — the same four bytes the desktop
         * shows for its own identity, so the two can be read side by side to confirm the phone paired with
         * the machine in front of you rather than some other one on the network.
         *
         * Kept here, and pure, so a settings screen can render the paired list straight from persisted
         * settings without needing a live [RemotePeers] (and therefore a running service) to ask.
         */
        fun fingerprintOf(encodedSpki: String): String? = Handshake.unb64(encodedSpki)?.let { spki ->
            RemoteCrypto.sha256(spki).take(4).joinToString(":") { "%02X".format(it) }
        }
    }
}
