package dev.mascwa.pulse.feature.lockboard

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.content.IntentSender
import android.os.Bundle
import android.os.PowerManager
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dev.mascwa.pulse.PulseApplication
import dev.mascwa.pulse.R
import dev.mascwa.pulse.widget.LockBoard
import dev.mascwa.pulse.widget.WidgetBoard
import dev.mascwa.pulse.widget.widgetOpenIntent
import kotlinx.coroutines.launch

/**
 * The widget's situation board, over the lock screen — the first thing seen when the screen comes
 * on, before unlocking.
 *
 * ## How it draws
 *
 * It does not have a layout of its own. It asks [WidgetBoard] for the SAME `RemoteViews` the widget
 * is drawn from, full-height form, and applies it into this window — so the lock screen and the
 * widget are one design with one set of rules, and anything fixed on the widget is fixed here too.
 * The board comes from [LockBoard], which every widget render feeds; when nothing recent is held it
 * shows a clock and a loading line, then redraws in place as soon as a board lands.
 *
 * ## Every tap unlocks first
 *
 * ⚠️ The board is full of tap targets — each region opens its screen, each event opens in the
 * calendar. From a screen shown over the lock screen, simply firing one would try to open an
 * ordinary screen behind a locked keyguard. So the context the board is applied with ([TapGate])
 * catches the launch: `RemoteViews` sends every click, list rows included, through
 * `Context.startIntentSender` on the view's context (read out of the platform), and the gate turns
 * that into "ask the keyguard to go away, and open this once it has". Cancel the unlock and nothing
 * opens.
 *
 * ## Never a trap
 *
 * Back leaves at once, showing the ordinary lock screen. Unlocking by any route — fingerprint, face,
 * the UNLOCK bar, a tap anywhere — ends it. It never keeps the screen on, never turns it on, and a
 * phone that is not locked when its screen comes on sends it away before it is in the way. It is
 * `showWhenLocked` and nothing more: it covers the keyguard the way a navigation app does, and has no
 * power to stop anybody using their own phone.
 *
 * ⚠️ Anyone who picks the phone up sees it, agenda titles included. That is what was asked for, and
 * Settings says so beside the switch.
 */
class LockBoardActivity : ComponentActivity() {

    private lateinit var boardHost: FrameLayout
    private val keyguard by lazy { getSystemService(KeyguardManager::class.java) }
    private val power by lazy { getSystemService(PowerManager::class.java) }
    private val gate by lazy { TapGate(this) }

    /** What to open once the keyguard has gone — set only while an unlock is being asked for. */
    private var pendingTap: Tap? = null
    private var unlockReceiver: BroadcastReceiver? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        alive = true
        setShowWhenLocked(true)

        boardHost = FrameLayout(this)
        val unlockBar = TextView(this).apply {
            text = UNLOCK_LABEL
            contentDescription = "Unlock"
            gravity = Gravity.CENTER
            setTextColor(getColor(R.color.nw_accent))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            letterSpacing = 0.2f
            val pad = dp(18)
            setPadding(pad, pad, pad, pad)
            setOnClickListener { unlock(null) }
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(boardHost, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(unlockBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val root = FrameLayout(this).apply {
            setBackgroundColor(getColor(R.color.lock_board_scrim))
            // A tap on anything that is not itself a tap target — empty board, a heading — is the
            // ordinary lock-screen gesture: unlock. Clickable views and the list consume their own.
            setOnClickListener { unlock(null) }
            addView(column, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            // ⚠️ Edge to edge is enforced at target 35, so the bars overlap the window: pad the
            // content, not the scrim, so the wallpaper still runs to the edges behind it.
            setOnApplyWindowInsetsListener { _, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                column.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
        }
        setContentView(root)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = finish()
        })

        // Unlocked — fingerprint, face, the bouncer after UNLOCK — by whichever route: see [unlocked].
        // ⚠️ Registered at runtime because it has to be: USER_PRESENT is never delivered to a
        // manifest receiver. No export flag, as for every protected system broadcast in this app.
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                if (intent?.action == Intent.ACTION_USER_PRESENT) unlocked()
            }
        }
        runCatching { registerReceiver(r, IntentFilter(Intent.ACTION_USER_PRESENT)) }
            .onSuccess { unlockReceiver = r }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                LockBoard.latest.collect { show(it) }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Shown with what is held, redrawn in place when a fresher board lands. On the app's scope,
        // so a load started as the screen comes on still finishes if the board is dismissed first.
        (application as? PulseApplication)?.let { app ->
            LockBoard.refreshIfStale(app, app.appScope, System.currentTimeMillis())
        }
    }

