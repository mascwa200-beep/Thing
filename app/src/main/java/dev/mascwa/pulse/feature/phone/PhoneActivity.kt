package dev.mascwa.pulse.feature.phone

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.Settings
import android.telecom.TelecomManager
import android.telephony.PhoneNumberUtils
import android.telephony.TelephonyManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameMillis
import androidx.lifecycle.lifecycleScope
import dev.mascwa.pulse.data.phone.CallLogRepository
import dev.mascwa.pulse.data.phone.ContactsRepository
import dev.mascwa.pulse.data.settings.AppSettings
import dev.mascwa.pulse.feature.lcarsboard.ConsoleSequence
import dev.mascwa.pulse.feature.lcarsboard.hideStatusBarForConsole
import dev.mascwa.pulse.ui.ProvideStardate
import dev.mascwa.pulse.ui.theme.NightwireTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import java.util.Locale

/**
 * The LCARS phone: a keypad, the calls, and the phone book, in the console.
 *
 * Its own activity rather than a route inside `MainActivity`, whose boot screen would stand in front of
 * a dial pad — and somebody who taps a phone number wants the keypad now. It answers `DIAL`, so Android
 * offers it as a phone app; a `tel:` link puts the number on the keypad and **never dials by itself**
 * (`DIAL` means "show me", not "call").
 *
 * ⚠️ **It reads two of Android's providers and keeps neither.** Everything here is rebuilt from the
 * providers each time the screen is shown, and nothing leaves the screen — `PhoneRecordsNothingTest`.
 *
 * ⚠️ **Calls go to Telecom, and emergency calls are Telecom's.** See [PhoneCalls].
 */
class PhoneActivity : ComponentActivity() {

    private val contactsRepo by lazy { ContactsRepository(applicationContext) }
    private val callLog by lazy { CallLogRepository(applicationContext) }

    private val tab = mutableStateOf(PhoneTab.KEYPAD)
    private val digits = mutableStateOf("")
    private val contacts = mutableStateOf<Loaded<List<PhoneBook.Contact>>>(Loaded.Loading)
    private val calls = mutableStateOf<Loaded<List<CallLogDigest.Row>>>(Loaded.Loading)
    private val notice = mutableStateOf<String?>(null)
    private val today = mutableStateOf(LocalDate.now())
    private val progress = mutableFloatStateOf(0f)

    /** A number waiting on the answer to the call permission; placed either way when it comes. */
    private var waitingToCall: String? = null

    /** Permissions asked for once already, so a second refusal opens the app's page instead. */
    private val asked = HashSet<String>()

