package dev.mascwa.pulse.core.telemetry

/**
 * How much mail is waiting, worked out from what the phone's own mail apps have already told you.
 *
 * ## Why this shape
 *
 * The alternative was IMAP: a host, a port, a username and an app password per mailbox. It works and
 * it reports true unread counts, and it asks somebody to go and mint a credential before the feature
 * does anything at all. Reading the notifications the mail app has *already posted* needs no
 * credential, no server, no network and no account — one grant, once.
 *
 * ⚠️ **The number means "notified and not cleared", and the wording says "new" rather than "unread"
 * for that reason.** They are genuinely different quantities: a mail read on a laptop is still
 * unread nowhere and its notification may still be sitting there; a notification swiped away is
 * gone while the mail is still unread. Printing "unread" would be a claim about the mailbox that
 * this method cannot support, and the honest limit is easier to live with than a number that is
 * quietly wrong. [Glance.line] is the only phrasing, so no surface can drift from it.
 *
 * ⚠️ **No total is ever computed across this and SMS.** Texts are counted exactly, from the content
 * provider, and that count is the only one drawn on the texts row. A combined figure would be a
 * number that can be wrong in a way neither of its halves is — and the moment it exists, somebody
 * compares it against a messages app and finds it off by the number of emails.
 *
 * ## Counts only — no names, no subjects
 *
 * `EXTRA_TITLE`, `EXTRA_TEXT` and `EXTRA_SUB_TEXT` are never read, and [Notice] has nowhere to put
 * them. The reason is concrete rather than cautious: `DebugUploader` uploads logcat, the breadcrumb
 * ring and the recent activity log, and `SecretScrub` structurally cannot protect a person's name or
 * a subject line — its strong pass matches exact values out of the settings blob, and a name is not
 * in the settings blob; its pattern pass looks for credential *shapes*, and "Re: your test results"
 * has none. Refusing to hold the data is the only real protection, which is the conclusion
 * `TranscriptPolicy` already reached in writing for the same reason.
 *
 * ## Which apps count
 *
 * ⚠️ **Neither available signal gates anything — the user picks.** `CATEGORY_EMAIL` is optional
 * metadata that many mail apps never set, so requiring it would under-count badly; and a hardcoded
 * allowlist goes stale *invisibly*, which is worse than being wrong out loud. So [Watch.chosen] is
 * ticked by hand and starts empty, [LIKELY_MAIL] only decides what the picker offers first, and a
 * stale guess costs one scroll rather than a silent zero.
 *
 * Pure: no Android types, so every rule here is exercised by an ordinary JVM test rather than
 * inferred from a device that happened to behave.
 */
object MailGlance {

    /**
     * What a posted notification is, as far as counting mail is concerned.
     *
     * ⚠️ An enum rather than the platform's raw category strings, and that is not tidiness: a
     * one-character typo in `"msg"` would silently re-admit every text message, with nothing on
     * screen wrong until somebody compared the widget against their messages app. Mapping the
     * strings once, in the Android layer, makes the mistake a compile error instead.
     */
    enum class Kind {
        /** The app said `CATEGORY_EMAIL`. */
        MAIL,

        /** The app said `CATEGORY_MESSAGE` — a text, a chat, an RCS thread. Never counted here. */
        MESSAGE,

        /** No category, or one that says nothing either way. Counted, because most mail apps are here. */
        OTHER,
    }

    /**
     * One notification currently on the shade, distilled to the six facts that decide a count.
     *
     * [groupKey] is the platform's own grouping identity, so an app running two accounts is two
     * buckets rather than one — see [waitingIn], where mixing them would lose a whole account.
     */
    data class Notice(
        /** `StatusBarNotification.getKey()` — stable, and the only thing that can dedupe a replay. */
        val key: String,
        val pkg: String,
        val groupKey: String,
        val kind: Kind,
        val isGroupSummary: Boolean,
        val isOngoing: Boolean,
        /** `Notification.number`, or 0 when the app did not set one. NOT trusted unconditionally. */
        val number: Int,
    )

    /** Which packages the user ticked, and which may never count whatever they ticked. */
    data class Watch(
        /** Ticked by hand. Empty until somebody chooses, so a fresh install counts nothing. */
        val chosen: Set<String> = emptySet(),
        /**
         * Barred outright — in practice the default SMS package.
         *
         * ⚠️ Applied BEFORE [chosen], so ticking it cannot admit it. The default SMS app is already
         * counted exactly from the content provider, and it may post with no category at all (RCS
         * through Google Messages does), so the [Kind] guard alone has a hole this closes.
         */
        val barred: Set<String> = emptySet(),
    )