    override fun onResume() {
        super.onResume()
        // ⚠️ A missing manager reads as "lit and unlocked", which sends the board away: a lock
        // screen that cannot tell whether the phone is locked has no business staying up.
        if (LockBoardPolicy.finishOnResume(power?.isInteractive != false, keyguard?.isKeyguardLocked == true)) unlocked()
    }

    override fun onDestroy() {
        unlockReceiver?.let { runCatching { unregisterReceiver(it) } }
        unlockReceiver = null
        alive = false
        super.onDestroy()
    }

    // ── drawing ─────────────────────────────────────────────────────────────────────────────────

    private fun show(snapshot: LockBoard.Snapshot?) {
        val view = snapshot?.let { drawn(it.board) } ?: waiting()
        boardHost.removeAllViews()
        boardHost.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    /**
     * The widget's own full board, applied here.
     *
     * ⚠️ Tried again without the agenda before giving up, for the reason the widget does the same:
     * the agenda is the one region whose content can be anything the diary holds, and a fortnight of
     * it must never be why the whole board fails to draw. There is no binder ceiling in-process, so
     * the shorter-list rung the widget has would buy nothing here.
     */
    private fun drawn(board: WidgetBoard.Board): View? {
        val attempts = listOfNotNull(board, board.agenda?.let { board.copy(agenda = null) })
        for (attempt in attempts) {
            val view = runCatching {
                WidgetBoard.render(this, attempt, full = true) { route, code -> widgetOpenIntent(this, route, code) }
                    .apply(gate, boardHost)
            }.getOrNull()
            if (view != null) return view
        }
        return null
    }

    /** Before the first board of this process lands: the time, and a line saying what is coming. */
    private fun waiting(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        addView(android.widget.TextClock(this@LockBoardActivity).apply {
            setTextColor(getColor(R.color.nw_ink))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 48f)
            gravity = Gravity.CENTER
        })
        addView(TextView(this@LockBoardActivity).apply {
            text = WAITING_LABEL
            setTextColor(getColor(R.color.nw_muted))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            gravity = Gravity.CENTER
        })
    }

    // ── unlocking ───────────────────────────────────────────────────────────────────────────────

    /** Unlock, then open [tap] if there is one. The keyguard's own screen asks for the PIN. */
    private fun unlock(tap: Tap?) {
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

    /** The real launch — the activity's own, which the gate is not in front of. */
    private fun open(tap: Tap) {
        runCatching {
            startIntentSender(tap.sender, tap.fillIn, tap.flagsMask, tap.flagsValues, tap.extraFlags, tap.options)
        }
    }

    /** One caught launch, held until the keyguard has gone. */
    private class Tap(
        val sender: IntentSender,
        val fillIn: Intent?,
        val flagsMask: Int,
        val flagsValues: Int,
        val extraFlags: Int,
        val options: Bundle?,
    )

    /**
     * The context the board is applied with: identical to the activity, except that a launch goes
     * through [unlock] first.
     *
     * ⚠️ Both overloads, because the platform has both and a caller could reach either; `RemoteViews`
     * uses the six-argument one today. `RemoteViews` wraps whatever context it is given in its own
     * `ContextWrapper`, which does not override either method, so the call arrives here.
     */
    private class TapGate(private val board: LockBoardActivity) : ContextWrapper(board) {
        override fun startIntentSender(
            intent: IntentSender,
            fillInIntent: Intent?,
            flagsMask: Int,
            flagsValues: Int,
            extraFlags: Int,
        ) = startIntentSender(intent, fillInIntent, flagsMask, flagsValues, extraFlags, null)

        override fun startIntentSender(
            intent: IntentSender,
            fillInIntent: Intent?,
            flagsMask: Int,
            flagsValues: Int,
            extraFlags: Int,
            options: Bundle?,
        ) {
            board.unlock(Tap(intent, fillInIntent, flagsMask, flagsValues, extraFlags, options))
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        /**
         * Whether a board is up. Read by the trigger so a second screen-off does not start another,
         * and kept from create to destroy for the reason [LockScreenGuests] gives: it is stopped every
         * time the screen goes off, which is exactly when it is asked.
         */
        @Volatile
        var alive: Boolean = false
            private set

        private const val UNLOCK_LABEL = "▲  UNLOCK"
        private const val WAITING_LABEL = "Reading the board…"
    }
}
