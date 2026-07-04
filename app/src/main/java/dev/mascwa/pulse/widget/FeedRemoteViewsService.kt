package dev.mascwa.pulse.widget

import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import dev.mascwa.pulse.PulseApplication
import dev.mascwa.pulse.R
import dev.mascwa.pulse.data.news.NewsCategory
import kotlinx.coroutines.runBlocking
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

private data class FeedRow(val category: String, val value: String, val color: Int)

// NIGHTWIRE palette as ARGB ints (top-level `val`, not `const` — `.toInt()` isn't a compile-time constant).
private val INK = 0xFFE6EFFA.toInt()
private val POSITIVE = 0xFF46F9A0.toInt()
private val NEGATIVE = 0xFFFF4D6D.toInt()

private class FeedFactory(private val context: Context) : RemoteViewsService.RemoteViewsFactory {

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
    override fun getItemId(position: Int): Long = position.toLong()
    override fun hasStableIds(): Boolean = false
    override fun getLoadingView(): RemoteViews? = null

    override fun getViewAt(position: Int): RemoteViews {
        val view = RemoteViews(context.packageName, R.layout.widget_feed_item)
        val row = rows.getOrNull(position) ?: return view
        view.setTextViewText(R.id.feed_item_cat, row.category)
        view.setTextViewText(R.id.feed_item_value, row.value)
        view.setTextColor(R.id.feed_item_value, row.color)
        view.setOnClickFillInIntent(R.id.feed_item_root, Intent())
        return view
    }

    private fun loadRows(): List<FeedRow> {
        val app = context.applicationContext as? PulseApplication ?: return emptyList()
        val c = app.container
        return runBlocking {
            val out = mutableListOf<FeedRow>()

            // Markets — top movers by absolute % change.
            runCatching {
                c.marketsRepository.fetchWatchlist(force = false).data.orEmpty()
                    .filter { it.changePercent != null }
                    .sortedByDescending { abs(it.changePercent ?: 0.0) }
                    .take(3)
                    .forEach { q ->
                        val pct = q.changePercent ?: 0.0
                        out += FeedRow("MARKETS", "${q.label}   ${signed(pct)}%", if (pct >= 0) POSITIVE else NEGATIVE)
                    }
            }
            // Fuel / energy benchmark (crude futures).
            runCatching {
                c.fuelRepository.fetch(force = false).data?.benchmarks?.firstOrNull()?.let { b ->
                    val pct = b.changePercent ?: 0.0
                    val price = b.price?.let { "%.2f".format(it) }.orEmpty()
                    out += FeedRow(
                        "FUEL", "${b.label}   $price   ${signed(pct)}%".trim(),
                        if (pct >= 0) POSITIVE else NEGATIVE,
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
                            out += FeedRow(cat, "${s.indicatorTitle}: ${"%.1f".format(pt.value)} ${s.unit}".trim(), INK)
                        }
                    }
            }
            // Top news headlines.
            runCatching {
                c.newsRepository.fetchCategory(NewsCategory.TOP, force = false).data.orEmpty()
                    .take(3)
                    .forEach { a -> out += FeedRow("NEWS · ${a.source.uppercase()}".take(28), a.title, INK) }
            }

            if (out.isEmpty()) out += FeedRow("PULSE", "Open Pulse to load the live feed", INK)
            out
        }
    }
}

private fun signed(v: Double): String = (if (v >= 0) "+" else "") + "%.2f".format(v)
