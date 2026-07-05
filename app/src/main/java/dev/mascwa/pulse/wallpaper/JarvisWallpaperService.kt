package dev.mascwa.pulse.wallpaper

import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
import dev.mascwa.pulse.PulseApplication
import dev.mascwa.pulse.core.telemetry.MarketMood
import dev.mascwa.pulse.core.telemetry.SpaceWeatherExplainers
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
 * The J.A.R.V.I.S. live wallpaper - a dense Iron-Man-OS HUD drawn to a raw [Canvas]: a central world-map radar
 * with a sweep, ringed by dials, gauges, a waveform graph, a loading bar, a molecule schematic, chevron framing
 * and telemetry tick-bars, in cyan/amber/white. Reads only on-device cached Pulse data.
 *
 * Frugal + defensive: draws only while visible, refreshes data <= once a minute on a background coroutine,
 * holds no bitmaps, and swallows any surface/IO error.
 */
class JarvisWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = ReactorEngine()

    private data class Snapshot(
        val lines: List<Pair<String, Int>> = emptyList(),
        val battery: Int = -1,
        val spark: List<Float> = emptyList(),     // normalized 0..1 series for the graph (real market sparkline)
        val sparkLabel: String = "RTLM-57",
        val memPct: Int = -1,                     // device RAM used %
        val kp: Double = -1.0,                    // geomagnetic K-index 0..9
        val markers: List<FloatArray> = emptyList(), // radar points: [nx, ny, kind] (0 you, 1 quake, 2 waypoint)
        val solarWind: Double = -1.0,             // solar wind speed km/s (drives the turbine spin)
        val breadthUp: Int = 0,                   // watchlist instruments up on the day
        val breadthDown: Int = 0,                 // watchlist instruments down on the day
        val breadthNet: Double = 0.0,             // equal-weighted average % change across the watchlist
        val breadthMood: String = "",             // plain-English breadth headline (MarketMood)
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

        // Cached date/time formatters — rebuilt on the ≤1/min data tick (refreshData) instead of
        // reconstructed every frame (~30 fps). Constructing SimpleDateFormat/DateFormat is expensive
        // (pattern parse + locale symbols); this was the biggest per-frame cost. Touched only on the main
        // looper thread (render + refreshData both run there), so no thread-safety concern. Rebuilding
        // ≤1/min keeps a system 12-/24-hour or locale change reflected within a minute.
        private var timeFmt: java.text.DateFormat =
            android.text.format.DateFormat.getTimeFormat(this@JarvisWallpaperService)
        private var dateFmt: java.text.DateFormat =
            android.text.format.DateFormat.getMediumDateFormat(this@JarvisWallpaperService)
        private var dayFmt = java.text.SimpleDateFormat("EEEE", java.util.Locale.getDefault())

        private val frame = Runnable { drawFrame() }

        override fun onVisibilityChanged(visible: Boolean) {
            this.visible = visible
            if (visible) { refreshData(); scheduleNext() } else handler.removeCallbacks(frame)
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            this.width = width; this.height = height; drawFrame()
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) { visible = false; handler.removeCallbacks(frame) }
        override fun onDestroy() { handler.removeCallbacks(frame); scope.cancel() }

        private fun scheduleNext() {
            handler.removeCallbacks(frame)
            if (visible) handler.postDelayed(frame, FRAME_MS)
        }

        private fun drawFrame() {
            val now = SystemClock.uptimeMillis()
            if (now - lastDataMs > DATA_INTERVAL_MS) refreshData()
            val holder = surfaceHolder
            var canvas: Canvas? = null
            try {
                canvas = holder.lockCanvas()
                if (canvas != null) runCatching { render(canvas) }
            } catch (_: Throwable) {
            } finally {
                if (canvas != null) runCatching { holder.unlockCanvasAndPost(canvas) }
            }
            scheduleNext()
        }

        private fun refreshData() {
            lastDataMs = SystemClock.uptimeMillis()
            // Refresh cached formatters (main thread, ≤1/min) so a 12-/24-hour or locale change is picked up.
            timeFmt = android.text.format.DateFormat.getTimeFormat(this@JarvisWallpaperService)
            dateFmt = android.text.format.DateFormat.getMediumDateFormat(this@JarvisWallpaperService)
            dayFmt = java.text.SimpleDateFormat("EEEE", java.util.Locale.getDefault())
            scope.launch {
                val app = applicationContext as? PulseApplication ?: return@launch
                val c = app.container
                val s = runCatching { c.settingsRepository.current() }.getOrNull()
                val out = mutableListOf<Pair<String, Int>>()
                var spark: List<Float> = emptyList()
                var sparkLabel = "RTLM-57"
                var kp = -1.0
                var solarWind = -1.0
                var breadthUp = 0
                var breadthDown = 0
                var breadthNet = 0.0
                var breadthMood = ""
                val markers = mutableListOf<FloatArray>()

                runCatching {
                    s?.let { st -> st.waypoints.firstOrNull { it.id == st.activeWaypointId }?.label }
                        ?.let { out.add("OBJ  ${it.take(30)}" to INK) }
                }
                runCatching {
                    val saved = s?.let { it.savedLocations.getOrNull(it.selectedLocationIndex) ?: it.savedLocations.firstOrNull() }
                    saved?.let { loc -> c.weatherRepository.fetch(loc.latitude, loc.longitude, loc.name, force = false).data }
                        ?.current?.let { cur ->
                            out.add("WX   ${Formatters.number(cur.temperature, 0)}° · ${WeatherCode.describe(cur.weatherCode)}" to INK)
                        }
                }
                // Markets: movers for the feed + the top mover's real intraday sparkline for the graph.
                runCatching {
                    val quotes = c.marketsRepository.fetchWatchlist(force = false).data.orEmpty()
                        .filter { it.changePercent != null }
                    // Breadth (up/down counts + net average) comes from the CI-tested core so the HUD
                    // and the Markets screen agree on the definition.
                    MarketMood.summarize(quotes.mapNotNull { it.changePercent })?.let { mood ->
                        breadthUp = mood.up
                        breadthDown = mood.down
                        breadthNet = mood.netChangePct
                        breadthMood = mood.headline
                    }
                    val sorted = quotes.sortedByDescending { abs(it.changePercent ?: 0.0) }
                    sorted.take(2).forEach { q ->
                        val pct = q.changePercent ?: 0.0
                        out.add("MKT  ${q.label.take(12)}  ${if (pct >= 0) "+" else ""}${"%.2f".format(pct)}%" to if (pct >= 0) POSITIVE else NEGATIVE)
                    }
                    val top = sorted.firstOrNull { it.sparkline.size >= 2 }
                    if (top != null) {
                        val sl = top.sparkline
                        val mn = sl.minOrNull() ?: 0.0
                        val mx = sl.maxOrNull() ?: 1.0
                        val rng = (mx - mn).let { if (it == 0.0) 1.0 else it }
                        spark = sl.map { ((it - mn) / rng).toFloat() }
                        sparkLabel = top.label.take(10).uppercase()
                    }
                }
                runCatching {
                    c.newsRepository.fetchCategory(NewsCategory.TOP, force = false).data?.firstOrNull()?.title
                        ?.let { out.add("NEWS ${it.take(38)}" to CYAN) }
                }
                runCatching {
                    val sw = c.spaceWeatherRepository.fetch(force = false).data
                    kp = sw?.kp ?: -1.0
                    solarWind = sw?.solarWindSpeed ?: -1.0
                    // Plain-English geomagnetic line (reuses the CI-tested explainer); amber when storming.
                    if (kp >= 0.0) out.add("SPC  ${SpaceWeatherExplainers.kp(kp).headline}" to if (kp >= 5.0) AMBER else CYAN)
                }
                // Radar markers: your saved location + recent earthquakes/incidents + the active waypoint, by lat/lon.
                runCatching {
                    val saved = s?.let { it.savedLocations.getOrNull(it.selectedLocationIndex) ?: it.savedLocations.firstOrNull() }
                    if (saved != null) {
                        markers.add(floatArrayOf(((saved.longitude + 180.0) / 360.0).toFloat(), ((90.0 - saved.latitude) / 180.0).toFloat(), 0f))
                        c.safetyRepository.fetch(saved.latitude, saved.longitude, force = false).data?.incidents.orEmpty().take(8).forEach { ev ->
                            markers.add(floatArrayOf(((ev.longitude + 180.0) / 360.0).toFloat(), ((90.0 - ev.latitude) / 180.0).toFloat(), 1f))
                        }
                    }
                    s?.let { st -> st.waypoints.firstOrNull { it.id == st.activeWaypointId } }?.let { wp ->
                        markers.add(floatArrayOf(((wp.longitude + 180.0) / 360.0).toFloat(), ((90.0 - wp.latitude) / 180.0).toFloat(), 2f))
                    }
                }
                val memPct = runCatching {
                    val am = applicationContext.getSystemService(android.app.ActivityManager::class.java)
                    val mi = android.app.ActivityManager.MemoryInfo()
                    am?.getMemoryInfo(mi)
                    if (mi.totalMem > 0L) (((mi.totalMem - mi.availMem) * 100.0) / mi.totalMem).toInt() else -1
                }.getOrDefault(-1)
                val battery = runCatching {
                    applicationContext.getSystemService(BatteryManager::class.java)
                        ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
                }.getOrDefault(-1)
                snapshot = Snapshot(out, battery, spark, sparkLabel, memPct, kp, markers, solarWind, breadthUp, breadthDown, breadthNet, breadthMood)
            }
        }

        private fun render(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return
            canvas.drawColor(VOID)
            val unit = minOf(w, h)
            val cx = w / 2f
            val t = SystemClock.uptimeMillis() - startMs
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            val snap = snapshot
            val now = java.util.Date()
            val timeStr = timeFmt.format(now)
            val dayStr = dayFmt.format(now).uppercase()
            val dateStr = dateFmt.format(now).uppercase()
// ===== Frame & edge telemetry =====
drawCornerFrame(canvas, p, w, h, t)
drawTelemetryTicks(canvas, p, cx, h * 0.045f, w * 0.9f, t)
drawTelemetryTicks(canvas, p, cx, h * 0.965f, w * 0.9f, t)

// ===== Top corner dials =====
drawFineDial(canvas, p, w * 0.17f, h * 0.135f, unit * 0.085f, t, snap.kp)
drawTurbineDial(canvas, p, w * 0.83f, h * 0.135f, unit * 0.085f, t, snap.solarWind)

// ===== Inline clock block (centred) =====
run {
    val clock = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    // Big glowing time
    clock.color = withFullAlpha(WHITE)
    clock.textSize = unit * 0.085f
    clock.setShadowLayer(unit * 0.04f, 0f, 0f, withAlpha(CYAN, 0.85f))
    canvas.drawText(timeStr, cx, h * 0.115f, clock)
    clock.clearShadowLayer()
    // Day small cyan
    clock.typeface = Typeface.MONOSPACE
    clock.color = withFullAlpha(CYAN)
    clock.textSize = unit * 0.032f
    clock.letterSpacing = 0.18f
    canvas.drawText(dayStr.uppercase(), cx, h * 0.115f + unit * 0.055f, clock)
    // Date tiny muted
    clock.color = withAlpha(MUTED, 0.85f)
    clock.textSize = unit * 0.024f
    clock.letterSpacing = 0.10f
    canvas.drawText(dateStr.uppercase(), cx, h * 0.115f + unit * 0.092f, clock)
}

// ===== Wave graph + sync loading bar =====
drawWaveGraph(canvas, p, w * 0.07f, h * 0.20f, w * 0.62f, h * 0.265f, t, snap.spark, snap.sparkLabel)
drawLoadingBar(canvas, p, w * 0.07f, h * 0.285f, w * 0.93f, h * 0.315f, t, if (snap.memPct in 0..100) snap.memPct else ((t / 120L) % 100L).toInt(), "MEM")

// ===== Central world radar =====
drawWorldRadar(canvas, p, cx, h * 0.49f, unit * 0.33f, t, snap.markers)

// ===== Lower instruments =====
drawBreadth(canvas, p, w * 0.20f, h * 0.685f, unit * 0.16f, t, snap.breadthUp, snap.breadthDown, snap.breadthNet, snap.breadthMood)
drawPercentGauge(canvas, p, w * 0.74f, h * 0.685f, unit * 0.135f, t, snap.battery.coerceIn(0, 100), "PWR")

// ===== Live data panel =====
drawDataPanel(canvas, p, w * 0.07f, h * 0.80f, w * 0.93f, h * 0.905f, snap.lines)

// ===== Tiny mono flavour labels =====
run {
    val flv = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        textAlign = Paint.Align.LEFT
        letterSpacing = 0.12f
    }
    // top-left under the frame
    flv.color = withAlpha(CYAN, 0.70f)
    flv.textSize = unit * 0.022f
    canvas.drawText("ARGUS DYNAMICS // NIGHTWIRE OS", w * 0.07f, h * 0.075f, flv)
    // bottom flavour line
    flv.color = withAlpha(AMBER, 0.70f)
    flv.textSize = unit * 0.020f
    val blink = if (((t / 600L) % 2L) == 0L) "" else " *"
    canvas.drawText("TRANSCEIVING MAIN DATA" + blink, w * 0.07f, h * 0.935f, flv)
}
        }

