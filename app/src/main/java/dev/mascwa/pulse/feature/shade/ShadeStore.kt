package dev.mascwa.pulse.feature.shade

import android.app.Notification
import android.app.PendingIntent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.lang.ref.WeakReference

/**
 * The notification shade as the LCARS home console sees it: a snapshot, rebuilt whole from
 * `getActiveNotifications` every time anything on the shade changes.
 *
 * ⚠️ **Memory only, and that is the privacy rule, not an optimisation.** The titles and text of
 * other apps' notifications are held here for as long as the process lives and nowhere else — never
 * in LCARS's settings, its logs, the activity log, a debug report or the audit ledger. A process death
 * loses the snapshot, and the listener's next connection rebuilds it from the shade itself.
 *
 * ⚠️ **A snapshot, never an accumulator** — the rule `MailNotificationListener` already follows, for
 * the same reason: posts and removals missed while the process was dead cannot leave anything stale.
 *
 * The live intents a notice opens with ([Handle]) live beside the snapshot rather than in it, because
 * a `PendingIntent` is a platform object and [ShadeDigest] stays pure.
 */
object ShadeStore {

    /** What tapping a notice does: its own intent, and whether Android would clear it on the tap. */
    class Handle(val contentIntent: PendingIntent?, val autoCancel: Boolean)

    class Item(val entry: ShadeDigest.Entry, val handle: Handle)

    data class View(val connected: Boolean, val groups: List<ShadeDigest.Group>)

    private val _view = MutableStateFlow(View(connected = false, groups = emptyList()))
    val view: StateFlow<View> = _view.asStateFlow()

    @Volatile private var handles: Map<String, Handle> = emptyMap()
    @Volatile private var listener: WeakReference<NotificationListenerService>? = null

    /** The reader is bound. Held weakly: the system owns the service's lifetime, not this object. */
    fun connected(service: NotificationListenerService) {
        listener = WeakReference(service)
    }

    /** The reader is gone. Everything it read goes with it, so nothing stale is ever offered. */
    fun disconnected() {
        listener = null
        handles = emptyMap()
        _view.value = View(connected = false, groups = emptyList())
    }

    fun publish(items: List<Item>, self: String) {
        handles = items.associate { it.entry.key to it.handle }
        _view.value = View(connected = true, groups = ShadeDigest.digest(items.map { it.entry }, self))
    }

    fun handle(key: String): Handle? = handles[key]

    /** Clear one notice, as swiping it off the shade would. False when the reader is not bound. */
    fun dismiss(key: String): Boolean {
        val service = listener?.get() ?: return false
        return runCatching { service.cancelNotification(key) }.isSuccess
    }

    /**
     * Clear what "Clear all" in the shade would clear, one key at a time, so the list sent is exactly
     * the one the owner saw — a notice arriving between drawing the button and pressing it is kept.
     */
    fun clear(keys: List<String>): Boolean {
        val service = listener?.get() ?: return false
        if (keys.isEmpty()) return true
        return runCatching { service.cancelNotifications(keys.toTypedArray()) }.isSuccess
    }
}

/**
 * Reading one `StatusBarNotification` into what the panel shows.
 *
 * ⚠️ Its own object, so the platform signatures here compile against nothing but `android.jar` and
 * the pure core — the same split, for the same reason, as `MailNotices`.
 */
internal object ShadeNotices {

    /**
     * One notice, or null if it cannot be read. Defensive throughout: this runs over every
     * notification on the phone, from arbitrary apps, inside a service the system rebinds in a loop
     * if it throws.
     */
    fun read(sbn: StatusBarNotification, labelOf: (String) -> String): ShadeStore.Item? = runCatching {
        val n = sbn.notification ?: return null
        val extras = n.extras
        val title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (
            extras?.getCharSequence(Notification.EXTRA_TEXT)
                ?: extras?.getCharSequence(Notification.EXTRA_BIG_TEXT)
                ?: extras?.getCharSequence(Notification.EXTRA_SUB_TEXT)
            )?.toString().orEmpty()
        val entry = ShadeDigest.Entry(
            key = sbn.key,
            pkg = sbn.packageName,
            appLabel = labelOf(sbn.packageName),
            title = title.trim(),
            text = text.trim(),
            postedAtMs = sbn.postTime,
            clearable = sbn.isClearable,
            groupKey = sbn.groupKey.orEmpty(),
            // ⚠️ The flag, not `sbn.isGroup`, which is true for a summary AND its children.
            isGroupSummary = (n.flags and Notification.FLAG_GROUP_SUMMARY) != 0,
        )
        ShadeStore.Item(entry, ShadeStore.Handle(n.contentIntent, (n.flags and Notification.FLAG_AUTO_CANCEL) != 0))
    }.getOrNull()
}
