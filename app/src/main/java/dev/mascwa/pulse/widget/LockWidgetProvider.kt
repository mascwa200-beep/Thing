package dev.mascwa.pulse.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.util.SizeF
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import dev.mascwa.pulse.MainActivity
import dev.mascwa.pulse.PulseApplication
import dev.mascwa.pulse.R
import dev.mascwa.pulse.core.telemetry.DayPart
import dev.mascwa.pulse.core.telemetry.Geodesy
import dev.mascwa.pulse.core.telemetry.MarketMood
import dev.mascwa.pulse.core.telemetry.Oracle
import dev.mascwa.pulse.core.telemetry.SatellitePasses
import dev.mascwa.pulse.core.telemetry.SpaceWeatherExplainers
import dev.mascwa.pulse.core.telemetry.Stardate
import dev.mascwa.pulse.core.telemetry.TaskBoard
import dev.mascwa.pulse.core.util.Formatters
import dev.mascwa.pulse.data.news.NewsCategory
import dev.mascwa.pulse.data.settings.WatchType
import dev.mascwa.pulse.data.oracle.DayAheadEngine
import dev.mascwa.pulse.data.oracle.OracleEngine
import dev.mascwa.pulse.data.weather.WeatherCode
import dev.mascwa.pulse.navigation.Routes
import dev.mascwa.pulse.widget.WidgetDiagnostics.Outcome
import dev.mascwa.pulse.widget.WidgetDiagnostics.Source
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs

/**
 * The app's one widget: what the Computer thinks you should know, in as much detail as you have
 * given it room for.
 *
 * ## What it draws
 *
 * It **leads with the Oracle** — the cross-signal engine that ranks insights across roughly eighteen
 * signal domains and, until now, reached the home screen not at all. That row is the only one on the
 * widget that is a *judgement* rather than a reading: the rest report a number, it says what to do
 * about several of them at once. Under it, in descending order of how much it would cost you to miss
 * it: an imminent departure, weather, the market mover and breadth, the day's lead headline, your
 * active objective and task, space weather, fuel, economy, and the device itself.
 *
 * ## How it adapts
 *
 * ⚠️ **The rows are generic and their roles are applied at render time** — see `widget_lock.xml`.
 * That is what lets one layout resource serve four genuinely different widgets: `RemoteViews`
 * accepts a `Map<SizeF, RemoteViews>` (API 31; this app's floor) and the **host** picks the entry
 * that fits, so resizing shows *more* rather than the same rows stretched. There is no
 * `onAppWidgetOptionsChanged` handling because none is needed: the host already holds every variant.
 *
 * ## Why it can no longer fail silently
 *
 * Two defects sat behind the owner's report, and both were failures to say anything:
 *
 *  - **The whole batch shared one timeout.** Seven sources ran in parallel inside a single
 *    `withTimeoutOrNull`, so one slow feed discarded *every* result, including the ones already
 *    finished. Blank lines then hid themselves and the widget appeared to shrink. Each source now
 *    has its own budget ([WIDGET_SOURCE_TIMEOUT_MS]) and a slow one costs only its own row.
 *  - **Every source swallowed its exception into a blank string**, so "could not find out" and
 *    "nothing to report" rendered identically — as absence. Every outcome is now recorded; see
 *    [WidgetDiagnostics].
 *
 * And when the render itself fails, this applies a deliberately tiny error card naming the reason
 * rather than applying nothing. ⚠️ Applying nothing is precisely the condition under which the
 * launcher draws its own *"Can't load widget"* — a string this app cannot replace, only avoid
 * needing.
 *
 * Reads only on-device cached data (`force = false` throughout, no GPS wake). Fully defensive: a
 * missing feed costs its own row and says so.
 */
class LockWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val started = System.currentTimeMillis()
                val outcomes = Collections.synchronizedMap(LinkedHashMap<Source, Outcome>())
                val loaded = runCatching { build(context, outcomes) }.getOrElse { err ->
                    // The load itself blew up. Fall through to the fault card with the reason.
                    fail(context, manager, ids, WidgetDiagnostics.describe(err), outcomes, started)
                    return@launch
                }
                ids.forEach { id ->
                    runCatching { render(context, manager, id, loaded, outcomes) }.onFailure { err ->
                        // ⚠️ The rich RemoteViews could not be applied — too large for the binder
                        // transaction, an un-whitelisted view, a resource gone missing. This is the
                        // exact case that ends in "Can't load widget", so apply the small card.
                        fail(context, manager, intArrayOf(id), WidgetDiagnostics.describe(err), outcomes, started)
                    }
                }
                record(context, outcomes, started, fault = null)
            } finally {
                pending.finish()
            }
        }
    }

    // ── rows ────────────────────────────────────────────────────────────────────────────────────

    /** How a row should look and where it should go. Size and colour are applied at render time. */
    private enum class Role(val sp: Float, val colorRes: Int, val bold: Boolean) {
        HEADER(13f, R.color.nw_accent, true),
        LEAD(15f, R.color.nw_ink, true),
        LEAD_DETAIL(11f, R.color.nw_muted, false),
        PRIMARY(12f, R.color.nw_ink, true),
        SECONDARY(11f, R.color.nw_muted, false),
        FOOTNOTE(10f, R.color.nw_muted, false),
        DEGRADED(10f, R.color.nw_negative, false),
    }

    /**
     * One line. [argb] overrides the role's colour where the value itself carries meaning — the
     * Oracle's urgency, a market direction. [route] makes the row its own tap target.
     */
    private data class Row(
        val text: String,
        val role: Role,
        val argb: Int? = null,
        val route: String? = null,
    )

    /** The weather, in the three lengths the widget needs it at. */
    private data class Wx(val head: String, val detail: String, val compact: String)

    /** The market read: the mover line, the breadth line, and the instruments themselves. */
    private data class Mkt(
        val head: String,
        val headArgb: Int,
        val breadth: String,
        val upPct: Int?,
        val indices: List<WidgetBoard.Cell>,
        val stocks: List<WidgetBoard.Cell>,
    )

    /**
     * One load, two renderings.
     *
     * The small sizes draw [rows] exactly as they always have; the tall ones draw [board]. Both come
     * from the same pass over the feeds, so the two forms can never disagree about what the widget
     * knows — and a size that shows less is showing less of the same reading, not a different one.
     */
    private data class Loaded(val rows: List<Row>, val board: WidgetBoard.Board)

    private fun render(
        context: Context,
        manager: AppWidgetManager,
        id: Int,
        loaded: Loaded,
        outcomes: Map<Source, Outcome>,
    ) {
        val rows = loaded.rows
        // Degradation is reported as its own row rather than hidden, and it goes last so it never
        // pushes real content off a small widget.
        val degraded = WidgetDiagnostics.degradedLine(outcomes)
        val all = if (degraded.isBlank()) rows else rows + Row(degraded, Role.DEGRADED, route = Routes.HOME)
        val open: (String, Int) -> PendingIntent = { route, code -> openIntent(context, route, code) }

        // ⚠️ The host picks. Each entry is the SAME layout with a different number of rows, so a
        // bigger widget genuinely shows more instead of the same lines with more air between them.
        val views = RemoteViews(
            mapOf(
                // ⚠️ Each height is DERIVED from its row count, not chosen: a row is ~16dp of 12sp
                // text plus its 2dp margin, over 20dp of vertical padding. A breakpoint below its
                // own content clips; one above it means the variant can never be picked and the
                // rows it adds are unreachable — invisibly, since the widget would simply look as
                // though it had one layout.
                SizeF(120f, 90f) to viewsFor(context, all, SMALL_ROWS),
                SizeF(180f, 165f) to viewsFor(context, all, MEDIUM_ROWS),
                SizeF(250f, 275f) to viewsFor(context, all, LARGE_ROWS),
                SizeF(300f, 385f) to viewsFor(context, all, MAX_ROWS),
                // ⚠️ The two entries the widget was missing, and their absence WAS the sparseness:
                // the resize range reaches 640dp while the tallest variant stopped at 385, so above
                // that the host had nothing richer to pick and stretched twenty rows instead.
                //
                // Both heights are derived from the board's own content, under the same rule as the
                // row variants above — a breakpoint below its content clips, one above it can never
                // be picked. Compact carries header + lead + both instrument strips + breadth +
                // readouts (~305dp of content, declared at 420); full adds the two-column regions
                // and the footer (~505dp, declared at 540). Both sit under `maxResizeHeight`.
                SizeF(300f, 420f) to WidgetBoard.render(context, loaded.board, full = false, open),
                SizeF(300f, 540f) to WidgetBoard.render(context, loaded.board, full = true, open),
            ),
        )
        manager.updateAppWidget(id, views)
    }

    private fun viewsFor(context: Context, rows: List<Row>, limit: Int): RemoteViews {
        val v = RemoteViews(context.packageName, R.layout.widget_lock)
        val shown = rows.take(limit.coerceAtMost(ROW_IDS.size))
        ROW_IDS.forEachIndexed { i, viewId ->
            val row = shown.getOrNull(i)
            if (row == null) {
                v.setViewVisibility(viewId, View.GONE)
                return@forEachIndexed
            }
            v.setViewVisibility(viewId, View.VISIBLE)
            v.setTextViewText(viewId, row.text)
            v.setTextViewTextSize(viewId, TypedValue.COMPLEX_UNIT_SP, row.role.sp)
            v.setTextColor(viewId, row.argb ?: ContextCompat.getColor(context, row.role.colorRes))
            // ⚠️ A distinct request code per row. `Intent.filterEquals` ignores extras, so rows
            // sharing a code would silently share ONE PendingIntent and every tap would land
            // wherever the last-built row pointed — the collision already corrected once in NotifId.
            v.setOnClickPendingIntent(viewId, openIntent(context, row.route ?: Routes.HOME, i))
        }
        return v
    }

    private fun openIntent(context: Context, route: String, requestCode: Int): PendingIntent {
        val open = Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(MainActivity.EXTRA_ROUTE, route)
        return PendingIntent.getActivity(
            context, requestCode, open,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    // ── the fault path ──────────────────────────────────────────────────────────────────────────

    /**
     * Draw the reason instead of drawing nothing.
     *
     * ⚠️ This must not be able to fail for the same reason the rich render did, which is why it
     * inflates the deliberately minimal `widget_error` layout and sets three strings on it. If even
     * this throws there is genuinely nothing left to do but record it.
     */
    private fun fail(
        context: Context,
        manager: AppWidgetManager,
        ids: IntArray,
        reason: String,
        outcomes: Map<Source, Outcome>,
        startedMs: Long,
    ) {
        record(context, outcomes, startedMs, fault = reason)
        val v = RemoteViews(context.packageName, R.layout.widget_error)
        v.setTextViewText(R.id.widget_error_reason, reason)
        v.setOnClickPendingIntent(R.id.widget_error_root, openIntent(context, Routes.HOME, FAULT_REQUEST_CODE))
        ids.forEach { id -> runCatching { manager.updateAppWidget(id, v) } }
    }

    /**
     * Keep what happened, in both places it can be read from.
     *
     * The rich form is in memory for the Crash Console, which runs in this same process. The compact
     * form goes through the activity log, which is persisted and already embedded in a debug
     * report — so a render outcome reaches the repository with no new upload path, and passes the
     * existing central credential scrub on the way.
     */
    private fun record(context: Context, outcomes: Map<Source, Outcome>, startedMs: Long, fault: String?) {
        val render = WidgetDiagnostics.Render(
            atMs = System.currentTimeMillis(),
            size = "adaptive",
            outcomes = outcomes.toMap(),
            elapsedMs = System.currentTimeMillis() - startedMs,
            fault = fault,
        )
        WidgetDiagnostics.record(render)
        runCatching {
            val app = context.applicationContext as? PulseApplication ?: return
            app.container.usageRepository.log("widget", WidgetDiagnostics.logLine(render))
        }
    }

    // ── the data ────────────────────────────────────────────────────────────────────────────────

    private suspend fun build(
        context: Context,
        outcomes: MutableMap<Source, Outcome>,
    ): Loaded {
        val app = context.applicationContext as? PulseApplication
            ?: return Loaded(
                listOf(Row("Application not ready", Role.PRIMARY)),
                WidgetBoard.Board(header = "APPLICATION NOT READY"),
            )
        val c = app.container
        val s = widgetSource<dev.mascwa.pulse.data.settings.AppSettings>(Source.DEVICE, outcomes) { c.settingsRepository.current() }
        val ctx = runCatching { c.deviceContextProvider.snapshot() }.getOrNull()
        val rows = mutableListOf<Row>()

        // Header: greeting, date and stardate. Free, and it is what makes the widget feel like part
        // of the ship rather than a data readout that happens to be on the home screen.
        val greeting = when (ctx?.dayPart) {
            DayPart.MORNING -> "GOOD MORNING"
            DayPart.AFTERNOON -> "GOOD AFTERNOON"
            DayPart.EVENING -> "GOOD EVENING"
            DayPart.NIGHT -> "GOOD NIGHT"
            null -> "LCARS"
        }
        val date = runCatching {
            SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(Date()).uppercase()
        }.getOrNull().orEmpty()
        val stardate = runCatching {
            val now = System.currentTimeMillis()
            // ⚠️ The device's own offset, not UTC. A stardate is a date said aloud, and one that
            // rolls its tenth at UTC midnight is wrong for most of the planet — the trap this
            // project has already shipped twice elsewhere.
            val offset = TimeZone.getDefault().getOffset(now) / 1000
            Stardate.format(Stardate.at(now, offset))
        }.getOrNull().orEmpty()
        rows += Row(
            listOf(greeting, date, stardate).filter { it.isNotBlank() }.joinToString(" · "),
            Role.HEADER,
            argb = ContextCompat.getColor(context, widgetAccentRes()),
        )
        // The board says where you are and what it is like there; the stardate drops to the line
        // under it. ⚠️ The place comes from `widgetPlace` below, which prefers a SAVED location and
        // only falls back to the device's own — nothing here may ever name a fixed town.
        val boardSubhead = listOf(greeting, stardate).filter { it.isNotBlank() }.joinToString(" · ")

        if (s == null) {
            return Loaded(rows, WidgetBoard.Board(header = date.ifBlank { greeting }, subhead = boardSubhead))
        }

        // ⚠️ Resolved ONCE, and on its own budget. Three feeds need a coordinate, and the fallback
        // branch can touch the location provider — doing that three times would triple the slowest
        // thing in the load. Null is a fact about us, not about the world, so the feeds that need
        // it are recorded as never asked rather than left silently absent.
        val place = withTimeoutOrNull(WIDGET_SOURCE_TIMEOUT_MS) { runCatching { widgetPlace(c, s) }.getOrNull() }
        if (place == null) {
            outcomes[Source.SAFETY] = Outcome.Skipped("no saved location")
            outcomes[Source.SKY] = Outcome.Skipped("no saved location")
        }

        // ⚠️ Captured out of the parallel block so the board can be assembled from the SAME pass
        // that fills the rows. Building it from a second read would let the two forms of the widget
        // disagree about what it knows — and they are meant to be one reading at two lengths.
        var wx: Wx? = null
        var mkt: Mkt? = null
        var headline: String? = null
        var taskLine: String? = null
        var studyLine: String? = null
        var skyLine: String? = null
        var spaceLine: String? = null
        var fuelLine: String? = null
        var econLine: String? = null
        var leadTitle: String? = null
        var leadDetail: String? = null
        var leadArgb: Int? = null
        var leadRoute: String? = null
        var alertLine: String? = null

        // Everything network-capable runs in parallel, each on its own budget.
        withTimeoutOrNull(WIDGET_LOAD_TIMEOUT_MS) {
            coroutineScope {
                val oracle = async {
                    widgetSource<dev.mascwa.pulse.core.telemetry.Insight>(Source.ORACLE, outcomes) {
                        // visible = 1: this widget renders exactly one insight, and telling the
                        // learning layer otherwise would credit rows nobody was shown.
                        OracleEngine.read(c, s, visible = 1).firstOrNull()
                    }
                }
                val departure = async {
                    widgetSource<String>(Source.DAY_AHEAD, outcomes) {
                        DayAheadEngine.imminentDeparture(c, s)?.first
                    }
                }
                val weather = async {
                    widgetSource<Wx>(Source.WEATHER, outcomes) {
                        val wd = resolveWeather(c, place) ?: return@widgetSource null
                        val cur = wd.current ?: return@widgetSource null
                        val u = wd.tempUnitSymbol
                        val feels = cur.apparentTemperature?.let { " · feels ${Formatters.number(it, 0)}$u" }.orEmpty()
                        val head = "WX  ${Formatters.number(cur.temperature, 0)}$u$feels · ${WeatherCode.describe(cur.weatherCode)}"
                        val day = wd.daily.firstOrNull()
                        // ⚠️ Hoisted to locals rather than null-checked in place. Kotlin will not
                        // smart-cast a public property declared in another module, and the feed
                        // repositories live in `:core:feeds` now — the trap that has cost this
                        // project three CI rounds, and one no local gate can see.
                        val hi = day?.tempMax
                        val lo = day?.tempMin
                        val hilo = if (hi != null && lo != null) {
                            "↑${Formatters.number(hi, 0)}$u ↓${Formatters.number(lo, 0)}$u"
                        } else ""
                        val rh = cur.humidity?.let { "${it.toInt()}% RH" }.orEmpty()
                        val aqi = wd.airQuality?.usAqi?.let { "AQI ${it.toInt()}" }.orEmpty()
                        Wx(
                            head = head,
                            detail = listOf(hilo, rh, aqi).filter { it.isNotBlank() }.joinToString("  ·  "),
                            compact = "${Formatters.number(cur.temperature, 0)}$u ${WeatherCode.describe(cur.weatherCode).uppercase()}",
                        )
                    }
                }
                val markets = async {
                    widgetSource<Mkt>(Source.MARKETS, outcomes) {
                        val quotes = c.marketsRepository.fetchWatchlist(force = false).data
                            .orEmpty().filter { it.changePercent != null }
                        if (quotes.isEmpty()) return@widgetSource null
                        val mover = quotes.maxByOrNull { abs(it.changePercent ?: 0.0) }
                        val pct = mover?.changePercent ?: 0.0
                        val head = mover?.let { "MKT  ${it.label}  ${signedPercent(pct)}%" }.orEmpty()
                        val colour = if (pct >= 0) R.color.nw_positive else R.color.nw_negative
                        val mood = MarketMood.summarize(quotes.mapNotNull { it.changePercent })
                        val breadth = mood?.let {
                            "${it.headline.uppercase()}  ·  NET ${signedPercent(it.netChangePct)}%  ·  ${it.up}▲ ${it.down}▼"
                        }.orEmpty()
                        // ⚠️ `Quote.type` carries the WatchType NAME, so the strips are a filter on
                        // data already in hand rather than a second fetch. The cells are capped at
                        // four because the strip has four slots — more would be silently dropped.
                        Mkt(
                            head = head,
                            headArgb = ContextCompat.getColor(context, colour),
                            breadth = breadth,
                            upPct = mood?.let { m -> if (m.total > 0) (m.upShare * 100).toInt() else null },
                            indices = quotes.filter { it.type == WatchType.INDEX.name }.take(4).map { cellFor(it) },
                            stocks = quotes.filter { it.type == WatchType.STOCK.name }.take(4).map { cellFor(it) },
                        )
                    }
                }
                val news = async {
                    widgetSource<String>(Source.NEWS, outcomes) {
                        c.newsRepository.fetchCategory(NewsCategory.TOP, force = false).data
                            ?.firstOrNull()?.let { "NEWS  ${it.source.uppercase()} · ${it.title}".take(110) }
                    }
                }
                val task = async {
                    widgetSource<String>(Source.TASKS, outcomes) {
                        val pending = TaskBoard.pending(c.taskStore.all())
                        pending.firstOrNull()?.let {
                            val more = if (pending.size > 1) "   (+${pending.size - 1})" else ""
                            "TASK  ${it.title.take(40)}$more"
                        }
                    }
                }
                val space = async {
                    widgetSource<String>(Source.SPACE, outcomes) {
                        c.spaceWeatherRepository.fetch(force = false, heavy = false).data?.kp
                            ?.let { "SPC  ${SpaceWeatherExplainers.kp(it).headline}" }
                    }
                }
                val fuel = async {
                    widgetSource<String>(Source.FUEL, outcomes) {
                        c.fuelRepository.fetch(force = false).data?.benchmarks?.firstOrNull()?.let { b ->
                            val price = b.price?.let { "$${"%.2f".format(it)}" }.orEmpty()
                            val pct = b.changePercent?.let { " ${signedPercent(it)}%" }.orEmpty()
                            "FUEL  ${b.label} $price$pct".trim()
                        }
                    }
                }
                // Nearby danger. ⚠️ Deliberately ABOVE everything measured in the row order below:
                // an incident a few kilometres away is the one line on this widget that could change
                // what someone does in the next hour.
                val safety = async {
                    if (place == null) null else widgetSource<String>(Source.SAFETY, outcomes) {
                        val near = c.safetyRepository.fetch(place.latitude, place.longitude, force = false)
                            .data?.incidents.orEmpty()
                            .filter { it.distanceMeters <= SAFETY_RADIUS_M }
                            .minByOrNull { it.distanceMeters }
                            ?: return@widgetSource null
                        val km = "%.0f".format(near.distanceMeters / 1000.0)
                        val more = if (near.severity.isNotBlank()) " · ${near.severity.uppercase()}" else ""
                        "⚠ ${near.title.take(52)}  ${km}km ${Geodesy.cardinal(near.bearing)}$more"
                    }
                }
                val study = async {
                    widgetSource<String>(Source.STUDY, outcomes) {
                        // No network and no coordinate — the cheapest feed on the widget.
                        val due = c.studyStore.dueCount()
                        if (due <= 0) null else "STUDY  $due review${if (due == 1) "" else "s"} due"
                    }
                }
                val sky = async {
                    if (place == null) null else widgetSource<String>(Source.SKY, outcomes) {
                        // ⚠️ `cachedElement`, never `element`. A widget must not start a Celestrak
                        // fetch on a half-hourly schedule; the observatory and Home keep the orbit
                        // current and this reads what they left behind. No cache is genuinely
                        // nothing to report, which is what the row's absence then means.
                        val elements = c.tleRepository.cachedElement(ISS_NORAD_ID) ?: return@widgetSource null
                        val sight = SatellitePasses.sighting(
                            elements,
                            SatellitePasses.Site(place.latitude, place.longitude),
                            System.currentTimeMillis(),
                        ) ?: return@widgetSource null
                        // Only when it is genuinely worth walking outside for. Announcing a station
                        // that is 3° up behind a building, or one in Earth's shadow, would teach a
                        // reader to ignore the row.
                        if (!sight.worthLookingUp || sight.kind != SatellitePasses.PassKind.VISIBLE) {
                            return@widgetSource null
                        }
                        "ISS  ${sight.look.altitudeDeg.toInt()}° up, " +
                            "${Geodesy.cardinal(sight.look.azimuthDeg)} — look now"
                    }
                }
                val econ = async {
                    widgetSource<String>(Source.ECONOMY, outcomes) {
                        c.economyRepository.fetchDashboard(force = false).data?.series.orEmpty()
                            .firstOrNull { it.latest != null }?.let { series ->
                                series.latest?.let {
                                    "ECON  ${series.indicatorTitle.take(24)}: ${"%.1f".format(it.value)} ${series.unit}".trim()
                                }
                            }
                    }
                }

                // The Oracle leads: it is the only row that weighs several feeds against each other.
                oracle.await()?.let { insight ->
                    leadTitle = insight.title
                    leadDetail = insight.detail
                    leadArgb = Oracle.urgencyArgb(insight.urgency).toInt()
                    leadRoute = insight.actionRoute ?: Routes.ORACLE
                    rows += Row(
                        insight.title,
                        Role.LEAD,
                        argb = Oracle.urgencyArgb(insight.urgency).toInt(),
                        route = insight.actionRoute ?: Routes.ORACLE,
                    )
                    rows += Row(insight.detail, Role.LEAD_DETAIL, route = insight.actionRoute ?: Routes.ORACLE)
                }
                // A departure is the only thing that EXPIRES, so it sits above everything measured.
                departure.await()?.let { rows += Row("▸ $it", Role.PRIMARY, route = Routes.NAV) }
                // Then the only other row that is about right now and about where you are.
                safety.await()?.let {
                    alertLine = it
                    rows += Row(it, Role.PRIMARY, argb = ContextCompat.getColor(context, R.color.nw_negative), route = Routes.SAFETY)
                }

                // The active objective needs no I/O — it is already in settings.
                s.waypoints.firstOrNull { it.id == s.activeWaypointId }?.label
                    ?.let { rows += Row("◎ ${it.take(40)}", Role.PRIMARY, route = Routes.NAV) }

                weather.await()?.let {
                    wx = it
                    rows += Row(it.head, Role.PRIMARY, route = Routes.WEATHER)
                    if (it.detail.isNotBlank()) rows += Row(it.detail, Role.SECONDARY, route = Routes.WEATHER)
                }
                markets.await()?.let {
                    mkt = it
                    if (it.head.isNotBlank()) rows += Row(it.head, Role.PRIMARY, argb = it.headArgb, route = Routes.MARKETS)
                    if (it.breadth.isNotBlank()) rows += Row(it.breadth, Role.SECONDARY, route = Routes.MARKETS)
                }
                news.await()?.let { headline = it; rows += Row(it, Role.PRIMARY, route = Routes.NEWS) }
                task.await()?.let { taskLine = it; rows += Row(it, Role.SECONDARY, route = Routes.HOME) }
                study.await()?.let { studyLine = it; rows += Row(it, Role.SECONDARY, route = Routes.STUDY) }
                sky.await()?.let { skyLine = it; rows += Row(it, Role.SECONDARY, route = Routes.ORBITAL) }
                space.await()?.let { spaceLine = it; rows += Row(it, Role.SECONDARY, route = Routes.HOME) }
                fuel.await()?.let { fuelLine = it; rows += Row(it, Role.SECONDARY, route = Routes.MARKETS) }
                econ.await()?.let { econLine = it; rows += Row(it, Role.SECONDARY, route = Routes.MARKETS) }
            }
        }

        val sysLine = ctx?.let {
            val batt = if (it.batteryPct >= 0) "${it.batteryPct}%" else "—"
            val chg = if (it.isCharging) " ⚡" else ""
            "SYS  PWR $batt$chg  ·  ${it.network.name}"
        }
        sysLine?.let { rows += Row(it, Role.FOOTNOTE) }

        // ── the same reading, as a board ────────────────────────────────────────────────────────
        // Nothing here fetches: every value was loaded once, above. A field left null is a region
        // the board simply will not draw.
        val econColumn = WidgetBoard.Column("ECONOMY", listOfNotNull(econLine, fuelLine))
        val dayColumn = WidgetBoard.Column("YOUR DAY", listOfNotNull(taskLine, studyLine))
        val board = WidgetBoard.Board(
            header = listOfNotNull(place?.name?.uppercase(), date.ifBlank { null }, wx?.compact)
                .joinToString("  ·  "),
            subhead = boardSubhead,
            alert = alertLine,
            alertRoute = Routes.SAFETY,
            lead = leadTitle,
            leadDetail = leadDetail,
            leadArgb = leadArgb,
            leadRoute = leadRoute,
            sources = listOfNotNull(headline),
            indices = mkt?.indices.orEmpty(),
            stocks = mkt?.stocks.orEmpty(),
            breadth = mkt?.breadth,
            breadthPct = mkt?.upPct,
            readouts = listOfNotNull(wx?.detail?.ifBlank { null }, spaceLine, skyLine),
            // ⚠️ Only pairs that have something go in. The second slot stays deliberately empty
            // here — water, fuel detail, data use and comms fill it in later slices, and an empty
            // labelled column would read as a feed that failed rather than one not built yet.
            pairs = listOf(econColumn to dayColumn),
            foot = sysLine,
        )
        return Loaded(rows, board)
    }

    /** One instrument, as the board draws it: what it is, what it did, and which way. */
    private fun cellFor(q: dev.mascwa.pulse.data.markets.Quote): WidgetBoard.Cell {
        val pct = q.changePercent ?: 0.0
        return WidgetBoard.Cell(
            label = q.label.take(9).uppercase(),
            value = "${signedPercent(pct)}%",
            colorRes = if (pct >= 0) R.color.nw_positive else R.color.nw_negative,
            series = q.sparkline,
        )
    }

    private companion object {
        /**
         * The row slots in `widget_lock.xml`, in order.
         *
         * ⚠️ Must stay the same length as the layout's rows. `WidgetLinkageTest` asserts every
         * `R.id` the code writes exists in the layout, which catches the direction that matters —
         * a slot named here and missing there.
         */
        val ROW_IDS = intArrayOf(
            R.id.widget_row_0, R.id.widget_row_1, R.id.widget_row_2, R.id.widget_row_3,
            R.id.widget_row_4, R.id.widget_row_5, R.id.widget_row_6, R.id.widget_row_7,
            R.id.widget_row_8, R.id.widget_row_9, R.id.widget_row_10, R.id.widget_row_11,
            R.id.widget_row_12, R.id.widget_row_13, R.id.widget_row_14, R.id.widget_row_15,
            R.id.widget_row_16, R.id.widget_row_17, R.id.widget_row_18, R.id.widget_row_19,
        )

        /** Nothing further away than this is "near you" on a home-screen row. */
        const val SAFETY_RADIUS_M = 150_000.0

        /** The station. Home propagates the same object from the same cached element set. */
        const val ISS_NORAD_ID = 25544

        /**
         * How many rows each size shows. Tuned so the smallest still carries the Oracle's headline
         * and its reasoning — a two-cell widget that showed only a greeting would be worse than
         * nothing, and the lead is the row worth the space.
         */
        const val SMALL_ROWS = 4
        const val MEDIUM_ROWS = 9
        const val LARGE_ROWS = 14
        const val MAX_ROWS = 20

        /** Distinct from every row's request code, which are the row indices. */
        const val FAULT_REQUEST_CODE = 90
    }
}
