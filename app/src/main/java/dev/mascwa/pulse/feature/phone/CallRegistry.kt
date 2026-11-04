package dev.mascwa.pulse.feature.phone

import android.os.Build
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.CallEndpoint
import android.telecom.VideoProfile
import androidx.annotation.RequiresApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.lang.ref.WeakReference
import java.util.IdentityHashMap
import java.util.concurrent.Executor

/**
 * The calls Telecom has handed LCARS, as the call screen and the notifications read them, and the one
 * way either of them acts on a call.
 *
 * Process-wide, fed by [LcarsInCallService]. The screen never holds a `Call` itself: it reads
 * [calls] and [audio] — plain snapshots — and asks this object to answer, hold, mute. That keeps the
 * live Telecom objects in one place, and means a screen that outlives its call (rotated, recreated,
 * left open) holds nothing that can act on a call that has gone.
 *
 * ⚠️ **The service is held WEAKLY.** Telecom owns its lifetime: it binds while there are calls and
 * unbinds when the last one ends. A strong reference here would keep a dead service object reachable
 * and let a stale screen call `setMuted` on it.
 *
 * Everything runs on the main thread — Telecom's callbacks arrive there, and so do taps.
 */
internal object CallRegistry {

    /** One call, as the screen shows it. */
    data class LiveCall(
        /** LCARS's own id for the call; Telecom gives none publicly. Stable for the call's life. */
        val id: String,
        val number: String,
        /** The phone book's name for the caller, or the network's, or null. */
        val name: String?,
        val state: Int,
        val capabilities: Int,
        /** Telecom's connect time, 0 before the call connects. */
        val connectMs: Long,
        val presentation: Int,
        val video: Boolean,
    ) {
        val phase: CallDisplay.Phase get() = CallDisplay.phase(state)
    }

    /** Where the sound is going, what else it could go to, and whether the microphone is muted. */
    data class Audio(
        val route: CallDisplay.Route = CallDisplay.Route.UNKNOWN,
        val available: Set<CallDisplay.Route> = emptySet(),
        val muted: Boolean = false,
    )

    private val _calls = MutableStateFlow<List<LiveCall>>(emptyList())
    val calls: StateFlow<List<LiveCall>> = _calls.asStateFlow()

    private val _audio = MutableStateFlow(Audio())
    val audio: StateFlow<Audio> = _audio.asStateFlow()

    private val live = LinkedHashMap<String, Call>()
    private val ids = IdentityHashMap<Call, String>()
    private var next = 1L
    private var service = WeakReference<LcarsInCallService>(null)

    /** API 34+'s endpoints, kept so a route can be asked for by the endpoint Telecom offered. */
    private var endpoints: List<Any> = emptyList()

    // ── fed by the service ──────────────────────────────────────────────────────────────────────

    @Synchronized
    fun attach(s: LcarsInCallService) {
        service = WeakReference(s)
    }

    @Synchronized
    fun detach(s: LcarsInCallService) {
        if (service.get() === s) service = WeakReference(null)
        // Telecom unbinds only once every call has gone, so anything still here is stale.
        live.clear()
        ids.clear()
        endpoints = emptyList()
        _calls.value = emptyList()
        _audio.value = Audio()
    }

    @Synchronized
    fun add(call: Call): String {
        val id = ids.getOrPut(call) { "call-${next++}" }
        live[id] = call
        publish()
        return id
    }

    @Synchronized
    fun remove(call: Call) {
        val id = ids.remove(call) ?: return
        live.remove(id)
        publish()
    }

    /** Something about a call changed: its state, its details, its capabilities. */
    @Synchronized
    fun changed() = publish()