// ===== drawCornerFrame =====
private fun drawCornerFrame(c: Canvas, p: Paint, w: Float, h: Float, t: Long) {
    val stroke = w * 0.0035f
    val arm = w * 0.10f
    val chamfer = w * 0.035f
    val inset = w * 0.04f
    val accent = w * 0.018f
    val edgeInset = h * 0.05f

    val savedColor = p.color
    val savedStrokeWidth = p.strokeWidth
    val savedStyle = p.style

    p.style = Paint.Style.STROKE
    p.strokeWidth = stroke
    p.pathEffect = null
    p.shader = null
    p.clearShadowLayer()

    val path = Path()

    // TOP-LEFT
    p.color = withFullAlpha(CYAN)
    path.reset()
    path.moveTo(inset, inset + chamfer)
    path.lineTo(inset + chamfer, inset)
    path.lineTo(inset + chamfer + arm, inset)
    path.moveTo(inset, inset + chamfer)
    path.lineTo(inset, inset + chamfer + arm)
    c.drawPath(path, p)

    // TOP-RIGHT
    path.reset()
    path.moveTo(w - inset, inset + chamfer)
    path.lineTo(w - (inset + chamfer), inset)
    path.lineTo(w - (inset + chamfer + arm), inset)
    path.moveTo(w - inset, inset + chamfer)
    path.lineTo(w - inset, inset + chamfer + arm)
    c.drawPath(path, p)

    // BOTTOM-LEFT
    path.reset()
    path.moveTo(inset, h - (inset + chamfer))
    path.lineTo(inset + chamfer, h - inset)
    path.lineTo(inset + chamfer + arm, h - inset)
    path.moveTo(inset, h - (inset + chamfer))
    path.lineTo(inset, h - (inset + chamfer + arm))
    c.drawPath(path, p)

    // BOTTOM-RIGHT
    path.reset()
    path.moveTo(w - inset, h - (inset + chamfer))
    path.lineTo(w - (inset + chamfer), h - inset)
    path.lineTo(w - (inset + chamfer + arm), h - inset)
    path.moveTo(w - inset, h - (inset + chamfer))
    path.lineTo(w - inset, h - (inset + chamfer + arm))
    c.drawPath(path, p)

    // ---- Small filled CYAN square accent inside each corner ----
    p.style = Paint.Style.FILL
    val pulse = (0.55f + 0.45f * (sin(t.toDouble() * 0.004).toFloat() * 0.5f + 0.5f)).coerceIn(0f, 1f)
    p.color = withAlpha(CYAN, pulse)
    val sq = accent
    val sqOff = inset + chamfer + arm * 0.18f
    c.drawRect(sqOff, sqOff, sqOff + sq, sqOff + sq, p)
    c.drawRect(w - sqOff - sq, sqOff, w - sqOff, sqOff + sq, p)
    c.drawRect(sqOff, h - sqOff - sq, sqOff + sq, h - sqOff, p)
    c.drawRect(w - sqOff - sq, h - sqOff - sq, w - sqOff, h - sqOff, p)

    // ---- Thin inset rule along TOP and BOTTOM edges ----
    p.style = Paint.Style.STROKE
    p.strokeWidth = stroke
    p.color = withAlpha(WHITE, 0.85f)

    val ruleStartX = inset + chamfer + arm
    val ruleEndX = w - (inset + chamfer + arm)
    val midX = (ruleStartX + ruleEndX) * 0.5f
    val gap = w * 0.045f
    val chevD = w * 0.022f

    c.drawLine(ruleStartX, edgeInset, midX - gap, edgeInset, p)
    c.drawLine(midX + gap, edgeInset, ruleEndX, edgeInset, p)
    p.color = withFullAlpha(CYAN)
    path.reset()
    path.moveTo(midX - gap * 0.5f, edgeInset + chevD * 0.5f)
    path.lineTo(midX, edgeInset - chevD * 0.5f)
    path.lineTo(midX + gap * 0.5f, edgeInset + chevD * 0.5f)
    c.drawPath(path, p)

    val edgeInsetB = h - edgeInset
    p.color = withAlpha(WHITE, 0.85f)
    c.drawLine(ruleStartX, edgeInsetB, midX - gap, edgeInsetB, p)
    c.drawLine(midX + gap, edgeInsetB, ruleEndX, edgeInsetB, p)
    p.color = withFullAlpha(CYAN)
    path.reset()
    path.moveTo(midX - gap * 0.5f, edgeInsetB - chevD * 0.5f)
    path.lineTo(midX, edgeInsetB + chevD * 0.5f)
    path.lineTo(midX + gap * 0.5f, edgeInsetB - chevD * 0.5f)
    c.drawPath(path, p)

    // ---- Row of 4 forward chevrons near top-left ----
    val chW = w * 0.018f
    val chH = w * 0.022f
    val chSpacing = w * 0.028f
    val rowY = inset + chamfer + arm + h * 0.018f
    var cx = ruleStartX + w * 0.01f
    val march = ((t / 240L) % 4L).toInt()
    for (i in 0 until 4) {
        val baseFade = 1.0f - i * 0.22f
        val twinkle = if (i == march) 1.0f else 0.7f
        p.color = withAlpha(CYAN, (baseFade * twinkle).coerceIn(0.15f, 1.0f))
        path.reset()
        path.moveTo(cx, rowY - chH * 0.5f)
        path.lineTo(cx + chW, rowY)
        path.lineTo(cx, rowY + chH * 0.5f)
        c.drawPath(path, p)
        cx += chSpacing
    }

    // ---- Row of 4 back chevrons near bottom-right ----
    val rowYb = h - (inset + chamfer + arm) - h * 0.018f
    var cxb = ruleEndX - w * 0.01f
    val marchB = ((t / 240L) % 4L).toInt()
    for (i in 0 until 4) {
        val baseFade = 1.0f - i * 0.22f
        val twinkle = if (i == marchB) 1.0f else 0.7f
        p.color = withAlpha(CYAN, (baseFade * twinkle).coerceIn(0.15f, 1.0f))
        path.reset()
        path.moveTo(cxb, rowYb - chH * 0.5f)
        path.lineTo(cxb - chW, rowYb)
        path.lineTo(cxb, rowYb + chH * 0.5f)
        c.drawPath(path, p)
        cxb -= chSpacing
    }

    p.pathEffect = null
    p.shader = null
    p.clearShadowLayer()
    p.color = savedColor
    p.strokeWidth = savedStrokeWidth
    p.style = savedStyle
}

