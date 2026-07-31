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
import dev.mascwa.pulse.core.telemetry.Choice
import dev.mascwa.pulse.core.telemetry.GameClock
import dev.mascwa.pulse.core.telemetry.LifeProfile
import dev.mascwa.pulse.core.telemetry.LifeStats
import dev.mascwa.pulse.core.telemetry.NeedKind
import dev.mascwa.pulse.core.telemetry.NeedTier
import dev.mascwa.pulse.core.telemetry.QuestKind
import dev.mascwa.pulse.core.telemetry.QuestView
import dev.mascwa.pulse.core.telemetry.Resolution
import dev.mascwa.pulse.core.telemetry.Special
import dev.mascwa.pulse.core.telemetry.SpecialGame
import dev.mascwa.pulse.data.game.SpecialGameStore
import dev.mascwa.pulse.navigation.Routes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Calendar
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The **always-on game overlay** — a floating window that draws over other apps (and the home screen) so the
 * whole S.P.E.C.I.A.L. survival game keeps running and is playable with Pulse closed. It surfaces the live
 * conditions (six needs), character vitals, the wasteland day, active objectives, and the core loop itself:
 * VENTURE into an encounter and resolve it by tapping a stat-gated choice (odds shown), SCAVENGE for loot,
 * and tend your needs with the self-care buttons — all without opening the app.
 *
 * Design, mirroring [dev.mascwa.pulse.data.perception.AmbientSensingService]:
 *  - **Opt-in, default OFF** (`AppSettings.gameOverlay`); [MainActivity] starts/stops it in step with the toggle.
 *  - Needs the user-granted **draw-over-other-apps** permission; the service no-ops (stops) without it.
 *  - A raw [WindowManager] `TYPE_APPLICATION_OVERLAY` view (programmatic Views — robust), non-focusable so the
 *    rest of the phone stays interactive; only the overlay's own controls take touch. Draggable by its header,
 *    tap the header to collapse to a compact bubble / expand.
 *  - A foreground service (persists in the background) with a low-importance STOP notification.
 *  - Fully defensive — any window / view / store hiccup can never crash the app.
 *
 * NOTE: the overlay is a couch-play surface — VENTURE/SCAVENGE here go through the store directly, which is
 * NOT the geo-gated path the in-app SPECIAL tab uses. It's the "play from anywhere" convenience the overlay
 * exists for; the geo-gated + gesture experience still lives in the app (the OPEN button jumps there).
 */
