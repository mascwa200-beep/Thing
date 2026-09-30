package dev.mascwa.pulse.feature.launcher

import android.app.Activity
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.SizeF
import android.view.View
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * The home console's widgets: other apps' widgets, hosted the way any launcher hosts them.
 *
 * Android gives a launcher an `AppWidgetHost`; every widget on it is an integer id the host allocated
 * and the store remembers ([HomeStore.widgets]). The decisions — full, orphaned, how tall, which to
 * offer, whether to configure — are [HomeWidgets]', where they are tested. This class asks Android.
 *
 * ## Adding one
 *
 * 1. Allocate an id and record it as PENDING, on disk.
 * 2. Bind it to the provider. Android lets that happen silently only for apps it already trusts, so
 *    usually the answer is [Step.Ask]: the caller opens Android's own "allow LCARS to place widgets"
 *    prompt and hands the result to [onBindResult]. There is no manifest permission for this — the
 *    prompt is how a launcher gets the right.
 * 3. If the widget has a configuration screen it did not mark optional, open it. Its answer arrives in
 *    the activity's `onActivityResult` under [REQUEST_CONFIGURE], and goes to [onConfigureResult].
 * 4. Only then is it placed. A cancel anywhere gives the id back to Android.
 *
 * ⚠️ **PENDING is on disk because every one of those steps leaves the home screen.** Its answer
 * arrives after `onStart`, where [reconcile] runs — and Android may have reclaimed the process in
 * between. Without the pending record, [reconcile] would delete the very widget being set up.
 *
 * ⚠️ **Every change to the list takes one lock.** A reconcile that read the list before a placement
 * and wrote it back after would silently take the new widget off again.
 *
 * ## Honest limits
 *
 * - A widget that scrolls inside itself may fight the console's own scroll.
 * - Only this profile's widgets are offered: `getInstalledProvidersForProfile(null)` is the calling
 *   user's.
 * - Widgets update only while the console is visible ([stop] runs in `onStop`), as on every launcher.
 */
internal class HomeWidgetHost(private val activity: Activity, private val store: HomeStore) {

    /** What the caller must do next after a step of adding a widget. */
    sealed interface Step {
        /** Nothing: it is placed, cancelled, or waiting on a screen already opened. */
        data object Done : Step

        /** Open this with `startActivityForResult`, and hand the result to [onBindResult]. */
        data class Ask(val intent: Intent) : Step

        /** It could not be added, and this says why. */
        data class Refused(val why: String) : Step
    }

    private val host = AppWidgetHost(activity.applicationContext, HOST_ID)
    private val manager = AppWidgetManager.getInstance(activity.applicationContext)
    private val lock = Mutex()

    /** Views made once and kept — see [WidgetActions.view]. Main thread only. */
    private val views = HashMap<Int, AppWidgetHostView>()
    private val failed = HashSet<Int>()
    private val sizes = HashMap<Int, SizeF>()

    /** The providers behind the last picker shown, by [WidgetOffer.key]. Main thread only. */
    private var offered: Map<String, AppWidgetProviderInfo> = emptyMap()

    /** What the console draws; null until the first [reconcile] has read what is placed. */
    val placed: MutableState<List<PlacedWidget>?> = mutableStateOf(null)

    fun start() {
        runCatching { host.startListening() }
    }

    fun stop() {
        runCatching { host.stopListening() }
    }

    fun clear() {
        views.clear()
        failed.clear()
        sizes.clear()
    }

    /** Square the console's list with what Android is holding, and redraw it. Every `onStart`. */
    suspend fun reconcile() = lock.withLock {
        val stored = store.widgets()
        // If the host cannot answer, change nothing: an empty answer here would wipe the console.
        val held = runCatching { host.appWidgetIds.toList() }.getOrNull() ?: stored
        val r = HomeWidgets.reconcile(stored, held, store.pendingWidget())
        r.orphans.forEach(::giveBack)
        if (r.keep != stored) store.saveWidgets(r.keep)
        publish(r.keep)
    }

    /** Every widget this phone offers that is worth offering, labelled, in the picker's order. */
    suspend fun offers(): List<WidgetOffer> {
        val found = withContext(Dispatchers.IO) {
            val pm = activity.packageManager
            val all = runCatching { manager.getInstalledProvidersForProfile(null) }.getOrNull().orEmpty()
            val labelled = all.map { info -> info to (runCatching { info.loadLabel(pm) }.getOrNull() ?: info.provider.className) }
            HomeWidgets.offered(labelled, activity.packageName) { (info, label) ->
                HomeWidgets.Offer(label, info.provider.packageName, info.widgetFeatures)
            }.map { (info, label) ->
                info to WidgetOffer(info.provider.flattenToString(), label, appName(pm, info.provider.packageName))
            }
        }
        offered = found.associate { (info, offer) -> offer.key to info }
        return found.map { it.second }
    }

