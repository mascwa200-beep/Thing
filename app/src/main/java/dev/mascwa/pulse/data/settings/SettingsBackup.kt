package dev.mascwa.pulse.data.settings

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Local, offline backup/restore of [AppSettings] — written to / read from a user-chosen file via
 * the Storage Access Framework. No network, nothing leaves the device unless the user shares the file.
 *
 * Sideloaded Pulse occasionally needs a one-time uninstall (after a signing change), which wipes all
 * config; a backup lets the user keep their watchlist, saved locations, emergency card, waypoints and
 * preferences across that reinstall.
 *
 * Credentials are deliberately excluded: export blanks the API keys + model/GitHub/cloud tokens so a
 * backup file never carries secrets, and restore keeps whatever credentials the device already has.
 */
object SettingsBackup {
    const val APP = "pulse"
    const val VERSION = 1

    /** Pretty, self-describing JSON; [encodeDefaults] so the header is always present and the file is
     *  readable. Lenient on the way back in so older/newer backups still load (unknown keys ignored,
     *  out-of-range enums coerced to defaults). */
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
        encodeDefaults = true
        prettyPrint = true
    }

    @Serializable
    data class Envelope(
        val app: String = APP,
        val version: Int = VERSION,
        val exportedAtMs: Long = 0L,
        val settings: AppSettings = AppSettings(),
    )

    /** Returns a copy with every credential blanked — safe to write to a shared/backed-up file. */
    fun redactSecrets(s: AppSettings): AppSettings = s.copy(
        apiKeys = ApiKeys(),
        jarvis = s.jarvis.copy(modelToken = "", githubToken = "", cloudApiKey = ""),
        spotify = SpotifyAuthState(),
        // ⚠️ The account DEFINITION travels — host, port, username, what you called it — and only
        // the password is stripped. Carrying the definition means a restore leaves rows that say
        // what they still need rather than nothing at all; see the matching half in [merge].
        emailAccounts = s.emailAccounts.map { it.copy(password = "") },
    )

    /** Lay a restored backup over the device's CURRENT settings, preserving the device's existing
     *  credentials (the backup never carries them) and its remote-link pairings. */
    fun merge(restored: AppSettings, current: AppSettings): AppSettings = restored.copy(
        apiKeys = current.apiKeys,
        jarvis = restored.jarvis.copy(
            modelToken = current.jarvis.modelToken,
            githubToken = current.jarvis.githubToken,
            cloudApiKey = current.jarvis.cloudApiKey,
        ),
        spotify = current.spotify,
        // ⚠️ The restored definitions are kept and each password is taken from the account on THIS
        // device that matches it, by host and username. Two halves matter:
        //
        //  * without this the restore would write back the blanked passwords from the backup and
        //    silently sign out every mailbox that was working a moment ago — the export half alone
        //    is a data-loss bug, not merely an incomplete one;
        //  * on a phone that has never held the account there is nothing to match, so the password
        //    stays blank. That is honest and the account is `usable == false`, so nothing asks it
        //    anything and the settings row says what it is missing. A row that looked configured
        //    and quietly failed would be worse than an empty one.
        emailAccounts = restored.emailAccounts.map { r ->
            r.copy(password = current.emailAccounts.firstOrNull { it.sameAccountAs(r) }?.password.orEmpty())
        },
        // Paired computers are public keys, not secrets — but the list is an AUTHORIZATION list, and
        // restoring an old backup must never silently re-admit a machine the user deliberately unpaired.
        // The device's own current pairings are the only truth about what may reach it.
        remote = current.remote,
    )

    /** Serialize a redacted backup of [current] settings, stamped [nowMs]. */
    fun encode(current: AppSettings, nowMs: Long): String =
        json.encodeToString(
            Envelope.serializer(),
            Envelope(version = VERSION, exportedAtMs = nowMs, settings = redactSecrets(current)),
        )

    /** Parse a backup file's [text] into settings to apply, merged over [current] (keeps device
     *  credentials). Throws if the text isn't an LCARS backup. */
    fun decode(text: String, current: AppSettings): AppSettings {
        val env = json.decodeFromString(Envelope.serializer(), text)
        require(env.app == APP) { "That file isn't an LCARS backup." }
        return merge(env.settings, current)
    }
}