    private val askCall = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        // Placed either way: without the permission the number goes to the phone's own dialler.
        waitingToCall?.let { place(it) }
        waitingToCall = null
    }

    private val askContacts = registerForActivityResult(ActivityResultContracts.RequestPermission()) { reload() }

    private val askCallLog = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { reload() }

    private val handler = Handler(Looper.getMainLooper())
    private var pending: Job? = null

    /** Calls and contacts change under the screen — a call ends, a contact syncs — so it follows them. */
    private val observer = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) = scheduleReload()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        hideStatusBarForConsole()
        read(intent)

        val defaults = AppSettings()
        val sweepMs = ConsoleSequence.durationFor(animatorScale())
        val actions = PhoneActions(
            tab = ::show,
            edit = { change -> digits.value = change(digits.value); notice.value = null },
            call = ::call,
            voicemail = { report(PhoneCalls.voicemail(this)) },
            text = { n -> if (!PhoneCalls.text(this, n)) notice.value = "No app on this phone sends texts." },
            addToContacts = { n ->
                if (!PhoneCalls.addToContacts(this, n)) notice.value = "Android's Contacts app would not open."
            },
            openContact = { p ->
                if (!PhoneCalls.openContact(this, p.id, p.lookupKey)) notice.value = "Android's Contacts app would not open."
            },
            grantContacts = { ask(Manifest.permission.READ_CONTACTS) { askContacts.launch(it.first()) } },
            grantCallLog = {
                ask(Manifest.permission.READ_CALL_LOG, Manifest.permission.WRITE_CALL_LOG) { askCallLog.launch(it) }
            },
        )

        setContent {
            NightwireTheme(accent = defaults.accentColor, amoledBlack = defaults.amoledBlack) {
                ProvideStardate {
                    // The console assembles itself once, when the phone first comes up.
                    LaunchedEffect(Unit) {
                        val start = withFrameMillis { it }
                        do {
                            val now = withFrameMillis { it }
                            progress.floatValue = ConsoleSequence.progressAt(now - start, true, 0f, sweepMs)
                        } while (progress.floatValue != 1f)
                    }
                    val readProgress = remember { { progress.floatValue } }
                    PhoneConsole(
                        tab = tab.value,
                        digits = digits.value,
                        contacts = contacts.value,
                        calls = calls.value,
                        notice = notice.value,
                        format = ::formatNumber,
                        clock = ::clock,
                        today = today.value,
                        progress = readProgress,
                        actions = actions,
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        read(intent)
    }

    override fun onStart() {
        super.onStart()
        runCatching {
            contentResolver.registerContentObserver(CallLog.Calls.CONTENT_URI, true, observer)
            contentResolver.registerContentObserver(ContactsContract.Contacts.CONTENT_URI, true, observer)
        }
        reload()
    }

    override fun onStop() {
        super.onStop()
        runCatching { contentResolver.unregisterContentObserver(observer) }
        pending?.cancel()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideStatusBarForConsole()
    }

    /**
     * A `tel:` link's number on the keypad, ready to call — never called. Anything else (the launcher,
     * a bare `DIAL`) leaves the screen as it was.
     */
    private fun read(intent: Intent?) {
        val data: Uri = intent?.data ?: return
        if (data.scheme.equals("tel", ignoreCase = true)) {
            digits.value = DialPad.fromTelUri(data.schemeSpecificPart.orEmpty())
            tab.value = PhoneTab.KEYPAD
            notice.value = null
        }
    }

    private fun show(t: PhoneTab) {
        tab.value = t
        notice.value = null
        if (t == PhoneTab.RECENTS) markSeenIfUnread()
    }

    /**
     * Opening RECENTS is looking at the missed calls, so they stop being "missed" everywhere.
     *
     * ⚠️ **Only when one is actually unread.** Marking writes the call log, the write fires the
     * observer, the observer reloads, and a reload on RECENTS comes back here — so an unconditional
     * mark is one write away from a loop, should the provider report a change that changed nothing.
     * With the guard, the reload after a mark finds nothing unread and stops.
     */
    private fun markSeenIfUnread() {
        val rows = (calls.value as? Loaded.Ready)?.value ?: return
        if (rows.any { it.unread }) markSeen()
    }

    private fun markSeen() {
        lifecycleScope.launch {
            callLog.markMissedRead()
            runCatching { getSystemService(TelecomManager::class.java)?.cancelMissedCallsNotification() }
        }
    }

    private fun call(number: String) {
        notice.value = null
        if (PhoneCalls.canCall(this)) {
            place(number)
            return
        }
        waitingToCall = number
        ask(Manifest.permission.CALL_PHONE) { askCall.launch(it.first()) }
    }

    private fun place(number: String) = report(PhoneCalls.call(this, number))

    private fun report(outcome: PhoneCalls.Outcome) {
        notice.value = when (outcome) {
            PhoneCalls.Outcome.Placed -> null
            PhoneCalls.Outcome.HandedToSystem -> "LCARS may not place calls, so the number is in the phone's own dialler."
            is PhoneCalls.Outcome.Refused -> outcome.why
        }
    }

    /**
     * Asks for [permissions], unless Android will no longer show the prompt — asked once already and
     * refused with "don't ask again" — in which case the app's own settings page is the only way left.
     */
    private fun ask(vararg permissions: String, launch: (Array<String>) -> Unit) {
        val missing = permissions.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) {
            reload()
            return
        }
        val blocked = missing.any { it in asked && !shouldShowRequestPermissionRationale(it) }
        asked += missing
        if (blocked) {
            runCatching {
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)),
                )
            }.onFailure { notice.value = "Android would not open LCARS's permission page." }
        } else {
            launch(missing.toTypedArray())
        }
    }

    /** Bursts of changes — a sync touching a hundred contacts — become one read. */
    private fun scheduleReload() {
        pending?.cancel()
        pending = lifecycleScope.launch {
            delay(RELOAD_SETTLE_MS)
            reload()
        }
    }

    private fun reload() {
        today.value = LocalDate.now()
        lifecycleScope.launch {
            contacts.value = when {
                !contactsRepo.canRead() -> Loaded.NoPermission
                else -> contactsRepo.rows().fold({ Loaded.Ready(PhoneBook.contacts(it)) }, { Loaded.Failed })
            }
        }
        lifecycleScope.launch {
            calls.value = when {
                !callLog.canRead() -> Loaded.NoPermission
                else -> callLog.recent().fold(
                    { Loaded.Ready(CallLogDigest.rows(it, ZoneId.systemDefault())) },
                    { Loaded.Failed },
                )
            }
            if (tab.value == PhoneTab.RECENTS) markSeenIfUnread()
        }
    }

    /**
     * A number written the way this country writes numbers. A code (`*`, `#`) or a pause (`,`, `;`)
     * is left exactly as typed: formatting it would move the characters somebody is reading off a
     * letter, and Android's formatter does not know what to do with them.
     */
    private fun formatNumber(number: String): String {
        if (number.any { it in "*#,;" }) return number
        return runCatching { PhoneNumberUtils.formatNumber(number, countryIso()) }.getOrNull() ?: number
    }

    /** The network's country, else the SIM's, else the language's: what a local number is local to. */
    private fun countryIso(): String {
        val tm = getSystemService(TelephonyManager::class.java)
        val network = runCatching { tm?.networkCountryIso }.getOrNull()
        val sim = runCatching { tm?.simCountryIso }.getOrNull()
        return (network?.takeIf { it.isNotBlank() } ?: sim?.takeIf { it.isNotBlank() } ?: Locale.getDefault().country)
            .uppercase(Locale.ROOT)
    }

    /** A time of day the way this phone writes one — 24-hour or not, as set in Android. */
    private fun clock(ms: Long): String = android.text.format.DateFormat.getTimeFormat(this).format(Date(ms))

    /** The system's animator scale; 0 means "remove animations", which the power-up honours. */
    private fun animatorScale(): Float =
        runCatching { Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) }
            .getOrDefault(1f)

    private companion object {
        const val RELOAD_SETTLE_MS = 400L
    }
}