    /** The first step of adding [offer]. */
    suspend fun begin(offer: WidgetOffer): Step = lock.withLock {
        val info = offered[offer.key] ?: return@withLock Step.Refused("That widget is no longer offered.")
        if (store.widgets().size >= HomeWidgets.MAX_WIDGETS) return@withLock Step.Refused(WIDGETS_FULL)
        // A setup abandoned by a process that died mid-way left its id pending; give it back first.
        store.pendingWidget()?.let(::giveBack)
        val id = runCatching { host.allocateAppWidgetId() }.getOrNull()
            ?: return@withLock Step.Refused("Android would not make room for a widget.")
        store.setPendingWidget(id)
        val bound = runCatching { manager.bindAppWidgetIdIfAllowed(id, info.profile, info.provider, null) }
            .getOrDefault(false)
        if (bound) {
            afterBound(id)
        } else {
            Step.Ask(
                Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE, info.profile),
            )
        }
    }

    /** Android's bind prompt has answered. A refusal is not an error, so it says nothing. */
    suspend fun onBindResult(allowed: Boolean): Step = lock.withLock {
        val id = store.pendingWidget() ?: return@withLock Step.Done
        if (allowed) afterBound(id) else abandon(id, null)
    }

    /** The widget's own configuration screen has answered. */
    suspend fun onConfigureResult(ok: Boolean): Step = lock.withLock {
        val id = store.pendingWidget() ?: return@withLock Step.Done
        if (ok) place(id) else abandon(id, null)
    }

    /** The prompt could not even be opened: give the id back rather than leave it pending. */
    suspend fun abandonPending(why: String): Step = lock.withLock {
        val id = store.pendingWidget() ?: return@withLock Step.Refused(why)
        abandon(id, why)
    }

    suspend fun remove(id: Int) = lock.withLock {
        val next = store.removeWidget(id)
        giveBack(id)
        publish(next)
    }

    /** The widget's view, made the first time it is asked for and kept. Main thread. */
    fun view(id: Int): View? {
        views[id]?.let { return it }
        if (id in failed) return null
        val info = runCatching { manager.getAppWidgetInfo(id) }.getOrNull()
        val made = info?.let { runCatching { host.createView(activity, id, it) }.getOrNull() }
        if (made == null) {
            failed += id
            return null
        }
        views[id] = made
        // A size may have arrived before the view existed; tell the widget now.
        sizes[id]?.let { runCatching { made.updateAppWidgetSize(Bundle(), listOf(it)) } }
        return made
    }

    /**
     * Tell the widget the size it is drawn at, so it lays itself out for it rather than for a guess.
     * Rounded to whole dp and sent only when it changes — a widget re-lays itself out on every call.
     */
    fun sized(id: Int, widthDp: Float, heightDp: Float) {
        val size = SizeF(widthDp.roundToInt().toFloat(), heightDp.roundToInt().toFloat())
        if (sizes[id] == size) return
        sizes[id] = size
        runCatching { views[id]?.updateAppWidgetSize(Bundle(), listOf(size)) }
    }

    // ── inside the lock ─────────────────────────────────────────────────────────────────────────

    private suspend fun afterBound(id: Int): Step {
        val info = runCatching { manager.getAppWidgetInfo(id) }.getOrNull()
            ?: return abandon(id, "That widget is no longer installed.")
        if (!HomeWidgets.needsConfigure(info.configure != null, info.widgetFeatures)) return place(id)
        return runCatching {
            host.startAppWidgetConfigureActivityForResult(activity, id, 0, REQUEST_CONFIGURE, null)
            Step.Done
        }.getOrElse { abandon(id, "That widget's setup screen would not open.") }
    }

    private suspend fun place(id: Int): Step {
        val next = store.addWidget(id)
        store.setPendingWidget(null)
        if (next == null) {
            giveBack(id)
            return Step.Refused(WIDGETS_FULL)
        }
        publish(next)
        return Step.Done
    }

    private suspend fun abandon(id: Int, why: String?): Step {
        store.setPendingWidget(null)
        giveBack(id)
        return why?.let { Step.Refused(it) } ?: Step.Done
    }

    private suspend fun publish(ids: List<Int>) {
        val density = activity.resources.displayMetrics.density
        val pm = activity.packageManager
        val list = withContext(Dispatchers.IO) {
            ids.mapNotNull { id ->
                val info = runCatching { manager.getAppWidgetInfo(id) }.getOrNull() ?: return@mapNotNull null
                val label = runCatching { info.loadLabel(pm) }.getOrNull() ?: info.provider.className
                PlacedWidget(id, label, HomeWidgets.heightDp(info.minHeight, density))
            }
        }
        // Views for widgets that are gone are dropped, so the host view does not outlive its widget.
        val live = list.map { it.id }.toSet()
        views.keys.retainAll(live)
        sizes.keys.retainAll(live)
        placed.value = list
    }

    /** Hand an id back to Android and forget everything about it. Main thread. */
    private fun giveBack(id: Int) {
        runCatching { host.deleteAppWidgetId(id) }
        views.remove(id)
        failed.remove(id)
        sizes.remove(id)
    }

    private fun appName(pm: PackageManager, pkg: String): String =
        runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrNull() ?: pkg

    companion object {
        /**
         * This launcher's host id. Any integer; it only has to stay the same across starts, because
         * Android keeps the widgets bound to it.
         */
        const val HOST_ID = 0x4C43

        /**
         * The widget configuration screen's request code. ⚠️ Below 0x10000 on purpose: the activity
         * result registry numbers its own requests from 0x10000 up, so this can never be answered to
         * one of them instead — read out of `ActivityResultRegistry`, not recalled.
         */
        const val REQUEST_CONFIGURE = 0x4C57
    }
}
