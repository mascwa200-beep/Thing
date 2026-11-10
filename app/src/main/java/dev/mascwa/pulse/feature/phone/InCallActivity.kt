package dev.mascwa.pulse.feature.phone

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.PowerManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameMillis
import androidx.compose.runtime.collectAsState
import dev.mascwa.pulse.data.settings.AppSettings
import dev.mascwa.pulse.feature.lcarsboard.ConsoleSequence
import dev.mascwa.pulse.feature.lcarsboard.hideStatusBarForConsole
import dev.mascwa.pulse.ui.ProvideStardate
import dev.mascwa.pulse.ui.theme.NightwireTheme
import android.provider.Settings
import android.telephony.PhoneNumberUtils
import android.telephony.TelephonyManager
import java.util.Locale
import kotlinx.coroutines.delay

/**
 * The call screen: who it is, how long it has been, and the buttons the call allows — over a locked
 * or sleeping phone when it rings.
 *
 * It holds no `Call`. It reads [CallRegistry]'s snapshots and asks that object to act, so the screen
 * rotating, being recreated or left open after the call has gone can touch nothing live.
 *
 * ⚠️ **It never dials, answers or declines an EMERGENCY call** — Telecom routes those to the built-in
 * dialler's own screen ([LcarsInCallService]), which is why the built-in dialler is never paused.
 *
 * ⚠️ **ANSWER from the ringing notification arrives as [ACTION_ANSWER], not a button the notification
 * taps.** Android 12 blocks an activity started from a receiver a notification started, so the card's
 * Answer opens this screen with the answer baked into the intent instead. The intent is consumed once
 * (its action cleared) so a recreation does not answer a call that has since changed.
 */
class InCallActivity : ComponentActivity() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        hideStatusBarForConsole()
        wakeLock = runCatching {
            getSystemService(PowerManager::class.java)
                ?.newWakeLock(PROXIMITY_SCREEN_OFF_WAKE_LOCK, "lcars:in-call")
        }.getOrNull()
        answerFrom(intent)

        val defaults = AppSettings()
        val sweepMs = ConsoleSequence.durationFor(animatorScale())

        setContent {
            NightwireTheme(accent = defaults.accentColor, amoledBlack = defaults.amoledBlack) {
                ProvideStardate {
                    val progress = remember { mutableFloatStateOf(0f) }
                    LaunchedEffect(Unit) {
                        val start = withFrameMillis { it }
                        do {
                            val now = withFrameMillis { it }
                            progress.floatValue = ConsoleSequence.progressAt(now - start, true, 0f, sweepMs)
                        } while (progress.floatValue != 1f)
                    }

                    val calls by CallRegistry.calls.collectAsState()
                    val audio by CallRegistry.audio.collectAsState()

                    // A once-a-second tick for the timer under an active call.
                    val now = remember { mutableLongStateOf(System.currentTimeMillis()) }
                    LaunchedEffect(Unit) {
                        while (true) {
                            now.longValue = System.currentTimeMillis()
                            delay(1_000)
                        }
                    }

                    // No call left — the last one ended. Nothing to show; step aside.
                    LaunchedEffect(calls.isEmpty()) {
                        if (calls.isEmpty()) finish()
                    }

                    // Blank the screen against a cheek only while the phone is held to an ear.
                    val focus = CallRegistry.focus(calls)
                    val heldToEar = focus != null && CallDisplay.heldToEar(audio.route, focus.phase)
                    LaunchedEffect(heldToEar) { if (heldToEar) acquireProximity() else releaseProximity() }

                    val readProgress = remember { { progress.floatValue } }
                    InCallConsole(
                        calls = calls,
                        audio = audio,
                        now = now.longValue,
                        format = ::formatNumber,
                        progress = readProgress,
                        actions = InCallActions(
                            answer = { CallRegistry.answer(it) },
                            decline = { CallRegistry.decline(it) },
                            end = { CallRegistry.end(it) },
                            hold = { CallRegistry.hold(it) },
                            resume = { CallRegistry.resume(it) },
                            swap = { CallRegistry.swap(it) },
                            merge = { CallRegistry.merge(it) },
                            addCall = {
                                runCatching {
                                    startActivity(
                                        Intent(this, PhoneActivity::class.java)
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                    )
                                }
                            },
                            mute = { CallRegistry.setMuted(it) },
                            route = { CallRegistry.route(it, mainExecutor) },
                            dtmfDown = { id, c -> CallRegistry.dtmfDown(id, c) },
                            dtmfUp = { CallRegistry.dtmfUp(it) },
                        ),
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        answerFrom(intent)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideStatusBarForConsole()
    }

    override fun onDestroy() {
        releaseProximity()
        super.onDestroy()
    }

    /** Answer the call the notification's Answer was built for, then clear the action so it is not re-run. */
    private fun answerFrom(intent: Intent?) {
        if (intent?.action != ACTION_ANSWER) return
        intent.getStringExtra(EXTRA_CALL)?.let { CallRegistry.answer(it) }
        intent.action = null
    }

    private fun acquireProximity() {
        runCatching { if (wakeLock?.isHeld != true) wakeLock?.acquire(PROXIMITY_HOLD_MS) }
    }

    private fun releaseProximity() {
        // WAIT_FOR_NO_PROXIMITY: let the screen come back only once the phone leaves the ear, never
        // blank-then-black as the call ends with the phone still up.
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release(RELEASE_FLAG_WAIT_FOR_NO_PROXIMITY) }
    }

    private fun formatNumber(number: String): String {
        if (number.any { it in "*#,;" }) return number
        return runCatching { PhoneNumberUtils.formatNumber(number, countryIso()) }.getOrNull() ?: number
    }

    private fun countryIso(): String {
        val tm = getSystemService(TelephonyManager::class.java)
        val network = runCatching { tm?.networkCountryIso }.getOrNull()
        val sim = runCatching { tm?.simCountryIso }.getOrNull()
        return (network?.takeIf { it.isNotBlank() } ?: sim?.takeIf { it.isNotBlank() } ?: Locale.getDefault().country)
            .uppercase(Locale.ROOT)
    }

    private fun animatorScale(): Float =
        runCatching { Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) }
            .getOrDefault(1f)

    companion object {
        const val ACTION_ANSWER = "dev.mascwa.pulse.phone.ANSWER"
        const val EXTRA_CALL = "call"

        // PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK = 32, RELEASE_FLAG_WAIT_FOR_NO_PROXIMITY = 1 —
        // both @hide on the SDK but public, stable constants (checked with javap).
        private const val PROXIMITY_SCREEN_OFF_WAKE_LOCK = 32
        private const val RELEASE_FLAG_WAIT_FOR_NO_PROXIMITY = 1
        private const val PROXIMITY_HOLD_MS = 60 * 60 * 1000L

        /** Open the call screen — used by Telecom's "return to call" and the card's tap. */
        fun open(context: Context) {
            runCatching {
                context.startActivity(Intent(context, InCallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }
}