class GameOverlayService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var windowManager: WindowManager? = null
    private var root: LinearLayout? = null
    private var params: WindowManager.LayoutParams? = null

    // View handles updated on each state emission.
    private var titleView: TextView? = null
    private var dayView: TextView? = null
    private var vitalsView: TextView? = null
    private var conditionView: TextView? = null
    private val needValueViews = HashMap<NeedKind, TextView>()
    private var encounterBox: LinearLayout? = null
    private var scavengeBox: LinearLayout? = null
    private var questsBox: LinearLayout? = null
    private var body: LinearLayout? = null

    private var collapsed = false
    private var observing = false
    private var lastStarted = 0L
    private var lastCharacter: Character? = null
    private var lastResolutionText: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            teardown(); stopForegroundCompat(); stopSelf(); return START_NOT_STICKY
        }
        if (!Settings.canDrawOverlays(this)) {
            stopForegroundCompat(); stopSelf(); return START_NOT_STICKY
        }
        runCatching { startForegroundCompat() }
        if (root == null) runCatching { buildAndAdd() }
        observe()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        teardown(); scope.cancel(); super.onDestroy()
    }

    private fun container() = runCatching { (application as PulseApplication).container }.getOrNull()
    private fun store(): SpecialGameStore? = container()?.specialGameStore

    // ----- live state -----

    private fun observe() {
        if (observing) return // onStartCommand can fire on every foreground return — collect only once.
        val store = store() ?: return
        observing = true
        scope.launch { runCatching { store.lifeFlow.collect { p -> runCatching { render(p) } } } }
        scope.launch {
            runCatching {
                store.characterFlow.collect { c ->
                    lastCharacter = c
                    runCatching { renderVitals(c); renderEncounter(); renderScavenge() }
                }
            }
        }
        scope.launch { runCatching { store.startedFlow.collect { s -> lastStarted = s; runCatching { renderDay() } } } }
        scope.launch {
            runCatching {
                store.resolutionFlow.collect { r ->
                    if (r != null) { lastResolutionText = resolutionLine(r); runCatching { renderEncounter() } }
                }
            }
        }
        scope.launch { runCatching { store.lastScavengeFlow.collect { runCatching { renderScavenge() } } } }
        container()?.questStore?.let { qs ->
            scope.launch { runCatching { qs.quests.collect { q -> runCatching { renderQuests(q) } } } }
        }
        // Keep decay + the scavenge cooldown moving even with the app closed (a passive display tick).
        scope.launch {
            while (isActive) {
                runCatching { store.tickNeeds() }
                runCatching { renderDay() }
                runCatching { renderScavenge() }
                delay(TICK_MS)
            }
        }
    }

    private fun render(profile: LifeProfile) {
        val overall = LifeStats.overallCondition(profile)
        titleView?.text = if (collapsed) "PIP-BOY  $overall%" else "PIP-BOY  ·  CND $overall%"
        val states = LifeStats.needStates(profile)
        val urgent = states.filter { it.tier.isConcern }.minByOrNull { it.value }
        conditionView?.text = if (urgent != null) "${urgent.kind.label}: ${urgent.condition} (${urgent.value})" else "All needs steady."
        for (st in states) {
            needValueViews[st.kind]?.apply {
                text = "${st.kind.label.uppercase().take(4)}  ${st.value}"
                setTextColor(tierColor(st.tier))
            }
        }
    }

    private fun renderVitals(c: Character) {
        val pts = if (c.unspent > 0) "   ＋${c.unspent} pts" else ""
        vitalsView?.text = "LVL ${c.level}   HP ${c.hp}/${c.maxHp}   ⚑ ${c.caps}$pts"
    }

    private fun renderDay() {
        val started = lastStarted
        if (started <= 0L) { dayView?.text = ""; return }
        val hour = runCatching { Calendar.getInstance().get(Calendar.HOUR_OF_DAY) }.getOrDefault(12)
        dayView?.text = runCatching { GameClock.banner(started, System.currentTimeMillis(), hour) }.getOrDefault("")
    }

    /** The encounter section: an active fight shows its prompt + a button per stat-gated choice (with odds);
     *  otherwise the last outcome + a VENTURE button. Rebuilt whenever the character or a resolution changes. */
    private fun renderEncounter() {
        val box = encounterBox ?: return
        val store = store() ?: return
        val c = lastCharacter ?: store.characterFlow.value
        box.removeAllViews()
        val enc = runCatching { store.encounterFor(c) }.getOrNull()
        if (enc != null) {
            box.addView(label(enc.title, GREEN, 11f, bold = true))
            box.addView(label(enc.prompt, DIM, 9.5f))
            enc.choices.forEachIndexed { i, ch ->
                val od = odds(c, ch)
                box.addView(pill("${gate(ch)} ${ch.text}  · $od", oddsColor(od)) { runCatching { store.choose(i) } })
            }
        } else {
            lastResolutionText?.let { box.addView(label(it, DIM, 10f)) }
            box.addView(pill("▸ VENTURE OUT", GREEN) { runCatching { store.venture(); lastResolutionText = null } })
        }
    }

    /** The scavenge section: a SCAVENGE button that dims to a cooldown countdown, plus the last haul readout. */
    private fun renderScavenge() {
        val box = scavengeBox ?: return
        val store = store() ?: return
        box.removeAllViews()
        val now = System.currentTimeMillis()
        val remaining = SpecialGameStore.SCAVENGE_COOLDOWN_MS - (now - store.lastScavengeMsFlow.value)
        if (remaining > 0) {
            box.addView(
                label("SEARCHED · ${(remaining / 1000)}s", DIM, 10f).apply {
                    background = buttonBg(); setPadding(dp(6), dp(6), dp(6), dp(6))
                },
            )
        } else {
            box.addView(pill("⚒ SCAVENGE THE AREA", GREEN) { runCatching { store.scavenge() } })
        }
        store.lastScavengeFlow.value?.let { haul ->
            val line = if (haul.isEmpty()) "Nothing but dust." else haul.entries.joinToString(" · ") { "${it.value}× ${it.key.replace('_', ' ')}" }
            box.addView(label("Found: $line", AMBER, 9.5f))
        }
    }

    /** The objectives section: the top active quests with progress. */
    private fun renderQuests(quests: List<QuestView>) {
        val box = questsBox ?: return
        box.removeAllViews()
        if (quests.isEmpty()) { box.addView(label("No active objectives.", DIM, 9.5f)); return }
        quests.take(3).forEach { q ->
            val done = q.done.coerceAtMost(q.quest.target)
            val col = when (q.quest.kind) {
                QuestKind.MAIN -> Color.parseColor("#E0C24A")
                QuestKind.DAILY -> GREEN
                else -> Color.parseColor("#D8D8D8")
            }
            box.addView(label("${q.quest.kind.label} · ${q.quest.title}", col, 10f, bold = true))
            box.addView(label("${q.quest.brief}  ($done/${q.quest.target})${if (q.complete) "  ✓" else ""}", DIM, 9f))
        }
    }

    // --- odds / labels ---

    private fun gate(ch: Choice): String = ch.stat?.let { "[${it.name.take(3)} ${ch.difficulty}]" } ?: "[SAFE]"

    private fun odds(c: Character, ch: Choice): String {
        val stat = ch.stat ?: return "SURE"
        val sv = c.stat(stat); val luck = c.stat(Special.LUCK)
        val wins = (1..SpecialGame.DIE).count { SpecialGame.check(sv, ch.difficulty, luck, it).success }
        val pct = wins * 100 / SpecialGame.DIE
        return when {
            pct >= 85 -> "SURE"; pct >= 65 -> "LIKELY"; pct >= 45 -> "EVEN"; pct >= 25 -> "RISKY"; else -> "LONGSHOT"
        }
    }

    private fun oddsColor(o: String): Int = when (o) { "SURE", "LIKELY" -> GREEN; "EVEN" -> AMBER; else -> RED }

    private fun resolutionLine(r: Resolution): String {
        val tag = if (r.crit) "✦ CRIT! " else if (r.success) "✓ " else "✗ "
        return tag + r.outcome.text
    }

    private fun tierColor(t: NeedTier): Int = when {
        t.isDire -> RED; t.isConcern -> AMBER; else -> GREEN
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

        // Header — drag handle + tap-to-collapse; OPEN jumps to the app, ✕ dismisses.
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val title = TextView(this).apply {
            text = "PIP-BOY"; setTextColor(GREEN); textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        titleView = title
        val open = TextView(this).apply {
            text = "OPEN"; setTextColor(GREEN); textSize = 10f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(dp(6), dp(2), dp(6), dp(2)); setOnClickListener { openGame() }
        }
        val close = TextView(this).apply {
            text = "  ✕"; setTextColor(DIM); textSize = 13f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(dp(8), dp(2), dp(2), dp(2)); setOnClickListener { stopEverything() }
        }
        header.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(open); header.addView(close)
        rootView.addView(header, matchWide())

        // Body — the whole game.
        val bodyView = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(6), 0, 0) }
        body = bodyView

        dayView = label("", GREEN, 10f, bold = true); bodyView.addView(dayView)
        vitalsView = label("", DIM, 10f); bodyView.addView(vitalsView)
        conditionView = label("", DIM, 10f); bodyView.addView(conditionView)

        // Needs — two rows of three value chips.
        val needsWrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(6), 0, dp(4)) }
        val order = NeedKind.entries.toList()
        var i = 0
        while (i < order.size) {
            val rowLayout = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            var col = 0
            while (col < 3 && i < order.size) {
                val kind = order[i]
                val chip = label("${kind.label.uppercase().take(4)}  --", GREEN, 10f)
                needValueViews[kind] = chip
                rowLayout.addView(chip, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                i++; col++
            }
            needsWrap.addView(rowLayout, matchWide())
        }
        bodyView.addView(needsWrap)

        // Care buttons — two rows of three.
        var j = 0
        while (j < order.size) {
            val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            var col = 0
            while (col < 3 && j < order.size) {
                btnRow.addView(
                    careButton(order[j]),
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(2), dp(2), dp(2), dp(2)) },
                )
                j++; col++
            }
            bodyView.addView(btnRow, matchWide())
        }

        // Encounter loop.
        bodyView.addView(divider())
        bodyView.addView(sectionHeader("■ WASTELAND"))
        encounterBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        bodyView.addView(encounterBox, matchWide())
        scavengeBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(4), 0, 0) }
        bodyView.addView(scavengeBox, matchWide())

        // Objectives.
        bodyView.addView(divider())
        bodyView.addView(sectionHeader("■ OBJECTIVES"))
        questsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        bodyView.addView(questsBox, matchWide())

        rootView.addView(bodyView, matchWide())
        root = rootView

        val lp = WindowManager.LayoutParams(
            dp(248),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START; x = dp(12); y = dp(96) }
        params = lp
        attachDrag(header, lp, wm, rootView)
        runCatching { wm.addView(rootView, lp) }
    }

    private fun careButton(kind: NeedKind): TextView = pill(kind.verb, GREEN) {
        val s = store() ?: return@pill
        when (kind) {
            NeedKind.HYDRATION -> s.drink()
            NeedKind.NOURISHMENT -> s.eat()
            NeedKind.ENERGY -> s.rest()
            NeedKind.HYGIENE -> s.wash()
            NeedKind.BRUSHING -> s.brushTeeth()
            NeedKind.FLOSSING -> s.floss()
        }
        runCatching { s.tickNeeds() }
    }.apply {
        gravity = Gravity.CENTER
        // Sit in the care-button row, not full-width — the row supplies weighted LayoutParams on addView.
        layoutParams = null
    }

    /** A rounded pill button. Full-width by default; care buttons override the layout to sit in a row. */
    private fun pill(text: String, color: Int, onClick: () -> Unit): TextView = TextView(this).apply {
        this.text = text
        setTextColor(color); textSize = 10.5f
        typeface = android.graphics.Typeface.MONOSPACE
        setPadding(dp(6), dp(6), dp(6), dp(6))
        background = buttonBg()
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(3), 0, 0) }
        setOnClickListener { onClick() }
    }

    private fun label(text: String, color: Int, size: Float, bold: Boolean = false): TextView = TextView(this).apply {
        this.text = text; setTextColor(color); textSize = size
        typeface = android.graphics.Typeface.MONOSPACE
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, dp(2), 0, 0)
    }

    private fun sectionHeader(text: String): TextView =
        label(text, Color.parseColor("#4C9A50"), 9f, bold = true).apply { setPadding(0, dp(6), 0, dp(2)) }

    private fun divider(): View = View(this).apply {
        setBackgroundColor(Color.parseColor("#24402A"))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply { setMargins(0, dp(6), 0, 0) }
    }

    private fun matchWide() = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    private fun attachDrag(header: View, lp: WindowManager.LayoutParams, wm: WindowManager, rootView: View) {
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var dragged = false
        header.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y; dragged = false; true }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - downX).roundToInt(); val dy = (e.rawY - downY).roundToInt()
                    if (abs(dx) > SLOP || abs(dy) > SLOP) dragged = true
                    lp.x = startX + dx; lp.y = startY + dy
                    runCatching { wm.updateViewLayout(rootView, lp) }; true
                }
                MotionEvent.ACTION_UP -> { if (!dragged) toggleCollapse(); true }
                else -> false
            }
        }
    }

    private fun toggleCollapse() {
        collapsed = !collapsed
        body?.visibility = if (collapsed) View.GONE else View.VISIBLE
        store()?.let { runCatching { render(it.lifeFlow.value) } }
    }

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
        container()?.let { c -> scope.launch { runCatching { c.settingsRepository.update { it.copy(gameOverlay = false) } } } }
        teardown(); stopForegroundCompat(); stopSelf()
    }

    private fun teardown() {
        root?.let { r -> runCatching { windowManager?.removeView(r) } }
        root = null; needValueViews.clear()
    }

    // ----- drawables / dimens -----

    private fun panelBg() = GradientDrawable().apply {
        setColor(Color.parseColor("#E6060A06")); cornerRadius = dp(10).toFloat(); setStroke(dp(1), Color.parseColor("#2E7D32"))
    }

    private fun buttonBg() = GradientDrawable().apply {
        setColor(Color.parseColor("#12351C")); cornerRadius = dp(6).toFloat(); setStroke(dp(1), Color.parseColor("#3C8C42"))
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
        val openIntent = PendingIntent.getActivity(
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
            .setContentText("Your survival game floats over other apps. Tap to open, or Stop.")
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stop)
            .setOngoing(true).setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    private fun ensureChannel() {
        val mgr = getSystemService(NotificationManager::class.java) ?: return
        if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Game overlay", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "The floating Pip-Boy survival overlay"; setShowBadge(false)
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

        fun start(context: Context) {
            if (!Settings.canDrawOverlays(context)) return
            runCatching {
                androidx.core.content.ContextCompat.startForegroundService(context, Intent(context, GameOverlayService::class.java))
            }
        }

        fun stop(context: Context) {
            runCatching { context.startService(Intent(context, GameOverlayService::class.java).setAction(ACTION_STOP)) }
        }
    }
}
