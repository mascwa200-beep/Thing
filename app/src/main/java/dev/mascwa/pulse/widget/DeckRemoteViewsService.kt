package dev.mascwa.pulse.widget

import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import androidx.core.graphics.drawable.toBitmap
import dev.mascwa.pulse.R
import dev.mascwa.pulse.feature.dial.DialLaunchTrampolineActivity

/**
 * Fills the Reactor Deck (a StackView) with every launchable app as a card — auto-populated like the app
 * list, sorted by name. Each card carries its package as a fill-in extra so a tap launches that app via the
 * trampoline. Icons are decoded lazily per visible card.
 */
class DeckRemoteViewsService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory = DeckFactory(applicationContext)
}

private class DeckFactory(private val context: Context) : RemoteViewsService.RemoteViewsFactory {

    private data class App(val pkg: String, val label: String)

    @Volatile private var apps: List<App> = emptyList()

    override fun onCreate() {}
    override fun onDataSetChanged() { apps = load() }
    override fun onDestroy() { apps = emptyList() }

    override fun getCount(): Int = apps.size
    override fun getViewTypeCount(): Int = 1
    override fun getItemId(position: Int): Long = position.toLong()
    override fun hasStableIds(): Boolean = false
    override fun getLoadingView(): RemoteViews? = null

    override fun getViewAt(position: Int): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_deck_item)
        val app = apps.getOrNull(position) ?: return views
        views.setTextViewText(R.id.deck_item_name, app.label)
        runCatching { context.packageManager.getApplicationIcon(app.pkg).toBitmap(144, 144) }
            .getOrNull()?.let { views.setImageViewBitmap(R.id.deck_item_icon, it) }
        views.setOnClickFillInIntent(
            R.id.deck_item_root,
            Intent().putExtra(DialLaunchTrampolineActivity.EXTRA_PKG, app.pkg),
        )
        return views
    }

    private fun load(): List<App> = runCatching {
        val pm = context.packageManager
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        pm.queryIntentActivities(main, 0)
            .mapNotNull { ri ->
                val pkg = ri.activityInfo?.packageName ?: return@mapNotNull null
                App(pkg, ri.loadLabel(pm).toString())
            }
            .distinctBy { it.pkg }
            .sortedBy { it.label.lowercase() }
    }.getOrDefault(emptyList())
}
