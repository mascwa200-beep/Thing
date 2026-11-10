package dev.mascwa.pulse.feature.phone

import android.content.Intent
import android.os.Build
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.CallEndpoint
import android.telecom.InCallService
import dev.mascwa.pulse.core.util.CrashLoopGuard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * What Telecom binds while LCARS is the phone app and a call exists: it hands every call to
 * [CallRegistry], keeps the call notification current ([CallNotices]), and brings the call screen up.
 *
 * ## What it is NOT responsible for
 *
 * - ⚠️ **Emergency calls.** Telecom hands every emergency call to the BUILT-IN dialler's call screen
 *   (`InCallController.EmergencyInCallServiceConnection.takeControl`), whichever app is the phone app.
 *   It does the same if this service dies or will not bind. That is Android's code, and the reason the
 *   built-in dialler is never paused (`AppSuspension`).
 * - **Ringing.** There is no `IN_CALL_SERVICE_RINGING` meta-data in the manifest, so Telecom plays
 *   the ringtone and vibrates itself, honouring Do Not Disturb exactly as it always has.
 *
 * Telecom binds it with `BIND_ALLOW_BACKGROUND_ACTIVITY_STARTS`, which is what lets [onCallAdded]
 * open the call screen for an outgoing call even though nothing of LCARS is on screen.
 *
 * ## The crash-loop guard
 *
 * ⚠️ A call screen that crashes on every call is a phone that cannot be answered. A crash anywhere in
 * this process — the screen's included, since it shares the process — brings this service down with
 * it, and Telecom rebinds. So every bind is counted ([CrashLoopGuard]): the pending start is written
 * synchronously in [onCreate], cleared once it has lived [CrashLoopGuard.HEALTHY_AFTER_MS] or on a
 * clean [onDestroy]. Three binds that never lived that long, within the window, stand LCARS down —
 * the call screen and missed-call receiver switch off and Telecom falls back to the built-in dialler,
 * so calls still work. The Settings switch turns it back on.
 */
class LcarsInCallService : InCallService() {

    private val callback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) = refresh()
        override fun onDetailsChanged(call: Call, details: Call.Details) = refresh()
        override fun onConferenceableCallsChanged(call: Call, conferenceableCalls: List<Call>) = refresh()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var stoodDown = false

    override fun onCreate() {
        super.onCreate()
        if (standDownIfLooping()) return
        CallRegistry.attach(this)
    }

    override fun onDestroy() {
        scope.cancel()
        // A clean destroy means this bind worked — clear the pending start, unless the guard already
        // stood down (in which case the record is evidence that must survive).
        if (!stoodDown) runCatching { PhoneStore(this).saveGuard(CrashLoopGuard.onHealthy()) }
        CallRegistry.detach(this)
        CallNotices.clear(this)
        super.onDestroy()
    }

    /**
     * True when this bind is the one that stands LCARS down — it has already switched the components
     * off and will do nothing else. Written synchronously before any call is touched, for the reason
     * [CrashLoopGuard] gives. A bind that then lives long enough clears the record off the main thread.
     */
    private fun standDownIfLooping(): Boolean {
        val now = System.currentTimeMillis()
        val store = PhoneStore(this)
        val verdict = runCatching { CrashLoopGuard.onStart(store.guardState(), now) }.getOrNull() ?: return false
        runCatching { store.saveGuard(verdict.state) }
        if (verdict.standDown) {
            stoodDown = true
            runCatching { PhoneSwitch.standDown(this, now) }
            return true
        }
        scope.launch {
            delay(CrashLoopGuard.HEALTHY_AFTER_MS)
            runCatching { store.saveGuard(CrashLoopGuard.onHealthy()) }
        }
        return false
    }

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        call.registerCallback(callback)
        CallRegistry.add(call)
        CallNotices.update(this)
        // A ringing call is announced by its full-screen notification, which Android turns into the
        // call screen when the phone is locked or asleep and a heads-up when it is in use — taking
        // over a screen somebody is reading is Android's decision, not ours. A call being PLACED was
        // asked for a moment ago, so its screen opens straight away.
        if (CallDisplay.phase(call.details?.state ?: CallDisplay.STATE_NEW) != CallDisplay.Phase.RINGING) {
            openScreen()
        }
    }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        call.unregisterCallback(callback)
        CallRegistry.remove(call)
        CallNotices.update(this)
    }

    @Deprecated("Superseded on API 34 by the endpoint callbacks, which are handled too")
    override fun onCallAudioStateChanged(audioState: CallAudioState) {
        CallRegistry.legacyAudio(audioState)
    }

    override fun onCallEndpointChanged(callEndpoint: CallEndpoint) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) CallRegistry.endpoint(callEndpoint)
    }

    override fun onAvailableCallEndpointsChanged(availableEndpoints: List<CallEndpoint>) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) CallRegistry.availableEndpoints(availableEndpoints)
    }

    override fun onMuteStateChanged(isMuted: Boolean) {
        CallRegistry.muteState(isMuted)
    }

    /** Telecom asks for the call screen — the "return to call" bar, a headset button. */
    override fun onBringToForeground(showDialpad: Boolean) {
        openScreen()
    }

    private fun refresh() {
        CallRegistry.changed()
        CallNotices.update(this)
    }

    private fun openScreen() {
        runCatching {
            startActivity(Intent(this, InCallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
