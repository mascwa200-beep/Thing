package dev.mascwa.pulse.feature.launcher

/**
 * The home console's widgets, as arithmetic over the ids Android hands out for them.
 *
 * A home screen that hosts other apps' widgets holds an `AppWidgetHost`, and every widget on it is an
 * integer id that host allocated. This file decides what to do with those ids; `LauncherActivity`
 * asks Android. Pure, so every rule runs under JUnit:
 *
 * - **A full console refuses.** [MAX_WIDGETS] at most, and adding past that answers null rather than
 *   quietly dropping a widget somebody placed. The dock follows the same rule, for the same reason.
 * - **Nothing is stranded.** An id Android is still holding that the console does not list is an
 *   orphan — left behind by a cancelled bind, or a process that died mid-way — and [reconcile] hands
 *   it back for deletion, so the host never accumulates widgets nobody can see.
 * - ⚠️ **Except the one being added right now.** An activity's results arrive AFTER its `onStart`,
 *   so a reconcile run on the way back from Android's bind prompt, or from the widget's own
 *   configuration screen, would otherwise delete the very widget whose answer is on its way.
 *   [reconcile] spares [pending] for exactly that.
 */
object HomeWidgets {

    const val MAX_WIDGETS = 6

    /** The tallest and shortest a widget is drawn, in dp, whatever it asks for. */
    const val MIN_HEIGHT_DP = 110
    const val MAX_HEIGHT_DP = 440

    /**
     * [stored] with [id] appended — unchanged if it is already there — or null when the console is
     * full and the widget must be refused.
     */
    fun add(stored: List<Int>, id: Int): List<Int>? = when {
        id in stored -> stored
        stored.size >= MAX_WIDGETS -> null
        else -> stored + id
    }

    fun remove(stored: List<Int>, id: Int): List<Int> = stored.filter { it != id }

    /** What [reconcile] decided: the widgets still shown, in their order, and ids to give back. */
    data class Reconciled(val keep: List<Int>, val orphans: List<Int>)

    /**
     * Square the console's list with what the host is actually holding.
     *
     * - A stored id the host no longer knows — the provider was uninstalled, or Android reset the
     *   host — is dropped: there is nothing left to draw.
     * - An id the host holds that the console does not list is an orphan, EXCEPT [pending].
     */
    fun reconcile(stored: List<Int>, hostIds: List<Int>, pending: Int?): Reconciled {
        val held = hostIds.toSet()
        val listed = stored.toSet()
        return Reconciled(
            keep = stored.filter { it in held }.distinct(),
            orphans = hostIds.filter { it !in listed && it != pending }.distinct(),
        )
    }

    /**
     * How tall to draw a widget whose provider asks for [minHeightPx] pixels, on a screen of
     * [density]. `AppWidgetProviderInfo.minHeight` is in pixels, not dp; a density Android failed to
     * report falls back to the floor rather than dividing by it.
     */
    fun heightDp(minHeightPx: Int, density: Float): Int {
        if (!(density > 0f)) return MIN_HEIGHT_DP
        return (minHeightPx / density).toInt().coerceIn(MIN_HEIGHT_DP, MAX_HEIGHT_DP)
    }

    /** The longest a widget's name is written on its panel's tab, ellipsis included. */
    const val MAX_TITLE_CHARS = 20

    /**
     * A widget's name as its tab carries it.
     *
     * ⚠️ **Capped, because the tab row cannot shrink it.** The tab is laid out first and unweighted,
     * so a name longer than the row takes all of it and REMOVE beside it is drawn at no width at all
     * — gone, with nothing to show it was ever there. Widget names are other apps' text with no limit.
     * Measured for the narrowest phone this runs on (360 dp): the row is about 260 dp, REMOVE and its
     * gap take about 64, and Antonio capitals at 14 sp with their tracking are about 7.6 dp each —
     * 20 characters is about 180 dp with the tab's padding. At the largest font scales a phone that
     * narrow can still crowd it; the full name is on the picker and in REMOVE's own dialog.
     */
    fun title(label: String): String {
        val name = label.trim()
        return if (name.length <= MAX_TITLE_CHARS) name else name.take(MAX_TITLE_CHARS - 1).trimEnd() + "…"
    }

    /** One provider in the picker, reduced to the three facts the picker decides on. */
    data class Offer(val label: String, val packageName: String, val features: Int)

    /**
     * The widgets worth offering, in a stable order: never one that asks to be hidden from pickers,
     * and never one of LCARS's own — the board it would show is already on the console.
     */
    fun <T> offered(providers: List<T>, ownPackage: String, facts: (T) -> Offer): List<T> =
        providers
            .filter { p ->
                val f = facts(p)
                f.packageName != ownPackage && f.features and WIDGET_FEATURE_HIDE_FROM_PICKER == 0
            }
            .sortedWith(compareBy<T> { facts(it).label.lowercase() }.thenBy { facts(it).packageName })

    /**
     * Whether a newly bound widget needs its configuration screen before it is shown: it names one,
     * and it has not said configuring it is optional.
     */
    fun needsConfigure(hasConfigure: Boolean, features: Int): Boolean =
        hasConfigure && features and WIDGET_FEATURE_CONFIGURATION_OPTIONAL == 0

    // android.appwidget.AppWidgetProviderInfo, restated so this file needs no Android to test. Each
    // value was read out of the platform-35 SDK stub with `javap -constants` — not recalled: they are
    // not in the order their names suggest.
    internal const val WIDGET_FEATURE_RECONFIGURABLE = 1
    internal const val WIDGET_FEATURE_HIDE_FROM_PICKER = 2
    internal const val WIDGET_FEATURE_CONFIGURATION_OPTIONAL = 4
}
