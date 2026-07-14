package dev.mascwa.pulse.feature.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import dev.mascwa.pulse.MainActivity
import dev.mascwa.pulse.PulseApplication
import dev.mascwa.pulse.core.telemetry.Character
import dev.mascwa.pulse.core.telemetry.GameClock
import dev.mascwa.pulse.core.telemetry.LifeProfile
import dev.mascwa.pulse.core.telemetry.LifeStats
import dev.mascwa.pulse.core.telemetry.NeedKind
import dev.mascwa.pulse.core.telemetry.NeedTier
import java.util.Calendar
import dev.mascwa.pulse.data.game.SpecialGameStore
import dev.mascwa.pulse.navigation.Routes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The **always-on game overlay** — a small floating window that draws over other apps (and the home screen)
 * so the S.P.E.C.I.A.L. survival game keeps running and updating whether or not Pulse is open. It shows the
 * live survival conditions (the six needs, decaying in real time) and a row of self-care buttons
 * (DRINK / EAT / REST / WASH / BRUSH / FLOSS) that tend those needs on the spot — no need to open the app.
 *
 * Design, mirroring [dev.mascwa.pulse.data.perception.AmbientSensingService]:
 *  - **Opt-in, default OFF** (`AppSettings.gameOverlay`); [MainActivity] starts/stops it in step with the toggle.
 *  - Needs the user-granted **draw-over-other-apps** permission; the service no-ops (stops) without it, and
 *    Settings offers the grant.
 *  - A raw [WindowManager] `TYPE_APPLICATION_OVERLAY` view — non-focusable, so the rest of the phone stays
 *    fully interactive; only the overlay's own buttons take touch. Draggable by its header; tap the header to
 *    collapse to a compact bubble / expand again.
 *  - A foreground service (so it legally persists in the background) with a low-importance STOP notification.
 *  - Fully defensive — any window/among-views hiccup can never crash the app.
 */
