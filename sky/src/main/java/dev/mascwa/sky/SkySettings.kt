package dev.mascwa.sky

import android.content.Context
import android.content.SharedPreferences
import dev.mascwa.pulse.sky.SkyPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The four things this application has to remember, and it is genuinely only four.
 *
 * ⚠️ **Plain [SharedPreferences], and DataStore is deliberately not used.** Both of the other
 * applications keep their preferences in a DataStore, which is the right tool where there is a
 * settings object with dozens of fields, a flow somebody collects, and read-modify-write races to
 * guard against. Here there is a token, a build number and two switches, nothing observes them, and
 * DataStore would be a new dependency — `datastore-preferences` plus `datastore-core` plus its okio
 * chain — on the one module in this repository built to run on the cheapest phone that exists.
 *
 * ⚠️ **Every read is on [Dispatchers.IO], and the first one is why.** `getSharedPreferences` parses
 * the whole XML file the first time it is asked, on whatever thread asks. That is exactly the
 * main-thread decode this repository swept twenty-two stores to remove, and it would be silly to
 * reintroduce it here for a file with four keys in it. The dispatcher is chosen HERE rather than at
 * the call site, so a caller cannot forget.
 *
 * ⚠️ **The token is stored in plain text, and the ABOUT surface says so.** The LCARS application
 * keeps its copy behind a Keystore-backed cipher; this one has no such machinery and inventing it
 * for a single string would be security theatre over a token whose whole scope is reading one
 * private repository's releases. Storing it plainly and telling the reader is the same position the
 * desktop companion takes.
 *
 * ⚠️ **Every reader passes its own fallback rather than sharing one.** A failure to open the
 * preferences file is not the same answer for all four: no token, no pending install — but
 * `autoSendReports` and `followByDefault` both fall back to TRUE, because turning fault reporting
 * off, or hiding the map's whole pointing mode, on the one phone whose preferences will not open is
 * the opposite of what is wanted in each case.
 *
 * Implements [SkyPreferences] directly rather than through an adapter: the seam asks for exactly the
 * two methods below, and this is already the class that owns them.
 */
class SkySettings(context: Context) : SkyPreferences {

    private val appContext = context.applicationContext

    private fun prefs(): SharedPreferences =
        appContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private suspend fun <T> read(fallback: T, block: (SharedPreferences) -> T): T =
        withContext(Dispatchers.IO) { runCatching { block(prefs()) }.getOrDefault(fallback) }

    /**
     * The GitHub token this app uses to see its own releases, or null when unset.
     *
     * ⚠️ Trimmed and blank-to-null on the way out, matching `UpdateRepository.token()`. A pasted
     * token usually carries a trailing newline, and an untrimmed one corrupts the `Bearer` header
     * into a 401 that reads as "the token is wrong" rather than "the token has a space in it".
     */
    suspend fun token(): String? =
        read<String?>(null) { it.getString(KEY_TOKEN, null) }?.trim()?.ifBlank { null }

    /** Saves the token, or clears it when handed something blank. */
    suspend fun setToken(value: String) {
        val trimmed = value.trim()
        write { editor ->
            if (trimmed.isEmpty()) editor.remove(KEY_TOKEN) else editor.putString(KEY_TOKEN, trimmed)
        }
    }

    /**
     * The build already committed but not yet confirmed as running — the one-at-a-time guard.
     *
     * ⚠️ **Persisted rather than held in a field, because an install kills this process.** A flag in
     * memory would be gone by the time the question mattered, and the app would fetch and commit the
     * same failing build on every launch for ever.
     */
    suspend fun pendingInstall(): Int = read(0) { it.getInt(KEY_PENDING, 0) }

    suspend fun setPendingInstall(code: Int) = write { editor ->
        if (code <= 0) editor.remove(KEY_PENDING) else editor.putInt(KEY_PENDING, code)
    }

    /**
     * Whether recorded faults are sent on by themselves. Default ON, and stated on the ABOUT card.
     *
     * ⚠️ It is the only thing in this application that leaves the device, so it is switchable and it
     * is said out loud — a report nobody knew was going anywhere would be the wrong trade even
     * though it is the more useful one.
     */
    suspend fun autoSendReports(): Boolean = read(true) { it.getBoolean(KEY_AUTO_SEND, true) }

    suspend fun setAutoSendReports(value: Boolean) = write { it.putBoolean(KEY_AUTO_SEND, value) }

    /**
     * Whether the map opens following where the phone is aimed. Default ON — this is a star map, and
     * holding it up at the sky is what it is for.
     *
     * ⚠️ **The fallback is TRUE, which inverts this class's own rule for the other three keys.** A
     * failure to read means the preferences file would not open, and answering "not following" there
     * hides the whole mode behind a chip nobody would think to press. The cost of guessing wrong is
     * one press; the cost of the other guess is a feature that appears not to exist.
     */
    override suspend fun followByDefault(): Boolean = read(true) { it.getBoolean(KEY_FOLLOW, true) }

    override suspend fun setFollowByDefault(value: Boolean) = write { it.putBoolean(KEY_FOLLOW, value) }

    /**
     * ⚠️ **`commit()`, not `apply()`, and the pending marker is the reason.** That value is written
     * immediately before `PackageInstaller.commit()`, which usually tears this process down — and
     * `apply()` only promises the write eventually, through a queue this path does not go through. A
     * lost marker is the loop it exists to prevent. The other three keys are written rarely enough
     * that a synchronous write on an IO thread costs nothing worth measuring.
     */
    private suspend fun write(block: (SharedPreferences.Editor) -> Unit) {
        withContext(Dispatchers.IO) {
            runCatching {
                val editor = prefs().edit()
                block(editor)
                editor.commit()
            }
            Unit
        }
    }

    private companion object {
        const val FILE = "sky_settings"
        const val KEY_TOKEN = "update_token"
        const val KEY_PENDING = "pending_install"
        const val KEY_AUTO_SEND = "auto_send_reports"
        const val KEY_FOLLOW = "follow_by_default"
    }
}
