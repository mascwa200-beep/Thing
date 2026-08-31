package dev.mascwa.pulse.data.comms

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Telephony
import androidx.core.content.ContextCompat
import dev.mascwa.pulse.core.cache.DiskCache
import dev.mascwa.pulse.data.settings.EmailAccount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

/**
 * What is waiting for you: unread texts, and unread mail in each mailbox you have added.
 *
 * ## The two halves are not alike
 *
 * SMS is a local content-provider query behind a runtime permission — instant, offline, and either
 * answerable or not. Mail is one TLS round trip per account, which is far too slow to sit in the
 * widget's four-second budget, so [refresh] is called by the background worker and [cached] is what
 * the widget reads. Same split the water block uses, for the same reason.
 *
 * ## ⚠️ READ_SMS is a one-way door and it is worth naming
 *
 * An app holding `READ_SMS` cannot be distributed through Google Play except in a handful of
 * declared categories, none of which this is. That costs nothing here — the app is sideloaded and
 * private, and has been since it was written — but it is a decision, not an oversight, and a future
 * session should know it was made deliberately rather than discover it.
 *
 * The permission is asked for at the point the feature is switched on, and the count is simply
 * absent without it. Nothing here nags.
 */
class CommsRepository(
    private val appContext: Context,
    private val cache: DiskCache,
    private val imap: ImapClient = ImapClient(),
) {

    /** One mailbox's answer, kept so the widget can read it without a network. */
    @Serializable
    data class MailboxState(
        val label: String,
        val unread: Int? = null,
        /** Why there is no count. Null when there is one. */
        val problem: String? = null,
    )

    /** Everything the comms block draws from. */
    @Serializable
    data class Comms(
        /** Unread texts, or null when the permission is not held. Zero is a real answer. */
        val sms: Int? = null,
        val mailboxes: List<MailboxState> = emptyList(),
        val checkedAtMs: Long = 0,
    )

    /** Whether the app may count texts. Absent without it — never a zero. */
    fun canReadSms(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.READ_SMS) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Unread texts, or null when the permission is not held.
     *
     * ⚠️ Null and 0 are different answers and must stay so. "You have no unread texts" is a fact
     * about the inbox; "this app may not look" is a fact about the app, and rendering them the same
     * way is how a feature comes to look broken.
     */
    suspend fun unreadSms(): Int? = withContext(Dispatchers.IO) {
        if (!canReadSms()) return@withContext null
        runCatching {
            appContext.contentResolver.query(
                Telephony.Sms.Inbox.CONTENT_URI,
                arrayOf(Telephony.Sms.Inbox._ID),
                "${Telephony.Sms.Inbox.READ} = 0",
                null,
                null,
            )?.use { it.count }
        }.getOrNull()
    }

    /**
     * Ask every usable mailbox, and the phone, and keep the answer.
     *
     * ⚠️ Takes the accounts rather than the whole `AppSettings`, which is the only thing it wanted
     * from it. That is the smaller dependency AND it keeps this file compilable against nothing but
     * the platform — `AppSettings` reaches into two other modules, and a file that cannot be
     * type-checked locally is one only CI can gate.
     *
     * Accounts are asked concurrently: three mailboxes one after another is three round trips of
     * latency, and they have nothing to do with each other.
     */
    suspend fun refresh(accounts: List<EmailAccount>): Comms = coroutineScope {
        val sms = async { unreadSms() }
        val boxes = accounts.map { account ->
            async { ask(account) }
        }
        val comms = Comms(
            sms = sms.await(),
            mailboxes = boxes.map { it.await() },
            checkedAtMs = System.currentTimeMillis(),
        )
        runCatching { cache.write(KEY, comms, Comms.serializer()) }
        comms
    }

    /**
     * What was last found. Never touches the network.
     *
     * ⚠️ SMS is re-read here rather than taken from the cache: it is a local query costing nothing,
     * and the cached value could be half an hour old when the answer is on the phone right now. The
     * mailboxes are the part that has to be cached.
     */
    suspend fun cached(): Comms? {
        val held = runCatching { cache.readAny(KEY, Comms.serializer())?.value }.getOrNull()
        val sms = unreadSms()
        return when {
            held != null -> held.copy(sms = sms)
            sms != null -> Comms(sms = sms)
            else -> null
        }
    }

    private suspend fun ask(account: EmailAccount): MailboxState {
        if (!account.usable) {
            // ⚠️ Says which part is missing rather than "not configured". A restored backup carries
            // the account definition and never the password, so "needs its password" is the state
            // somebody will actually be in, and telling them is the whole point of keeping the row.
            val why = when {
                !account.enabled -> "switched off"
                account.host.isBlank() -> "no server"
                account.username.isBlank() -> "no username"
                else -> "needs its password"
            }
            return MailboxState(account.display, problem = why)
        }
        return when (val r = imap.unread(account.host, account.port, account.username, account.password)) {
            is ImapClient.Result.Unread -> MailboxState(account.display, unread = r.count)
            is ImapClient.Result.Refused -> MailboxState(account.display, problem = r.message)
            is ImapClient.Result.Unreachable -> MailboxState(account.display, problem = r.message)
        }
    }

    private companion object {
        const val KEY = "comms_unread"
    }
}