    @Synchronized
    fun legacyAudio(state: CallAudioState) {
        // On API 34+ the endpoint callbacks are the truth; this older one is ignored there so the two
        // cannot disagree about where the sound is going.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            _audio.value = _audio.value.copy(muted = state.isMuted)
            return
        }
        _audio.value = Audio(
            route = CallDisplay.routeFromLegacy(state.route),
            available = CallDisplay.routesFromLegacyMask(state.supportedRouteMask),
            muted = state.isMuted,
        )
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    @Synchronized
    fun endpoint(current: CallEndpoint) {
        _audio.value = _audio.value.copy(route = CallDisplay.routeFromEndpoint(current.endpointType))
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    @Synchronized
    fun availableEndpoints(list: List<CallEndpoint>) {
        endpoints = list
        _audio.value = _audio.value.copy(available = list.map { CallDisplay.routeFromEndpoint(it.endpointType) }.toSet())
    }

    @Synchronized
    fun muteState(muted: Boolean) {
        _audio.value = _audio.value.copy(muted = muted)
    }

    private fun publish() {
        _calls.value = live.map { (id, call) -> snapshot(id, call) }
    }

    private fun snapshot(id: String, call: Call): LiveCall {
        val d = call.details
        return LiveCall(
            id = id,
            number = d?.handle?.schemeSpecificPart.orEmpty(),
            name = (d?.contactDisplayName ?: d?.callerDisplayName)?.takeIf { it.isNotBlank() },
            state = d?.state ?: CallDisplay.STATE_NEW,
            capabilities = d?.callCapabilities ?: 0,
            connectMs = d?.connectTimeMillis ?: 0L,
            presentation = d?.handlePresentation ?: CallLogDigest.PRESENTATION_ALLOWED,
            video = d?.let { VideoProfile.isVideo(it.videoState) } ?: false,
        )
    }

    // ── what the screen and the notifications ask for ──────────────────────────────────────────

    /** The call the screen is about: one ringing, else the one in progress, else any. */
    fun focus(list: List<LiveCall>): LiveCall? =
        list.firstOrNull { it.phase == CallDisplay.Phase.RINGING }
            ?: list.firstOrNull { it.phase in FOREGROUND_PHASES }
            ?: list.firstOrNull { it.phase != CallDisplay.Phase.ENDED }
            ?: list.firstOrNull()

    private val FOREGROUND_PHASES = setOf(
        CallDisplay.Phase.ACTIVE,
        CallDisplay.Phase.DIALING,
        CallDisplay.Phase.CONNECTING,
    )

    @Synchronized
    private fun call(id: String): Call? = live[id]

    /**
     * ⚠️ **Answered audio-only, always** — a video call included. LCARS has no camera surface for a
     * call, and answering with video would turn the camera on with nowhere to show it. The screen says
     * so on a video call.
     */
    fun answer(id: String) {
        call(id)?.answer(VideoProfile.STATE_AUDIO_ONLY)
    }

    fun decline(id: String) {
        call(id)?.reject(false, null)
    }

    fun end(id: String) {
        call(id)?.disconnect()
    }

    fun hold(id: String) {
        call(id)?.hold()
    }

    fun resume(id: String) {
        call(id)?.unhold()
    }

    /**
     * Swap between the call in hand and the one on hold. A conference that says it can swap does so
     * itself; otherwise resuming the held call is the swap — Telecom holds the active one for it.
     */
    fun swap(focusId: String) {
        val focus = call(focusId) ?: return
        if ((focus.details?.callCapabilities ?: 0) and CallDisplay.CAPABILITY_SWAP_CONFERENCE != 0) {
            focus.swapConference()
            return
        }
        val held = synchronized(this) {
            live.entries.firstOrNull { (id, c) -> id != focusId && c.details?.state == CallDisplay.STATE_HOLDING }?.value
        }
        held?.unhold()
    }

    /** Merge into a conference, the way the network says this call can be merged. */
    fun merge(focusId: String) {
        val focus = call(focusId) ?: return
        if ((focus.details?.callCapabilities ?: 0) and CallDisplay.CAPABILITY_MERGE_CONFERENCE != 0) {
            focus.mergeConference()
        } else {
            focus.conferenceableCalls.firstOrNull()?.let(focus::conference)
        }
    }

    fun dtmfDown(id: String, c: Char) {
        call(id)?.playDtmfTone(c)
    }

    fun dtmfUp(id: String) {
        call(id)?.stopDtmfTone()
    }

    fun setMuted(muted: Boolean) {
        service.get()?.setMuted(muted)
    }

    /**
     * Send the sound to [route]. On API 34+ by the endpoint Telecom itself offered — the only way to
     * reach a particular Bluetooth device — and below that by the older route bits.
     */
    fun route(route: CallDisplay.Route, executor: Executor) {
        val s = service.get() ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            routeByEndpoint(s, route, executor)
            return
        }
        CallDisplay.legacyRoute(route)?.let { @Suppress("DEPRECATION") s.setAudioRoute(it) }
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun routeByEndpoint(s: LcarsInCallService, route: CallDisplay.Route, executor: Executor) {
        val target = synchronized(this) {
            endpoints.filterIsInstance<CallEndpoint>().firstOrNull { CallDisplay.routeFromEndpoint(it.endpointType) == route }
        } ?: return
        runCatching {
            s.requestCallEndpointChange(
                target,
                executor,
                object : android.os.OutcomeReceiver<Void, android.telecom.CallEndpointException> {
                    override fun onResult(result: Void?) = Unit
                },
            )
        }
    }
}