class GameOverlayService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var windowManager: WindowManager? = null
    private var root: LinearLayout? = null
    private var params: WindowManager.LayoutParams? = null

    // View handles updated on each state emission.
    private var titleView: TextView? = null
    private var conditionView: TextView? = null
    private var vitalsView: TextView? = null
    private var dayView: TextView? = null
    private val needValueViews = HashMap<NeedKind, TextView>()
    private var body: LinearLayout? = null
    private var collapsed = false
    private var observing = false
    private var lastStarted = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            teardown()
            stopForegroundCompat()
            stopSelf()
            return START_NOT_STICKY
        }
        // Can't draw an overlay without the permission — don't sit foreground for nothing.
        if (!Settings.canDrawOverlays(this)) {
            stopForegroundCompat()
            stopSelf()
            return START_NOT_STICKY
        }
        runCatching { startForegroundCompat() }
        if (root == null) runCatching { buildAndAdd() }
        observe()
        // NOT_STICKY: an overlay resurrected from the background can't reliably re-add its window; the
        // MainActivity effect re-starts it on next foreground. It keeps running until then.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        teardown()
        scope.cancel()
        super.onDestroy()
    }

    private fun container() = runCatching { (application as PulseApplication).container }.getOrNull()
    private fun store(): SpecialGameStore? = container()?.specialGameStore

    // ----- live state -----

    private fun observe() {
        if (observing) return // onStartCommand can fire on every foreground return — collect only once.
        val store = store() ?: return
        observing = true
        // Reflect the decaying needs live.
        scope.launch {
            runCatching { store.lifeFlow.collect { profile -> runCatching { render(profile) } } }
        }
        // Character vitals (level / HP / caps).
        scope.launch {
            runCatching { store.characterFlow.collect { c -> runCatching { renderVitals(c) } } }
        }
        // Wasteland day (recomputed against the wall clock each tick, so the phase advances).
        scope.launch {
            runCatching { store.startedFlow.collect { s -> lastStarted = s; runCatching { renderDay() } } }
        }
        // Keep the decay moving even with the app closed (a passive display tick — no disk writes).
        scope.launch {
            while (isActive) {
                runCatching { store.tickNeeds() }
                runCatching { renderDay() }
                delay(TICK_MS)
            }
        }
    }

    private fun renderVitals(c: Character) {
        vitalsView?.text = "LVL ${c.level}   HP ${c.hp}/${c.maxHp}   ⚑ ${c.caps}"
    }

    private fun renderDay() {
        val started = lastStarted
        if (started <= 0L) { dayView?.text = ""; return }
        val hour = runCatching { Calendar.getInstance().get(Calendar.HOUR_OF_DAY) }.getOrDefault(12)
        dayView?.text = runCatching { GameClock.banner(started, System.currentTimeMillis(), hour) }.getOrDefault("")
    }

    private fun render(profile: LifeProfile) {
        val overall = LifeStats.overallCondition(profile)
        titleView?.text = if (collapsed) "PIP-BOY  $overall%" else "PIP-BOY  ·  CND $overall%"
        val states = LifeStats.needStates(profile)
        val urgent = states.filter { it.tier.isConcern }.minByOrNull { it.value }
        conditionView?.text = if (urgent != null) {
            "${urgent.kind.label}: ${urgent.condition} (${urgent.value})"
        } else {
            "All needs steady."
        }
        for (st in states) {
            needValueViews[st.kind]?.apply {
                text = "${st.kind.label.uppercase().take(4)}  ${st.value}"
                setTextColor(tierColor(st.tier))
            }
        }
    }

    private fun tierColor(t: NeedTier): Int = when {
        t.isDire -> RED
        t.isConcern -> AMBER
        else -> GREEN
    }

    // ----- view construction -----

    private fun buildAndAdd() {
        val wm = getSystemService(WINDOW_SERVICE) as? WindowManager ?: return
        windowManager = wm

        val rootView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = panelBg()
            setPadding(dp(10), dp(8), dp(10), dp(10))
        }

        // Header — drag handle + tap-to-collapse; a ✕ closes the overlay entirely.
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(this).apply {
            text = "PIP-BOY"
            setTextColor(GREEN)
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        titleView = title
        val open = TextView(this).apply {
            text = "OPEN"
            setTextColor(GREEN)
            textSize = 10f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(dp(6), dp(2), dp(6), dp(2))
            setOnClickListener { openGame() }
        }
        val close = TextView(this).apply {
            text = "  ✕"
            setTextColor(DIM)
            textSize = 13f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(dp(8), dp(2), dp(2), dp(2))
            setOnClickListener { stopEverything() }
        }
        header.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(open)
        header.addView(close)
        rootView.addView(header, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // Body — condition summary + needs grid + care buttons.
        val bodyView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6), 0, 0)
        }
        body = bodyView

        dayView = TextView(this).apply {
            setTextColor(GREEN)
            textSize = 10f
            typeface = android.graphics.Typeface.MONOSPACE
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        bodyView.addView(dayView)

        vitalsView = TextView(this).apply {
            setTextColor(DIM)
            textSize = 10f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(0, dp(2), 0, 0)
        }
        bodyView.addView(vitalsView)

        conditionView = TextView(this).apply {
            setTextColor(DIM)
            textSize = 10f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(0, dp(2), 0, 0)
        }
        bodyView.addView(conditionView)

        // Needs: two rows of three compact value chips.
        val needsWrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6), 0, dp(6))
        }
        val order = NeedKind.entries.toList()
        var i = 0
        while (i < order.size) {
            val rowLayout = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            var c = 0
            while (c < 3 && i < order.size) {
                val kind = order[i]
                val chip = TextView(this).apply {
                    text = "${kind.label.uppercase().take(4)}  --"
                    setTextColor(GREEN)
                    textSize = 10f
                    typeface = android.graphics.Typeface.MONOSPACE
                    setPadding(dp(2), dp(1), dp(6), dp(1))
                }
                needValueViews[kind] = chip
                rowLayout.addView(chip, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                i++; c++
            }
            needsWrap.addView(rowLayout, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        bodyView.addView(needsWrap)

        // Care buttons — two rows of three, each tends its need on the spot.
        var j = 0
        while (j < order.size) {
            val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            var c = 0
            while (c < 3 && j < order.size) {
                val kind = order[j]
                btnRow.addView(careButton(kind), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(2), dp(2), dp(2), dp(2)) })
                j++; c++
            }
            bodyView.addView(btnRow, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        rootView.addView(bodyView, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root = rootView

        val lp = WindowManager.LayoutParams(
            dp(224),
            WindowManager.LayoutParams.WRAP_CONTENT,
            // minSdk 31 → TYPE_APPLICATION_OVERLAY (API 26+) is always available.
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(12)
            y = dp(120)
        }
        params = lp
        attachDrag(header, lp, wm, rootView)
        runCatching { wm.addView(rootView, lp) }
    }

    /** Care button: a rounded pill that tends [kind] when tapped (works with the app closed). */
    private fun careButton(kind: NeedKind): TextView = TextView(this).apply {
        text = kind.verb
        setTextColor(GREEN)
        textSize = 11f
        typeface = android.graphics.Typeface.MONOSPACE
        gravity = Gravity.CENTER
        setPadding(dp(4), dp(6), dp(4), dp(6))
        background = buttonBg()
        setOnClickListener {
            val s = store() ?: return@setOnClickListener
            when (kind) {
                NeedKind.HYDRATION -> s.drink()
                NeedKind.NOURISHMENT -> s.eat()
                NeedKind.ENERGY -> s.rest()
                NeedKind.HYGIENE -> s.wash()
                NeedKind.BRUSHING -> s.brushTeeth()
                NeedKind.FLOSSING -> s.floss()
            }
            runCatching { s.tickNeeds() }
        }
    }

    /** Header touch: a small drag moves the window; a tap (no drag) collapses / expands the body. */
    private fun attachDrag(header: View, lp: WindowManager.LayoutParams, wm: WindowManager, rootView: View) {
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var dragged = false
        header.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    startX = lp.x; startY = lp.y
                    dragged = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - downX).roundToInt()
                    val dy = (e.rawY - downY).roundToInt()
                    if (abs(dx) > SLOP || abs(dy) > SLOP) dragged = true
                    lp.x = startX + dx
                    lp.y = startY + dy
                    runCatching { wm.updateViewLayout(rootView, lp) }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragged) toggleCollapse()
                    true
                }
                else -> false
            }
        }
    }

    private fun toggleCollapse() {
        collapsed = !collapsed
        body?.visibility = if (collapsed) View.GONE else View.VISIBLE
        // Refresh the title's %, which changes with collapse.
        store()?.let { runCatching { render(it.lifeFlow.value) } }
    }

    /** Bring up the full PIP-BOY game (for the parts that need the whole screen — encounters/gestures/map).
     *  The draw-over-apps permission exempts us from background-activity-launch restrictions. */
    private fun openGame() {
        runCatching {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .putExtra(MainActivity.EXTRA_ROUTE, Routes.TACNET)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    private fun stopEverything() {
        // Reflect the user's dismissal in settings so MainActivity doesn't restart us on next foreground.
        container()?.let { c ->
            scope.launch { runCatching { c.settingsRepository.update { it.copy(gameOverlay = false) } } }
        }
        teardown()
        stopForegroundCompat()
        stopSelf()
    }

    private fun teardown() {
        root?.let { r -> runCatching { windowManager?.removeView(r) } }
        root = null
        needValueViews.clear()
    }

    // ----- drawables / dimens -----

    private fun panelBg() = GradientDrawable().apply {
        setColor(Color.parseColor("#E6060A06"))
        cornerRadius = dp(10).toFloat()
        setStroke(dp(1), Color.parseColor("#2E7D32"))
    }

    private fun buttonBg() = GradientDrawable().apply {
        setColor(Color.parseColor("#12351C"))
        cornerRadius = dp(6).toFloat()
        setStroke(dp(1), Color.parseColor("#3C8C42"))
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()

    // ----- foreground / notification -----

    private fun startForegroundCompat() {
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        else if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        else 0
        ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(), type)
    }

    private fun stopForegroundCompat() {
        runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) }
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_ROUTE, Routes.TACNET),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, GameOverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle("Pip-Boy overlay active")
            .setContentText("Your survival stats float over other apps. Tap to open, or Stop.")
            .setContentIntent(open)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    private fun ensureChannel() {
        val mgr = getSystemService(NotificationManager::class.java) ?: return
        if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Game overlay", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "The floating Pip-Boy survival overlay"
                    setShowBadge(false)
                },
            )
        }
    }

    companion object {
        private const val CHANNEL_ID = "game_overlay"
        private const val NOTIF_ID = 4401
        private const val TICK_MS = 30_000L
        private const val SLOP = 8
        const val ACTION_STOP = "dev.mascwa.pulse.overlay.STOP"

        private val GREEN = Color.parseColor("#6CE06A")
        private val AMBER = Color.parseColor("#E0B84A")
        private val RED = Color.parseColor("#E85C5C")
        private val DIM = Color.parseColor("#8FB58C")

        /** Start the overlay (call only when the setting is on AND [Settings.canDrawOverlays] is true). */
        fun start(context: Context) {
            if (!Settings.canDrawOverlays(context)) return
            runCatching {
                androidx.core.content.ContextCompat.startForegroundService(
                    context, Intent(context, GameOverlayService::class.java),
                )
            }
        }

        /** Stop the overlay and remove its window. */
        fun stop(context: Context) {
            runCatching {
                context.startService(Intent(context, GameOverlayService::class.java).setAction(ACTION_STOP))
            }
        }
    }
}
