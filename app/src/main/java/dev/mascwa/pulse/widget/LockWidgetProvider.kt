package dev.mascwa.pulse.widget

import android.Manifest
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.util.SizeF
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import dev.mascwa.pulse.MainActivity
import dev.mascwa.pulse.PulseApplication
import dev.mascwa.pulse.R
import dev.mascwa.pulse.core.device.MobileData
import dev.mascwa.pulse.core.telemetry.BreakingNews
import dev.mascwa.pulse.core.telemetry.DayPart
import dev.mascwa.pulse.core.telemetry.Geodesy
import dev.mascwa.pulse.core.telemetry.MailGlance
import dev.mascwa.pulse.core.telemetry.MarketMood
import dev.mascwa.pulse.core.telemetry.NetworkKind
import dev.mascwa.pulse.core.telemetry.Oracle
import dev.mascwa.pulse.core.telemetry.SatellitePasses
import dev.mascwa.pulse.core.telemetry.SpaceWeatherExplainers
import dev.mascwa.pulse.core.telemetry.Stardate
import dev.mascwa.pulse.core.telemetry.TaskBoard
import dev.mascwa.pulse.core.telemetry.WeatherExplainers
import dev.mascwa.pulse.core.util.Formatters
import dev.mascwa.pulse.data.breaking.BreakingCoverageRepository
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
import kotlinx.coroutines.withContext
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
                // Armed after the render, from what the render drew. Both are best-effort and never
                // throw: a widget that drew and failed to schedule its next redraw is still a widget
                // that drew, and the periodic refresh is the backstop.
                WidgetBoundaryAlarm.schedule(context, loaded.nextRefreshMs)
                CalendarChangeWorker.ensure(context)
            } finally {
                pending.finish()
            }
        }
    }

    /**
     * The last widget was removed: take down the two things that exist only to redraw it.
     *
     * Both would stand themselves down on their next firing anyway — each finds no widget ids and
     * cancels — but the calendar trigger would first wait for the next calendar change to learn that,
     * and there is no reason to leave a job registered with the system for a widget nobody has.
     */
    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        WidgetBoundaryAlarm.cancel(context)
        CalendarChangeWorker.cancel(context)
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
        /**
         * A tap target that is not an app route — the calendar's up-next row opens the event in the
         * calendar app. Wins over [route] when set.
         */
        val pending: PendingIntent? = null,
    )

    /** The weather, in the lengths the widget needs it at, plus the air as its own reading. */
    private data class Wx(val head: String, val detail: String, val compact: String, val air: String?)

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
    private data class Loaded(
        val rows: List<Row>,
        val board: WidgetBoard.Board,
        /**
         * When the agenda next changes on its own — an event starting or ending, or midnight. The
         * boundary alarm is armed for it after the render, so NOW and NEXT flip on time rather than
         * whenever the next periodic refresh lands. Null when the calendar was never read.
         */
        val nextRefreshMs: Long? = null,
    )

    private fun render(
        context: Context,
        manager: AppWidgetManager,
        id: Int,
        loaded: Loaded,
        outcomes: Map<Source, Outcome>,
    ) {
        // ⚠️ NEEDS YOU goes just above the SYS footnote rather than at the end. `fit` sheds from the
        // tail, and "you could fix this" is worth more of a short widget than a battery percentage.
        // It is not given a reserved slot like the degraded line: that one reports the widget being
        // wrong, this one only how it could be more.
        val needsYou = WidgetDiagnostics.needsYouLine(outcomes).takeIf { it.isNotBlank() }
        val rows = loaded.rows.let { base ->
            if (needsYou == null) {
                base
            } else {
                val at = base.indexOfLast { it.role == Role.FOOTNOTE }.let { if (it < 0) base.size else it }
                base.toMutableList().apply {
                    add(at, Row(needsYou, Role.FOOTNOTE, argb = ContextCompat.getColor(context, widgetAccentRes()), route = Routes.SETTINGS))
                }
            }
        }
        // ⚠️ THE COMMENT THAT USED TO BE HERE WAS EXACTLY BACKWARDS. It read "it goes last so it
        // never pushes real content off a small widget" — but `viewsFor` ends in `rows.take(limit)`,
        // so going last is precisely what made this THE FIRST ROW DROPPED. A fully-populated widget
        // builds 21 rows against a cap of 20, so the one line whose entire job is to say a feed had
        // failed was the one thing guaranteed to be missing exactly when it was true.
        //
        // It is now passed separately and given a RESERVED slot, so it displaces a content row
        // instead of being displaced by one. That is the right way round: the notice exists to say
        // the content is incomplete, so spending one row on it costs nothing that was not already
        // in doubt.
        // ⚠️ "Tap for why" opens the crash console, which is where the why IS — the widget's own
        // render record, source by source. It used to open HOME, a promise with nothing behind it.
        val degradedRow = WidgetDiagnostics.degradedLine(outcomes)
            .takeIf { it.isNotBlank() }
            ?.let { Row(it, Role.DEGRADED, route = Routes.CRASH_LOG) }
        val open: (String, Int) -> PendingIntent = { route, code -> openIntent(context, route, code) }
        // ⚠️ Drawn ONCE, here, and the same instances go to both board variants. They share a
        // `BitmapCache` that de-duplicates on identity, so reusing the instances costs one copy of
        // each chart instead of two — but only because they are the same objects. Drawing inside
        // `WidgetBoard.render` would quietly double the parcel.
        // ⚠️ The board could not report a failure AT ALL until now — `degradedLine` was folded
        // only into the row list, so on both tall sizes a dead source looked like a quiet day.
        val board = withCharts(
            context,
            loaded.board.copy(
                degraded = degradedRow?.text,
                degradedRoute = Routes.CRASH_LOG,
                needsYou = needsYou,
                needsYouRoute = Routes.SETTINGS,
            ),
        )

        // ⚠️ The agenda is the one region whose size depends on the diary, and a binder transaction
        // has a hard ceiling. So a render that fails is retried with a shorter list and then with no
        // list, BEFORE the caller falls back to the fault card: fourteen days of a busy diary must
        // never be the reason the whole widget says it could not draw. Only the last failure is
        // rethrown, so the fault card still names a real reason when even the plain board fails.
        val attempts = listOfNotNull(
            board,
            board.agenda?.takeIf { it.rows.size > REDUCED_AGENDA_ROWS }
                ?.let { board.copy(agenda = it.copy(rows = it.rows.take(REDUCED_AGENDA_ROWS))) },
            board.agenda?.let { board.copy(agenda = null) },
        )
        var lastError: Throwable? = null
        for (attempt in attempts) {
            val applied = runCatching { manager.updateAppWidget(id, variants(context, rows, degradedRow, attempt, open)) }
            if (applied.isSuccess) return
            lastError = applied.exceptionOrNull()
        }
        throw lastError ?: IllegalStateException("no render attempt was made")
    }

    /** Every size variant, from one board. The host picks the entry that fits. */
    private fun variants(
        context: Context,
        rows: List<Row>,
        degradedRow: Row?,
        board: WidgetBoard.Board,
        open: (String, Int) -> PendingIntent,
    ): RemoteViews {
        // ⚠️ The host picks. Each entry is the SAME layout with a different number of rows, so a
        // bigger widget genuinely shows more instead of the same lines with more air between them.
        return RemoteViews(
            mapOf(
                // ⚠️ Each height is DERIVED from its row count, not chosen: a row is ~16dp of 12sp
                // text plus its 2dp margin, over 20dp of vertical padding. A breakpoint below its
                // own content clips; one above it means the variant can never be picked and the
                // rows it adds are unreachable — invisibly, since the widget would simply look as
                // though it had one layout.
                SizeF(120f, 90f) to viewsFor(context, rows, degradedRow, SMALL_ROWS),
                SizeF(180f, 165f) to viewsFor(context, rows, degradedRow, MEDIUM_ROWS),
                SizeF(250f, 275f) to viewsFor(context, rows, degradedRow, LARGE_ROWS),
                SizeF(300f, 385f) to viewsFor(context, rows, degradedRow, MAX_ROWS),
                // ⚠️ The two entries the widget was missing, and their absence WAS the sparseness:
                // the resize range reached 640dp while the tallest variant stopped at 385, so above
                // that the host had nothing richer to pick and stretched twenty rows instead.
                //
                // Both heights are derived from the board's own content, under the same rule as the
                // row variants above — a breakpoint below its content clips, one above it can never
                // be picked. Compact carries header + lead + both instrument strips + breadth +
                // readouts (~319dp of content, ~333dp with the up-next line, declared at 420); full
                // adds the three two-column regions and the footer (see the full entry below).
                //
                // ⚠️ Both grew with the board: a fourth readout is ~14dp and the third pair region
                // is a label plus four lines, ~70dp. A breakpoint left where it was would be BELOW
                // its content, which clips — the rule governing every entry in this list. Every
                // height here must sit under `maxResizeHeight` in `lock_widget_info.xml` (1000dp),
                // which is what makes it reachable at all.
                SizeF(300f, 420f) to WidgetBoard.render(context, board, full = false, open),
                // ⚠️ Still the tallest entry, and it no longer needs a taller sibling: the agenda list
                // at the bottom of the full board takes every dp above this, at any height, so a
                // widget stretched to 800dp or more fills rather than ending two fifths of the way
                // down — which is what the owner's screenshot showed before the list existed.
                //
                // ⚠️ Moved from 610 to 680, by the same derivation as every entry above. The full
                // board gained the up-next line (~14dp), a rule (~9dp) and the agenda heading (~12dp)
                // — ~624dp before the list gets anything — and a list squeezed to zero rows is a
                // heading over nothing. 680 leaves room for a day heading and one two-line event
                // (~18 + ~29dp); below it the compact board, which carries the up-next line, is the
                // better answer.
                SizeF(300f, FULL_BOARD_MIN_HEIGHT_DP) to WidgetBoard.render(context, board, full = true, open),
            ),
        )
    }

    /**
     * One size of the row form.
     *
     * ⚠️ [degradedRow] is a separate parameter rather than the last element of [rows] because
     * the cut below is a plain `take`, and anything appended past the cap is dropped in silence.
     * Reserving it a slot is what makes the notice survive the case it exists to report.
     */
    private fun viewsFor(
        context: Context,
        rows: List<Row>,
        degradedRow: Row?,
        limit: Int,
    ): RemoteViews {
        val v = RemoteViews(context.packageName, R.layout.widget_lock)
        val shown = WidgetDiagnostics.fit(rows, limit.coerceAtMost(ROW_IDS.size), degradedRow)
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
            v.setOnClickPendingIntent(viewId, row.pending ?: openIntent(context, row.route ?: Routes.HOME, i))
        }
        return v
    }

    /**
     * Draw the strips' sparklines, or leave the board as text.
     *
     * ⚠️ The budget is ARITHMETIC, not an estimate. `RemoteViews.estimateMemoryUsage()` exists on
     * the platform but is not something to lean on here — this module allocated the bitmaps and
     * therefore knows exactly what they cost, and a number we computed cannot disagree with what we
     * actually attached. Over the budget the charts are simply dropped: the label and the value are
     * the reading, and the line is context that is not worth a widget that fails to draw at all.
     */
    private fun withCharts(context: Context, board: WidgetBoard.Board): WidgetBoard.Board {
        fun draw(cells: List<WidgetBoard.Cell>): List<Pair<WidgetBoard.Cell, android.graphics.Bitmap?>> =
            cells.map { cell ->
                cell to runCatching {
                    WidgetCharts.sparkline(cell.series, ContextCompat.getColor(context, cell.colorRes))
                }.getOrNull()
            }

        val indices = draw(board.indices)
        val stocks = draw(board.stocks)
        val bytes = WidgetCharts.bytesOf(indices.map { it.second } + stocks.map { it.second })
        if (bytes > WidgetCharts.BUDGET_BYTES) return board
        return board.copy(
            indices = indices.map { (cell, chart) -> cell.copy(chart = chart) },
            stocks = stocks.map { (cell, chart) -> cell.copy(chart = chart) },
        )
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
        // ⚠️ The SAME preference the header clock follows, so the agenda cannot print 14:00
        // beside a header reading 2:00 PM. Locale.US on the PATTERN only — the pattern is a
        // format string, while the rendered digits still come out in the device's own locale.
        val clock = SimpleDateFormat(
            if (s?.use24HourClock != false) "HH:mm" else "h:mm a",
            Locale.getDefault(),
        )
        // ⚠️ When the board was last REDRAWN. A widget is a picture, and the ticking clock beside it
        // made every line read as live, so a widget nothing had redrawn for hours looked current. This
        // stamp answers exactly that and no more: a redraw can still serve old cached data when the
        // network fails, and saying so is `staleNote`'s job, drawn beside it.
        val updated = "UPDATED ${clock.format(Date())}"
        // The board says where you are and what it is like there; the stardate drops to the line
        // under it. ⚠️ The place comes from `widgetPlace` below, which prefers a SAVED location and
        // only falls back to the device's own — nothing here may ever name a fixed town.
        // The stamp sits BEFORE the stardate: the subhead is one line, and when it ellipsizes it is
        // the decoration that should go, not the fact.
        val boardSubhead = listOf(greeting, updated, stardate).filter { it.isNotBlank() }.joinToString(" · ")

        if (s == null) {
            return Loaded(rows, WidgetBoard.Board(header = date.ifBlank { greeting }, subhead = boardSubhead))
        }

        // ⚠️ ONE instant and ONE zone for the whole calendar reading. The agenda's NOW/NEXT, its day
        // headings and the alarm that refreshes it at the next boundary must all agree about "now",
        // and a zone read twice can differ across a clock change mid-load. The zone enters the core
        // as a function — `getOffset` at each instant — which is what makes it DST-correct.
        val nowMs = System.currentTimeMillis()
        val zone = TimeZone.getDefault()
        val offsetAt: (Long) -> Long = { ms -> zone.getOffset(ms).toLong() }
        // ⚠️ Day labels are formatted in UTC ON PURPOSE. The core hands back epoch DAYS, and an epoch
        // day's UTC midnight formatted in UTC is that date everywhere — formatted in the device zone
        // it is the day before for anyone west of Greenwich, which is the all-day trap the core exists
        // to avoid, walked straight back into at the last step.
        val dayFmt = SimpleDateFormat("EEE d MMM", Locale.getDefault()).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val agendaFormat = Agenda.Format(
            clock = { ms -> clock.format(Date(ms)) },
            day = { epochDay -> dayFmt.format(Date(epochDay * Agenda.DAY_MS)).uppercase() },
        )

        // ⚠️ Resolved ONCE, and on its own budget. Four feeds need a coordinate, and the fallback
        // branch can touch the location provider — doing that three times would triple the slowest
        // thing in the load. Null is a fact about us, not about the world, so the feeds that need
        // it are recorded as never asked rather than left silently absent.
        //
        // ⚠️ With the REASON. Every one of these used to read "no saved location", including for an
        // owner who had turned device location on and whose real problem was a permission or a phone
        // that had not taken a fix since it slept — three different things to do, one sentence. And
        // WEATHER was not in the list at all: it ran with no place, returned null and was recorded
        // as Empty, "nothing to report", which is not what a widget with no idea where it is knows.
        // Null here means the budget ran out; a failed Result means the lookup threw. They are kept
        // apart below because they are different sentences.
        val placeAttempt = withTimeoutOrNull(WIDGET_SOURCE_TIMEOUT_MS) {
            runCatching {
                widgetPlaceLookup(
                    c, s,
                    hasLocationPermission = {
                        context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                            context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                    },
                    isLocationOn = {
                        context.getSystemService(LocationManager::class.java)?.isLocationEnabled != false
                    },
                )
            }
        }
        val placeLookup = placeAttempt?.getOrNull()
        val place = placeLookup?.place
        if (place == null) {
            // ⚠️ None of these four feeds was ASKED, so none of them may be reported as having failed
            // to answer — which is what TimedOut would say, in the degraded line, as "no answer from
            // weather, safety, sky +1". The review caught exactly that: this budget is four seconds
            // and the location provider's own fix wait is eight, so indoors or GPS-only it runs out
            // on ordinary refreshes. What the owner can do about a slow fix is keep a saved place,
            // which always answers at once — so that is the sentence, and it is fixable.
            val outcome = when {
                placeAttempt == null ->
                    Outcome.Skipped("location too slow — add a saved place", fixable = true)
                placeLookup == null ->
                    // Threw: ours, not the owner's. Kept for the crash console, out of NEEDS YOU.
                    Outcome.Skipped(
                        "location lookup failed: " +
                            WidgetDiagnostics.describe(placeAttempt.exceptionOrNull() ?: IllegalStateException("no result")),
                    )
                else -> Outcome.Skipped(placeLookup.why ?: "no location", fixable = true)
            }
            for (src in PLACE_SOURCES) outcomes[src] = outcome
        }

        // ⚠️ Asked once, up front, for the same reason `place` is: the answer decides whether a
        // source runs at all, and a source that returns null from inside `widgetSource` is recorded
        // as Empty — "asked, nothing to report" — which is the wrong thing to say about a
        // permission that was never granted. One cheap AppOps call.
        val usageAccess = runCatching { c.mobileData.hasUsageAccess() }.getOrDefault(false)
        if (!usageAccess) outcomes[Source.DATA] = Outcome.Skipped("Usage Access not granted", fixable = true)

        // ⚠️ Same reasoning, and the calendar needs it more than most: `upcoming` returns an empty
        // list for a refused permission, an unavailable provider AND a genuinely clear diary, so
        // without asking first the widget would quietly tell someone their week is free when it was
        // never allowed to look. Asked here rather than inside the source for the reason above —
        // null from inside `widgetSource` is recorded as Empty.
        val canReadCalendar = runCatching { c.calendarRepository.canRead() }.getOrDefault(false)
        if (!canReadCalendar) outcomes[Source.CALENDAR] = Outcome.Skipped("calendar permission not granted", fixable = true)

        // ⚠️ Captured out of the parallel block so the board can be assembled from the SAME pass
        // that fills the rows. Building it from a second read would let the two forms of the widget
        // disagree about what it knows — and they are meant to be one reading at two lengths.
        var wx: Wx? = null
        var mkt: Mkt? = null
        var headlines: List<String> = emptyList()
        var taskLine: String? = null
        var studyLine: String? = null
        var skyLine: String? = null
        var spaceLine: String? = null
        var waterLine: String? = null
        var fuelLines: List<String> = emptyList()
        var dataLine: String? = null
        var commsLines: List<String> = emptyList()
        // When each time-sensitive feed's data was SAVED — see `staleNote`. Written from several
        // coroutines at once.
        val dataAt = java.util.concurrent.ConcurrentHashMap<Source, Long>()
        var agendaEvents: List<Agenda.Event> = emptyList()
        var agendaPlan: Agenda.Plan? = null
        var econLines: List<String> = emptyList()
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
                    if (place == null) null else widgetSource<Wx>(Source.WEATHER, outcomes) {
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
                        // The air, in words as well as a number. ⚠️ Through the CI-tested explainer
                        // the WEATHER screen already uses, so the widget and the screen cannot end
                        // up disagreeing about where "unhealthy" starts.
                        val air = wd.airQuality?.usAqi?.let { v ->
                            WeatherExplainers.airQuality(v, wd.airQuality?.europeanAqi)?.headline
                                ?.let { "AIR  ${v.toInt()} US AQI · ${it.uppercase()}" }
                        }
                        Wx(
                            head = head,
                            detail = listOf(hilo, rh, aqi).filter { it.isNotBlank() }.joinToString("  ·  "),
                            compact = "${Formatters.number(cur.temperature, 0)}$u ${WeatherCode.describe(cur.weatherCode).uppercase()}",
                            air = air,
                        )
                    }
                }
                val markets = async {
                    widgetSource<Mkt>(Source.MARKETS, outcomes) {
                        val fetched = c.marketsRepository.fetchWatchlist(force = false)
                        dataAt[Source.MARKETS] = fetched.timestampEpochMs
                        val quotes = fetched.data
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
                // ⚠️ Three lines for the price of the one fetch that was already happening. The
                // provider asked for the whole TOP list and then threw all but `.firstOrNull()`
                // away, so spending it on three DIFFERENT newsrooms costs no network at all.
                //
                // ⚠️ Not `fetchBreaking`, tempting though it is: a 5-minute TTL that `RefreshWorker`
                // does not warm on the same key, inside a four-second budget. And emphatically not
                // `BreakingCoverageRepository.coverage`, which calls the uncached, always-network
                // `news.search()`.
                val news = async {
                    widgetSource<List<String>>(Source.NEWS, outcomes) {
                        val fetched = c.newsRepository.fetchCategory(NewsCategory.TOP, force = false)
                        dataAt[Source.NEWS] = fetched.timestampEpochMs
                        val articles = fetched.data.orEmpty()
                        BreakingNews.perOutlet(
                            articles,
                            outlet = { it.source },
                            timeMs = { it.publishedEpochMs },
                            max = WidgetBoard.MAX_SOURCES,
                            prefer = { BreakingCoverageRepository.isTrusted(it) },
                        ).map { "NEWS  ${it.source.uppercase()} · ${it.title}".take(110) }
                            .ifEmpty { null }
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
                        val fetched = c.spaceWeatherRepository.fetch(force = false, heavy = false)
                        dataAt[Source.SPACE] = fetched.timestampEpochMs
                        fetched.data?.kp?.let { "SPC  ${SpaceWeatherExplainers.kp(it).headline}" }
                    }
                }
                val fuel = async {
                    widgetSource<List<String>>(Source.FUEL, outcomes) {
                        // ⚠️ ONE fetch for both lines. The pump price rides on the same call as the
                        // benchmark — `usRetail` was being fetched and thrown away — and asking the
                        // repository twice inside a four-second budget would pay for it twice.
                        val d = c.fuelRepository.fetch(force = false).data ?: return@widgetSource null
                        val crude = d.benchmarks.firstOrNull()?.let { b ->
                            val price = b.price?.let { "$${"%.2f".format(it)}" }.orEmpty()
                            val pct = b.changePercent?.let { " ${signedPercent(it)}%" }.orEmpty()
                            "FUEL  ${b.label} $price$pct".trim()
                        }
                        // ⚠️ US-only and key-gated, by the repository — EIA is an American agency
                        // and the World Bank retired both of its pump-price indicators, so there is
                        // no free worldwide equivalent to fall back to. Absent elsewhere rather
                        // than blank, which is the honest rendering of "we cannot know this here".
                        val pump = d.usRetail
                            .filter { it.usdPerGallon != null }
                            .take(2)
                            .joinToString("  ·  ") { rp ->
                                "${pumpLabel(rp.product)} $${"%.2f".format(rp.usdPerGallon)}"
                            }
                            .ifBlank { null }
                            ?.let { "PUMP  $it /gal" }
                        listOfNotNull(crude, pump).ifEmpty { null }
                    }
                }
                val data = async {
                    if (!usageAccess) null else widgetSource<String>(Source.DATA, outcomes) {
                        // Local, no network, no coordinate — among the cheapest sources here, but
                        // still inside a budget: the sibling call in the Security Audit is what
                        // once froze that screen.
                        when (val r = c.mobileData.sinceCycleStart(s.dataCycleDay)) {
                            is MobileData.Reading.Used ->
                                "DATA  ${Formatters.number(r.bytes / 1_000_000_000.0, 2)} GB " +
                                    "· day ${r.daysInto} of ${r.lengthDays}"
                            // A phone with no radio has no mobile allowance, so this genuinely is
                            // "asked, answered, nothing to report" rather than a failure.
                            is MobileData.Reading.NoRadio -> null
                            // Cannot happen — `usageAccess` gated the call — but the `when` is
                            // exhaustive and treating it as absence would hide a real regression.
                            is MobileData.Reading.NoAccess -> null
                            is MobileData.Reading.Failed -> throw IllegalStateException(r.reason)
                        }
                    }
                }
                // Nearby danger. ⚠️ Deliberately ABOVE everything measured in the row order below:
                // an incident a few kilometres away is the one line on this widget that could change
                // what someone does in the next hour.
                val safety = async {
                    if (place == null) null else widgetSource<String>(Source.SAFETY, outcomes) {
                        val fetched = c.safetyRepository.fetch(place.latitude, place.longitude, force = false)
                        dataAt[Source.SAFETY] = fetched.timestampEpochMs
                        val near = fetched.data?.incidents.orEmpty()
                            .filter { it.distanceMeters <= SAFETY_RADIUS_M }
                            .minByOrNull { it.distanceMeters }
                            ?: return@widgetSource null
                        val km = "%.0f".format(near.distanceMeters / 1000.0)
                        val more = if (near.severity.isNotBlank()) " · ${near.severity.uppercase()}" else ""
                        "⚠ ${near.title.take(52)}  ${km}km ${Geodesy.cardinal(near.bearing)}$more"
                    }
                }
                val water = async {
                    if (place == null) null else widgetSource<String>(Source.WATER, outcomes) {
                        // ⚠️ `cached`, never `fetch`. This is a network source, and a widget must
                        // not start a NOAA request on a half-hourly schedule inside a four-second
                        // budget; `RefreshWorker` fills the cache and this reads what it left.
                        // Nothing held is genuinely nothing to report, which is what the row's
                        // absence then means.
                        //
                        // ⚠️ Null is also the ordinary answer for most of the world: NOAA measures
                        // American water, and there are no tide-prediction stations in Michigan at
                        // all, which is why the reading follows the LAKE where the owner lives.
                        c.waterRepository.cached(place.latitude, place.longitude)?.line
                    }
                }
                // Fourteen days of the diary, from the start of today — so an event already under
                // way is included. No network and no cache: a direct Instances query, which expands
                // recurrences for us, and filters hidden calendars and declined invitations exactly
                // as the calendar app does (see CalendarRepository).
                //
                // ⚠️ `agenda` is NOT a suspend function and does its ContentProvider query on
                // whatever thread calls it, so it is dispatched explicitly. It happens to be called
                // from an IO dispatcher here already, but relying on that would make this correct by
                // where it sits rather than by what it says.
                //
                // ⚠️ Deliberately not `CalendarObjectivesRepository.upcoming`, which geocodes each
                // event's location string over the network.
                //
                // ⚠️ The query asks for more than the list shows. The CORE caps what is drawn and
                // counts what it left out, and it can only count what it was given — capping in the
                // query would make "+N more" impossible to state.
                val calendar = async {
                    if (!canReadCalendar) null else widgetSource<List<Agenda.Event>>(Source.CALENDAR, outcomes) {
                        withContext(Dispatchers.IO) {
                            val today = Agenda.localDay(nowMs, offsetAt)
                            c.calendarRepository
                                .agenda(
                                    fromMs = Agenda.startOfLocalDay(today, offsetAt),
                                    toMs = Agenda.startOfLocalDay(today + AGENDA_DAYS, offsetAt),
                                    max = AGENDA_QUERY_MAX,
                                )
                                .map { ev ->
                                    Agenda.Event(
                                        id = ev.id,
                                        title = ev.title,
                                        startMs = ev.startMs,
                                        endMs = ev.endMs,
                                        allDay = ev.allDay,
                                        location = ev.location,
                                        calendarName = ev.calendarName,
                                        colorArgb = ev.colorArgb,
                                    )
                                }
                                .ifEmpty { null }
                        }
                    }
                }
                val comms = async {
                    widgetSource<List<String>>(Source.COMMS, outcomes) {
                        // ⚠️ `cached`, never `refresh`. Each mailbox is a TLS round trip and the
                        // worker is what pays for them; this reads what it left. The SMS half IS
                        // re-read here, because it is a local query costing nothing and a cached
                        // value could be half an hour behind an answer that is on the phone now.
                        val c2 = c.commsRepository.cached() ?: return@widgetSource null
                        buildList {
                            // ⚠️ Null is not zero. "No unread texts" is a fact about the inbox and
                            // "this app may not look" is a fact about the app, so a missing
                            // permission draws nothing rather than a reassuring 0.
                            c2.sms?.let { add("TEXTS  $it unread") }
                            // ⚠️ "new", not "unread", and the word comes from the core rather than
                            // from here. This counts what the phone's mail apps notified about and
                            // that has not been cleared, which is a different quantity from the
                            // IMAP rows below — so it is its own line and is never summed with
                            // them. Absent until notification access is granted.
                            MailGlance.line(c2.notifiedMail)?.let { add("MAIL  $it") }
                            c2.mailboxes.take(2).forEach { box ->
                                val label = box.label.take(12).uppercase()
                                // A mailbox that could not answer says so rather than vanishing —
                                // a row that disappears reads as a feature that broke.
                                add(box.unread?.let { "$label  $it unread" }
                                    ?: "$label  ${box.problem.orEmpty().take(28)}")
                            }
                        }.ifEmpty { null }
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
                    widgetSource<List<String>>(Source.ECONOMY, outcomes) {
                        // ⚠️ The dashboard was fetched whole and all but its FIRST usable series
                        // thrown away. The board has a column for it, so take the three that answer
                        // "how is the economy" — prices, growth, jobs — in that order, and keep
                        // whatever else is there behind them rather than inventing a ranking.
                        val series = c.economyRepository.fetchDashboard(force = false).data?.series.orEmpty()
                            .filter { it.latest != null }
                        if (series.isEmpty()) return@widgetSource null
                        val wanted = listOf("FP.CPI.TOTL.ZG", "NY.GDP.MKTP.KD.ZG", "SL.UEM.TOTL.ZS")
                        val ordered = wanted.mapNotNull { id -> series.firstOrNull { it.indicatorId == id } } +
                            series.filter { it.indicatorId !in wanted }
                        ordered.take(3).mapNotNull { s2 ->
                            s2.latest?.let { p ->
                                // ⚠️ These are World Bank ANNUAL series, so the year is not
                                // decoration — "2.9%" without it reads as this month's print.
                                "${s2.indicatorTitle.take(18)} ${Formatters.number(p.value, 1)}${s2.unit.take(10)} · ${p.year}"
                            }
                        }.ifEmpty { null }
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

                // The calendar is local and quick, and what you are in or about to be in belongs
                // straight after anything urgent — ahead of every measured reading below it.
                calendar.await()?.let { agendaEvents = it }
                val plan = Agenda.plan(agendaEvents, nowMs, offsetAt, AGENDA_DAYS, AGENDA_MAX_ITEMS)
                agendaPlan = plan
                Agenda.upNext(plan)?.let { e ->
                    rows += Row(
                        Agenda.upNextLine(e, plan.today, agendaFormat),
                        Role.PRIMARY,
                        argb = if (e.state == Agenda.State.NOW) ContextCompat.getColor(context, widgetAccentRes()) else null,
                        pending = CalendarIntents.event(
                            context, e.event.id, e.event.startMs, e.event.endMs, CalendarIntents.RC_UP_NEXT_ROW,
                        ),
                    )
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
                // ⚠️ The ROW form still takes ONE headline, deliberately. Rows are the scarce
                // resource here — the list already overruns its own cap — so the extra outlets
                // are spent on the board, which has three slots sitting empty.
                news.await()?.let {
                    headlines = it
                    it.firstOrNull()?.let { top -> rows += Row(top, Role.PRIMARY, route = Routes.NEWS) }
                }
                task.await()?.let { taskLine = it; rows += Row(it, Role.SECONDARY, route = Routes.HOME) }
                study.await()?.let { studyLine = it; rows += Row(it, Role.SECONDARY, route = Routes.STUDY) }
                sky.await()?.let { skyLine = it; rows += Row(it, Role.SECONDARY, route = Routes.ORBITAL) }
                space.await()?.let { spaceLine = it; rows += Row(it, Role.SECONDARY, route = Routes.HOME) }
                water.await()?.let { waterLine = it; rows += Row(it, Role.SECONDARY, route = Routes.WEATHER) }
                fuel.await()?.let {
                    fuelLines = it
                    rows += Row(it.first(), Role.SECONDARY, route = Routes.FUEL)
                }
                data.await()?.let { dataLine = it; rows += Row(it, Role.SECONDARY, route = Routes.SETTINGS) }
                comms.await()?.let {
                    commsLines = it
                    rows += Row(it.first(), Role.SECONDARY, route = Routes.SETTINGS)
                }
                econ.await()?.let {
                    econLines = it
                    rows += Row("ECON  ${it.first()}", Role.SECONDARY, route = Routes.ECONOMY)
                }
            }
        }

        // ⚠️ The stamp says when the board was REDRAWN; this says when any time-sensitive data on it
        // was actually saved, when that is old. Only feeds that drew something count — a feed that
        // drew nothing has no age to confess to.
        val today = Agenda.localDay(nowMs, offsetAt)
        val dayName = SimpleDateFormat("EEE", Locale.getDefault())
        val whenLabel: (Long) -> String = { ms ->
            if (Agenda.localDay(ms, offsetAt) == today) clock.format(Date(ms)) else dayName.format(Date(ms)).uppercase()
        }
        val feedNote = WidgetDiagnostics.staleNote(dataAt.filterKeys { outcomes[it] == Outcome.Ok }, nowMs, whenLabel)
        // Only when something was actually drawn at that place: a kept fix under four feeds that all
        // went quiet has no line to qualify.
        val placeNote = WidgetDiagnostics.placeNote(
            place?.recordedAtMs?.takeIf { PLACE_SOURCES.any { src -> outcomes[src] == Outcome.Ok } },
            nowMs,
            whenLabel,
        )
        // One slot for both, joined, so neither pushes the stamp beside it any further along a
        // one-line subhead than the old note alone did.
        val staleNote = listOf(feedNote, placeNote).filter { it.isNotBlank() }.joinToString(" · ")
            .takeIf { it.isNotBlank() }
        val sysLine = ctx?.let {
            val batt = if (it.batteryPct >= 0) "${it.batteryPct}%" else "—"
            val chg = if (it.isCharging) " ⚡" else ""
            listOfNotNull("SYS  PWR $batt$chg", networkAtRefresh(it.network), updated, staleNote)
                .joinToString("  ·  ")
        }
        sysLine?.let { rows += Row(it, Role.FOOTNOTE) }

        // ⚠️ The board splits what the row form combines, and the split is the point rather than a
        // difference of taste. The battery belongs in THIS PHONE beside the data and the storage —
        // every fact about this handset in one block — while the footer keeps the connection. Both
        // regions draw only at full height, so leaving the combined line in the footer as well
        // would print the same percentage a couple of centimetres below itself on every full
        // render. The row form is untouched: on a short widget one combined line is right, and
        // there is no column there for it to move into.
        //
        // `isPowerSave` is read by `snapshot()` on every load and has never been drawn anywhere in
        // the widget. It earns the space because it changes what the phone will DO — it defers the
        // background work this very board is fed by — so a stale reading and a saver-mode reading
        // look identical without it.
        val powerLine = ctx?.let {
            val batt = if (it.batteryPct >= 0) "${it.batteryPct}%" else "—"
            val chg = if (it.isCharging) " ⚡" else ""
            val saver = if (it.isPowerSave) "  ·  saver" else ""
            "POWER  $batt$chg$saver"
        }
        val netLine = ctx?.let { "SYS  ${networkAtRefresh(it.network)}" }

        // ── the same reading, as a board ────────────────────────────────────────────────────────
        // Nothing here fetches: every value was loaded once, above. A field left null is a region
        // the board simply will not draw.
        // Free space, through the one accessor. Null means the volume could not be read, which is
        // a different fact from "full" and must not render as zero.
        val storageLine = runCatching {
            c.deviceContextProvider.storage()?.let {
                "STORAGE  ${Formatters.number(it.freeBytes / 1_000_000_000.0, 1)} GB free"
            }
        }.getOrNull()

        val econColumn = WidgetBoard.Column("ECONOMY", econLines, Routes.ECONOMY)
        val dayColumn = WidgetBoard.Column("YOUR DAY", listOfNotNull(taskLine, studyLine, skyLine), Routes.STUDY)
        // ⚠️ Split into two rather than grown to four lines: a column holds three, and the crude
        // benchmark and the pump price answer a different question from what this handset has
        // spent. The second slot of the pair was left empty in the last slice for exactly this.
        val fuelColumn = WidgetBoard.Column("FUEL", fuelLines, Routes.FUEL)
        val phoneColumn = WidgetBoard.Column("THIS PHONE", listOfNotNull(powerLine, dataLine, storageLine), Routes.SETTINGS)
        val commsColumn = WidgetBoard.Column("WAITING", commsLines, Routes.SETTINGS)
        // ⚠️ The agenda used to be this pair's right half — four lines of today, the only calendar the
        // widget had. It is now its own region at the foot of the full board (see `agendaBlock`), so
        // this half is empty again and the pair draws WAITING alone, as it did before the agenda.
        val upNext = agendaPlan?.let(Agenda::upNext)
        val board = WidgetBoard.Board(
            header = listOfNotNull(place?.name?.uppercase(), date.ifBlank { null }, wx?.compact)
                .joinToString("  ·  "),
            headerRoute = Routes.HOME,
            // ⚠️ This is the ONLY reader of `use24HourClock` in the whole repository. The switch has
            // existed in Settings and done nothing at all — declared, given a PrefSwitch the user
            // can flip, and consulted by nobody. Following the DEVICE setting here instead would
            // have been the easy call and would have left it dead.
            clock24 = s?.use24HourClock ?: true,
            // The stale note sits beside the stamp it qualifies, and ahead of the stardate for the
            // same reason the stamp does: when the line ellipsizes, the decoration goes first.
            subhead = listOfNotNull(greeting, updated, staleNote, stardate)
                .filter { it.isNotBlank() }.joinToString(" · "),
            upNext = upNext?.let { Agenda.upNextLine(it, agendaPlan?.today ?: it.day, agendaFormat) },
            upNextArgb = upNext?.takeIf { it.state == Agenda.State.NOW }
                ?.let { ContextCompat.getColor(context, widgetAccentRes()) },
            upNextOpen = upNext?.let {
                CalendarIntents.event(context, it.event.id, it.event.startMs, it.event.endMs, CalendarIntents.RC_UP_NEXT_BOARD)
            },
            agenda = agendaBlock(context, agendaPlan, canReadCalendar, outcomes[Source.CALENDAR], agendaFormat, nowMs, offsetAt),
            alert = alertLine,
            alertRoute = Routes.SAFETY,
            lead = leadTitle,
            leadDetail = leadDetail,
            leadArgb = leadArgb,
            leadRoute = leadRoute,
            sources = headlines,
            sourcesRoute = Routes.NEWS,
            // ⚠️ The label states the horizon because the two numbers in a cell are not the same
            // span: the percentage is TODAY's move, and the line is `Quote.sparkline` — about a
            // month of daily closes. Intraday bars exist but are memory-cached only, so a restarted
            // widget process would pay a network fetch per instrument inside a four-second budget,
            // through a semaphore that allows five at a time. Daily closes are the honest choice;
            // letting the line imply intraday would not be.
            indices = mkt?.indices.orEmpty(),
            indicesLabel = "MAJOR INDICES · % TODAY, LINE 30 DAYS",
            stocks = mkt?.stocks.orEmpty(),
            stocksLabel = "KEY STOCKS · % TODAY, LINE 30 DAYS",
            breadth = mkt?.breadth,
            breadthPct = mkt?.upPct,
            // ⚠️ TRIMMED here rather than left to fall off the end. The board draws a fixed
            // number of readout slots and `getOrNull` past the last one is silent — which is how
            // the water line came to vanish whenever the weather detail, the air quality and space
            // weather were all present. Ordered most-consequential first, so what is shed is the
            // least useful line rather than whichever happened to be last.
            readouts = listOfNotNull(
                wx?.detail?.ifBlank { null }?.let { WidgetBoard.Readout(it, Routes.WEATHER) },
                // The air lives on WEATHER's own AIR sub-tab, so that is where it opens.
                wx?.air?.let { WidgetBoard.Readout(it, Routes.WEATHER) },
                // The lake or tide gauge has no screen of its own; the forecast is the nearest thing.
                waterLine?.let { WidgetBoard.Readout(it, Routes.WEATHER) },
                spaceLine?.let { WidgetBoard.Readout(it, Routes.SPACE_WX) },
            ).take(WidgetBoard.MAX_READOUTS),
            // ⚠️ Only pairs that have something GO in — `pairs` drops an empty column rather than
            // drawing a labelled blank, which would read as a feed that failed rather than one
            // this phone cannot answer. WAITING is empty until a mailbox is added or the texts
            // permission is granted, so on a fresh install that row is simply absent.
            // The board already hides a region whose two columns are both empty, so a column with
            // nothing in it costs nothing — but the LIST is still capped, and past the cap a whole
            // region would disappear without a word. Trimmed knowingly, same as the readouts.
            pairs = listOf(
                econColumn to dayColumn,
                fuelColumn to phoneColumn,
                commsColumn to WidgetBoard.Column("", emptyList()),
            ).take(WidgetBoard.MAX_PAIRS),
            foot = netLine,
            footRoute = Routes.SETTINGS,
        )
        // ⚠️ From the SAME events and the same instant the agenda was planned with, so the alarm
        // fires at a boundary this reading actually drew — computed from a second read, it could
        // arm for an event the board does not show. Only when the calendar could be read: without
        // it there is no boundary to be right about, and the periodic refresh covers the date.
        val nextRefreshMs = if (canReadCalendar) Agenda.nextBoundaryMs(agendaEvents, nowMs, offsetAt) else null
        return Loaded(rows, board, nextRefreshMs)
    }

    /**
     * The fourteen-day list as the full board draws it — or the one sentence saying why there is no
     * list.
     *
     * ⚠️ Three different empties, three different sentences, two different tap targets. "The calendar
     * may not be read", "the calendar could not be read this time" and "there is nothing in it" are
     * facts about the app, about this refresh and about the diary respectively; rendering them the
     * same way is how a widget comes to tell someone their fortnight is free when it was never
     * allowed to look. Only the first sends the tap to the permission page.
     */
    private fun agendaBlock(
        context: Context,
        plan: Agenda.Plan?,
        canRead: Boolean,
        outcome: Outcome?,
        fmt: Agenda.Format,
        nowMs: Long,
        offsetAt: (Long) -> Long,
    ): WidgetBoard.AgendaBlock {
        val heading = "AGENDA · $AGENDA_DAYS DAYS"
        if (!canRead) {
            return WidgetBoard.AgendaBlock(
                heading, emptyList(), "Calendar access not granted — tap to allow it",
                emptyOpensSettings = true, todayMs = nowMs,
            )
        }
        if (outcome is Outcome.Failed || outcome is Outcome.TimedOut) {
            return WidgetBoard.AgendaBlock(
                heading, emptyList(), "Could not read the calendar this time — tap to open it", todayMs = nowMs,
            )
        }
        if (plan == null || plan.all.isEmpty()) {
            return WidgetBoard.AgendaBlock(
                heading, emptyList(), "Nothing in the next $AGENDA_DAYS days — tap to open your calendar",
                todayMs = nowMs,
            )
        }
        val accent = ContextCompat.getColor(context, widgetAccentRes())
        val withCalendar = Agenda.showsCalendars(plan)
        val todayStart = Agenda.startOfLocalDay(plan.today, offsetAt)
        val rows = buildList {
            // A list that opens on Thursday reads as though today had been forgotten. Say it is clear.
            if (plan.days.firstOrNull()?.kind != Agenda.DayKind.TODAY) {
                add(
                    WidgetBoard.AgendaRow(
                        WidgetBoard.AgendaKind.DAY, Agenda.clearTodayHeading(plan.today, fmt),
                        textArgb = accent, fillIn = CalendarIntents.dayFillIn(todayStart),
                    ),
                )
            }
            plan.days.forEach { day ->
                add(
                    WidgetBoard.AgendaRow(
                        WidgetBoard.AgendaKind.DAY, Agenda.dayHeading(day, fmt),
                        textArgb = accent.takeIf { day.kind == Agenda.DayKind.TODAY },
                        fillIn = CalendarIntents.dayFillIn(Agenda.startOfLocalDay(day.epochDay, offsetAt)),
                    ),
                )
                day.entries.forEach { e ->
                    add(
                        WidgetBoard.AgendaRow(
                            WidgetBoard.AgendaKind.EVENT,
                            Agenda.titleLine(e, fmt),
                            Agenda.detailLine(e, fmt, withCalendar),
                            textArgb = accent.takeIf { e.state == Agenda.State.NOW },
                            barArgb = e.event.colorArgb,
                            fillIn = CalendarIntents.eventFillIn(e.event.id, e.event.startMs, e.event.endMs),
                        ),
                    )
                }
            }
            // ⚠️ Never silently short. A list that stops is otherwise indistinguishable from a diary
            // that stops, and the cap exists for the binder, not because the rest does not matter.
            if (plan.omitted > 0) {
                add(
                    WidgetBoard.AgendaRow(
                        WidgetBoard.AgendaKind.EVENT,
                        "+${plan.omitted} more in the next $AGENDA_DAYS days",
                        "open your calendar to see them",
                        fillIn = CalendarIntents.dayFillIn(todayStart),
                    ),
                )
            }
        }
        val n = plan.all.size
        return WidgetBoard.AgendaBlock(
            "$heading · $n ${if (n == 1) "EVENT" else "EVENTS"}", rows, todayMs = nowMs,
        )
    }

    /**
     * EIA's own product name, short enough for a widget column.
     *
     * ⚠️ Matched on a keyword rather than sliced to a fixed length: the feed returns "Regular
     * Gasoline" and "No 2 Diesel", so taking the first word gives "Regular" and "No" — and "No" is
     * not a fuel. Anything unrecognised keeps its first word, which is the honest fallback.
     */
    private fun pumpLabel(product: String): String {
        val p = product.lowercase()
        return when {
            "diesel" in p -> "diesel"
            "regular" in p -> "reg"
            "premium" in p -> "prem"
            "midgrade" in p || "mid-grade" in p -> "mid"
            else -> product.substringBefore(' ').lowercase()
        }
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

    /**
     * The connection as it was when the reading was taken.
     *
     * ⚠️ "OFFLINE" alone read as a claim about now. The worker often refreshes while the phone is
     * dozing, when the platform reports no network at all, so a phone sitting on home Wi-Fi showed
     * OFFLINE for as long as the reading stood.
     */
    private fun networkAtRefresh(kind: NetworkKind): String =
        if (kind == NetworkKind.OFFLINE) "OFFLINE AT REFRESH" else kind.name

    private companion object {
        /** The feeds that need a coordinate, and so go quiet together when there is none. */
        val PLACE_SOURCES = listOf(Source.WEATHER, Source.SAFETY, Source.SKY, Source.WATER)

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

        /** How many days the agenda covers, today included. The owner's choice. */
        const val AGENDA_DAYS = 14

        /**
         * How many events the list draws. A binder transaction has a hard ceiling and every row is a
         * small RemoteViews of its own; sixty events with their day headings is ~75 rows, well
         * inside it with the charts, and [REDUCED_AGENDA_ROWS] is the retry if a phone disagrees.
         */
        const val AGENDA_MAX_ITEMS = 60

        /**
         * How many the query may return. More than [AGENDA_MAX_ITEMS] on purpose: the core counts
         * what the cap leaves out, and it can only count what it was handed.
         */
        const val AGENDA_QUERY_MAX = 400

        /** The shorter list a failed render is retried with, before it is retried with none. */
        const val REDUCED_AGENDA_ROWS = 24

        /**
         * The full board's breakpoint. See the note where it is used: ~624dp of regions before the
         * list gets anything, plus room for a day heading and one event.
         */
        const val FULL_BOARD_MIN_HEIGHT_DP = 680f

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
