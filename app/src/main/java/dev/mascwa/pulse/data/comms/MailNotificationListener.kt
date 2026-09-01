package dev.mascwa.pulse.data.comms

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import dev.mascwa.pulse.PulseApplication
import dev.mascwa.pulse.core.telemetry.MailGlance
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Reads the notification shade and works out how much mail is waiting.
 *
 * This is the whole of "link your email without a password": no host, no port, no credential, no
 * network. The mail app has already been told and has already said so on screen; this counts what it
 * said. Every rule for arriving at the number lives in [MailGlance] — pure and JVM-tested, because
 * none of it is worth deciding on a device — and every platform signature lives in [MailNotices],
 * which compiles against the real `android.jar` locally. What is left here is the lifecycle.
 *
 * ## ⚠️ The manifest entry is the part that fails silently
 *
 * Every other service in this app is `exported="false"`, and copying that here would mean the system
 * binder can never reach the component: no error, no log, no crash — a feature that never counts
 * anything, which reads as a bug in the counting rather than in the declaration. It must be
 * `exported="true"` **with** `android:permission="android.permission.BIND_NOTIFICATION_LISTENER_SERVICE"`,
 * which is what actually protects it, since only the system holds that permission.
 *
 * ## ⚠️ A snapshot, never an accumulator
 *
 * Every trigger recomputes the whole picture from [getActiveNotifications]. Posts and removals are
 * *triggers*, not data. That kills a family of bugs at once — removals missed while the process was
 * dead, posts replayed across a rebind, a drift that never self-corrects — because a snapshot cannot
 * drift. It is also why being killed costs nothing: the next connection rebuilds the truth.
 *
 * ## ⚠️ This runs for every notification on the phone
 *
 * Hundreds a day, on the main thread, from apps with nothing to do with mail. So the callbacks do as
 * close to nothing as possible: a package check against a field held in memory, then a debounced
 * hand-off to a background scope. The body is wrapped, because an exception thrown here would
 * crash-loop an app that uploads its own crash reports.
 */
class MailNotificationListener : NotificationListenerService() {

    private var scope: CoroutineScope? = null
    private var pending: Job? = null

    /**
     * The packages that count, held in memory.
     *
     * ⚠️ A field rather than a settings read, because the alternative is decrypting the whole
     * settings blob — through the Keystore — once per notification. Refreshed whenever the settings
     * change, which is every moment it can actually change.
     */
    @Volatile
    private var watch: MailGlance.Watch = MailGlance.Watch()

    override fun onCreate() {
        super.onCreate()
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope = s
        val container = (application as? PulseApplication)?.container ?: return
        s.launch {
            runCatching {
                container.settingsRepository.settings.collect { settings ->
                    watch = MailGlance.Watch(
                        chosen = settings.mailApps.toSet(),
                        barred = setOfNotNull(MailNotices.defaultSmsPackage(this@MailNotificationListener)),
                    )
                    // The ticked set is what decides the answer, so a change to it is as good a
                    // reason to recompute as a notification arriving.
                    recomputeSoon()
                }
            }
        }
    }

    override fun onDestroy() {
        scope?.cancel()
        scope = null
        super.onDestroy()
    }

    /**
     * The system has bound us, and [getActiveNotifications] is now legal.
     *
     * ⚠️ An unconditional recompute, and it is the load-bearing one. Everything that happened while
     * the process was dead — mail arriving, mail being cleared — is invisible to the callbacks and
     * visible here, so this is what makes the count self-correcting after an OS kill rather than
     * merely eventually-consistent.
     */
    override fun onListenerConnected() {
        super.onListenerConnected()
        recomputeSoon(immediate = true)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) = onShadeChanged(sbn)

    override fun onNotificationRemoved(sbn: StatusBarNotification?) = onShadeChanged(sbn)

    private fun onShadeChanged(sbn: StatusBarNotification?) {
        runCatching {
            val pkg = sbn?.packageName ?: return
            // Learn that this app notifies, so the picker has something to offer. Free for a
            // package already recorded — see MailNoticeStore.noticeSeen.
            store()?.noticeSeen(pkg, MailNotices.claimsEmail(sbn))
            // ⚠️ The package check comes FIRST. Most notifications on a phone are not mail, and
            // recomputing for each of them would mean reading the whole shade every time a chat
            // arrives. Nothing about the count can change unless a counted app is involved.
            if (pkg !in watch.chosen) return
            recomputeSoon()
        }
    }

    /**
     * Recompute, coalescing a burst.
     *
     * A sync delivering five messages posts six notifications in about a second, and reading the
     * whole shade six times for one answer is waste on the main thread's doorstep. [immediate] is
     * for the connection case, where there is nothing to coalesce and the count may be badly stale.
     */
    private fun recomputeSoon(immediate: Boolean = false) {
        val s = scope ?: return
        if (!immediate && pending?.isActive == true) return
        pending = s.launch {
            if (!immediate) delay(COALESCE_MS)
            runCatching { recompute() }
        }
    }

    private fun recompute() {
        val store = store() ?: return
        // ⚠️ Throws SecurityException when the listener is not connected — an ordinary state rather
        // than an error, so it is caught here rather than guarded by a flag of our own, which would
        // be a second and drifting copy of something the system already knows.
        val active = runCatching { activeNotifications }.getOrNull() ?: return
        // ⚠️ Learn the whole shade, not just what arrives from now on. Without this the picker is
        // empty for as long as it takes the next notification to appear — which, immediately after
        // somebody grants access and goes looking for their mail app, is the worst possible moment
        // to have nothing to offer them.
        store.noticeSeenAll(
            active.map { MailNoticeStore.SeenApp(it.packageName, MailNotices.claimsEmail(it)) },
        )
        store.publish(MailGlance.summarise(active.mapNotNull { MailNotices.toNotice(it) }, watch))
    }

    private fun store(): MailNoticeStore? =
        runCatching { (application as? PulseApplication)?.container?.mailNoticeStore }.getOrNull()

    private companion object {
        const val COALESCE_MS = 1_200L
    }
}
