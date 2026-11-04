package dev.mascwa.pulse.feature.phone

import android.content.Intent
import android.os.Build
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.CallEndpoint
import android.telecom.InCallService

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
 */
class LcarsInCallService : InCallService() {

    private val callback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) = refresh()
        override fun onDetailsChanged(call: Call, details: Call.Details) = refresh()
        override fun onConferenceableCallsChanged(call: Call, conferenceableCalls: List<Call>) = refresh()
    }

    override fun onCreate() {
        super.onCreate()
        CallRegistry.attach(this)
    }

    override fun onDestroy() {
        CallRegistry.detach(this)
        CallNotices.clear(this)
        super.onDestroy()
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