// ===== drawTelemetryTicks =====
private fun drawTelemetryTicks(c: Canvas, p: Paint, cx: Float, y: Float, span: Float, t: Long) {
    val count = 60
    val phase = (t / 120f).toDouble()
    val gap = span / count
    val left = cx - span / 2f
    val maxH = span * 0.02f

    p.style = Paint.Style.STROKE
    p.strokeWidth = (gap * 0.32f).coerceAtLeast(1f)
    p.pathEffect = null
    p.shader = null
    p.clearShadowLayer()

    for (i in 0 until count) {
        val seed = i.toDouble() * 12.9898 + phase
        val norm = abs(sin(seed)).toFloat()
        val h = maxH * (0.15f + 0.85f * norm)
        val x = left + gap * (i + 0.5f)
        val isAmber = (i % 10 == 0)

        if (isAmber) {
            val ah = h * 1.65f
            p.color = withFullAlpha(AMBER)
            p.strokeWidth = (gap * 0.42f).coerceAtLeast(1f)
            p.setShadowLayer(gap * 1.4f, 0f, 0f, withAlpha(AMBER, 0.6f))
            c.drawLine(x, y, x, y - ah, p)
            p.clearShadowLayer()
            p.strokeWidth = (gap * 0.32f).coerceAtLeast(1f)
        } else {
            p.color = withAlpha(CYAN, 0.40f + 0.55f * norm)
            c.drawLine(x, y, x, y - h, p)
        }
    }

    p.color = withAlpha(CYAN, 0.35f)
    p.strokeWidth = (span * 0.0035f).coerceAtLeast(1f)
    c.drawLine(left, y + maxH * 0.18f, left + span, y + maxH * 0.18f, p)

    p.style = Paint.Style.FILL
    p.pathEffect = null
    p.clearShadowLayer()
}

// Continent outlines (normalized 0..1 coords) for the world radar — hoisted to a single shared
// allocation instead of rebuilding ~37 FloatArrays every wallpaper frame (~30 fps, always-on).
private val WORLD_CONTINENTS: Array<Array<FloatArray>> = arrayOf(
    arrayOf( // N. America
        floatArrayOf(0.13f, 0.16f), floatArrayOf(0.27f, 0.16f), floatArrayOf(0.30f, 0.30f),
        floatArrayOf(0.22f, 0.42f), floatArrayOf(0.16f, 0.34f), floatArrayOf(0.12f, 0.24f)
    ),
    arrayOf( // S. America
        floatArrayOf(0.28f, 0.50f), floatArrayOf(0.34f, 0.50f), floatArrayOf(0.37f, 0.62f),
        floatArrayOf(0.32f, 0.82f), floatArrayOf(0.28f, 0.70f), floatArrayOf(0.27f, 0.58f)
    ),
    arrayOf( // Europe
        floatArrayOf(0.45f, 0.18f), floatArrayOf(0.55f, 0.18f),
        floatArrayOf(0.54f, 0.30f), floatArrayOf(0.46f, 0.30f)
    ),
    arrayOf( // Africa
        floatArrayOf(0.46f, 0.32f), floatArrayOf(0.60f, 0.34f), floatArrayOf(0.60f, 0.52f),
        floatArrayOf(0.52f, 0.70f), floatArrayOf(0.47f, 0.52f), floatArrayOf(0.45f, 0.40f)
    ),
    arrayOf( // Asia
        floatArrayOf(0.55f, 0.14f), floatArrayOf(0.86f, 0.16f), floatArrayOf(0.84f, 0.40f),
        floatArrayOf(0.66f, 0.44f), floatArrayOf(0.58f, 0.32f), floatArrayOf(0.55f, 0.22f)
    ),
    arrayOf( // Australia
        floatArrayOf(0.78f, 0.62f), floatArrayOf(0.90f, 0.62f),
        floatArrayOf(0.90f, 0.74f), floatArrayOf(0.80f, 0.74f)
    )
)