    /** One app, and how much of its mail is waiting. */
    data class App(val pkg: String, val waiting: Int)

    /** The whole reading. */
    data class Glance(val apps: List<App>, val total: Int) {
        /** The one phrasing — see [MailGlance.line]. */
        val line: String? get() = line(total)
    }

    /**
     * The one phrasing, or null when there is nothing to say.
     *
     * ⚠️ "new", never "unread" — see this file's KDoc. Null rather than "0 new", because zero here
     * means "nothing has been notified", which on a phone with notifications switched off is not a
     * statement about the mailbox at all; and null for null, which is "this app may not look".
     *
     * A function beside [Glance] rather than only a property on it, because the count reaches the
     * widget as a bare number through the comms cache — the whole `Glance` is not carried across —
     * and a second `"$n new"` written at that call site is how one phrasing becomes two.
     */
    fun line(total: Int?): String? = if (total == null || total <= 0) null else "$total new"

    /**
     * What is waiting, per app and in total.
     *
     * The filters run in a deliberate order and each closes a hole the others leave:
     *
     *  1. **Dedupe by [Notice.key]** — a rebind replays notifications that are already on the shade,
     *     and a listener that has just reconnected would otherwise count them twice.
     *  2. **Drop ongoing** — K-9 and FairEmail run a foreground service with a permanent
     *     notification, so without this those users would see a stuck "1 new" for ever.
     *  3. **Drop [Kind.MESSAGE]** — texts are counted exactly elsewhere, and a message counted here
     *     as well would appear twice on one widget.
     *  4. **Barred before chosen** — see [Watch.barred].
     */
    fun summarise(notices: List<Notice>, watch: Watch): Glance {
        val eligible = notices
            .distinctBy { it.key }
            .filterNot { it.isOngoing }
            .filter { it.kind != Kind.MESSAGE }
            .filterNot { it.pkg in watch.barred }
            .filter { it.pkg in watch.chosen }

        val apps = eligible
            .groupBy { it.pkg }
            .map { (pkg, forApp) ->
                App(pkg, forApp.groupBy { it.groupKey }.values.sumOf { waitingIn(it) })
            }
            .filter { it.waiting > 0 }
            .sortedWith(compareByDescending<App> { it.waiting }.thenBy { it.pkg })

        return Glance(apps, apps.sumOf { it.waiting })
    }

    /**
     * How much one group of one app is waiting on.
     *
     * ⚠️ **[Notice.number] is trusted ONLY when the group has no children, and that is the whole
     * point of splitting this out.** Some apps set `number` to the entire inbox unread on *every*
     * child, so three children each carrying 57 would give 171 — a number that is not wrong by a
     * little, and that grows with how much mail you have never dealt with.
     *
     * ⚠️ **A summary reporting zero counts as one, not as none.** Zero means the app declined to
     * say how much, not that nothing is there; it posted a notification, so something is. Rendering
     * that as "0 new" would be a claim about the mailbox — the same `null`-versus-`0` distinction
     * the SMS count already documents.
     *
     * Summaries are summed rather than taken singly because one app can post several: a mailbox each
     * for two accounts is two summaries in two groups, and taking the first would lose an account.
     */
    private fun waitingIn(group: List<Notice>): Int {
        val children = group.count { !it.isGroupSummary }
        if (children > 0) return children
        return group.sumOf { it.number.coerceAtLeast(1) }
    }

    /**
     * Packages that look like mail, offered at the top of the picker.
     *
     * ⚠️ **This gates nothing and ticks nothing.** It decides an ordering, so an entry that is wrong
     * or has gone stale costs one scroll past "show every app that notifies" — which is exactly why
     * a list I cannot verify from here is acceptable at all. It must never contain a messaging
     * package: seeding one would put a text-message app at the top of a list of mail apps, and a
     * hurried tick would then double-count every text. A test holds that.
     */
    val LIKELY_MAIL: Set<String> = setOf(
        "com.google.android.gm",                    // Gmail
        "com.microsoft.office.outlook",             // Outlook
        "com.android.email",                        // AOSP Email
        "com.samsung.android.email.provider",       // Samsung Email
        "com.fsck.k9",                              // K-9 Mail
        "app.k9mail",                               // K-9 Mail, newer id
        "eu.faircode.email",                        // FairEmail
        "ch.protonmail.android",                    // Proton Mail
        "de.tutao.tutanota",                        // Tuta
        "me.bluemail.mail",                         // BlueMail
        "org.kman.AquaMail",                        // Aqua Mail
        "com.yahoo.mobile.client.android.mail",     // Yahoo Mail
        "ru.yandex.mail",                           // Yandex Mail
        "com.zoho.mail",                            // Zoho Mail
    )
}
