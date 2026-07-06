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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * The J.A.R.V.I.S. live wallpaper — a dense, glanceable "Iron-Man-OS" command deck drawn to a raw [Canvas],
 * modelled element-for-element on the JARVIS dashboard reference: a header (wordmark · system status · live
 * clock · bell/gear), two live market sparkline charts, a topographical terrain strip, a SYS.OP.LINK GPS
 * panel, a WARFARE/COMM-RELAY column (spectrum · radar scope · subsystems · orbital · hex telemetry), a
 * Threats-by-Country map, a breaking-news card, and a // LIVE FEED ticker — all cyan/amber/green/red on near-black.
 *
 * Real on-device Pulse data drives the useful panels (clock, sparklines, GPS lat/lon, subsystems from device
 * health, news headline, live feed); the cockpit theater (spectrum/radar/globe/hex/topo/threats) is drawn
 * procedurally and deterministically. Frugal + defensive: draws only while visible, refreshes data ≤ once a
 * minute on a background coroutine, holds no bitmaps, and swallows any surface/IO error.
 */
class JarvisWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = ReactorEngine()

    private data class Snapshot(
        val battery: Int = -1,
        val memPct: Int = -1,                     // device RAM used %
        val tempC: Float = Float.NaN,             // battery temperature °C (thermal subsystem)
        val kp: Double = -1.0,                    // geomagnetic K-index 0..9
        val solarWind: Double = -1.0,             // solar wind speed km/s
        // Two live market sparklines (top-2 movers by |Δ%|) for the header charts.
        val spark: List<Float> = emptyList(),
        val sparkLabel: String = "MARKET-01",
        val sparkPct: Double = 0.0,
        val spark2: List<Float> = emptyList(),
        val sparkLabel2: String = "MARKET-02",
        val sparkPct2: Double = 0.0,
        // Market breadth (up/down + net avg) for the live-feed / status.
        val breadthUp: Int = 0,
        val breadthDown: Int = 0,
        val breadthNet: Double = 0.0,
        // GPS readout (saved location — no GPS wake).
        val lat: Double = Double.NaN,
        val lon: Double = Double.NaN,
        val place: String = "",
        val hasFix: Boolean = false,
        // Radar contacts: [nx, ny] normalized in the scope, kind (0 you, 1 incident, 2 waypoint).
        val markers: List<FloatArray> = emptyList(),
        // Breaking news card.
        val newsTitle: String = "",
        val newsSource: String = "",
        val newsAgeMs: Long = 0L,
        // // LIVE FEED lines: (text, colour).
        val lines: List<Pair<String, Int>> = emptyList(),
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

        // Cached time formatter — rebuilt on the ≤1/min data tick (not per frame). The reference shows a
        // seconds-resolution 24h clock, so we format HH:mm:ss ourselves; rebuilt ≤1/min picks up a locale change.
        private var clockFmt = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())

        private val frame = Runnable { drawFrame() }
        // Reused across a frame; the wallpaper is single-threaded on the main looper.
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

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
            clockFmt = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
            scope.launch {
                val app = applicationContext as? PulseApplication ?: return@launch
                val c = app.container
                val s = runCatching { c.settingsRepository.current() }.getOrNull()
                val out = mutableListOf<Pair<String, Int>>()
                var spark: List<Float> = emptyList(); var sparkLabel = "MARKET-01"; var sparkPct = 0.0
                var spark2: List<Float> = emptyList(); var sparkLabel2 = "MARKET-02"; var sparkPct2 = 0.0
                var breadthUp = 0; var breadthDown = 0; var breadthNet = 0.0
                var kp = -1.0; var solarWind = -1.0
                var lat = Double.NaN; var lon = Double.NaN; var place = ""; var hasFix = false
                var newsTitle = ""; var newsSource = ""; var newsAgeMs = 0L
                val markers = mutableListOf<FloatArray>()

                val saved = s?.let { it.savedLocations.getOrNull(it.selectedLocationIndex) ?: it.savedLocations.firstOrNull() }
                if (saved != null) { lat = saved.latitude; lon = saved.longitude; place = saved.name; hasFix = true }

                // Markets: two sparklines (top-2 movers) + breadth + two feed lines.
                runCatching {
                    val quotes = c.marketsRepository.fetchWatchlist(force = false).data.orEmpty()
                        .filter { it.changePercent != null }
                    MarketMood.summarize(quotes.mapNotNull { it.changePercent })?.let { mood ->
                        breadthUp = mood.up; breadthDown = mood.down; breadthNet = mood.netChangePct
                    }
                    val movers = quotes.sortedByDescending { abs(it.changePercent ?: 0.0) }
                    movers.take(2).forEach { q ->
                        val pct = q.changePercent ?: 0.0
                        out.add("MKT  ${q.label.take(12)}  ${signed(pct)}%" to if (pct >= 0) POSITIVE else NEGATIVE)
                    }
                    val withSpark = movers.filter { it.sparkline.size >= 2 }
                    withSpark.getOrNull(0)?.let { spark = norm(it.sparkline); sparkLabel = it.label.take(14).uppercase(); sparkPct = it.changePercent ?: 0.0 }
                    withSpark.getOrNull(1)?.let { spark2 = norm(it.sparkline); sparkLabel2 = it.label.take(14).uppercase(); sparkPct2 = it.changePercent ?: 0.0 }
                }
                // News: breaking headline card + a feed line.
                runCatching {
                    c.newsRepository.fetchCategory(NewsCategory.TOP, force = false).data?.firstOrNull()?.let { a ->
                        newsTitle = a.title; newsSource = a.source; newsAgeMs = a.publishedEpochMs
                        out.add("NEWS ${a.title.take(40)}" to CYAN)
                    }
                }
                // Space weather: Kp + solar wind + a feed line.
                runCatching {
                    val sw = c.spaceWeatherRepository.fetch(force = false).data
                    kp = sw?.kp ?: -1.0; solarWind = sw?.solarWindSpeed ?: -1.0
                    if (kp >= 0.0) out.add("SPC  ${SpaceWeatherExplainers.kp(kp).headline}" to if (kp >= 5.0) AMBER else CYAN)
                }
                // Radar contacts: your location (centre) + nearby incidents + active waypoint, mapped to the scope.
                runCatching {
                    if (saved != null) {
                        markers.add(floatArrayOf(0f, 0f, 0f))
                        c.safetyRepository.fetch(saved.latitude, saved.longitude, force = false).data?.incidents.orEmpty().take(7).forEach { ev ->
                            val dx = ((ev.longitude - saved.longitude) * 6.0).toFloat().coerceIn(-1f, 1f)
                            val dy = ((saved.latitude - ev.latitude) * 6.0).toFloat().coerceIn(-1f, 1f)
                            markers.add(floatArrayOf(dx, dy, 1f))
                        }
                    }
                    s?.let { st -> st.waypoints.firstOrNull { it.id == st.activeWaypointId } }?.let { wp ->
                        if (saved != null) {
                            val dx = ((wp.longitude - saved.longitude) * 6.0).toFloat().coerceIn(-1f, 1f)
                            val dy = ((saved.latitude - wp.latitude) * 6.0).toFloat().coerceIn(-1f, 1f)
                            markers.add(floatArrayOf(dx, dy, 2f))
                        }
                    }
                }
                val memPct = runCatching {
                    val am = applicationContext.getSystemService(android.app.ActivityManager::class.java)
                    val mi = android.app.ActivityManager.MemoryInfo(); am?.getMemoryInfo(mi)
                    if (mi.totalMem > 0L) (((mi.totalMem - mi.availMem) * 100.0) / mi.totalMem).toInt() else -1
                }.getOrDefault(-1)
                val battery = runCatching {
                    applicationContext.getSystemService(BatteryManager::class.java)
                        ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
                }.getOrDefault(-1)
                val tempC = runCatching {
                    val bm = applicationContext.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
                    val t = bm?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
                    if (t > 0) t / 10f else Float.NaN
                }.getOrDefault(Float.NaN)

                snapshot = Snapshot(
                    battery, memPct, tempC, kp, solarWind,
                    spark, sparkLabel, sparkPct, spark2, sparkLabel2, sparkPct2,
                    breadthUp, breadthDown, breadthNet,
                    lat, lon, place, hasFix, markers,
                    newsTitle, newsSource, newsAgeMs, out,
                )
            }
        }

        // ============================ RENDER ============================
        private fun render(canvas: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            if (w <= 0f || h <= 0f) return
            canvas.drawColor(VOID)
            val u = minOf(w, h)                 // scale unit
            val t = SystemClock.uptimeMillis() - startMs
            val snap = snapshot
            val timeStr = runCatching { clockFmt.format(java.util.Date()) }.getOrDefault("--:--:--")

            val mL = w * 0.030f; val mR = w * 0.970f      // page margins
            val status = systemStatus(snap)

            // 1) HEADER
            drawHeader(canvas, w, h, u, timeStr, status.first, status.second)

            // 2) TWO MARKET SPARKLINE CHARTS
            drawSparkChart(canvas, mL, h * 0.070f, mR, h * 0.150f, u, t, snap.sparkLabel, snap.spark, snap.sparkPct)
            drawSparkChart(canvas, mL, h * 0.160f, mR, h * 0.240f, u, t, snap.sparkLabel2, snap.spark2, snap.sparkPct2)

            // 3) TOPOGRAPHICAL MAPPING STRIP
            drawTopoStrip(canvas, mL, h * 0.252f, mR, h * 0.330f, u, t)

            // 4) TWO-COLUMN MID REGION
            val midL0 = mL; val midL1 = w * 0.492f
            val midR0 = w * 0.508f; val midR1 = mR
            // Left-top: SYS.OP.LINK GPS panel
            drawSysOpLink(canvas, midL0, h * 0.344f, midL1, h * 0.560f, u, t, snap)
            // Left-bottom: Threats by Country
            drawThreats(canvas, midL0, h * 0.572f, midL1, h * 0.780f, u, t)
            // Right column: WARFARE / COMM RELAY (spans the whole mid region)
            drawCommRelay(canvas, midR0, h * 0.344f, midR1, h * 0.780f, u, t, snap)

            // 5) BREAKING NEWS CARD
            drawNewsCard(canvas, mL, h * 0.792f, mR, h * 0.884f, u, snap)

            // 6) // LIVE FEED
            drawLiveFeed(canvas, mL, h * 0.896f, mR, h * 0.990f, u, snap)
        }
    }

    // ============================ HELPERS ============================

    /** Draw monospace text. Returns the advance width so callers can lay out inline runs. */
    private fun txt(
        c: Canvas, s: String, x: Float, y: Float, size: Float, color: Int,
        bold: Boolean = false, align: Paint.Align = Paint.Align.LEFT, spacing: Float = 0f, glow: Float = 0f,
    ): Float {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.typeface = if (bold) MONO_BOLD else MONO
        p.textSize = size; p.color = withFullAlpha(color); p.textAlign = align; p.letterSpacing = spacing
        if (glow > 0f) p.setShadowLayer(glow, 0f, 0f, withAlpha(color, 0.8f))
        c.drawText(s, x, y, p)
        return p.measureText(s)
    }

    /** Faint panel fill + a corner-bracket frame in [accent]. */
    private fun panel(c: Canvas, x0: Float, y0: Float, x1: Float, y1: Float, accent: Int, u: Float, fillA: Float = 0.30f) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        // fill
        p.style = Paint.Style.FILL; p.color = withAlpha(PANEL, fillA)
        c.drawRect(x0, y0, x1, y1, p)
        // faint full border
        p.style = Paint.Style.STROKE; p.strokeWidth = u * 0.0016f; p.color = withAlpha(accent, 0.22f)
        c.drawRect(x0, y0, x1, y1, p)
        // bright corner brackets
        p.strokeWidth = u * 0.0035f; p.color = withAlpha(accent, 0.9f)
        val a = u * 0.020f
        // TL
        c.drawLine(x0, y0, x0 + a, y0, p); c.drawLine(x0, y0, x0, y0 + a, p)
        // TR
        c.drawLine(x1, y0, x1 - a, y0, p); c.drawLine(x1, y0, x1, y0 + a, p)
        // BL
        c.drawLine(x0, y1, x0 + a, y1, p); c.drawLine(x0, y1, x0, y1 - a, p)
        // BR
        c.drawLine(x1, y1, x1 - a, y1, p); c.drawLine(x1, y1, x1, y1 - a, p)
    }

    /** A titled section rule: ■ + TITLE + hairline to the right edge. Returns the baseline used. */
    private fun sectionHead(c: Canvas, s: String, x0: Float, x1: Float, y: Float, u: Float, accent: Int): Float {
        val sz = u * 0.020f
        val p = Paint(Paint.ANTI_ALIAS_FLAG); p.color = withFullAlpha(accent)
        c.drawRect(x0, y - sz * 0.8f, x0 + sz * 0.7f, y - sz * 0.1f, p)
        val adv = txt(c, s, x0 + sz * 1.1f, y, sz, accent, bold = true, spacing = 0.10f)
        p.strokeWidth = u * 0.0016f; p.color = withAlpha(accent, 0.4f)
        c.drawLine(x0 + sz * 1.3f + adv, y - sz * 0.35f, x1, y - sz * 0.35f, p)
        return y
    }

    // ---------- 1) HEADER ----------
    private fun drawHeader(c: Canvas, w: Float, h: Float, u: Float, timeStr: String, statusText: String, statusColor: Int) {
        val y = h * 0.028f
        // Wordmark + subtitle (left)
        txt(c, "JARVIS", w * 0.030f, y, u * 0.036f, WHITE, bold = true, spacing = 0.14f, glow = u * 0.02f)
        txt(c, "JUST A RATHER VERY INTELLIGENT SYSTEM", w * 0.030f, y + u * 0.024f, u * 0.0135f, CYAN, spacing = 0.08f)
        // SYSTEM STATUS
        txt(c, "SYSTEM STATUS", w * 0.400f, y - u * 0.010f, u * 0.0125f, MUTED, spacing = 0.10f)
        val p = Paint(Paint.ANTI_ALIAS_FLAG); p.color = withFullAlpha(statusColor)
        c.drawCircle(w * 0.400f + u * 0.010f, y + u * 0.014f, u * 0.007f, p)
        txt(c, statusText, w * 0.400f + u * 0.024f, y + u * 0.020f, u * 0.019f, statusColor, bold = true, spacing = 0.06f)
        // LOCAL TIME
        txt(c, "LOCAL TIME", w * 0.560f, y - u * 0.010f, u * 0.0125f, MUTED, spacing = 0.10f)
        txt(c, timeStr, w * 0.560f, y + u * 0.020f, u * 0.022f, CYAN, bold = true, spacing = 0.06f, glow = u * 0.012f)
        // Bell + gear glyphs (decorative — a wallpaper isn't touchable)
        glyphBell(c, w * 0.905f, y + u * 0.006f, u * 0.017f)
        glyphGear(c, w * 0.955f, y + u * 0.006f, u * 0.017f)
        // header underline
        p.style = Paint.Style.STROKE; p.strokeWidth = u * 0.0016f; p.color = withAlpha(CYAN, 0.25f)
        c.drawLine(w * 0.030f, h * 0.058f, w * 0.970f, h * 0.058f, p)
    }

    private fun glyphBell(c: Canvas, cx: Float, cy: Float, r: Float) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG); p.style = Paint.Style.STROKE
        p.strokeWidth = r * 0.14f; p.color = withAlpha(CYAN, 0.85f)
        val path = Path()
        path.moveTo(cx - r * 0.7f, cy + r * 0.5f)
        path.quadTo(cx - r * 0.7f, cy - r * 0.7f, cx, cy - r * 0.8f)
        path.quadTo(cx + r * 0.7f, cy - r * 0.7f, cx + r * 0.7f, cy + r * 0.5f)
        c.drawPath(path, p)
        c.drawLine(cx - r * 0.85f, cy + r * 0.5f, cx + r * 0.85f, cy + r * 0.5f, p)
        p.style = Paint.Style.FILL
        c.drawCircle(cx, cy + r * 0.8f, r * 0.16f, p)
    }

    private fun glyphGear(c: Canvas, cx: Float, cy: Float, r: Float) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG); p.style = Paint.Style.STROKE
        p.strokeWidth = r * 0.14f; p.color = withAlpha(CYAN, 0.85f)
        c.drawCircle(cx, cy, r * 0.5f, p)
        for (i in 0 until 8) {
            val a = Math.toRadians(i * 45.0)
            val sx = cx + (r * 0.6f * cos(a)).toFloat(); val sy = cy + (r * 0.6f * sin(a)).toFloat()
            val ex = cx + (r * 0.9f * cos(a)).toFloat(); val ey = cy + (r * 0.9f * sin(a)).toFloat()
            c.drawLine(sx, sy, ex, ey, p)
        }
    }

    // ---------- 2) SPARKLINE CHART ----------
    private fun drawSparkChart(c: Canvas, x0: Float, y0: Float, x1: Float, y1: Float, u: Float, t: Long, label: String, series: List<Float>, pct: Double) {
        panel(c, x0, y0, x1, y1, CYAN, u, fillA = 0.22f)
        val pad = u * 0.012f
        txt(c, label, x0 + pad, y0 + u * 0.022f, u * 0.0135f, CYAN, spacing = 0.10f)
        if (series.isEmpty()) {
            txt(c, "AWAITING FEED…", x0 + pad, (y0 + y1) / 2f, u * 0.014f, MUTED)
            return
        }
        val plotT = y0 + u * 0.030f; val plotB = y1 - pad
        val plotL = x0 + pad; val plotR = x1 - pad
        // faint baseline grid
        val g = Paint(Paint.ANTI_ALIAS_FLAG); g.style = Paint.Style.STROKE; g.strokeWidth = u * 0.0010f
        g.color = withAlpha(CYAN, 0.10f)
        for (i in 0..3) { val gy = plotT + (plotB - plotT) * i / 3f; c.drawLine(plotL, gy, plotR, gy, g) }
        // line
        val up = pct >= 0.0
        val lineCol = CYAN
        val path = Path(); val n = series.size
        for (i in series.indices) {
            val px = plotL + (plotR - plotL) * i / (n - 1).coerceAtLeast(1)
            val py = plotB - (plotB - plotT) * series[i]
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        val lp = Paint(Paint.ANTI_ALIAS_FLAG); lp.style = Paint.Style.STROKE
        lp.strokeWidth = u * 0.0026f; lp.color = withFullAlpha(lineCol)
        lp.setShadowLayer(u * 0.012f, 0f, 0f, withAlpha(lineCol, 0.6f))
        c.drawPath(path, lp)
        lp.clearShadowLayer()
        // moving endpoint dot
        val lastX = plotR; val lastY = plotB - (plotB - plotT) * series.last()
        val dp = Paint(Paint.ANTI_ALIAS_FLAG); dp.color = withFullAlpha(lineCol)
        val pulse = 0.6f + 0.4f * abs(sin(t / 500.0)).toFloat()
        c.drawCircle(lastX, lastY, u * 0.004f * pulse + u * 0.002f, dp)
        // % readout (right)
        txt(c, "${signed(pct)}%", x1 - pad, y0 + u * 0.022f, u * 0.0145f, if (up) POSITIVE else NEGATIVE, bold = true, align = Paint.Align.RIGHT)
    }

    // ---------- 3) TOPOGRAPHICAL STRIP ----------
    private fun drawTopoStrip(c: Canvas, x0: Float, y0: Float, x1: Float, y1: Float, u: Float, t: Long) {
        panel(c, x0, y0, x1, y1, CYAN, u, fillA = 0.20f)
        val pad = u * 0.012f
        txt(c, "TOPOGRAPHICAL MAPPING: SECTOR 4", x0 + pad, y0 + u * 0.022f, u * 0.015f, CYAN, bold = true, spacing = 0.06f)
        txt(c, "CONTOUR INTERVAL: 10M  |  GROUND DENSITY: NOMINAL  |  SUB-SURFACE: DENSE  |  PEAK: 84M",
            x0 + pad, y0 + u * 0.040f, u * 0.0115f, MUTED, spacing = 0.02f)
        // terrain silhouette on the right ~60%
        val tL = x0 + (x1 - x0) * 0.42f; val tR = x1 - pad
        val base = y1 - pad; val topY = y0 + u * 0.048f
        val path = Path(); path.moveTo(tL, base)
        val steps = 26
        for (i in 0..steps) {
            val fx = i / steps.toFloat()
            val px = tL + (tR - tL) * fx
            // deterministic ridged silhouette (a couple of stacked sines) — stable, not animated
            val n = (sin(fx * 9.0) * 0.5 + sin(fx * 21.0 + 1.3) * 0.28 + sin(fx * 4.0 + 0.6) * 0.5).toFloat()
            val hgt = ((n * 0.5f + 0.5f).coerceIn(0f, 1f)) * (base - topY)
            path.lineTo(px, base - hgt)
        }
        path.lineTo(tR, base); path.close()
        val fp = Paint(Paint.ANTI_ALIAS_FLAG); fp.style = Paint.Style.FILL; fp.color = withAlpha(CYAN, 0.10f)
        c.drawPath(path, fp)
        fp.style = Paint.Style.STROKE; fp.strokeWidth = u * 0.0018f; fp.color = withAlpha(CYAN, 0.7f)
        c.drawPath(path, fp)
        // peak marker: dashed vertical + dot (slowly drifting)
        val drift = (0.35f + 0.30f * abs(sin(t / 4000.0)).toFloat())
        val markX = tL + (tR - tL) * drift
        // find silhouette height at markX
        val fx = ((markX - tL) / (tR - tL)).coerceIn(0f, 1f)
        val nn = (sin(fx * 9.0) * 0.5 + sin(fx * 21.0 + 1.3) * 0.28 + sin(fx * 4.0 + 0.6) * 0.5).toFloat()
        val peakY = base - ((nn * 0.5f + 0.5f).coerceIn(0f, 1f)) * (base - topY)
        val dp = Paint(Paint.ANTI_ALIAS_FLAG); dp.style = Paint.Style.STROKE; dp.strokeWidth = u * 0.0014f
        dp.color = withAlpha(NEGATIVE, 0.7f); dp.pathEffect = DashPathEffect(floatArrayOf(u * 0.006f, u * 0.006f), 0f)
        c.drawLine(markX, topY, markX, base, dp); dp.pathEffect = null
        dp.style = Paint.Style.FILL; dp.color = withFullAlpha(NEGATIVE)
        c.drawCircle(markX, peakY, u * 0.004f, dp)
        txt(c, "PEAK: 84M", markX + u * 0.006f, topY + u * 0.014f, u * 0.011f, NEGATIVE)
    }

    // ---------- 4a) SYS.OP.LINK GPS PANEL ----------
    private fun drawSysOpLink(c: Canvas, x0: Float, y0: Float, x1: Float, y1: Float, u: Float, t: Long, s: Snapshot) {
        panel(c, x0, y0, x1, y1, CYAN, u, fillA = 0.28f)
        val pad = u * 0.014f; var yy = y0 + u * 0.028f
        txt(c, "[SYS.OP.LINK.ACTV]", x0 + pad, yy, u * 0.018f, CYAN, bold = true, spacing = 0.04f, glow = u * 0.008f)
        // hairline under title
        val hp = Paint(Paint.ANTI_ALIAS_FLAG); hp.style = Paint.Style.STROKE; hp.strokeWidth = u * 0.0014f
        hp.color = withAlpha(CYAN, 0.3f); c.drawLine(x0 + pad, yy + u * 0.010f, x1 - pad, yy + u * 0.010f, hp)
        yy += u * 0.040f
        val val0 = if (s.hasFix) POSITIVE else MUTED
        val lat = if (s.lat.isNaN()) "----" else "%.3f° %s".format(abs(s.lat), if (s.lat >= 0) "N" else "S")
        val lon = if (s.lon.isNaN()) "----" else "%.3f° %s".format(abs(s.lon), if (s.lon >= 0) "E" else "W")
        txt(c, "LAT: $lat", x0 + pad, yy, u * 0.016f, val0); yy += u * 0.024f
        txt(c, "LON: $lon", x0 + pad, yy, u * 0.016f, val0); yy += u * 0.024f
        txt(c, "ALT: ${if (s.hasFix) "—— MSL" else "----"}", x0 + pad, yy, u * 0.016f, MUTED); yy += u * 0.030f
        txt(c, "SAT-LOCK: ${if (s.hasFix) "ACQUIRED" else "SEARCHING"}", x0 + pad, yy, u * 0.015f, if (s.hasFix) POSITIVE else AMBER); yy += u * 0.022f
        txt(c, "UPLINK: SECURE E2E", x0 + pad, yy, u * 0.015f, POSITIVE); yy += u * 0.034f
        // TX / RX / S-N bars — TX=battery, RX=link (100-mem), S-N=derived
        val barW = (x1 - pad) - (x0 + pad + u * 0.075f)
        drawLabeledBar(c, "TX PWR", x0 + pad, yy, u * 0.075f, barW, u, s.battery.coerceIn(0, 100) / 100f, POSITIVE); yy += u * 0.026f
        val rx = if (s.memPct in 0..100) (100 - s.memPct) / 100f else 0.6f
        drawLabeledBar(c, "RX GAIN", x0 + pad, yy, u * 0.075f, barW, u, rx, CYAN); yy += u * 0.026f
        val sn = (0.45f + 0.2f * abs(sin(t / 1700.0)).toFloat())
        drawLabeledBar(c, "S/N RAT", x0 + pad, yy, u * 0.075f, barW, u, sn, AMBER); yy += u * 0.040f
        txt(c, "NODE CLUSTER ONL", (x0 + x1) / 2f, yy, u * 0.013f, CYAN, align = Paint.Align.CENTER, spacing = 0.06f); yy += u * 0.020f
        txt(c, "ROUTING: OPTIMAL", (x0 + x1) / 2f, yy, u * 0.013f, CYAN, align = Paint.Align.CENTER, spacing = 0.06f)
    }

    private fun drawLabeledBar(c: Canvas, label: String, x: Float, y: Float, labelW: Float, barW: Float, u: Float, frac: Float, color: Int) {
        txt(c, label, x, y + u * 0.004f, u * 0.013f, INK)
        val bx = x + labelW; val bh = u * 0.010f
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = withAlpha(CYAN, 0.14f); c.drawRect(bx, y - bh, bx + barW, y, p)
        p.color = withFullAlpha(color); c.drawRect(bx, y - bh, bx + barW * frac.coerceIn(0f, 1f), y, p)
    }

    // ---------- 4b) THREATS BY COUNTRY ----------
    private fun drawThreats(c: Canvas, x0: Float, y0: Float, x1: Float, y1: Float, u: Float, t: Long) {
        panel(c, x0, y0, x1, y1, NEGATIVE, u, fillA = 0.26f)
        val pad = u * 0.014f
        txt(c, "Threats by Country", x0 + pad, y0 + u * 0.024f, u * 0.016f, INK, bold = true)
        // world map (upper ~45%)
        val mapT = y0 + u * 0.034f; val mapB = y0 + (y1 - y0) * 0.42f
        val mp = Paint(Paint.ANTI_ALIAS_FLAG)
        for (poly in WORLD_CONTINENTS) {
            val path = Path()
            for ((i, pt) in poly.withIndex()) {
                val px = x0 + pad + (x1 - pad - x0 - pad) * pt[0]
                val py = mapT + (mapB - mapT) * pt[1]
                if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
            path.close()
            mp.style = Paint.Style.FILL; mp.color = withAlpha(NEGATIVE, 0.12f); c.drawPath(path, mp)
            mp.style = Paint.Style.STROKE; mp.strokeWidth = u * 0.0012f; mp.color = withAlpha(NEGATIVE, 0.4f); c.drawPath(path, mp)
        }
        // hot-zone blips (deterministic positions) with a soft pulse
        val pulse = 0.5f + 0.5f * abs(sin(t / 900.0)).toFloat()
        mp.style = Paint.Style.FILL
        for (z in THREAT_ZONES) {
            val px = x0 + pad + (x1 - pad - x0 - pad) * z[0]
            val py = mapT + (mapB - mapT) * z[1]
            mp.color = withAlpha(NEGATIVE, 0.18f * pulse); c.drawCircle(px, py, u * 0.012f * pulse, mp)
            mp.color = withFullAlpha(NEGATIVE); c.drawCircle(px, py, u * 0.004f, mp)
        }
        // ranked list (lower ~55%)
        var ly = mapB + u * 0.030f
        val rowH = ((y1 - u * 0.012f) - ly) / THREATS.size
        for ((name, n) in THREATS) {
            mp.color = withFullAlpha(NEGATIVE); c.drawCircle(x0 + pad + u * 0.006f, ly - u * 0.005f, u * 0.005f, mp)
            txt(c, name, x0 + pad + u * 0.020f, ly, u * 0.014f, INK)
            txt(c, n.toString(), x1 - pad, ly, u * 0.015f, CYAN, bold = true, align = Paint.Align.RIGHT)
            ly += rowH
        }
    }

    // ---------- 4c) WARFARE / COMM RELAY ----------
    private fun drawCommRelay(c: Canvas, x0: Float, y0: Float, x1: Float, y1: Float, u: Float, t: Long, s: Snapshot) {
        panel(c, x0, y0, x1, y1, CYAN, u, fillA = 0.24f)
        val pad = u * 0.014f
        var yy = y0 + u * 0.026f
        sectionHead(c, "WARFARE / COMM RELAY", x0 + pad, x1 - pad, yy, u, CYAN); yy += u * 0.026f
        // FREQ SPECTRUM SCAN
        txt(c, "FREQ SPECTRUM SCAN [SHF]", x0 + pad, yy, u * 0.012f, MUTED, spacing = 0.04f); yy += u * 0.012f
        val specT = yy; val specB = yy + u * 0.055f
        drawSpectrum(c, x0 + pad, specT, x1 - pad, specB, u, t)
        yy = specB + u * 0.024f
        // RADAR SCOPE
        val scopeR = (x1 - x0) * 0.30f
        val scopeCx = (x0 + x1) / 2f; val scopeCy = yy + scopeR
        drawRadarScope(c, scopeCx, scopeCy, scopeR, u, t, s.markers)
        yy = scopeCy + scopeR + u * 0.030f
        // SUBSYSTEMS
        txt(c, "[SUBSYSTEMS]", x0 + pad, yy, u * 0.013f, CYAN, bold = true, spacing = 0.04f); yy += u * 0.020f
        val thermalOk = s.tempC.isNaN() || s.tempC < 40f
        val subs = listOf(
            Triple("SECURE ENCRYPTION", POSITIVE, "OK"),
            Triple("GYRO STABILIZATION", POSITIVE, "OK"),
            Triple("SATELLITE UPLINK", if (s.hasFix) POSITIVE else AMBER, if (s.hasFix) "OK" else "SRCH"),
            Triple("THERMAL LOAD (${if (thermalOk) "NOM" else "HI"})", if (thermalOk) AMBER else NEGATIVE, ""),
            Triple("POWER DISTRIBUTION", if (s.battery in 0..20) NEGATIVE else POSITIVE, if (s.battery >= 0) "${s.battery}%" else "OK"),
        )
        val sp = Paint(Paint.ANTI_ALIAS_FLAG)
        for ((name, col, tag) in subs) {
            sp.color = withFullAlpha(col); c.drawRect(x0 + pad, yy - u * 0.011f, x0 + pad + u * 0.011f, yy, sp)
            txt(c, name, x0 + pad + u * 0.018f, yy, u * 0.0125f, INK)
            if (tag.isNotEmpty()) txt(c, tag, x1 - pad, yy, u * 0.0115f, col, align = Paint.Align.RIGHT)
            yy += u * 0.019f
        }
        yy += u * 0.010f
        // ORBITAL GLOBE
        val globeR = (x1 - x0) * 0.24f
        val globeCx = (x0 + x1) / 2f; val globeCy = yy + globeR
        drawOrbital(c, globeCx, globeCy, globeR, u, t)
        yy = globeCy + globeR + u * 0.026f
        // ENCRYPTED TELEMETRY STREAM (hex)
        txt(c, "ENCRYPTED TELEMETRY STREAM", x0 + pad, yy, u * 0.012f, CYAN, bold = true, spacing = 0.02f); yy += u * 0.016f
        drawHexStream(c, x0 + pad, yy, x1 - pad, y1 - pad, u, t)
    }

    private fun drawSpectrum(c: Canvas, x0: Float, y0: Float, x1: Float, y1: Float, u: Float, t: Long) {
        val bars = 26; val gap = (x1 - x0) / bars
        val p = Paint(Paint.ANTI_ALIAS_FLAG); p.style = Paint.Style.FILL
        // baseline
        p.strokeWidth = u * 0.0012f; p.style = Paint.Style.STROKE; p.color = withAlpha(CYAN, 0.2f)
        c.drawLine(x0, y1, x1, y1, p); p.style = Paint.Style.FILL
        for (i in 0 until bars) {
            val phase = i * 0.7 + t / 260.0
            val hFrac = (0.25 + 0.75 * (0.5 + 0.5 * sin(phase)) * (0.5 + 0.5 * sin(i * 2.3 + t / 900.0))).toFloat().coerceIn(0.08f, 1f)
            val bx = x0 + gap * i + gap * 0.18f
            val bw = gap * 0.64f
            val by = y1 - (y1 - y0) * hFrac
            // colour by band: mostly green, a few amber/red peaks
            p.color = withFullAlpha(when {
                hFrac > 0.85f && i % 7 == 0 -> NEGATIVE
                hFrac > 0.7f && i % 3 == 0 -> AMBER
                else -> POSITIVE
            })
            c.drawRect(bx, by, bx + bw, y1, p)
        }
    }

    private fun drawRadarScope(c: Canvas, cx: Float, cy: Float, r: Float, u: Float, t: Long, markers: List<FloatArray>) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        // rings
        p.style = Paint.Style.STROKE; p.strokeWidth = u * 0.0012f
        for (i in 1..3) { p.color = withAlpha(POSITIVE, 0.18f); c.drawCircle(cx, cy, r * i / 3f, p) }
        // crosshair
        p.color = withAlpha(POSITIVE, 0.14f)
        c.drawLine(cx - r, cy, cx + r, cy, p); c.drawLine(cx, cy - r, cx, cy + r, p)
        // sweep wedge
        val sweep = (t / 22.0) % 360.0
        val wedge = Path(); wedge.moveTo(cx, cy)
        val steps = 22; val span = 55.0
        for (i in 0..steps) {
            val a = Math.toRadians(sweep - span * i / steps)
            wedge.lineTo(cx + (r * sin(a)).toFloat(), cy - (r * cos(a)).toFloat())
        }
        wedge.close()
        p.style = Paint.Style.FILL; p.shader = android.graphics.RadialGradient(
            cx, cy, r, withAlpha(POSITIVE, 0.35f), withAlpha(POSITIVE, 0.0f), android.graphics.Shader.TileMode.CLAMP)
        c.drawPath(wedge, p); p.shader = null
        // leading edge
        p.style = Paint.Style.STROKE; p.strokeWidth = u * 0.0018f; p.color = withFullAlpha(POSITIVE)
        val sa = Math.toRadians(sweep)
        c.drawLine(cx, cy, cx + (r * sin(sa)).toFloat(), cy - (r * cos(sa)).toFloat(), p)
        // contacts (real incident markers + your position)
        p.style = Paint.Style.FILL
        if (markers.isEmpty()) {
            // decorative blips if no real data
            for (b in DECOR_BLIPS) {
                p.color = withAlpha(POSITIVE, 0.8f)
                c.drawCircle(cx + r * 0.7f * b[0], cy + r * 0.7f * b[1], u * 0.003f, p)
            }
        } else {
            for (m in markers) {
                val col = when (m[2].toInt()) { 0 -> CYAN; 2 -> AMBER; else -> NEGATIVE }
                p.color = withFullAlpha(col)
                c.drawCircle(cx + r * 0.85f * m[0].coerceIn(-1f, 1f), cy + r * 0.85f * m[1].coerceIn(-1f, 1f), u * 0.0035f, p)
            }
        }
        // centre
        p.color = withFullAlpha(POSITIVE); c.drawCircle(cx, cy, u * 0.003f, p)
    }

    private fun drawOrbital(c: Canvas, cx: Float, cy: Float, r: Float, u: Float, t: Long) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG); p.style = Paint.Style.STROKE; p.strokeWidth = u * 0.0012f
        // globe
        p.color = withAlpha(CYAN, 0.35f); c.drawCircle(cx, cy, r, p)
        // graticule
        p.color = withAlpha(CYAN, 0.18f)
        for (i in 1..2) { val rx = r * i / 3f; c.drawOval(RectF(cx - r, cy - rx, cx + r, cy + rx), p) }
        c.drawLine(cx, cy - r, cx, cy + r, p)
        val ov = RectF(cx - r * 0.45f, cy - r, cx + r * 0.45f, cy + r); c.drawOval(ov, p)
        // dashed orbit ellipse (tilted) + moving satellites
        p.color = withAlpha(CYAN, 0.55f); p.pathEffect = DashPathEffect(floatArrayOf(u * 0.006f, u * 0.005f), 0f)
        val orbit = RectF(cx - r * 1.15f, cy - r * 0.5f, cx + r * 1.15f, cy + r * 0.5f)
        c.drawOval(orbit, p); p.pathEffect = null
        p.style = Paint.Style.FILL
        val a1 = t / 1400.0
        val s1x = cx + (r * 1.15f * cos(a1)).toFloat(); val s1y = cy + (r * 0.5f * sin(a1)).toFloat()
        p.color = withFullAlpha(NEGATIVE); c.drawCircle(s1x, s1y, u * 0.004f, p)
        txt(c, "AO-ZETA", s1x + u * 0.006f, s1y, u * 0.010f, NEGATIVE)
        val a2 = a1 + Math.PI
        val s2x = cx + (r * 1.15f * cos(a2)).toFloat(); val s2y = cy + (r * 0.5f * sin(a2)).toFloat()
        p.color = withFullAlpha(POSITIVE); c.drawCircle(s2x, s2y, u * 0.004f, p)
        txt(c, "MIL-SAT", s2x - u * 0.05f, s2y, u * 0.010f, POSITIVE)
    }

    private fun drawHexStream(c: Canvas, x0: Float, y0: Float, x1: Float, y1: Float, u: Float, t: Long) {
        val rows = 5; val rowH = (y1 - y0) / rows
        val size = u * 0.0105f
        var yy = y0 + size
        val roll = (t / 700L).toInt()
        for (r in 0 until rows) {
            val addr = "0x0F%02X:".format((r * 0x10 + roll * 0x0E) and 0xFF)
            val sb = StringBuilder()
            for (b in 0 until 10) {
                val v = ((r * 37 + b * 53 + roll * 17) and 0xFF)
                sb.append("%02X ".format(v))
            }
            txt(c, addr, x0, yy, size, MUTED, spacing = 0.02f)
            // highlight a couple of "hot" bytes in red for effect (deterministic)
            txt(c, sb.toString().trim(), x0 + u * 0.055f, yy, size, if ((r + roll) % 3 == 0) NEGATIVE else INK, spacing = 0.04f)
            yy += rowH
        }
    }

    // ---------- 5) BREAKING NEWS CARD ----------
    private fun drawNewsCard(c: Canvas, x0: Float, y0: Float, x1: Float, y1: Float, u: Float, s: Snapshot) {
        panel(c, x0, y0, x1, y1, NEGATIVE, u, fillA = 0.30f)
        val pad = u * 0.020f
        val title = s.newsTitle.orIfBlank("AWAITING BREAKING NEWS FEED…")
        // headline (white, wrapped to 2 lines, proportional-ish via mono)
        val hSize = u * 0.026f
        val maxChars = ((x1 - x0 - pad * 2) / (hSize * 0.60f)).toInt().coerceAtLeast(8)
        val (l1, l2) = wrap2(title, maxChars)
        txt(c, l1, x0 + pad, y0 + u * 0.036f, hSize, WHITE, bold = true)
        if (l2.isNotEmpty()) txt(c, l2, x0 + pad, y0 + u * 0.036f + hSize * 1.15f, hSize, WHITE, bold = true)
        // source line (red)
        val age = if (s.newsAgeMs > 0L) Formatters.relativeTime(s.newsAgeMs).uppercase() else ""
        val src = if (s.newsSource.isNotBlank()) "${s.newsSource.uppercase()} · $age" else "PULSE WIRE · LIVE"
        txt(c, src, x0 + pad, y1 - u * 0.016f, u * 0.014f, NEGATIVE, spacing = 0.04f)
    }

    // ---------- 6) LIVE FEED ----------
    private fun drawLiveFeed(c: Canvas, x0: Float, y0: Float, x1: Float, y1: Float, u: Float, s: Snapshot) {
        panel(c, x0, y0, x1, y1, CYAN, u, fillA = 0.24f)
        val pad = u * 0.020f
        txt(c, "// LIVE FEED", x0 + pad, y0 + u * 0.032f, u * 0.020f, CYAN, bold = true, spacing = 0.06f, glow = u * 0.008f)
        val rows = s.lines.take(4)
        if (rows.isEmpty()) { txt(c, "• SYNCING SUBSYSTEMS…", x0 + pad, y0 + u * 0.070f, u * 0.016f, MUTED); return }
        var yy = y0 + u * 0.070f
        val rowH = ((y1 - u * 0.014f) - yy) / rows.size.coerceAtLeast(1)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        for ((text, col) in rows) {
            p.color = withFullAlpha(col); c.drawCircle(x0 + pad + u * 0.004f, yy - u * 0.006f, u * 0.004f, p)
            txt(c, text, x0 + pad + u * 0.018f, yy, u * 0.016f, col, spacing = 0.02f)
            yy += rowH
        }
    }

    // ---------- small utils ----------
    private fun systemStatus(s: Snapshot): Pair<String, Int> = when {
        s.battery in 0..12 -> "CRITICAL" to NEGATIVE
        (!s.tempC.isNaN() && s.tempC >= 44f) -> "CAUTION" to AMBER
        s.memPct in 90..100 -> "CAUTION" to AMBER
        else -> "OPTIMAL" to CYAN
    }

    private fun signed(v: Double): String = (if (v >= 0) "+" else "") + "%.2f".format(v)
    private fun norm(sl: List<Double>): List<Float> {
        val mn = sl.minOrNull() ?: 0.0; val mx = sl.maxOrNull() ?: 1.0
        val rng = (mx - mn).let { if (it == 0.0) 1.0 else it }
        return sl.map { ((it - mn) / rng).toFloat() }
    }
    private fun String.orIfBlank(d: String) = if (this.isBlank()) d else this
    private fun wrap2(s: String, maxChars: Int): Pair<String, String> {
        if (s.length <= maxChars) return s to ""
        var cut = s.lastIndexOf(' ', maxChars).let { if (it <= 0) maxChars else it }
        val first = s.substring(0, cut).trim()
        var rest = s.substring(cut).trim()
        if (rest.length > maxChars) rest = rest.substring(0, (maxChars - 1).coerceAtLeast(1)).trim() + "…"
        return first to rest
    }

    private companion object {
        val VOID = 0xFF02060A.toInt()
        val PANEL = 0xFF0A1420.toInt()
        val CYAN = 0xFF57D1FF.toInt()
        val AMBER = 0xFFFFB43A.toInt()
        val WHITE = 0xFFF2F8FF.toInt()
        val INK = 0xFFCFE0F2.toInt()
        val MUTED = 0xFF5A7488.toInt()
        val POSITIVE = 0xFF4DF0A0.toInt()
        val NEGATIVE = 0xFFFF5A72.toInt()

        val MONO: Typeface = Typeface.MONOSPACE
        val MONO_BOLD: Typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)

        const val FRAME_MS = 33L
        const val DATA_INTERVAL_MS = 60_000L

        // Decorative "global threat monitor" list (no real per-country feed exists — theatrical, like the
        // reference). Stable so the panel doesn't flicker.
        val THREATS = listOf(
            "India" to 455, "United States" to 320, "Indonesia" to 275,
            "China" to 260, "Brazil" to 175, "Spain" to 135, "Italy" to 127,
        )
        // Hot-zone blip positions on the world map (normalized 0..1).
        val THREAT_ZONES = arrayOf(
            floatArrayOf(0.70f, 0.42f), floatArrayOf(0.78f, 0.34f), floatArrayOf(0.20f, 0.34f),
            floatArrayOf(0.32f, 0.66f), floatArrayOf(0.50f, 0.30f), floatArrayOf(0.82f, 0.62f),
        )
        val DECOR_BLIPS = arrayOf(
            floatArrayOf(0.4f, -0.3f), floatArrayOf(-0.5f, 0.2f), floatArrayOf(0.1f, 0.6f), floatArrayOf(-0.2f, -0.55f),
        )

        fun withAlpha(base: Int, a: Float): Int =
            (base and 0x00FFFFFF) or (((a.coerceIn(0f, 1f) * 255f).toInt()) shl 24)

        fun withFullAlpha(base: Int): Int = base or 0xFF000000.toInt()
    }
}

// Continent outlines (normalized 0..1 coords) for the Threats-by-Country world map — one shared allocation.
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