// ===== drawWorldRadar =====
private fun drawWorldRadar(c: Canvas, p: Paint, cx: Float, cy: Float, rad: Float, t: Long, markers: List<FloatArray>) {
    val r = 0.78f * rad

    fun mapX(nx: Float): Float = cx - r + nx * 2f * r
    fun mapY(ny: Float): Float = cy - r + ny * 2f * r

    p.clearShadowLayer()
    p.shader = null

    // ---- 1) Outer graduation tick ring ----
    p.style = Paint.Style.STROKE
    var deg = 0
    while (deg < 360) {
        val major = (deg % 30) == 0
        val a = Math.toRadians(deg.toDouble())
        val ca = cos(a).toFloat()
        val sa = sin(a).toFloat()
        val inner = if (major) rad * 0.93f else rad * 0.965f
        p.color = withAlpha(CYAN, if (major) 0.55f else 0.25f)
        p.strokeWidth = if (major) rad * 0.012f else rad * 0.006f
        c.drawLine(cx + ca * inner, cy + sa * inner, cx + ca * rad, cy + sa * rad, p)
        deg += 4
    }

    // ---- 2) Dashed concentric orbit rings ----
    val phase = (t / 18f) % (rad * 0.2f)
    p.color = withAlpha(CYAN, 0.45f)
    p.strokeWidth = rad * 0.008f
    p.pathEffect = DashPathEffect(floatArrayOf(rad * 0.06f, rad * 0.04f), phase)
    c.drawCircle(cx, cy, rad * 0.94f, p)
    p.color = withAlpha(AMBER, 0.45f)
    p.strokeWidth = rad * 0.007f
    p.pathEffect = DashPathEffect(floatArrayOf(rad * 0.04f, rad * 0.05f), -phase)
    c.drawCircle(cx, cy, rad * 0.80f, p)
    p.color = withAlpha(CYAN, 0.35f)
    p.strokeWidth = rad * 0.006f
    p.pathEffect = DashPathEffect(floatArrayOf(rad * 0.03f, rad * 0.03f), phase)
    c.drawCircle(cx, cy, rad * 0.64f, p)
    p.pathEffect = null

    // ---- Clip path for the globe interior ----
    val circlePath = Path()
    circlePath.addCircle(cx, cy, r, Path.Direction.CW)

    c.save()
    c.clipPath(circlePath)

    // ---- 3) Equirectangular graticule ----
    p.style = Paint.Style.STROKE
    p.color = withAlpha(CYAN, 0.12f)
    p.strokeWidth = rad * 0.004f
    var gi = 1
    while (gi < 8) {
        val ny = gi / 8f
        val gy = mapY(ny)
        c.drawLine(cx - r, gy, cx + r, gy, p)
        val nx = gi / 8f
        val gx = mapX(nx)
        c.drawLine(gx, cy - r, gx, cy + r, p)
        gi++
    }

    // ---- 4) Continents ----
    for (poly in WORLD_CONTINENTS) {
        val polyPath = Path()
        polyPath.moveTo(mapX(poly[0][0]), mapY(poly[0][1]))
        var i = 1
        while (i < poly.size) {
            polyPath.lineTo(mapX(poly[i][0]), mapY(poly[i][1]))
            i++
        }
        polyPath.close()
        p.style = Paint.Style.FILL
        p.color = withAlpha(CYAN, 0.18f)
        c.drawPath(polyPath, p)
        p.style = Paint.Style.STROKE
        p.color = withAlpha(CYAN, 0.40f)
        p.strokeWidth = rad * 0.004f
        c.drawPath(polyPath, p)
    }

    // ---- 5) Rotating sweep (inside clip) ----
    val sweepDeg = ((t / 30.0) % 360.0)
    val sweepRad = Math.toRadians(sweepDeg)
    val sca = cos(sweepRad).toFloat()
    val ssa = sin(sweepRad).toFloat()

    val wedge = Path()
    wedge.moveTo(cx, cy)
    val wedgeRect = RectF(cx - r, cy - r, cx + r, cy + r)
    wedge.arcTo(wedgeRect, (sweepDeg - 40.0).toFloat(), 40f, false)
    wedge.close()
    p.style = Paint.Style.FILL
    p.color = withAlpha(CYAN, 0.10f)
    c.drawPath(wedge, p)

    p.style = Paint.Style.STROKE
    p.color = withAlpha(CYAN, 0.85f)
    p.strokeWidth = rad * 0.010f
    c.drawLine(cx, cy, cx + sca * r, cy + ssa * r, p)

    c.restore()

    // ---- 6) Real geo markers: your location (cyan), recent quakes/incidents (amber), waypoint (white) ----
    val pts = if (markers.isNotEmpty()) markers else listOf(
        floatArrayOf(0.22f, 0.30f, 1f), floatArrayOf(0.55f, 0.40f, 1f), floatArrayOf(0.78f, 0.66f, 1f)
    )
    var mi = 0
    while (mi < pts.size) {
        val m = pts[mi]
        val mx = mapX(m[0].coerceIn(0f, 1f))
        val my = mapY(m[1].coerceIn(0f, 1f))
        val dx = mx - cx
        val dy = my - cy
        if (dx * dx + dy * dy <= r * r) {
            val kind = if (m.size > 2) m[2].toInt() else 1
            val col = when (kind) { 0 -> CYAN; 2 -> WHITE; else -> AMBER }
            val mpulse = 0.5f + 0.5f * sin((t / 320.0) + mi * 1.3).toFloat()
            val msz = rad * 0.022f
            val dia = Path()
            dia.moveTo(mx, my - msz)
            dia.lineTo(mx + msz, my)
            dia.lineTo(mx, my + msz)
            dia.lineTo(mx - msz, my)
            dia.close()
            p.style = Paint.Style.FILL
            p.color = withAlpha(col, 0.25f + 0.55f * mpulse)
            c.drawPath(dia, p)
            p.style = Paint.Style.STROKE
            p.color = withAlpha(col, 0.50f + 0.40f * mpulse)
            p.strokeWidth = rad * 0.005f
            c.drawPath(dia, p)
            c.drawLine(mx, my - msz, mx, my - msz * 2.0f, p)
        }
        mi++
    }

    // ---- 7) Centre crosshair ----
    p.style = Paint.Style.STROKE
    p.color = withAlpha(WHITE, 0.65f)
    p.strokeWidth = rad * 0.005f
    val ch = rad * 0.05f
    c.drawLine(cx - ch, cy, cx + ch, cy, p)
    c.drawLine(cx, cy - ch, cx, cy + ch, p)
    p.color = withAlpha(CYAN, 0.70f)
    p.strokeWidth = rad * 0.006f
    c.drawCircle(cx, cy, rad * 0.025f, p)

    p.style = Paint.Style.FILL
    p.pathEffect = null
    p.clearShadowLayer()
}

