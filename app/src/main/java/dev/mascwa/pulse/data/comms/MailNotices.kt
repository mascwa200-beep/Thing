package dev.mascwa.pulse.data.comms

import android.app.Notification
import android.content.Context
import android.provider.Telephony
import android.service.notification.StatusBarNotification
import dev.mascwa.pulse.core.telemetry.MailGlance

/**
 * The translation from what the platform hands a notification listener to what [MailGlance] counts.
 *
 * ⚠️ **Its own file, deliberately, and the reason is verification rather than tidiness.**
 * [MailNotificationListener] reaches the app's container, which reaches everything, so it is a file
 * only CI can type-check — and this is exactly the half where getting a platform API wrong is
 * plausible and invisible. Kept apart, it compiles against nothing but the platform classes and the
 * pure core, so `tools/android_compile_check.sh` gates every signature here in about a minute. Same
 * split, for the same stated reason, as `TranscriptSeal`.
 */
internal object MailNotices {

    /**
     * One notification, as far as counting mail is concerned, or null if it cannot be read.
     *
     * Fully defensive: this runs over every notification on the phone, from arbitrary apps, and a
     * throw here would take down a listener the system would then rebind in a loop.
     */
    fun toNotice(sbn: StatusBarNotification): MailGlance.Notice? = runCatching {
        val n = sbn.notification ?: return null
        MailGlance.Notice(
            key = sbn.key,
            pkg = sbn.packageName,
            // `getGroupKey` is never null, and for an ungrouped notification it is the package's own
            // default — either way it is exactly the identity MailGlance wants to bucket by.
            groupKey = sbn.groupKey.orEmpty(),
            kind = kindOf(n.category),
            // ⚠️ The FLAG, not `sbn.isGroup`. `isGroup` is true for a summary AND for every child
            // under it, so it cannot tell them apart — and counting a summary as a child is how
            // every grouped mail app comes out exactly one too many.
            isGroupSummary = (n.flags and Notification.FLAG_GROUP_SUMMARY) != 0,
            isOngoing = sbn.isOngoing,
            number = n.number,
        )
    }.getOrNull()

    /**
     * The platform's category string as the closed set the core reasons about.
     *
     * ⚠️ Mapped here, once, so a typo cannot silently re-admit every text message — which is what a
     * raw `"msg"` compared somewhere downstream would do, with nothing on screen wrong until
     * somebody compared the widget against their messages app.
     */
    fun kindOf(category: String?): MailGlance.Kind = when (category) {
        Notification.CATEGORY_EMAIL -> MailGlance.Kind.MAIL
        Notification.CATEGORY_MESSAGE -> MailGlance.Kind.MESSAGE
        else -> MailGlance.Kind.OTHER
    }

    /** Whether the app said outright that this is mail — the picker's only real hint. */
    fun claimsEmail(sbn: StatusBarNotification): Boolean =
        runCatching { sbn.notification?.category == Notification.CATEGORY_EMAIL }.getOrDefault(false)

    /**
     * The app the platform delivers texts to, so it can never be counted as mail as well.
     *
     * ⚠️ Read fresh rather than cached: the default can be changed at any time, and this is the
     * guard that stops a text arriving through Google Messages — which may carry no category at all
     * over RCS — being counted here on top of the exact content-provider count.
     */
    fun defaultSmsPackage(context: Context): String? =
        runCatching { Telephony.Sms.getDefaultSmsPackage(context) }.getOrNull()
}
