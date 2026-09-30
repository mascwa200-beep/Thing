package dev.mascwa.pulse.feature.lockboard

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameMillis
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dev.mascwa.pulse.PulseApplication
import dev.mascwa.pulse.data.settings.AppSettings
import dev.mascwa.pulse.feature.lcarsboard.BoardTap
import dev.mascwa.pulse.feature.lcarsboard.ConsoleSequence
import dev.mascwa.pulse.feature.lcarsboard.hideStatusBarForConsole
import dev.mascwa.pulse.ui.ProvideStardate
import dev.mascwa.pulse.ui.theme.NightwireTheme
import dev.mascwa.pulse.widget.LockBoard
import dev.mascwa.pulse.widget.openRouteIntent
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The lock screen, as a full LCARS console — the first thing seen when the screen comes on, before
 * unlocking.
 *
 * ## How it draws
 *
 * [LockConsole]: solid LCARS chrome around the widget's board, each region in its own panel. The
 * board is the SAME data the widget draws, from [LockBoard], which every widget render feeds, so the
 * lock screen and the widget cannot disagree about what is going on. When nothing recent is held it
 * shows the time and a loading line, then fills in place as soon as a board lands.
 *
 * ## The power-up, and the power-down
 *
 * The console assembles itself when the screen comes on and takes itself apart the same way when
 * the screen goes off — one timeline, run forward and back ([ConsoleSequence]).
 *
 * - **Up** begins on `ACTION_SCREEN_ON`, which Android sends as the phone STARTS waking, so it plays
 *   as the display lights. [onStart] covers a console shown while the screen is already on.
 * - **Down** cannot wait for `ACTION_SCREEN_OFF`: that is sent only after the display is already
 *   dark. So while the console is in front it watches `PowerManager.isInteractive`, which turns
 *   false the moment the power button is pressed, and starts the reverse sweep then.
 * - ⚠️ **Android darkens the display on its own schedule and no app can hold it lit**, so how much
 *   of the power-down is seen is measured rather than promised: the time from that first signal to
 *   the screen being off goes to [LockBoardLog] and appears under the Settings switch.
 *
 * ## Every tap unlocks first
 *
 * ⚠️ The board is full of tap targets — each region opens its screen, each event opens in the
 * calendar. From a screen shown over the lock screen, simply firing one would try to open an
 * ordinary screen behind a locked keyguard. So a tap is HELD, the keyguard is asked to go away, and
 * the held tap opens once it has. Cancel the unlock and nothing opens.
 *
 * ## It is the lock screen, not something in front of it
 *
 * ⚠️ **Back does nothing.** When Back finished it, the stock lock screen was one press away, which made
 * this an overlay rather than the lock screen the owner asked for. Now the ways past it are the ones
 * past any lock screen: unlocking — fingerprint, face, or UNLOCK and then Android's PIN — or the power
 * button.
 *
 * ⚠️ **Android's lock is still underneath, and that is deliberate (the owner's choice).** No app can
 * replace the keyguard while a PIN is set: `setKeyguardDisabled` does nothing while a credential
 * exists, and the alternative, removing the PIN, would cost the phone its real lock and GrapheneOS its
 * credential-bound encryption. So the PIN and fingerprint stay Android's, and this is what you see.
 *
 * ## Never a trap
 *
 * Without Back, the console needs its own way to call for help, so EMERGENCY opens the system
 * emergency dialer, which Android shows over the lock screen. If that cannot be opened, EMERGENCY asks
 * for the unlock instead, because Android's PIN screen carries its own Emergency button. It never keeps
 * the screen on, never turns it on, and a phone that is not locked when its screen comes on sends it
 * away before it is in the way. It is `showWhenLocked` and nothing more: it has no power to stop
 * anybody using their own phone, only to be what they see first.
 *
 * ⚠️ Anyone who picks the phone up sees it, agenda titles included. That is what was asked for, and
 * Settings says so beside the switch.
 */
class LockBoardActivity : ComponentActivity() {

    private val keyguard by lazy { getSystemService(KeyguardManager::class.java) }
    private val power by lazy { getSystemService(PowerManager::class.java) }

    /** What to open once the keyguard has gone — set only while an unlock is being asked for. */
    private var pendingTap: BoardTap? = null
    private var receiver: BroadcastReceiver? = null

    /** Where the console stands: 0 dark, 1 fully up. Read inside draw layers, so a sweep never recomposes. */
    private val progress = mutableFloatStateOf(0f)