// ===== drawPercentGauge =====
private fun drawPercentGauge(c: Canvas, p: Paint, cx: Float, cy: Float, rad: Float, t: Long, pct: Int, label: String) {
    val v = if (pct < 0) 0 else if (pct > 100) 100 else pct
    val frac = v.toFloat() / 100f

    val segCount = 24
    val segGap = 4f
    val segArc = (360f / segCount) - segGap
    val ringR = rad * 0.92f
    val ringRect = RectF(cx - ringR, cy - ringR, cx + ringR, cy + ringR)
    val litSegs = (frac * segCount).toInt()

    p.style = Paint.Style.STROKE
    p.strokeCap = Paint.Cap.BUTT
    p.strokeWidth = rad * 0.10f
    p.pathEffect = null
    p.shader = null
    for (i in 0 until segCount) {
        val start = (-90f) + i * (360f / segCount) + (segGap / 2f)
        if (i < litSegs) {
            p.color = withFullAlpha(CYAN)
            p.setShadowLayer(rad * 0.05f, 0f, 0f, withAlpha(CYAN, 0.6f))
        } else {
            p.color = withAlpha(CYAN, 0.18f)
            p.clearShadowLayer()
        }
        c.drawArc(ringRect, start, segArc, false, p)
    }
    p.clearShadowLayer()

    val progR = rad * 0.78f
    val progRect = RectF(cx - progR, cy - progR, cx + progR, cy + progR)
    val sweep = frac * 360f
    p.style = Paint.Style.STROKE
    p.strokeCap = Paint.Cap.ROUND
    p.strokeWidth = rad * 0.035f
    p.color = withFullAlpha(AMBER)
    p.setShadowLayer(rad * 0.06f, 0f, 0f, withAlpha(AMBER, 0.7f))
    if (sweep > 0f) c.drawArc(progRect, -90f, sweep, false, p)
    p.clearShadowLayer()

    val tipAngle = Math.toRadians((-90f + sweep).toDouble())
    val tipX = (cx + progR * cos(tipAngle)).toFloat()
    val tipY = (cy + progR * sin(tipAngle)).toFloat()
    val pulse = (0.5 + 0.5 * sin(t.toDouble() / 260.0)).toFloat()
    p.style = Paint.Style.FILL
    p.color = withFullAlpha(AMBER)
    p.setShadowLayer(rad * (0.05f + 0.04f * pulse), 0f, 0f, withFullAlpha(AMBER))
    c.drawCircle(tipX, tipY, rad * 0.045f, p)
    p.clearShadowLayer()
    p.color = withFullAlpha(WHITE)
    c.drawCircle(tipX, tipY, rad * 0.018f, p)

    val innerR = rad * 0.62f
    val innerRect = RectF(cx - innerR, cy - innerR, cx + innerR, cy + innerR)
    p.style = Paint.Style.STROKE
    p.strokeCap = Paint.Cap.BUTT
    p.strokeWidth = rad * 0.012f
    p.color = withAlpha(WHITE, 0.55f)
    c.drawArc(innerRect, 0f, 360f, false, p)

    p.style = Paint.Style.FILL
    p.pathEffect = null
    p.shader = null
    p.clearShadowLayer()

    val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        textAlign = Paint.Align.CENTER
    }

    txt.color = withAlpha(MUTED, 0.85f)
    txt.textSize = rad * 0.13f
    val lbl = if (label.length > 16) label.substring(0, 16) else label
    c.drawText(lbl.uppercase(), cx, cy - rad * 0.18f, txt)

    txt.color = withFullAlpha(CYAN)
    txt.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    txt.textSize = rad * 0.42f
    txt.setShadowLayer(rad * 0.05f, 0f, 0f, withAlpha(CYAN, 0.6f))
    c.drawText("$v%", cx, cy + rad * 0.10f, txt)
    txt.clearShadowLayer()

    txt.typeface = Typeface.MONOSPACE
    txt.color = withAlpha(CYAN, 0.7f)
    txt.textSize = rad * 0.09f
    val hex = (0x100 + (v * 0x55 / 100)).toString(16).uppercase().substring(1)
    c.drawText("LVL.0x$hex", cx, cy + rad * 0.30f, txt)

    txt.color = withAlpha(MUTED, 0.8f)
    txt.textSize = rad * 0.10f
    txt.textAlign = Paint.Align.LEFT
    c.drawText("0", cx - rad * 0.86f, cy + rad * 0.035f, txt)
    txt.textAlign = Paint.Align.RIGHT
    c.drawText("100", cx + rad * 0.86f, cy + rad * 0.035f, txt)

    p.style = Paint.Style.FILL
    p.strokeCap = Paint.Cap.BUTT
    p.pathEffect = null
    p.shader = null
    p.clearShadowLayer()
}

// ===== drawTurbineDial =====
private fun drawTurbineDial(c: Canvas, p: Paint, cx: Float, cy: Float, rad: Float, t: Long, solarWind: Double) {
    p.style = Paint.Style.STROKE
    p.pathEffect = null
    p.shader = null
    p.clearShadowLayer()

    p.strokeWidth = rad * 0.012f
    p.color = withAlpha(CYAN, 0.85f)
    c.drawCircle(cx, cy, rad * 0.72f, p)

    p.strokeWidth = rad * 0.018f
    p.color = withAlpha(CYAN, 0.40f)
    c.drawCircle(cx, cy, rad * 0.62f, p)

    p.strokeWidth = rad * 0.008f
    p.color = withAlpha(CYAN, 0.22f)
    c.drawCircle(cx, cy, rad * 0.50f, p)

    val bladeCount = 48
    // Spin rate tracks the real solar-wind speed (km/s): ~400 = nominal, faster wind = faster turbine.
    val sf = if (solarWind > 0.0) (solarWind / 400.0).coerceIn(0.4, 3.0) else 1.0
    val spin = (t.toDouble() / 40.0) * sf
    val inner = rad * 0.35f
    val outer = rad * 0.70f
    p.strokeWidth = rad * 0.020f
    val step = 360.0 / bladeCount
    for (i in 0 until bladeCount) {
        val ang = spin + i * step
        val r = Math.toRadians(ang)
        val ca = cos(r)
        val sa = sin(r)
        val x0 = (cx + inner * ca).toFloat()
        val y0 = (cy + inner * sa).toFloat()
        val x1 = (cx + outer * ca).toFloat()
        val y1 = (cy + outer * sa).toFloat()
        p.color = if (i % 2 == 0) withAlpha(CYAN, 0.90f) else withAlpha(CYAN, 0.30f)
        c.drawLine(x0, y0, x1, y1, p)
    }

    p.strokeWidth = rad * 0.030f
    p.color = withFullAlpha(AMBER)
    p.setShadowLayer(rad * 0.06f, 0f, 0f, withAlpha(AMBER, 0.7f))
    val tickIn = rad * 0.74f
    val tickOut = rad * 0.86f
    for (k in 0 until 4) {
        val ang = k * 90.0 - 90.0
        val r = Math.toRadians(ang)
        val ca = cos(r)
        val sa = sin(r)
        val tx0 = (cx + tickIn * ca).toFloat()
        val ty0 = (cy + tickIn * sa).toFloat()
        val tx1 = (cx + tickOut * ca).toFloat()
        val ty1 = (cy + tickOut * sa).toFloat()
        c.drawLine(tx0, ty0, tx1, ty1, p)
    }
    p.clearShadowLayer()

    p.strokeWidth = rad * 0.010f
    p.color = withAlpha(CYAN, 0.55f)
    val dashOn = rad * 0.10f
    val dashOff = rad * 0.06f
    val phase = (t.toDouble() / 60.0).toFloat()
    p.pathEffect = DashPathEffect(floatArrayOf(dashOn, dashOff), phase)
    c.drawCircle(cx, cy, rad * 0.92f, p)
    p.pathEffect = null

    val hubR = rad * 0.30f
    p.style = Paint.Style.FILL
    p.color = withFullAlpha(INK)
    c.drawCircle(cx, cy, hubR, p)

    p.style = Paint.Style.FILL
    p.color = withAlpha(VOID, 0.85f)
    c.drawCircle(cx, cy, hubR * 0.78f, p)

    p.style = Paint.Style.STROKE
    p.strokeWidth = rad * 0.022f
    p.color = withFullAlpha(CYAN)
    p.setShadowLayer(rad * 0.05f, 0f, 0f, withAlpha(CYAN, 0.8f))
    c.drawCircle(cx, cy, hubR, p)
    p.clearShadowLayer()

    val pulse = (0.5 + 0.5 * sin(t.toDouble() / 320.0)).toFloat()
    p.style = Paint.Style.FILL
    p.color = withAlpha(WHITE, 0.18f + 0.30f * pulse)
    c.drawCircle(cx, cy, hubR * 0.28f, p)
    p.color = withAlpha(CYAN, 0.55f + 0.40f * pulse)
    c.drawCircle(cx, cy, hubR * 0.12f, p)

    run {
        val lab = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.MONOSPACE; textAlign = Paint.Align.CENTER; textSize = rad * 0.22f
            color = withAlpha(CYAN, 0.9f)
        }
        c.drawText(if (solarWind > 0.0) "SOL ${solarWind.toInt()}" else "SOL --", cx, cy + rad * 1.42f, lab)
    }

    p.style = Paint.Style.STROKE
    p.strokeWidth = rad * 0.012f
    p.color = withFullAlpha(CYAN)
    p.pathEffect = null
    p.clearShadowLayer()
}

