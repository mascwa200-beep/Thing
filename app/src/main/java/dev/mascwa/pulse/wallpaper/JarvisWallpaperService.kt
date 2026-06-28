package dev.mascwa.pulse.wallpaper

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
import dev.mascwa.pulse.PulseApplication
import dev.mascwa.pulse.core.util.Formatters
import dev.mascwa.pulse.data.weather.WeatherCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * The J.A.R.V.I.S. arc-reactor live wallpaper — the in-app Stark HUD reactor ([HudReactor]) ported to a
 * raw [Canvas] so it can BE the home screen, with a glanceable Pulse data readout (clock · active objective ·
 * weather · top market mover) underneath. "Controlled by the Pulse app": it reads the same on-device cached
 * data the widgets do (no network of its own beyond the repos' cache), and takes its accent / AMOLED look
 * straight from the user's Pulse settings.
 *
 * Frugal + defensive by design: it draws only while visible (paused otherwise), refreshes data at most once a
 * minute on a background coroutine, holds no bitmaps/particle arrays (everything is procedural), and swallows
 * any surface/IO error rather than crashing the home screen.
 */
class JarvisWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = ReactorEngine()

    /** A snapshot of the glanceable data — built on IO, read on the draw thread (volatile, never blocks draw). */
    private data class Snapshot(
        val objective: String = "",
        val weather: String = "",
        val mover: String = "",
        val moverUp: Boolean = true,
    )

    private inner class ReactorEngine : WallpaperService.Engine() {
        private val handler = Handler(Looper.getMainLooper())
        private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        private val startMs = SystemClock.uptimeMillis()

        private var visible = false
        private var width = 0
        private var height = 0
        private var lastDataMs = 0L

        @Volatile private var snapshot = Snapshot()
        @Volatile private var bgColor = VOID
        @Volatile private var accentColor = SKY

        private val frame = Runnable { drawFrame() }

        override fun onVisibilityChanged(visible: Boolean) {
            this.visible = visible
            if (visible) {
                refreshData(force = true)
                scheduleNext()
            } else {
                handler.removeCallbacks(frame)
            }
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            this.width = width
            this.height = height
            drawFrame()
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            visible = false
            handler.removeCallbacks(frame)
        }

        override fun onDestroy() {
            handler.removeCallbacks(frame)
            scope.cancel()
        }

        private fun scheduleNext() {
            handler.removeCallbacks(frame)
            if (visible) handler.postDelayed(frame, FRAME_MS)
        }

        private fun drawFrame() {
            // Throttled data refresh (≤ once / minute) so the readout stays live without a hot loop.
            val now = SystemClock.uptimeMillis()
            if (now - lastDataMs > DATA_INTERVAL_MS) refreshData(force = false)

            val holder = surfaceHolder
            var canvas: Canvas? = null
            try {
                canvas = holder.lockCanvas()
                if (canvas != null) runCatching { render(canvas) }
            } catch (_: Throwable) {
                // Surface not ready / transient — skip this frame.
            } finally {
                if (canvas != null) runCatching { holder.unlockCanvasAndPost(canvas) }
            }
            scheduleNext()
        }

        private fun refreshData(force: Boolean) {
            lastDataMs = SystemClock.uptimeMillis()
            scope.launch {
                val app = applicationContext as? PulseApplication ?: return@launch
                val c = app.container
                val s = runCatching { c.settingsRepository.current() }.getOrNull()

                // Look straight from the user's Pulse appearance settings.
                bgColor = if (s?.amoledBlack == true) BLACK else VOID
                accentColor = s?.accentColor?.argb?.toInt() ?: SKY

                val objective = s?.let { st ->
                    st.waypoints.firstOrNull { it.id == st.activeWaypointId }?.label
                }.orEmpty().take(26)

                val weather = runCatching {
                    val saved = s?.let { it.savedLocations.getOrNull(it.selectedLocationIndex) ?: it.savedLocations.firstOrNull() }
                    // Cached only (force=false) and a saved location only — a wallpaper must never wake GPS.
                    saved?.let { loc ->
                        c.weatherRepository.fetch(loc.latitude, loc.longitude, loc.name, force = false).data
                    }?.let { wd ->
                        wd.current?.let { cur ->
                            "${Formatters.number(cur.temperature, 0)}${wd.tempUnitSymbol} · ${WeatherCode.describe(cur.weatherCode)}"
                        }
                    }
                }.getOrNull().orEmpty()

                var mover = ""
                var moverUp = true
                runCatching {
                    val quotes = c.marketsRepository.fetchWatchlist(force = false).data.orEmpty()
                    val top = quotes.filter { it.changePercent != null }
                        .maxByOrNull { abs(it.changePercent ?: 0.0) }
                    if (top?.changePercent != null) {
                        val pct = top.changePercent ?: 0.0
                        moverUp = pct >= 0
                        mover = "${top.label.take(12)}  ${if (moverUp) "+" else ""}${"%.2f".format(pct)}%"
                    }
                }

                snapshot = Snapshot(objective, weather, mover, moverUp)
            }
        }

        private fun render(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return
            canvas.drawColor(bgColor)

            val elapsed = SystemClock.uptimeMillis() - startMs
            val spin = (elapsed % 24000L) / 24000f * 360f
            val spin2 = -((elapsed % 30000L) / 30000f * 360f)
            val pp = (elapsed % 3000L).toFloat()
            val pulse = if (pp < 1500f) pp / 1500f else (3000f - pp) / 1500f

            val cx = w / 2f
            val cy = h * 0.32f
            val rMax = minOf(w, h) * 0.30f
            drawReactor(canvas, cx, cy, rMax, spin, spin2, pulse, SKY, accentColor)

            val unit = minOf(w, h)
            val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textAlign = Paint.Align.CENTER
                typeface = Typeface.MONOSPACE
            }

            // Clock — locale-aware (honours the device 12/24h setting).
            val now = java.util.Date()
            text.color = withFullAlpha(SKY)
            text.textSize = unit * 0.12f
            canvas.drawText(
                android.text.format.DateFormat.getTimeFormat(this@JarvisWallpaperService).format(now),
                cx, h * 0.575f, text,
            )
            text.color = withAlpha(INK, 0.8f)
            text.textSize = unit * 0.032f
            canvas.drawText(
                android.text.format.DateFormat.getMediumDateFormat(this@JarvisWallpaperService).format(now).uppercase(),
                cx, h * 0.61f, text,
            )

            // Glanceable Pulse readout.
            val snap = snapshot
            text.textSize = unit * 0.030f
            var y = h * 0.675f
            val lineH = unit * 0.046f
            if (snap.objective.isNotBlank()) {
                text.color = withAlpha(INK, 0.92f)
                canvas.drawText("OBJ  ${snap.objective}", cx, y, text); y += lineH
            }
            if (snap.weather.isNotBlank()) {
                text.color = withAlpha(INK, 0.92f)
                canvas.drawText("WX   ${snap.weather}", cx, y, text); y += lineH
            }
            if (snap.mover.isNotBlank()) {
                text.color = withFullAlpha(if (snap.moverUp) POSITIVE else NEGATIVE)
                canvas.drawText("MKT  ${snap.mover}", cx, y, text); y += lineH
            }

            text.color = withAlpha(MUTED, 0.7f)
            text.textSize = unit * 0.021f
            canvas.drawText("J.A.R.V.I.S.  ·  PULSE", cx, h * 0.94f, text)
        }
    }

    /** Port of [dev.mascwa.pulse.feature.jarvis.HudReactor] to a framework Canvas (same angles/ratios). */
    private fun drawReactor(
        canvas: Canvas, cx: Float, cy: Float, rMax: Float,
        spin: Float, spin2: Float, pulse: Float, primary: Int, accent: Int,
    ) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)

        // Fine tick ring (longer every 30°).
        p.style = Paint.Style.STROKE
        p.strokeWidth = (rMax * 0.006f).coerceAtLeast(1f)
        p.color = withAlpha(primary, 0.22f)
        var deg = 0
        while (deg < 360) {
            val a = Math.toRadians(deg.toDouble())
            val inner = if (deg % 30 == 0) rMax * 0.88f else rMax * 0.95f
            canvas.drawLine(
                cx + (inner * sin(a)).toFloat(), cy - (inner * cos(a)).toFloat(),
                cx + (rMax * sin(a)).toFloat(), cy - (rMax * cos(a)).toFloat(), p,
            )
            deg += 6
        }

        arcRing(canvas, p, cx, cy, rMax * 0.84f, withAlpha(primary, 0.75f), rMax * 0.022f, spin, 3, 0.66f)
        p.style = Paint.Style.STROKE
        p.color = withAlpha(primary, 0.22f)
        p.strokeWidth = rMax * 0.008f
        canvas.drawCircle(cx, cy, rMax * 0.66f, p)
        arcRing(canvas, p, cx, cy, rMax * 0.66f, withAlpha(accent, 0.85f), rMax * 0.018f, spin2, 6, 0.4f)
        arcRing(canvas, p, cx, cy, rMax * 0.46f, withAlpha(primary, 0.55f), rMax * 0.016f, spin * 1.6f, 4, 0.5f)

        // Cardinal bright nodes on the outer ring.
        p.style = Paint.Style.FILL
        p.color = withFullAlpha(accent)
        for (d in intArrayOf(0, 90, 180, 270)) {
            val a = Math.toRadians(d.toDouble())
            canvas.drawCircle(
                cx + (rMax * 0.84f * sin(a)).toFloat(), cy - (rMax * 0.84f * cos(a)).toFloat(), rMax * 0.02f, p,
            )
        }

        // Arc-reactor core — layered glow + pulsing centre + tri-coil hint.
        val coreR = rMax * 0.26f
        p.color = withAlpha(primary, 0.10f); canvas.drawCircle(cx, cy, coreR * 1.5f, p)
        p.color = withAlpha(primary, 0.18f); canvas.drawCircle(cx, cy, coreR, p)
        p.color = withAlpha(accent, 0.25f + 0.35f * pulse); canvas.drawCircle(cx, cy, coreR * (0.55f + 0.12f * pulse), p)
        p.color = withAlpha(primary, 0.9f); canvas.drawCircle(cx, cy, coreR * 0.18f, p)

        p.style = Paint.Style.STROKE
        p.strokeWidth = rMax * 0.012f
        p.color = withAlpha(primary, 0.5f)
        val triR = coreR * 0.7f
        val tri = Path()
        for (i in 0..2) {
            val a = Math.toRadians(90.0 + i * 120.0)
            val x = cx + (triR * cos(a)).toFloat()
            val yy = cy - (triR * sin(a)).toFloat()
            if (i == 0) tri.moveTo(x, yy) else tri.lineTo(x, yy)
        }
        tri.close()
        canvas.drawPath(tri, p)
    }

    private fun arcRing(
        canvas: Canvas, p: Paint, cx: Float, cy: Float, radius: Float,
        color: Int, width: Float, rotation: Float, segments: Int, fill: Float,
    ) {
        p.style = Paint.Style.STROKE
        p.color = color
        p.strokeWidth = width
        val step = 360f / segments
        val sweep = step * fill
        val rect = RectF(cx - radius, cy - radius, cx + radius, cy + radius)
        for (i in 0 until segments) {
            canvas.drawArc(rect, rotation + i * step, sweep, false, p)
        }
    }

    private companion object {
        // NIGHTWIRE palette (mirrors ui/theme/Color.kt) as ARGB ints. Not `const` — `.toInt()` isn't a
        // compile-time constant (the hex literals are Long; we want their low-32-bit signed-int bit pattern).
        val VOID = 0xFF05070D.toInt()
        val BLACK = 0xFF000000.toInt()
        val SKY = 0xFF5AD1FF.toInt()
        val INK = 0xFFE6EFFA.toInt()
        val MUTED = 0xFF5E708C.toInt()
        val POSITIVE = 0xFF46F9A0.toInt()
        val NEGATIVE = 0xFFFF4D6D.toInt()

        const val FRAME_MS = 33L            // ~30 fps while visible
        const val DATA_INTERVAL_MS = 60_000L

        fun withAlpha(base: Int, a: Float): Int =
            (base and 0x00FFFFFF) or (((a.coerceIn(0f, 1f) * 255f).toInt()) shl 24)

        fun withFullAlpha(base: Int): Int = base or 0xFF000000.toInt()
    }
}
