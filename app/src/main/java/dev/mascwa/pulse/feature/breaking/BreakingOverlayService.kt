package dev.mascwa.pulse.feature.breaking

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.mascwa.pulse.R
import kotlin.math.abs

/**
 * The breaking-news card, floating over whatever you are doing — without taking it away from you.
 *
 * ⚠️ **This replaces a takeover that destroyed your place in the app.** The previous path started
 * [BreakingNewsActivity] with `FLAG_ACTIVITY_CLEAR_TASK`, which wipes the back stack: dismissing it
 * could not return you anywhere, because there was no longer anywhere to return to. It was also
 * opaque and full-screen for a single headline. Three properties fix that, and all three are load-
 * bearing:
 *
 * 1. **`FLAG_NOT_FOCUSABLE`** — the window never takes input focus, so the app behind keeps typing,
 *    scrolling and playing exactly as it was.
 * 2. **`FLAG_NOT_TOUCH_MODAL`** — touches outside this card go **through** to whatever is behind it.
 *    That is the "work around the overlay" half: you do not have to deal with it to carry on.
 * 3. **It is not an Activity.** Nothing is launched, nothing is navigated, no task is cleared. The
 *    thing you were doing is not interrupted in the Android sense at all — a card simply appears
 *    over it.
 *
 * Programmatic Views rather than Compose, which is this repo's rule for overlay windows: a
 * `ComposeView` in a raw `WindowManager` window needs a lifecycle/saved-state owner attached by
 * hand, and getting that subtly wrong fails at runtime on a device rather than in CI.
 *
 * A foreground service because a window has to outlive the call that created it, and because this is
 * started from a background worker. Its notification is deliberately the lowest importance the
 * platform allows — it is the receipt for a visible window, not an alert. The card is the alert.
 */
class BreakingOverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var card: View? = null
    private val handler = Handler(Looper.getMainLooper())
    private val autoDismiss = Runnable { dismiss() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Hoisted above everything that can fail: a foreground service that reaches its timeout
        // without calling this is killed with ForegroundServiceDidNotStartInTimeException.
        ensureChannel()
        ServiceCompat.startForeground(
            this, NOTIF_ID, ongoing(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                0
            },
        )

        if (intent?.action == ACTION_DISMISS) {
            dismiss()
            return START_NOT_STICKY
        }

        val headline = intent?.getStringExtra(EXTRA_HEADLINE)?.takeIf { it.isNotBlank() }
        val source = intent?.getStringExtra(EXTRA_SOURCE).orEmpty()
        val query = intent?.getStringExtra(EXTRA_QUERY)?.takeIf { it.isNotBlank() } ?: headline
        if (headline == null || !Settings.canDrawOverlays(this)) {
            // No grant means no window — and no pretending. The caller falls back to the
            // notification path, which is the platform ceiling without the permission.
            stopSelf()
            return START_NOT_STICKY
        }

        show(headline, source, query.orEmpty())
        // A card nobody dismisses must not sit over the phone forever. Long enough to read and come
        // back to; short enough that an unattended phone clears itself.
        handler.removeCallbacks(autoDismiss)
        handler.postDelayed(autoDismiss, AUTO_DISMISS_MS)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(autoDismiss)
        removeCard()
        super.onDestroy()
    }

    // ---- the window ----------------------------------------------------------------------------

    private fun show(headline: String, source: String, query: String) {
        removeCard() // a second story replaces the first rather than stacking
        val wm = getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: run { stopSelf(); return }
        windowManager = wm

        val view = buildCard(headline, source) { dismiss() }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            // The three flags this whole class exists for — see the KDoc.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(72)
        }

        // Drag by the card so it can be moved off anything it happens to be covering — the other
        // half of "work around it". Threshold so a tap on a button is never read as a drag.
        var downY = 0
        var startY = 0
        var dragging = false
        view.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { downY = e.rawY.toInt(); startY = params.y; dragging = false; false }
                MotionEvent.ACTION_MOVE -> {
                    val dy = e.rawY.toInt() - downY
                    if (dragging || abs(dy) > dp(8)) {
                        dragging = true
                        params.y = (startY + dy).coerceAtLeast(dp(16))
                        runCatching { wm.updateViewLayout(view, params) }
                        true
                    } else {
                        false
                    }
                }
                else -> false
            }
        }

        if (runCatching { wm.addView(view, params) }.isFailure) { stopSelf(); return }
        card = view

        view.findViewById<View>(ID_OPEN)?.setOnClickListener {
            // A real destination you can back out of — no CLEAR_TASK, so your place survives.
            runCatching {
                startActivity(
                    Intent(this, BreakingNewsActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        putExtra(BreakingNewsActivity.EXTRA_HEADLINE, headline)
                        putExtra(BreakingNewsActivity.EXTRA_QUERY, query)
                    },
                )
            }
            dismiss()
        }
    }

    private fun removeCard() {
        card?.let { v -> runCatching { windowManager?.removeView(v) } }
        card = null
    }

    private fun dismiss() {
        removeCard()
        stopSelf()
    }

    // ---- the card, drawn by hand ----------------------------------------------------------------

    private fun buildCard(headline: String, source: String, onClose: () -> Unit): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(14), dp(12), dp(10), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dp(4).toFloat()
                // Not fully opaque: the owner asked for a transparent card, and seeing a little of
                // what is behind it is what stops it reading as a takeover.
                setColor(Color.argb(232, 8, 8, 10))
                setStroke(dp(1), ACCENT)
            }
            layoutParams = ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { setMargins(dp(12), 0, dp(12), 0) }
        }

        // The LCARS rail, so the card is recognisably the same console as everything else.
        root.addView(
            View(this).apply {
                setBackgroundColor(ACCENT)
                layoutParams = LinearLayout.LayoutParams(dp(4), ViewGroup.LayoutParams.MATCH_PARENT)
                    .apply { rightMargin = dp(12) }
            },
        )

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        column.addView(
            TextView(this).apply {
                text = if (source.isBlank()) "BREAKING" else "BREAKING · ${source.uppercase()}"
                setTextColor(ACCENT)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                letterSpacing = 0.14f
                maxLines = 1
            },
        )
        column.addView(
            TextView(this).apply {
                text = headline
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                // One story, in full. The owner asked for this card to carry that one story and
                // nothing else, so it is not truncated to a single line.
                maxLines = 4
                setPadding(0, dp(4), 0, dp(8))
            },
        )
        column.addView(
            TextView(this).apply {
                id = ID_OPEN
                text = "FULL COVERAGE  ▸"
                setTextColor(ACCENT)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                letterSpacing = 0.1f
                setPadding(0, dp(2), dp(8), dp(2))
            },
        )
        root.addView(column)

        root.addView(
            TextView(this).apply {
                text = "✕"
                setTextColor(Color.argb(190, 255, 255, 255))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                setPadding(dp(10), dp(2), dp(6), dp(10))
                setOnClickListener { onClose() }
            },
        )
        return root
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // ---- the receipt ----------------------------------------------------------------------------

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = getSystemService(NotificationManager::class.java) ?: return
        if (mgr.getNotificationChannel(CHANNEL) != null) return
        mgr.createNotificationChannel(
            NotificationChannel(CHANNEL, "Breaking news card", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Shown only while a breaking-news card is on screen."
                setShowBadge(false)
            },
        )
    }

    private fun ongoing(): Notification =
        NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_pulse)
            .setContentTitle("Breaking news card")
            .setContentText("Tap the card's ✕ to dismiss it.")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setSilent(true)
            .setOngoing(true)
            .build()

    companion object {
        const val EXTRA_HEADLINE = "headline"
        const val EXTRA_SOURCE = "source"
        const val EXTRA_QUERY = "query"
        const val ACTION_DISMISS = "dev.mascwa.pulse.BREAKING_OVERLAY_DISMISS"

        private const val CHANNEL = "breaking_overlay"
        // ⚠️ Was 7401 — the SAME id as SensoriumService's ongoing notification, and the two run at the
        // same time. See NotifId for what that did to both of them.
        private const val NOTIF_ID = dev.mascwa.pulse.notifications.NotifId.FGS_BREAKING_OVERLAY
        private const val ID_OPEN = 0x7E51
        private const val AUTO_DISMISS_MS = 5 * 60_000L
        private val ACCENT = Color.parseColor("#FFB000")

        /** True when the window can actually be drawn — the caller falls back when it cannot. */
        fun canShow(context: Context): Boolean = Settings.canDrawOverlays(context)

        fun show(context: Context, headline: String, source: String, query: String) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, BreakingOverlayService::class.java)
                    .putExtra(EXTRA_HEADLINE, headline)
                    .putExtra(EXTRA_SOURCE, source)
                    .putExtra(EXTRA_QUERY, query),
            )
        }
    }
}
