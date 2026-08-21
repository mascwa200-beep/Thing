package dev.mascwa.pulse.widget

import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import androidx.core.content.ContextCompat
import dev.mascwa.pulse.MainActivity
import dev.mascwa.pulse.PulseApplication
import dev.mascwa.pulse.R
import dev.mascwa.pulse.data.news.NewsCategory
import dev.mascwa.pulse.navigation.Routes
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs

/**
 * Backs the live-feed widget's auto-flipping AdapterViewFlipper. The factory builds a compact set of
 * glanceable rows — top market movers · fuel/energy benchmark · economy/inflation indicators · top news —
 * from the app's on-device cached data. Loaded synchronously on the binder thread (allowed for a
 * RemoteViewsFactory) and fully defensive per source, so one missing feed never blanks the whole widget.
 */
class FeedRemoteViewsService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory = FeedFactory(applicationContext)
}

/**
 * One line of the feed.
 *
 * [route] is where tapping this row lands. It exists because the fill-in intent was empty and could
 * not have carried it anyway — see [FeedWidgetProvider] for the template flag that made every row
 * in the widget do the same thing.
 */
private data class FeedRow(
    val category: String,
    val value: String,
    val color: Int,
    val route: String,
)

private class FeedFactory(private val context: Context) : RemoteViewsService.RemoteViewsFactory {

    // Resolved once per factory. Colours now come from the shared widget tokens; these used to be
    // hardcoded ARGB ints under a comment that named them as the retired NIGHTWIRE palette, which
    // meant market direction on the home screen was drawn in a green and a red the app had dropped.
    private val ink by lazy { ContextCompat.getColor(context, R.color.nw_ink) }
    private val positive by lazy { ContextCompat.getColor(context, R.color.nw_positive) }
    private val negative by lazy { ContextCompat.getColor(context, R.color.nw_negative) }

    @Volatile private var rows: List<FeedRow> = emptyList()

    override fun onCreate() {}

    /**
     * Rebuild the rows. The load blocks on cached-data reads via [runBlocking]; if the framework interrupts
     * this binder thread mid-refresh (host teardown, a superseded update) that surfaces as an
     * [InterruptedException] straight out of [runBlocking] — which the per-source `runCatching` blocks inside
     * it can't catch. Guard the whole call so a cancelled refresh never crashes the widget, and keep the
     * last-known rows rather than blanking the feed. We deliberately don't re-assert the interrupt: we've
     * handled the cancellation, and re-interrupting this pooled framework thread could fail an unrelated task.
     */
    override fun onDataSetChanged() {
        rows = runCatching { loadRows() }.getOrElse { rows }
    }

    override fun onDestroy() { rows = emptyList() }

    override fun getCount(): Int = rows.size
    override fun getViewTypeCount(): Int = 1

    /**
     * ⚠️ Stable, and keyed on the row rather than its index.
     *
     * With `hasStableIds() = false` and the position as the id, the host had no way to tell that
     * the row it was showing still existed after a refresh, so the flipper snapped back to the
     * first row every time the background worker ran — which is every refresh interval, all day.
     */
    override fun getItemId(position: Int): Long =
        rows.getOrNull(position)?.let { (it.category + it.value).hashCode().toLong() }
            ?: position.toLong()

    override fun hasStableIds(): Boolean = true
    override fun getLoadingView(): RemoteViews? = null

    override fun getViewAt(position: Int): RemoteViews {
        val view = RemoteViews(context.packageName, R.layout.widget_feed_item)
        val row = rows.getOrNull(position) ?: return view
        view.setTextViewText(R.id.feed_item_cat, row.category)
        view.setTextViewText(R.id.feed_item_value, row.value)
        view.setTextColor(R.id.feed_item_value, row.color)
        // The fill-in carries the row's own destination, which is the whole point of a template.
        view.setOnClickFillInIntent(
            R.id.feed_item_root,
            Intent().putExtra(MainActivity.EXTRA_ROUTE, row.route),
        )
        return view
    }

    private fun loadRows(): List<FeedRow> {
        val app = context.applicationContext as? PulseApplication ?: return emptyList()
        val c = app.container
        // ⚠️ Bounded, like every other widget load. `force = false` is not "cache only" — on a cold
        // or expired cache it goes to the network, and this is a **binder thread** blocked by
        // `runBlocking`, so an unbounded wait here holds a framework thread rather than just this
        // widget. On timeout the previous rows stand.
        return runBlocking {
            withTimeoutOrNull(WIDGET_LOAD_TIMEOUT_MS) {
                val out = mutableListOf<FeedRow>()

                // Markets — top movers by absolute % change.
                runCatching {
                    c.marketsRepository.fetchWatchlist(force = false).data.orEmpty()
                        .filter { it.changePercent != null }
                        .sortedByDescending { abs(it.changePercent ?: 0.0) }
                        .take(3)
                        .forEach { q ->
                            val pct = q.changePercent ?: 0.0
                            out += FeedRow(
                                "MARKETS", "${q.label}   ${signedPercent(pct)}%",
                                if (pct >= 0) positive else negative, Routes.MARKETS,
                            )
                        }
                }
                // Fuel / energy benchmark (crude futures).
                runCatching {
                    c.fuelRepository.fetch(force = false).data?.benchmarks?.firstOrNull()?.let { b ->
                        val pct = b.changePercent ?: 0.0
                        val price = b.price?.let { "%.2f".format(it) }.orEmpty()
                        out += FeedRow(
                            "FUEL", "${b.label}   $price   ${signedPercent(pct)}%".trim(),
                            if (pct >= 0) positive else negative, Routes.FUEL,
                        )
                    }
                }
                // Economy / inflation indicators (float an inflation/CPI series first if present).
                runCatching {
                    c.economyRepository.fetchDashboard(force = false).data?.series.orEmpty()
                        .sortedByDescending {
                            it.indicatorTitle.contains("inflation", true) || it.indicatorTitle.contains("CPI", true)
                        }
                        .take(2)
                        .forEach { s ->
                            s.latest?.let { pt ->
                                val cat = if (s.indicatorTitle.contains("inflation", true)) "INFLATION" else "ECONOMY"
                                out += FeedRow(
                                    cat, "${s.indicatorTitle}: ${"%.1f".format(pt.value)} ${s.unit}".trim(),
                                    ink, Routes.ECONOMY,
                                )
                            }
                        }
                }
                // Top news headlines.
                runCatching {
                    c.newsRepository.fetchCategory(NewsCategory.TOP, force = false).data.orEmpty()
                        .take(3)
                        .forEach { a ->
                            out += FeedRow(
                                "NEWS · ${a.source.uppercase()}".take(28), a.title, ink, Routes.NEWS,
                            )
                        }
                }

                if (out.isEmpty()) {
                    out += FeedRow("LCARS", "Open LCARS to load the live feed", ink, Routes.HOME)
                }
                out.toList()
            } ?: rows
        }
    }
}
