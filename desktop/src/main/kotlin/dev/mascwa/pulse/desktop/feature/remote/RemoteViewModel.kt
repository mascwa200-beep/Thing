package dev.mascwa.pulse.desktop.feature.remote

import dev.mascwa.pulse.desktop.remote.DesktopIdentity
import dev.mascwa.pulse.desktop.remote.Handshake
import dev.mascwa.pulse.desktop.remote.RemoteClient
import dev.mascwa.pulse.desktop.remote.RemoteCommand
import dev.mascwa.pulse.desktop.remote.RemoteRequest
import dev.mascwa.pulse.desktop.remote.RemoteStatus
import dev.mascwa.pulse.desktop.settings.DesktopSettingsStore
import dev.mascwa.pulse.desktop.settings.PairedPhone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Whether the phone is answering right now. */
enum class ConnectionState(val label: String) {
    UNKNOWN("Unknown"),
    ONLINE("Online"),
    OFFLINE("Not answering"),
}

/** A paired phone as the UI needs it — settings plus the decoded key and its display fingerprint. */
data class PairedView(
    val name: String,
    val host: String,
    val port: Int,
    val identitySpki: ByteArray,
    val fingerprint: String,
)

data class RemoteUiState(
    val host: String = "",
    val code: String = "",
    val busy: Boolean = false,
    val paired: PairedView? = null,
    val connection: ConnectionState = ConnectionState.UNKNOWN,
    val status: Map<String, String> = emptyMap(),
    val message: String? = null,
    val messageIsError: Boolean = false,
    val fingerprint: String = "",
) {
    /** A status flag as a boolean. The phone reports flags as "1"/"0". */
    fun flag(key: String): Boolean = status[key] == "1"

    /** The human-readable rows for the status panel, in a deliberate reading order. */
    fun readout(): List<Pair<String, String>> = buildList {
        status["battery"]?.let { add("Battery" to "$it%" + if (status["charging"] == "1") " (charging)" else "") }
        status["version"]?.let { add("App version" to it) }
        status["android"]?.let { add("Android" to it) }
        status["uptime"]?.let { add("Awake for" to it) }
        status["network"]?.let { add("Network" to it) }
        status["radio"]?.let { add("Radio" to it) }
    }
}

/**
 * Drives the remote panel. Deliberately plain — Compose Desktop has no AndroidX ViewModel, so this is a
 * simple class holding a [StateFlow] and launching work on the scope the app owns.
 *
 * Every exchange re-reads the paired phone's stored public key and passes it to the client, so a device
 * that is not the one we paired with is refused before any command is sent.
 */