// ===== drawFineDial =====
private fun drawFineDial(c: Canvas, p: Paint, cx: Float, cy: Float, rad: Float, t: Long, kp: Double) {
    p.style = Paint.Style.STROKE
    p.pathEffect = null
    p.shader = null
    p.clearShadowLayer()
    p.strokeWidth = rad * 0.02f
    p.color = withAlpha(CYAN, 0.55f)
    c.drawCircle(cx, cy, rad, p)

    val innerR = rad * 0.62f
    p.strokeWidth = rad * 0.015f
    p.color = withAlpha(CYAN, 0.35f)
    c.drawCircle(cx, cy, innerR, p)

    val tickOuter = rad
    var deg = 0
    while (deg < 360) {
        val a = Math.toRadians(deg.toDouble() - 90.0)
        val ca = cos(a)
        val sa = sin(a)
        val major = (deg % 45) == 0
        val tickLen = if (major) rad * 0.14f else rad * 0.07f
        val tickInner = tickOuter - tickLen
        if (major) {
            p.strokeWidth = rad * 0.022f
            p.color = withAlpha(WHITE, 0.85f)
        } else {
            p.strokeWidth = rad * 0.012f
            p.color = withAlpha(CYAN, 0.45f)
        }
        val x0 = (cx + ca * tickInner).toFloat()
        val y0 = (cy + sa * tickInner).toFloat()
        val x1 = (cx + ca * tickOuter).toFloat()
        val y1 = (cy + sa * tickOuter).toFloat()
        c.drawLine(x0, y0, x1, y1, p)
        deg += 5
    }

    val arcRect = RectF(cx - rad * 0.88f, cy - rad * 0.88f, cx + rad * 0.88f, cy + rad * 0.88f)
    p.style = Paint.Style.STROKE
    p.strokeWidth = rad * 0.05f
    p.color = withAlpha(AMBER, 0.9f)
    p.setShadowLayer(rad * 0.12f, 0f, 0f, withAlpha(AMBER, 0.6f))
    c.drawArc(arcRect, -60f, 34f, false, p)
    p.clearShadowLayer()

    // Needle points to the real geomagnetic K-index (0..9 -> 0..270 deg from top); sweeps if unknown.
    val sweep = if (kp in 0.0..9.0) (kp / 9.0) * 270.0 else ((t.toDouble() / 60.0) % 360.0)
    val ang = Math.toRadians(sweep - 90.0)
    val ca = cos(ang)
    val sa = sin(ang)
    val pa = Math.toRadians(sweep)
    val pca = cos(pa)
    val psa = sin(pa)

    val tipR = rad * 0.92f
    val baseR = rad * 0.66f
    val halfW = rad * 0.11f

    val tipX = (cx + ca * tipR).toFloat()
    val tipY = (cy + sa * tipR).toFloat()
    val bcx = cx + ca * baseR
    val bcy = cy + sa * baseR
    val bLx = (bcx + pca * halfW).toFloat()
    val bLy = (bcy + psa * halfW).toFloat()
    val bRx = (bcx - pca * halfW).toFloat()
    val bRy = (bcy - psa * halfW).toFloat()

    val tri = Path()
    tri.moveTo(tipX, tipY)
    tri.lineTo(bLx, bLy)
    tri.lineTo(bRx, bRy)
    tri.close()

    p.style = Paint.Style.FILL
    p.color = withFullAlpha(CYAN)
    p.setShadowLayer(rad * 0.16f, 0f, 0f, withAlpha(CYAN, 0.8f))
    c.drawPath(tri, p)
    p.clearShadowLayer()

    p.color = withAlpha(WHITE, 0.9f)
    c.drawCircle(tipX, tipY, rad * 0.035f, p)

    val nubR = innerR - rad * 0.04f
    var cardinal = 0
    while (cardinal < 4) {
        val na = Math.toRadians(cardinal * 90.0 - 90.0)
        val nx = (cx + cos(na) * nubR).toFloat()
        val ny = (cy + sin(na) * nubR).toFloat()
        p.style = Paint.Style.FILL
        p.color = if (cardinal == 0) withFullAlpha(AMBER) else withAlpha(CYAN, 0.85f)
        c.drawCircle(nx, ny, rad * 0.05f, p)
        cardinal++
    }

    p.style = Paint.Style.FILL
    p.color = withAlpha(INK, 0.9f)
    c.drawCircle(cx, cy, rad * 0.11f, p)
    p.style = Paint.Style.STROKE
    p.strokeWidth = rad * 0.02f
    p.color = withFullAlpha(CYAN)
    c.drawCircle(cx, cy, rad * 0.11f, p)
    p.style = Paint.Style.FILL
    p.color = withFullAlpha(WHITE)
    c.drawCircle(cx, cy, rad * 0.04f, p)

    run {
        val lab = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.MONOSPACE; textAlign = Paint.Align.CENTER; textSize = rad * 0.22f
            color = withAlpha(CYAN, 0.9f)
        }
        c.drawText(if (kp in 0.0..9.0) "Kp ${"%.1f".format(kp)}" else "Kp --", cx, cy + rad * 1.42f, lab)
    }

    p.style = Paint.Style.FILL
    p.strokeWidth = 0f
    p.pathEffect = null
    p.clearShadowLayer()
    p.color = withFullAlpha(CYAN)
}

// ===== drawWaveGraph =====
private fun drawWaveGraph(c: Canvas, p: Paint, l: Float, top: Float, r: Float, b: Float, t: Long, series: List<Float>, seriesLabel: String) {
    val w = r - l
    val h = b - top
    val unit = if (w < h) w else h
    val pad = unit * 0.04f

    p.clearShadowLayer()
    p.shader = null
    p.pathEffect = null

    p.style = Paint.Style.STROKE
    p.strokeWidth = unit * 0.012f
    p.color = withAlpha(CYAN, 0.35f)
    val frame = RectF(l, top, r, b)
    c.drawRoundRect(frame, unit * 0.03f, unit * 0.03f, p)

    p.style = Paint.Style.FILL
    p.color = withAlpha(PANEL, 0.18f)
    c.drawRoundRect(frame, unit * 0.03f, unit * 0.03f, p)

    p.style = Paint.Style.STROKE
    p.strokeWidth = unit * 0.02f
    p.color = withAlpha(CYAN, 0.85f)
    val bl = unit * 0.12f
    c.drawLine(l, top, l + bl, top, p)
    c.drawLine(l, top, l, top + bl, p)
    c.drawLine(r, top, r - bl, top, p)
    c.drawLine(r, top, r, top + bl, p)
    c.drawLine(l, b, l + bl, b, p)
    c.drawLine(l, b, l, b - bl, p)
    c.drawLine(r, b, r - bl, b, p)
    c.drawLine(r, b, r, b - bl, p)

    val px0 = l + pad
    val px1 = r - pad
    val py0 = top + pad + unit * 0.10f
    val py1 = b - pad - unit * 0.06f
    val pw = px1 - px0
    val ph = py1 - py0
    val mid = py0 + ph * 0.5f
    val amp = ph * 0.32f

    p.style = Paint.Style.STROKE
    p.strokeWidth = unit * 0.005f
    p.color = withAlpha(CYAN, 0.12f)
    val rows = 4
    var gi = 0
    while (gi <= rows) {
        val gy = py0 + ph * (gi.toFloat() / rows.toFloat())
        c.drawLine(px0, gy, px1, gy, p)
        gi++
    }
    val cols = 6
    var ci = 0
    while (ci <= cols) {
        val gx = px0 + pw * (ci.toFloat() / cols.toFloat())
        c.drawLine(gx, py0, gx, py1, p)
        ci++
    }

    p.strokeWidth = unit * 0.006f
    p.color = withAlpha(CYAN, 0.30f)
    p.pathEffect = DashPathEffect(floatArrayOf(unit * 0.04f, unit * 0.03f), (t % 1000L).toFloat() * 0.02f)
    c.drawLine(px0, mid, px1, mid, p)
    p.pathEffect = null

    val path = Path()
    var endX = px1
    var endY = mid
    if (series.size >= 2) {
        // Real series (normalized 0..1) -> the panel.
        val n = series.size
        var i = 0
        while (i < n) {
            val frac = i.toFloat() / (n - 1).toFloat()
            val x = px0 + pw * frac
            val y = py1 - series[i].coerceIn(0f, 1f) * ph
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            endX = x; endY = y
            i++
        }
    } else {
        // Fallback animated trace until the real sparkline is cached.
        val samples = 40
        var i = 0
        while (i <= samples) {
            val frac = i.toFloat() / samples.toFloat()
            val x = px0 + pw * frac
            val s1 = sin(i * 0.5 + t / 300.0).toFloat() * 0.6f
            val s2 = sin(i * 1.7 + t / 170.0).toFloat() * 0.4f
            val y = mid + amp * (s1 + s2)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            endX = x; endY = y
            i++
        }
    }

    p.style = Paint.Style.STROKE
    p.strokeWidth = unit * 0.016f
    p.color = withFullAlpha(CYAN)
    p.setShadowLayer(unit * 0.05f, 0f, 0f, withAlpha(CYAN, 0.8f))
    c.drawPath(path, p)
    p.clearShadowLayer()

    p.style = Paint.Style.FILL
    p.color = withFullAlpha(WHITE)
    p.setShadowLayer(unit * 0.04f, 0f, 0f, withFullAlpha(CYAN))
    c.drawCircle(endX, endY, unit * 0.018f, p)
    p.clearShadowLayer()

    p.style = Paint.Style.STROKE
    p.strokeWidth = unit * 0.008f
    p.color = withAlpha(AMBER, 0.7f)
    val ticks = 12
    var ti = 0
    while (ti <= ticks) {
        val tx = px0 + pw * (ti.toFloat() / ticks.toFloat())
        val major = ti % 3 == 0
        val tl = if (major) unit * 0.045f else unit * 0.025f
        c.drawLine(tx, py1, tx, py1 + tl, p)
        ti++
    }

    val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        textAlign = Paint.Align.LEFT
        textSize = unit * 0.07f
        color = withAlpha(CYAN, 0.9f)
    }
    c.drawText(seriesLabel.ifBlank { "RTLM-57" }, px0 + unit * 0.01f, top + pad + unit * 0.085f, label)

    p.style = Paint.Style.FILL
    p.pathEffect = null
    p.clearShadowLayer()
}