    /** Which way the console is heading. The sweep follows it from wherever it currently is. */
    private val powered = mutableStateOf(false)

    /** When the current power-down began (elapsed realtime), until it has been measured. */
    private var powerDownAt: Long? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        alive = true
        setShowWhenLocked(true)
        // The evidence the start was not refused: a background start Android drops returns
        // normally, so this is the only place that knows the board really came into being.
        LockBoardLog.record(this, LockBoardPolicy.Outcome.SHOWN)

        // Edge to edge, so the console can draw behind the camera cutout. The gesture bar keeps light
        // icons over the black ground; the status bar is hidden just below.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        // The console owns the top of the screen: Android's status bar is hidden, and the header
        // carries the time and the battery instead. The gesture bar stays.
        hideStatusBarForConsole()

        val defaults = AppSettings()
        val sweepMs = ConsoleSequence.durationFor(animatorScale())
        setContent {
            NightwireTheme(accent = defaults.accentColor, amoledBlack = defaults.amoledBlack) {
                ProvideStardate {
                    val snapshot by LockBoard.latest.collectAsStateWithLifecycle()
                    val target by powered
                    // ONE timeline, driven toward whichever end `powered` names, from wherever it is
                    // — which is what makes a power-down that interrupts a power-up continue from the
                    // half-assembled console instead of flashing it up first.
                    LaunchedEffect(target) {
                        val from = progress.floatValue
                        val end = if (target) 1f else 0f
                        if (from == end) return@LaunchedEffect
                        val start = withFrameMillis { it }
                        do {
                            val now = withFrameMillis { it }
                            progress.floatValue = ConsoleSequence.progressAt(now - start, target, from, sweepMs)
                        } while (progress.floatValue != end)
                    }
                    val readProgress = remember { { progress.floatValue } }
                    LockConsole(snapshot?.board, readProgress, ::unlock, ::emergency)
                }
            }
        }

