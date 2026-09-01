package dev.mascwa.pulse.data.settings

import kotlinx.serialization.Serializable

/**
 * One mailbox to count unread messages in.
 *
 * ⚠️ **[password] is a live credential and every place that handles credentials must know about it**,
 * which is why this type and the three registrations below arrived in one commit:
 *
 *  1. **At rest** it is covered already — `SettingsRepository` puts the WHOLE settings blob through
 *     `SecretCrypto` when `security.encryptSecretsAtRest` is on, so a field here is encrypted by
 *     construction rather than by a separate step somebody has to remember.
 *  2. **In a debug report** — [allSecretValues] carries it, so `SecretScrub`'s exact-match pass
 *     removes it from anything `DebugUploader` sends. That pass is the load-bearing one: a password
 *     has no shape a pattern could recognise.
 *  3. **In a backup** — `SettingsBackup.redactSecrets` blanks it on export and `merge` restores the
 *     device's own on import.
 *
 * ⚠️ Gmail, Outlook and Yahoo no longer accept the password you sign into the website with; they
 * want an app-specific password, and what they return when you use the wrong one says only
 * "authentication failed". `ImapProtocol.explain` names it, because otherwise a correct setup and a
 * wrong KIND of password look identical.
 */
@Serializable
data class EmailAccount(
    /** What to call it on screen. Blank falls back to the username. */
    val label: String = "",
    /** IMAP host, e.g. `imap.gmail.com`. */
    val host: String = "",
    /** Implicit TLS. 143 (cleartext, STARTTLS) is deliberately not offered. */
    val port: Int = 993,
    val username: String = "",
    /** ⚠️ A live credential. See the three registrations above. */
    val password: String = "",
    /** Off keeps the account without asking it anything. */
    val enabled: Boolean = true,
) {
    /** Host and username together are what identifies an account across a backup and a restore. */
    fun sameAccountAs(other: EmailAccount): Boolean =
        host.equals(other.host, ignoreCase = true) && username.equals(other.username, ignoreCase = true)

    /** Ready to be asked: switched on, addressable, and holding something to sign in with. */
    val usable: Boolean
        get() = enabled && host.isNotBlank() && username.isNotBlank() && password.isNotBlank()

    /** What to call it. */
    val display: String get() = label.ifBlank { username }.ifBlank { host }
}