// ===== drawLoadingBar =====
private fun drawLoadingBar(c: Canvas, p: Paint, l: Float, top: Float, r: Float, b: Float, t: Long, pct: Int, label: String) {
    val clamped = if (pct < 0) 0 else if (pct > 100) 100 else pct
    val frac = clamped.toFloat() / 100f
    val w = r - l
    val h = b - top
    val unit = if (h < (w * 0.06f)) h else (w * 0.06f)
    val stroke = (unit * 0.16f).coerceAtLeast(1f)

    p.clearShadowLayer()
    p.pathEffect = null
    p.shader = null

    val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        textSize = unit * 1.05f
        textAlign = Paint.Align.LEFT
    }
    val capY = top - (unit * 0.55f)
    txt.color = withFullAlpha(CYAN)
    c.drawText("$label $clamped%", l, capY, txt)
    txt.textAlign = Paint.Align.RIGHT
    txt.color = withAlpha(AMBER, 0.85f)
    val dots = ((t / 350L) % 4L).toInt()
    val tail = when (dots) { 0 -> "" ; 1 -> "." ; 2 -> ".." ; else -> "..." }
    c.drawText("LOADING$tail", r, capY, txt)

    val trackRect = RectF(l, top, r, b)
    p.style = Paint.Style.STROKE
    p.strokeWidth = stroke
    p.color = withAlpha(CYAN, 0.55f)
    val rad = h * 0.22f
    c.drawRoundRect(trackRect, rad, rad, p)

    val capLen = h * 0.55f
    val capInset = unit * 0.6f
    p.strokeWidth = stroke * 1.4f
    p.color = withFullAlpha(CYAN)
    c.drawLine(l + capInset, top + (h - capLen) * 0.5f, l + capInset, top + (h + capLen) * 0.5f, p)
    c.drawLine(l + capInset, top + (h - capLen) * 0.5f, l + capInset + unit * 0.45f, top + (h - capLen) * 0.5f, p)
    c.drawLine(l + capInset, top + (h + capLen) * 0.5f, l + capInset + unit * 0.45f, top + (h + capLen) * 0.5f, p)
    c.drawLine(r - capInset, top + (h - capLen) * 0.5f, r - capInset, top + (h + capLen) * 0.5f, p)
    c.drawLine(r - capInset, top + (h - capLen) * 0.5f, r - capInset - unit * 0.45f, top + (h - capLen) * 0.5f, p)
    c.drawLine(r - capInset, top + (h + capLen) * 0.5f, r - capInset - unit * 0.45f, top + (h + capLen) * 0.5f, p)

    val pad = stroke * 1.4f
    val fillL = l + capInset + unit * 0.7f
    val fillR = r - capInset - unit * 0.7f
    val fillTop = top + pad
    val fillBot = b - pad
    val fillSpan = fillR - fillL
    val fillH = fillBot - fillTop

    if (fillSpan > 0f) {
        val fillW = fillSpan * frac
        val edgeX = fillL + fillW

        p.style = Paint.Style.FILL
        if (fillW > 0f) {
            val fr = RectF(fillL, fillTop, edgeX, fillBot)
            p.color = withAlpha(CYAN, 0.85f)
            p.setShadowLayer(unit * 0.9f, 0f, 0f, withAlpha(CYAN, 0.6f))
            c.drawRoundRect(fr, rad * 0.5f, rad * 0.5f, p)
            p.clearShadowLayer()
        }

        val segCount = 24
        val segW = fillSpan / segCount
        p.style = Paint.Style.STROKE
        p.strokeWidth = (stroke * 0.6f).coerceAtLeast(1f)
        p.color = withAlpha(INK, 0.7f)
        var i = 1
        while (i < segCount) {
            val sx = fillL + segW * i
            if (sx < edgeX - segW * 0.2f) {
                c.drawLine(sx, fillTop + fillH * 0.18f, sx, fillBot - fillH * 0.18f, p)
            }
            i++
        }

        if (frac > 0f && frac < 1f) {
            val pulse = (0.55f + 0.45f * sin(t.toDouble() * 0.006).toFloat())
            p.style = Paint.Style.FILL
            val edgeW = unit * 0.6f
            val er = RectF(edgeX - edgeW, fillTop, edgeX, fillBot)
            p.color = withAlpha(AMBER, 0.55f + 0.4f * pulse)
            c.drawRect(er, p)
            p.style = Paint.Style.STROKE
            p.strokeWidth = stroke * 1.2f
            p.color = withFullAlpha(AMBER)
            p.setShadowLayer(unit * 1.2f, 0f, 0f, withAlpha(AMBER, 0.7f * pulse))
            c.drawLine(edgeX, fillTop - pad * 0.4f, edgeX, fillBot + pad * 0.4f, p)
            p.clearShadowLayer()
        } else if (frac >= 1f) {
            p.style = Paint.Style.STROKE
            p.strokeWidth = stroke * 1.3f
            p.color = withAlpha(WHITE, 0.9f)
            c.drawLine(fillR, fillTop, fillR, fillBot, p)
        }
    }

    p.pathEffect = null
    p.clearShadowLayer()
    p.style = Paint.Style.FILL
    p.strokeWidth = 0f
    p.color = withFullAlpha(WHITE)
}