        // Back does nothing: this is the lock screen, not something in front of it. See the class
        // notes for the ways past it, and EMERGENCY for the one that needs no unlock.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = Unit
        })

        // ⚠️ Registered at runtime because they have to be: USER_PRESENT is never delivered to a
        // manifest receiver, and the screen broadcasts are runtime-only too. No export flag, as for
        // every protected system broadcast in this app.
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_USER_PRESENT -> unlocked()
                    Intent.ACTION_SCREEN_ON -> powerUp()
                    Intent.ACTION_SCREEN_OFF -> closePowerDown()
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        runCatching { registerReceiver(r, filter) }.onSuccess { receiver = r }

        // The earliest sign the screen is going off: isInteractive turns false at the power button.
        // Only while the console is in front — nobody watches a power-down behind something else.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) {
                    delay(SLEEP_POLL_MS)
                    if (power?.isInteractive == false) {
                        beginPowerDown()
                        break
                    }
                }
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // A bar swiped into view, or a window that was over this one, can leave it showing.
        if (hasFocus) hideStatusBarForConsole()
    }

    override fun onStart() {
        super.onStart()
        // Shown with what is held, redrawn in place when a fresher board lands. On the app's scope,
        // so a load started as the screen comes on still finishes if the board is dismissed first.
        (application as? PulseApplication)?.let { app ->
            LockBoard.refreshIfStale(app, app.appScope, System.currentTimeMillis())
        }
        // Already lit — the safety-net start at screen-on, or a return to the console — so power up
        // now rather than waiting for a screen-on that has already happened.
        if (power?.isInteractive != false) powerUp()
    }

    override fun onResume() {
        super.onResume()
        // ⚠️ A missing manager reads as "lit and unlocked", which sends the board away: a lock
        // screen that cannot tell whether the phone is locked has no business staying up.
        if (LockBoardPolicy.finishOnResume(power?.isInteractive != false, keyguard?.isKeyguardLocked == true)) unlocked()
    }

    override fun onPause() {
        super.onPause()
        // Pausing because the phone is going to sleep: the power-down starts here if the watch
        // above has not already seen it.
        if (power?.isInteractive == false) beginPowerDown()
    }

    override fun onStop() {
        // The screen is off, or the console has been left: measure what the power-down had, and
        // stand the console at dark so the next wake assembles it from nothing.
        closePowerDown()
        powered.value = false
        progress.floatValue = 0f
        super.onStop()
    }

    override fun onDestroy() {
        receiver?.let { runCatching { unregisterReceiver(it) } }
        receiver = null
        alive = false
        super.onDestroy()
    }

    // ── the sweep ───────────────────────────────────────────────────────────────────────────────

    private fun powerUp() {
        powerDownAt = null
        powered.value = true
    }

    /** Start taking the console apart. Once per going-to-sleep, and only if it was up to take apart. */
    private fun beginPowerDown() {
        if (powerDownAt != null || !powered.value) return
        powerDownAt = SystemClock.elapsedRealtime()
        powered.value = false
    }

    /** The screen is off: record how long the power-down had. Idempotent — the first caller measures. */
    private fun closePowerDown() {
        val at = powerDownAt ?: return
        powerDownAt = null
        LockBoardLog.recordPowerDown(this, SystemClock.elapsedRealtime() - at)
    }

    /** The system's animator scale; 0 means "remove animations", which the sweep honours. */
    private fun animatorScale(): Float =
        runCatching { Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) }
            .getOrDefault(1f)

    // ── unlocking ───────────────────────────────────────────────────────────────────────────────

    /** Unlock, then open [tap] if there is one. The keyguard's own screen asks for the PIN. */
    private fun unlock(tap: BoardTap?) {
        pendingTap = tap
        val km = keyguard
        if (km == null || !km.isKeyguardLocked) {
            unlocked()
            return
        }
        km.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() = unlocked()

            override fun onDismissCancelled() {
                pendingTap = null
            }

            override fun onDismissError() {
                pendingTap = null
            }
        })
    }

    /**
     * EMERGENCY: the system emergency dialer, which Android shows over the lock screen, so help needs
     * no unlock.
     *
     * ⚠️ **Opened by action string, because the tidy API is not public.**
     * `TelecomManager.createLaunchEmergencyDialerIntent` exists in the platform and is absent from the
     * public SDK, so an app cannot call it. CI said so, where the local check, which compiles against
     * Robolectric's full framework, did not. The actions tried are what the platform itself uses:
     * - `ACTION_DIAL_EMERGENCY`, read out of the framework's `Intent`, where the constant is hidden but
     *   its value is a plain string;
     * - `com.android.phone.EmergencyDialer.DIAL`, the emergency dialer's own action in AOSP's
     *   telephony package.
     *
     * ⚠️ If neither opens, or the phone has no dialer at all, this asks for the unlock instead. Android's
     * PIN screen carries its own Emergency button, so the way to a call is never a dead end.
     */
    private fun emergency() {
        val opened = EMERGENCY_DIALER_ACTIONS.any { action ->
            runCatching { startActivity(Intent(action)) }.isSuccess
        }
        if (!opened) unlock(null)
    }

    /**
     * The keyguard has gone: open what was tapped, if anything, and step aside.
     *
     * ⚠️ **Three signals say the phone is unlocked and Android does not order them**: the dismiss
     * callback, the USER_PRESENT broadcast, and [onResume] finding a lit, unlocked phone. They used
     * to act separately, and the order mattered — `onResume` winning finished the board with a tap
     * still held, so the callback then launched from an activity already finishing and the event
     * somebody unlocked in order to open could be lost; USER_PRESENT arriving while a tap was held
     * did nothing, leaving the board over an unlocked phone if the callback never came. Every signal
     * comes here instead, and the held tap is taken exactly once, so whichever arrives first opens it
     * and the others find nothing left to do.
     */
    private fun unlocked() {
        val tap = pendingTap
        pendingTap = null
        tap?.let(::open)
        if (!isFinishing) finish()
    }

    /**
     * The real launch. A route opens LCARS with an explicit intent — never through the widget's
     * PendingIntent helper, which would retarget the placed widget's own tap (see [BoardTap]).
     */
    private fun open(tap: BoardTap) {
        runCatching {
            when (tap) {
                is BoardTap.Route -> startActivity(openRouteIntent(this, tap.route))
                is BoardTap.Sender -> startIntentSender(tap.sender, tap.fillIn, 0, 0, 0)
            }
        }
    }

    companion object {
        /**
         * Whether a board is up. Read by the trigger so a second screen-off does not start another,
         * and kept from create to destroy for the reason [LockScreenGuests] gives: it is stopped every
         * time the screen goes off, which is exactly when it is asked.
         */
        @Volatile
        var alive: Boolean = false
            private set

        /** How often the console checks whether the phone has started going to sleep. */
        private const val SLEEP_POLL_MS = 50L

        /** Tried in order by [emergency]; see there for where each comes from. */
        private val EMERGENCY_DIALER_ACTIONS = listOf(
            "android.intent.action.DIAL_EMERGENCY",
            "com.android.phone.EmergencyDialer.DIAL",
        )
    }
}
