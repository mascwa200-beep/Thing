package dev.mascwa.pulse.wallpaper

import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
import dev.mascwa.pulse.PulseApplication
import dev.mascwa.pulse.core.util.Formatters
import dev.mascwa.pulse.data.news.NewsCategory
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
 * The J.A.R.V.I.S. arc-reactor live wallpaper — an advanced Stark-HUD home screen drawn to a raw [Canvas].
 * A multi-layer reactor (graduated rings, a rotating dashed ring, gear teeth, counter-rotating arc rings,
 * twin hexagons, orbiting satellites and a glowing pulsing core) sits over a cyan glow, a faint grid and
 * corner HUD brackets, with a live Pulse readout (clock · objective · weather · top market movers · a news
 * headline) and a bottom telemetry strip (battery · link · sync). Reads only on-device cached data and the
 * user's accent / AMOLED settings.
 *
 * Frugal + defensive: draws only while visible, refreshes data ≤ once a minute on a background coroutine,
 * holds no bitmaps (gradients are cached by size/accent; orbit params are static), and swallows any error.
 */
class JarvisWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = ReactorEngine()

    private data class Mover(val text: String, val up: Boolean)

    /** A snapshot of the glanceable data — built on IO, read on the draw thread (volatile, never blocks draw). */
    private data class Snapshot(
        val objective: String = "",
        val weather: String = "",
        val movers: List<Mover> = emptyList(),
        val headline: String = "",
        val battery: Int = -1,
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
        @Volatile private var showReadout = true

        private var glowShader: RadialGradient? = null
        private var vignetteShader: RadialGradient? = null
        private var shaderAccent = 0
        private var shaderW = 0
        private var shaderH = 0

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

                bgColor = if (s?.amoledBlack == true) BLACK else VOID
                accentColor = s?.accentColor?.argb?.toInt() ?: SKY
                showReadout = s?.liveWallpaperReadout ?: true

                val objective = s?.let { st ->
                    st.waypoints.firstOrNull { it.id == st.activeWaypointId }?.label
                }.orEmpty().take(28)

                val weather = runCatching {
                    val saved = s?.let { it.savedLocations.getOrNull(it.selectedLocationIndex) ?: it.savedLocations.firstOrNull() }
                    saved?.let { loc ->
                        c.weatherRepository.fetch(loc.latitude, loc.longitude, loc.name, force = false).data
                    }?.let { wd ->
                        wd.current?.let { cur ->
                            "${Formatters.number(cur.temperature, 0)}${wd.tempUnitSymbol} · ${WeatherCode.describe(cur.weatherCode)}"
                        }
                    }
                }.getOrNull().orEmpty()

                val movers = runCatching {
                    c.marketsRepository.fetchWatchlist(force = false).data.orEmpty()
                        .filter { it.changePercent != null }
                        .sortedByDescending { abs(it.changePercent ?: 0.0) }
                        .take(3)
                        .map { q ->
                            val pct = q.changePercent ?: 0.0
                            Mover("${q.label.take(12)}  ${if (pct >= 0) "+" else ""}${"%.2f".format(pct)}%", pct >= 0)
                        }
                }.getOrDefault(emptyList())

                val headline = runCatching {
                    c.newsRepository.fetchCategory(NewsCategory.TOP, force = false).data?.firstOrNull()?.title
                }.getOrNull().orEmpty().take(40)

                val battery = runCatching {
                    applicationContext.getSystemService(BatteryManager::class.java)
                        ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
                }.getOrDefault(-1)

                snapshot = Snapshot(objective, weather, movers, headline, battery)
            }
        }

        private fun ensureShaders(w: Int, h: Int, cx: Float, cy: Float, unit: Float) {
            if (glowShader != null && shaderAccent == accentColor && shaderW == w && shaderH == h) return
            shaderAccent = accentColor; shaderW = w; shaderH = h
            glowShader = RadialGradient(
                cx, cy, unit * 0.6f,
                intArrayOf(withAlpha(accentColor, 0.20f), withAlpha(SKY, 0.07f), 0x00000000),
                floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP,
            )
            vignetteShader = RadialGradient(
                w / 2f, h / 2f, maxOf(w, h) * 0.72f,
                intArrayOf(0x00000000, 0x00000000, withAlpha(BLACK, 0.6f)),
                floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP,
            )
        }

        private fun render(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return
            canvas.drawColor(bgColor)

            val unit = minOf(w, h)
            val cx = w / 2f
            val cy = h * 0.30f
            val rMax = unit * 0.34f
            ensureShaders(width, height, cx, cy, unit)

            val elapsed = SystemClock.uptimeMillis() - startMs
            val spin = (elapsed % 24000L) / 24000f * 360f
            val spin2 = -((elapsed % 30000L) / 30000f * 360f)
            val pp = (elapsed % 3000L).toFloat()
            val pulse = if (pp < 1500f) pp / 1500f else (3000f - pp) / 1500f

            val p = Paint(Paint.ANTI_ALIAS_FLAG)

            // Background: cyan glow behind the reactor + faint grid.
            glowShader?.let { p.shader = it; canvas.drawRect(0f, 0f, w, h, p); p.shader = null }
            drawGrid(canvas, p, w, h, unit)

            // Corner HUD brackets + top wordmark.
            drawHudFrame(canvas, p, w, h, unit)

            // The reactor + its orbiters.
            drawReactor(canvas, p, cx, cy, rMax, spin, spin2, pulse, elapsed, SKY, accentColor)

            // Vignette over the edges (keeps text legible, frames the scene).
            vignetteShader?.let { p.shader = it; canvas.drawRect(0f, 0f, w, h, p); p.shader = null }

            drawText(canvas, w, h, unit, cx)
        }

        private fun drawGrid(canvas: Canvas, p: Paint, w: Float, h: Float, unit: Float) {
            p.style = Paint.Style.STROKE
            p.strokeWidth = 1f
            p.color = withAlpha(SKY, 0.04f)
            val step = unit * 0.11f
            var x = step
            while (x < w) { canvas.drawLine(x, 0f, x, h, p); x += step }
            var y = step
            while (y < h) { canvas.drawLine(0f, y, w, y, p); y += step }
        }

        private fun drawHudFrame(canvas: Canvas, p: Paint, w: Float, h: Float, unit: Float) {
            val m = unit * 0.06f
            val len = unit * 0.07f
            p.style = Paint.Style.STROKE
            p.strokeWidth = unit * 0.005f
            p.color = withAlpha(accentColor, 0.7f)
            // Four L-shaped corner brackets.
            canvas.drawLine(m, m, m + len, m, p); canvas.drawLine(m, m, m, m + len, p)
            canvas.drawLine(w - m, m, w - m - len, m, p); canvas.drawLine(w - m, m, w - m, m + len, p)
            canvas.drawLine(m, h - m, m + len, h - m, p); canvas.drawLine(m, h - m, m, h - m - len, p)
            canvas.drawLine(w - m, h - m, w - m - len, h - m, p); canvas.drawLine(w - m, h - m, w - m, h - m - len, p)

            val t = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.MONOSPACE }
            t.color = withAlpha(SKY, 0.8f); t.textSize = unit * 0.024f; t.letterSpacing = 0.18f
            canvas.drawText("ARGUS DYNAMICS", m, m + unit * 0.105f, t)
            t.color = withAlpha(MUTED, 0.8f); t.textSize = unit * 0.018f; t.letterSpacing = 0.22f
            canvas.drawText("NIGHTWIRE // PULSE OS", m, m + unit * 0.14f, t)
        }

        private fun drawText(canvas: Canvas, w: Float, h: Float, unit: Float, cx: Float) {
            val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textAlign = Paint.Align.CENTER
                typeface = Typeface.MONOSPACE
            }
            val now = java.util.Date()

            // Clock with a soft glow.
            text.setShadowLayer(unit * 0.03f, 0f, 0f, withAlpha(SKY, 0.85f))
            text.color = withFullAlpha(INK)
            text.textSize = unit * 0.135f
            canvas.drawText(
                android.text.format.DateFormat.getTimeFormat(this@JarvisWallpaperService).format(now),
                cx, h * 0.545f, text,
            )
            text.clearShadowLayer()

            text.color = withAlpha(SKY, 0.85f)
            text.textSize = unit * 0.03f
            text.letterSpacing = 0.2f
            canvas.drawText(
                java.text.SimpleDateFormat("EEEE", java.util.Locale.getDefault()).format(now).uppercase(),
                cx, h * 0.585f, text,
            )
            text.color = withAlpha(MUTED, 0.85f)
            text.textSize = unit * 0.026f
            canvas.drawText(
                android.text.format.DateFormat.getMediumDateFormat(this@JarvisWallpaperService).format(now).uppercase(),
                cx, h * 0.62f, text,
            )
            text.letterSpacing = 0f

            if (showReadout) drawReadout(canvas, w, h, unit, cx, text)

            // Bottom telemetry strip.
            val snap = snapshot
            val bat = if (snap.battery in 0..100) "${snap.battery}%" else "—"
            text.color = withAlpha(MUTED, 0.85f)
            text.textSize = unit * 0.022f
            text.letterSpacing = 0.1f
            canvas.drawText("PWR $bat   ·   LINK ●   ·   NIGHTWIRE   ·   SYNC OK", cx, h * 0.95f, text)
            text.letterSpacing = 0f
        }

        private fun drawReadout(canvas: Canvas, w: Float, h: Float, unit: Float, cx: Float, text: Paint) {
            val snap = snapshot
            val lines = buildList {
                if (snap.objective.isNotBlank()) add("OBJ  ${snap.objective}" to INK)
                if (snap.weather.isNotBlank()) add("WX   ${snap.weather}" to INK)
                snap.movers.forEach { add("MKT  ${it.text}" to if (it.up) POSITIVE else NEGATIVE) }
                if (snap.headline.isNotBlank()) add("NET  ${snap.headline}" to SKY)
            }
            if (lines.isEmpty()) return

            val lineH = unit * 0.044f
            val top = h * 0.655f
            val panelH = lineH * lines.size + unit * 0.04f
            val panelW = w * 0.86f
            // Framed panel behind the readout.
            val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = withAlpha(PANEL, 0.55f) }
            val rect = RectF(cx - panelW / 2f, top - unit * 0.03f, cx + panelW / 2f, top - unit * 0.03f + panelH)
            canvas.drawRoundRect(rect, unit * 0.02f, unit * 0.02f, bg)
            bg.style = Paint.Style.STROKE; bg.strokeWidth = unit * 0.003f; bg.color = withAlpha(accentColor, 0.4f)
            canvas.drawRoundRect(rect, unit * 0.02f, unit * 0.02f, bg)

            text.textSize = unit * 0.029f
            var y = top + unit * 0.012f
            lines.forEach { (line, color) ->
                text.color = withFullAlpha(color)
                canvas.drawText(line, cx, y, text)
                y += lineH
            }
        }

        /** Advanced multi-layer reactor + orbiting satellites. */
        private fun drawReactor(
            canvas: Canvas, p: Paint, cx: Float, cy: Float, rMax: Float,
            spin: Float, spin2: Float, pulse: Float, elapsed: Long, primary: Int, accent: Int,
        ) {
            // Soft outer halo.
            p.style = Paint.Style.FILL
            p.color = withAlpha(primary, 0.05f)
            canvas.drawCircle(cx, cy, rMax * 1.02f, p)

            // Fine graduation ring (longer/brighter every 30°).
            p.style = Paint.Style.STROKE
            p.strokeWidth = (rMax * 0.006f).coerceAtLeast(1f)
            var deg = 0
            while (deg < 360) {
                val a = Math.toRadians(deg.toDouble())
                val major = deg % 30 == 0
                p.color = withAlpha(primary, if (major) 0.5f else 0.2f)
                val inner = if (major) rMax * 0.86f else rMax * 0.93f
                canvas.drawLine(
                    cx + (inner * sin(a)).toFloat(), cy - (inner * cos(a)).toFloat(),
                    cx + (rMax * sin(a)).toFloat(), cy - (rMax * cos(a)).toFloat(), p,
                )
                deg += 5
            }

            // Rotating dashed ring.
            p.color = withAlpha(primary, 0.45f)
            p.strokeWidth = rMax * 0.01f
            p.pathEffect = DashPathEffect(floatArrayOf(rMax * 0.05f, rMax * 0.035f), (elapsed % 4000L) / 4000f * rMax * 0.085f)
            canvas.drawCircle(cx, cy, rMax * 0.8f, p)
            p.pathEffect = null

            // Gear teeth (slow turn).
            p.strokeWidth = rMax * 0.03f
            p.color = withAlpha(primary, 0.28f)
            var gd = 0
            while (gd < 360) {
                val a = Math.toRadians((gd + spin * 0.3f).toDouble())
                val g0 = rMax * 0.9f
                val g1 = rMax * 0.97f
                canvas.drawLine(
                    cx + (g0 * sin(a)).toFloat(), cy - (g0 * cos(a)).toFloat(),
                    cx + (g1 * sin(a)).toFloat(), cy - (g1 * cos(a)).toFloat(), p,
                )
                gd += 30
            }

            // Counter-rotating arc rings.
            arcRing(canvas, p, cx, cy, rMax * 0.84f, withAlpha(primary, 0.75f), rMax * 0.022f, spin, 3, 0.66f)
            arcRing(canvas, p, cx, cy, rMax * 0.66f, withAlpha(accent, 0.85f), rMax * 0.018f, spin2, 6, 0.4f)
            arcRing(canvas, p, cx, cy, rMax * 0.5f, withAlpha(primary, 0.55f), rMax * 0.016f, spin * 1.6f, 4, 0.5f)

            // Twin hexagons (counter-rotating).
            p.color = withAlpha(accent, 0.4f); p.strokeWidth = rMax * 0.01f
            canvas.drawPath(hexPath(cx, cy, rMax * 0.6f, spin * 0.5), p)
            p.color = withAlpha(primary, 0.3f)
            canvas.drawPath(hexPath(cx, cy, rMax * 0.4f, -spin * 0.7 + 30.0), p)

            // Radial spokes.
            p.color = withAlpha(primary, 0.22f); p.strokeWidth = rMax * 0.008f
            for (i in 0 until 8) {
                val a = Math.toRadians((i * 45.0 + spin2 * 0.5))
                canvas.drawLine(
                    cx + (rMax * 0.3f * sin(a)).toFloat(), cy - (rMax * 0.3f * cos(a)).toFloat(),
                    cx + (rMax * 0.62f * sin(a)).toFloat(), cy - (rMax * 0.62f * cos(a)).toFloat(), p,
                )
            }

            // Cardinal bright nodes.
            p.style = Paint.Style.FILL
            p.color = withFullAlpha(accent)
            for (d in intArrayOf(0, 90, 180, 270)) {
                val a = Math.toRadians(d.toDouble())
                canvas.drawCircle(cx + (rMax * 0.84f * sin(a)).toFloat(), cy - (rMax * 0.84f * cos(a)).toFloat(), rMax * 0.022f, p)
            }

            // Orbiting satellites.
            for (o in ORBITS) {
                val a = Math.toRadians(((elapsed % o[1].toLong()) / o[1] * 360f + o[2]).toDouble())
                val r = rMax * o[0]
                val x = cx + (r * sin(a)).toFloat()
                val y = cy - (r * cos(a)).toFloat()
                p.color = withAlpha(primary, 0.22f); canvas.drawCircle(x, y, rMax * 0.05f, p)
                p.color = withFullAlpha(primary); canvas.drawCircle(x, y, rMax * 0.022f, p)
            }

            // Core — layered glow, hexagonal core, tri-coil, pulsing centre.
            val coreR = rMax * 0.26f
            p.color = withAlpha(primary, 0.10f); canvas.drawCircle(cx, cy, coreR * 1.7f, p)
            p.color = withAlpha(accent, 0.18f); canvas.drawCircle(cx, cy, coreR * 1.2f, p)
            p.color = withAlpha(accent, 0.28f + 0.4f * pulse); canvas.drawCircle(cx, cy, coreR * (0.6f + 0.14f * pulse), p)
            p.style = Paint.Style.STROKE; p.strokeWidth = rMax * 0.01f
            p.color = withAlpha(primary, 0.6f); canvas.drawPath(hexPath(cx, cy, coreR, spin.toDouble()), p)
            p.color = withAlpha(primary, 0.5f)
            val triR = coreR * 0.6f
            val tri = Path()
            for (i in 0..2) {
                val a = Math.toRadians(90.0 + i * 120.0)
                val x = cx + (triR * cos(a)).toFloat()
                val yy = cy - (triR * sin(a)).toFloat()
                if (i == 0) tri.moveTo(x, yy) else tri.lineTo(x, yy)
            }
            tri.close()
            canvas.drawPath(tri, p)
            p.style = Paint.Style.FILL
            p.setShadowLayer(rMax * 0.06f, 0f, 0f, withFullAlpha(primary))
            p.color = withFullAlpha(primary); canvas.drawCircle(cx, cy, coreR * 0.2f, p)
            p.clearShadowLayer()
        }

        private fun hexPath(cx: Float, cy: Float, r: Float, rotDeg: Double): Path {
            val path = Path()
            for (i in 0..5) {
                val a = Math.toRadians(60.0 * i + rotDeg)
                val x = cx + (r * cos(a)).toFloat()
                val y = cy + (r * sin(a)).toFloat()
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            path.close()
            return path
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
    }

    private companion object {
        // NIGHTWIRE palette (mirrors ui/theme/Color.kt) as ARGB ints. Not `const` — `.toInt()` isn't a
        // compile-time constant (the hex literals are Long; we want their low-32-bit signed-int bit pattern).
        val VOID = 0xFF05070D.toInt()
        val BLACK = 0xFF000000.toInt()
        val PANEL = 0xFF0E121C.toInt()
        val SKY = 0xFF5AD1FF.toInt()
        val INK = 0xFFE6EFFA.toInt()
        val MUTED = 0xFF5E708C.toInt()
        val POSITIVE = 0xFF46F9A0.toInt()
        val NEGATIVE = 0xFFFF4D6D.toInt()

        const val FRAME_MS = 33L            // ~30 fps while visible
        const val DATA_INTERVAL_MS = 60_000L

        // Orbiting satellites: {radiusFactor, periodMs, phaseDeg} — static, no per-frame allocation.
        val ORBITS = arrayOf(
            floatArrayOf(0.95f, 18000f, 0f),
            floatArrayOf(1.06f, 26000f, 140f),
            floatArrayOf(0.72f, 14000f, 60f),
            floatArrayOf(1.14f, 33000f, 220f),
        )

        fun withAlpha(base: Int, a: Float): Int =
            (base and 0x00FFFFFF) or (((a.coerceIn(0f, 1f) * 255f).toInt()) shl 24)

        fun withFullAlpha(base: Int): Int = base or 0xFF000000.toInt()
    }
}