class RemoteViewModel(
    private val scope: CoroutineScope,
    private val settings: DesktopSettingsStore,
    private val identity: DesktopIdentity = DesktopIdentity(),
    private val client: RemoteClient = RemoteClient(identity),
) {
    private val _state = MutableStateFlow(RemoteUiState())
    val state: StateFlow<RemoteUiState> = _state.asStateFlow()

    init {
        scope.launch {
            val s = settings.current()
            val paired = s.pairedPhones.lastOrNull()?.toView()
            _state.value = _state.value.copy(
                host = s.lastRemoteHost,
                paired = paired,
                fingerprint = identity.fingerprint(),
            )
            if (paired != null) refreshStatus()
        }
    }

    fun setHost(v: String) { _state.value = _state.value.copy(host = v.trim()) }

    /** Codes are six digits; filtering here means the Pair button's enablement is honest. */
    fun setCode(v: String) {
        _state.value = _state.value.copy(code = v.filter { it.isDigit() }.take(6))
    }

    fun pair() {
        val s = _state.value
        if (s.busy) return
        val (host, port) = splitHostPort(s.host)
        _state.value = s.copy(busy = true, message = null)
        scope.launch {
            when (val out = client.exchange(host, port, RemoteRequest(RemoteCommand.STATUS), pairingCode = s.code)) {
                is RemoteClient.Outcome.Success -> {
                    val status = RemoteStatus.parse(out.reply.payload)
                    val phone = PairedPhone(
                        name = status["device"]?.takeIf { it.isNotBlank() } ?: "Phone",
                        host = host,
                        port = port,
                        identitySpki = Handshake.b64(out.peerSpki),
                        pairedAtMs = System.currentTimeMillis(),
                    )
                    settings.update { cur ->
                        // Re-pairing the same phone replaces its entry rather than accumulating duplicates.
                        cur.copy(
                            pairedPhones = cur.pairedPhones.filterNot { it.identitySpki == phone.identitySpki } + phone,
                            lastRemoteHost = s.host,
                        )
                    }
                    _state.value = _state.value.copy(
                        busy = false,
                        code = "",
                        paired = phone.toView(),
                        connection = ConnectionState.ONLINE,
                        status = status,
                        message = "Paired with ${phone.name}.",
                        messageIsError = false,
                    )
                }
                is RemoteClient.Outcome.Failure -> fail(out.reason)
            }
        }
    }

    fun refreshStatus() {
        val paired = _state.value.paired ?: return
        if (_state.value.busy) return
        _state.value = _state.value.copy(busy = true)
        scope.launch {
            when (val out = send(paired, RemoteRequest(RemoteCommand.STATUS))) {
                is RemoteClient.Outcome.Success -> _state.value = _state.value.copy(
                    busy = false,
                    connection = ConnectionState.ONLINE,
                    status = RemoteStatus.parse(out.reply.payload),
                    message = null,
                )
                is RemoteClient.Outcome.Failure -> {
                    _state.value = _state.value.copy(connection = ConnectionState.OFFLINE)
                    fail(out.reason)
                }
            }
        }
    }

    /**
     * Flip a switch. The new value is applied optimistically so the UI responds immediately, then the
     * authoritative status from the phone replaces it — if the command failed, the switch snaps back
     * rather than lying about the phone's real state.
     */
    fun setFlag(command: RemoteCommand, statusKey: String, on: Boolean) {
        val paired = _state.value.paired ?: return
        if (_state.value.busy) return
        _state.value = _state.value.copy(
            busy = true,
            status = _state.value.status + (statusKey to if (on) "1" else "0"),
            message = null,
        )
        scope.launch {
            when (val out = send(paired, RemoteRequest(command, if (on) "true" else "false"))) {
                is RemoteClient.Outcome.Success -> {
                    _state.value = _state.value.copy(busy = false, connection = ConnectionState.ONLINE)
                    refreshStatus()
                }
                is RemoteClient.Outcome.Failure -> {
                    _state.value = _state.value.copy(connection = ConnectionState.OFFLINE)
                    fail(out.reason)
                    refreshStatus()   // pull the truth back so the switch cannot stay wrong
                }
            }
        }
    }

    /** Fire a one-shot action (refresh, brief, radio stop). */
    fun act(command: RemoteCommand, arg: String = "") {
        val paired = _state.value.paired ?: return
        if (_state.value.busy) return
        _state.value = _state.value.copy(busy = true, message = null)
        scope.launch {
            when (val out = send(paired, RemoteRequest(command, arg))) {
                is RemoteClient.Outcome.Success -> _state.value = _state.value.copy(
                    busy = false,
                    connection = ConnectionState.ONLINE,
                    message = if (out.reply.ok) "Done." else "The phone declined that.",
                    messageIsError = !out.reply.ok,
                )
                is RemoteClient.Outcome.Failure -> {
                    _state.value = _state.value.copy(connection = ConnectionState.OFFLINE)
                    fail(out.reason)
                }
            }
        }
    }

    fun forget() {
        val paired = _state.value.paired ?: return
        scope.launch {
            val spki = Handshake.b64(paired.identitySpki)
            settings.update { it.copy(pairedPhones = it.pairedPhones.filterNot { p -> p.identitySpki == spki }) }
            _state.value = RemoteUiState(host = paired.host, fingerprint = identity.fingerprint())
        }
    }

    private suspend fun send(paired: PairedView, request: RemoteRequest) =
        client.exchange(paired.host, paired.port, request, expectedPeerSpki = paired.identitySpki)

    private fun fail(reason: String) {
        _state.value = _state.value.copy(busy = false, message = reason, messageIsError = true)
    }

    /** Accepts "192.168.1.42" or "192.168.1.42:8765". A bad port falls back to the default rather than failing. */
    private fun splitHostPort(raw: String): Pair<String, Int> {
        val trimmed = raw.trim()
        val idx = trimmed.lastIndexOf(':')
        // Guard against IPv6 literals, where colons are part of the address itself.
        if (idx <= 0 || trimmed.count { it == ':' } > 1) return trimmed to RemoteClient.DEFAULT_PORT
        val port = trimmed.substring(idx + 1).toIntOrNull()?.takeIf { it in 1..65535 }
        return if (port == null) trimmed to RemoteClient.DEFAULT_PORT
        else trimmed.substring(0, idx) to port
    }

    private fun PairedPhone.toView(): PairedView? {
        val spki = Handshake.unb64(identitySpki) ?: return null
        return PairedView(name, host, port, spki, DesktopIdentity.fingerprintOf(spki))
    }
}