// ===== drawBreadth (market breadth: watchlist up vs down) =====
private fun drawBreadth(c: Canvas, p: Paint, cx: Float, cy: Float, size: Float, t: Long, up: Int, down: Int, net: Double, mood: String) {
    p.clearShadowLayer()
    p.shader = null
    p.pathEffect = null

    val total = up + down
    val upFrac = if (total > 0) up.toFloat() / total.toFloat() else 0.5f
    val upPct = (upFrac * 100f).toInt()
    val bullish = upFrac >= 0.5f
    val breath = (0.5 + 0.5 * sin(t.toDouble() / 1400.0)).toFloat()

    // Dashed frame (matches the panel idiom).
    p.style = Paint.Style.STROKE
    p.strokeWidth = size * 0.006f
    p.color = withAlpha(CYAN, 0.20f + 0.12f * breath)
    p.pathEffect = DashPathEffect(floatArrayOf(size * 0.03f, size * 0.025f), (t % 4000L).toFloat() * 0.04f)
    val frame = RectF(cx - size * 0.46f, cy - size * 0.42f, cx + size * 0.46f, cy + size * 0.42f)
    c.drawRoundRect(frame, size * 0.04f, size * 0.04f, p)
    p.pathEffect = null

    val lab = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE; textAlign = Paint.Align.LEFT
    }
    lab.textSize = size * 0.05f
    lab.color = withAlpha(CYAN, 0.6f + 0.3f * breath)
    c.drawText("MKT BREADTH", cx - size * 0.42f, cy - size * 0.30f, lab)

    // Plain-English mood headline (auto-sized to fit the panel), tri-state coloured to match the
    // direction (POSITIVE leaning up / NEGATIVE leaning down / AMBER mixed, on MarketMood's boundaries).
    if (mood.isNotEmpty()) {
        val moodText = mood.uppercase()
        val moodColor = when {
            upFrac >= 0.55f -> POSITIVE
            upFrac <= 0.45f -> NEGATIVE
            else -> AMBER
        }
        val moodPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD); textAlign = Paint.Align.CENTER
        }
        val baseMoodSize = size * 0.052f
        moodPaint.textSize = baseMoodSize
        val avail = size * 0.84f
        val measured = moodPaint.measureText(moodText)
        if (measured > avail && measured > 0f) moodPaint.textSize = baseMoodSize * (avail / measured)
        moodPaint.color = withFullAlpha(moodColor)
        c.drawText(moodText, cx, cy - size * 0.215f, moodPaint)
    }

    // Big up-share percent (breadth headline).
    val pct = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD); textAlign = Paint.Align.CENTER
    }
    pct.textSize = size * 0.15f
    pct.color = withFullAlpha(if (bullish) POSITIVE else NEGATIVE)
    pct.setShadowLayer(size * 0.04f, 0f, 0f, withAlpha(if (bullish) POSITIVE else NEGATIVE, 0.6f))
    c.drawText("$upPct%", cx, cy - size * 0.04f, pct)
    pct.clearShadowLayer()

    // Net average % change across the basket (signed).
    val netUp = net >= 0.0
    val netStr = "NET ${if (netUp) "+" else ""}${"%.2f".format(net)}%"
    val netPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD); textAlign = Paint.Align.CENTER
    }
    netPaint.textSize = size * 0.06f
    netPaint.color = withFullAlpha(if (netUp) POSITIVE else NEGATIVE)
    netPaint.setShadowLayer(size * 0.02f, 0f, 0f, withAlpha(if (netUp) POSITIVE else NEGATIVE, 0.5f))
    c.drawText(netStr, cx, cy + size * 0.06f, netPaint)
    netPaint.clearShadowLayer()

    // Split bar: green (up) | red (down).
    val barL = cx - size * 0.40f
    val barR = cx + size * 0.40f
    val barW = barR - barL
    val barTop = cy + size * 0.12f
    val barBot = cy + size * 0.22f
    val splitX = barL + barW * upFrac
    p.style = Paint.Style.FILL
    p.color = withAlpha(PANEL, 0.6f)
    c.drawRoundRect(RectF(barL, barTop, barR, barBot), size * 0.03f, size * 0.03f, p)
    if (upFrac > 0f) {
        p.color = withAlpha(POSITIVE, 0.85f)
        c.drawRoundRect(RectF(barL, barTop, splitX, barBot), size * 0.03f, size * 0.03f, p)
    }
    if (upFrac < 1f) {
        p.color = withAlpha(NEGATIVE, 0.85f)
        c.drawRoundRect(RectF(splitX, barTop, barR, barBot), size * 0.03f, size * 0.03f, p)
    }
    p.style = Paint.Style.STROKE
    p.strokeWidth = size * 0.012f
    p.color = withFullAlpha(WHITE)
    c.drawLine(splitX, barTop - size * 0.02f, splitX, barBot + size * 0.02f, p)

    // Up / down counts.
    val cnt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE; textAlign = Paint.Align.CENTER
    }
    cnt.textSize = size * 0.05f
    cnt.color = withFullAlpha(POSITIVE)
    c.drawText("UP $up", cx - size * 0.20f, cy + size * 0.36f, cnt)
    cnt.color = withFullAlpha(NEGATIVE)
    c.drawText("DN $down", cx + size * 0.20f, cy + size * 0.36f, cnt)

    p.style = Paint.Style.FILL
    p.strokeWidth = 0f
    p.pathEffect = null
    p.clearShadowLayer()
    p.color = withFullAlpha(CYAN)
}

// ===== drawDataPanel =====
private fun drawDataPanel(c: Canvas, p: Paint, l: Float, top: Float, r: Float, b: Float, lines: List<Pair<String, Int>>) {
    val w = r - l
    val h = b - top
    if (w <= 0f || h <= 0f) return

    val rect = RectF(l, top, r, b)
    val rad = (h * 0.04f).coerceIn(4f, 14f)

    p.clearShadowLayer()
    p.style = Paint.Style.FILL
    p.color = withAlpha(PANEL, 0.55f)
    p.pathEffect = null
    p.shader = null
    c.drawRoundRect(rect, rad, rad, p)

    p.style = Paint.Style.STROKE
    p.strokeWidth = (h * 0.006f).coerceAtLeast(1f)
    p.color = withAlpha(CYAN, 0.55f)
    c.drawRoundRect(rect, rad, rad, p)

    val br = (w * 0.07f).coerceIn(8f, 28f)
    val bw = (h * 0.010f).coerceAtLeast(1.5f)
    p.strokeWidth = bw
    p.color = withFullAlpha(CYAN)
    c.drawLine(l, top, l + br, top, p)
    c.drawLine(l, top, l, top + br, p)
    c.drawLine(r, top, r - br, top, p)
    c.drawLine(r, top, r, top + br, p)
    c.drawLine(l, b, l + br, b, p)
    c.drawLine(l, b, l, b - br, p)
    c.drawLine(r, b, r - br, b, p)
    c.drawLine(r, b, r, b - br, p)

    val pad = (w * 0.05f).coerceIn(6f, 22f)

    val headSize = (h * 0.085f).coerceIn(8f, 22f)
    val head = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textAlign = Paint.Align.LEFT
        textSize = headSize
        color = withFullAlpha(CYAN)
        letterSpacing = 0.12f
    }
    val headY = top + pad + headSize
    c.drawText("// LIVE FEED", l + pad, headY, head)

    p.style = Paint.Style.STROKE
    p.strokeWidth = (h * 0.004f).coerceAtLeast(1f)
    p.color = withAlpha(CYAN, 0.30f)
    val divY = headY + headSize * 0.45f
    c.drawLine(l + pad, divY, r - pad, divY, p)

    val n = lines.size
    if (n > 0) {
        val rowsTop = divY + (h * 0.04f)
        val rowsBottom = b - pad
        val avail = rowsBottom - rowsTop
        val slot = avail / n
        val rowSize = (slot * 0.62f).coerceIn(7f, headSize)

        val rowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.MONOSPACE
            textAlign = Paint.Align.LEFT
            textSize = rowSize
            letterSpacing = 0.04f
        }

        for (i in 0 until n) {
            val pair = lines[i]
            val baseY = rowsTop + slot * (i.toFloat() + 0.5f) + rowSize * 0.36f
            rowPaint.color = withFullAlpha(pair.second)
            p.style = Paint.Style.FILL
            p.color = withAlpha(pair.second, 0.85f)
            val markY = baseY - rowSize * 0.30f
            c.drawCircle(l + pad + rowSize * 0.20f, markY, rowSize * 0.16f, p)
            c.drawText(pair.first, l + pad + rowSize * 0.70f, baseY, rowPaint)
        }
    }

    p.style = Paint.Style.FILL
    p.strokeWidth = 0f
    p.pathEffect = null
    p.shader = null
    p.clearShadowLayer()
    p.color = withFullAlpha(WHITE)
}
    }

    private companion object {
        val VOID = 0xFF02060A.toInt()
        val BLACK = 0xFF000000.toInt()
        val PANEL = 0xFF081018.toInt()
        val CYAN = 0xFF45C8F5.toInt()
        val AMBER = 0xFFFFA23A.toInt()
        val WHITE = 0xFFE9F4FF.toInt()
        val INK = 0xFFE6EFFA.toInt()
        val MUTED = 0xFF4A6076.toInt()
        val POSITIVE = 0xFF46F9A0.toInt()
        val NEGATIVE = 0xFFFF4D6D.toInt()

        const val FRAME_MS = 33L
        const val DATA_INTERVAL_MS = 60_000L

        fun withAlpha(base: Int, a: Float): Int =
            (base and 0x00FFFFFF) or (((a.coerceIn(0f, 1f) * 255f).toInt()) shl 24)

        fun withFullAlpha(base: Int): Int = base or 0xFF000000.toInt()
    }
}
