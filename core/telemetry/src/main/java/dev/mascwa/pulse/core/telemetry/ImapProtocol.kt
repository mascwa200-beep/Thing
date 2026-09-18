package dev.mascwa.pulse.core.telemetry

/**
 * As much of IMAP as it takes to ask a mailbox how many messages are unread.
 *
 * Three commands — `LOGIN`, `STATUS "INBOX" (UNSEEN)`, `LOGOUT` — over a TLS socket. That is the
 * whole conversation, which is why there is no library here: this repository has hand-rolled
 * RFC 3161 timestamps, ICY metadata and its own remote protocol for the same reason, and a mail
 * library would be several megabytes to send two lines.
 *
 * ## What is in here and what is not
 *
 * The socket is not. Everything below is a pure function of the bytes that came back, so the part
 * that is easy to get wrong is testable without a server, and the part that needs a server is a
 * dozen lines of read-and-write. That split is what lets the whole client be exercised against a
 * fake in-process server.
 *
 * ## ⚠️ Quoting is the security-relevant part
 *
 * A password is user-supplied text going into a command whose arguments are delimited by quotes. An
 * unescaped `"` would end the string early and leave the rest of the password being read as further
 * IMAP arguments — at best an inscrutable `BAD`, at worst a command the user did not type. [quote]
 * escapes both characters RFC 3501 requires, and [quotable] refuses outright the one case quoting
 * cannot cover: a value containing CR or LF, which must be sent as a literal or not at all. A
 * password with a newline in it is not a password anybody has, so refusing beats sending something
 * malformed.
 */
object ImapProtocol {

    /** What a tagged response said about the command that carried the tag. */
    enum class Status { OK, NO, BAD }

    /** The default port. IMAP over implicit TLS; 143 is the cleartext one and is not offered. */
    const val TLS_PORT = 993

    /**
     * Whether [value] can be sent as a quoted string at all.
     *
     * ⚠️ CR and LF cannot be escaped — they end the command — so a value carrying one has to go as
     * a literal (`{n}\r\n…`) or not at all. Nothing here needs literals, so this is a refusal.
     */
    fun quotable(value: String): Boolean = '\r' !in value && '\n' !in value

    /**
     * [value] as an IMAP quoted string, backslash and double-quote escaped.
     *
     * The order matters: backslashes first, or the backslash added in front of a quote would itself
     * be escaped by the second pass and the quote would go out unprotected.
     */
    fun quote(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    /** The LOGIN command for [user] and [password], with [tag]. Null when either cannot be quoted. */
    fun login(tag: String, user: String, password: String): String? {
        if (!quotable(user) || !quotable(password) || !quotable(tag)) return null
        return "$tag LOGIN ${quote(user)} ${quote(password)}"
    }

    /** The STATUS command asking [mailbox] for its unread count. */
    fun statusUnseen(tag: String, mailbox: String = "INBOX"): String? {
        if (!quotable(mailbox) || !quotable(tag)) return null
        return "$tag STATUS ${quote(mailbox)} (UNSEEN)"
    }

    /**
     * The status a tagged response line reports, or null when the line is not this command's answer.
     *
     * ⚠️ Untagged lines (`* …`) and continuations (`+ …`) return null rather than a status, because
     * a client must keep reading until its own tag comes back — a server is free to send any number
     * of untagged lines first, and treating the greeting's `* OK` as the answer would report every
     * command successful before it had run.
     */
    fun taggedStatus(line: String, tag: String): Status? {
        val t = line.trim()
        if (!t.startsWith("$tag ")) return null
        val rest = t.removePrefix("$tag ").trimStart()
        return when {
            rest.startsWith("OK", ignoreCase = true) -> Status.OK
            rest.startsWith("NO", ignoreCase = true) -> Status.NO
            rest.startsWith("BAD", ignoreCase = true) -> Status.BAD
            else -> null
        }
    }

    /**
     * The unread count in an untagged `STATUS` response, or null when the line is not one.
     *
     * ⚠️ Read out of the LAST parenthesised group on the line, not by searching the whole line for
     * the word. A mailbox may be named anything — `* STATUS "Old UNSEEN mail" (MESSAGES 4)` is a
     * legal response — and a whole-line search would take "mail" as the count and answer null, or
     * worse take a number from the name. The attribute list is always the trailing group.
     *
     * The server may return the attributes in any order and may include ones we did not ask for;
     * `MESSAGES 231 UNSEEN 3` and `UNSEEN 3` are both ordinary.
     */
    fun unseen(line: String): Int? {
        val t = line.trim()
        if (!t.startsWith("*")) return null
        if (!t.contains("STATUS", ignoreCase = true)) return null
        val close = t.lastIndexOf(')')
        if (close < 0) return null
        val open = t.lastIndexOf('(', close)
        if (open < 0) return null
        val parts = t.substring(open + 1, close).trim().split(' ').filter { it.isNotBlank() }
        val at = parts.indexOfFirst { it.equals("UNSEEN", ignoreCase = true) }
        if (at < 0 || at + 1 >= parts.size) return null
        return parts[at + 1].toIntOrNull()?.takeIf { it >= 0 }
    }

    /**
     * A human sentence for a login that was refused.
     *
     * ⚠️ Gmail, Outlook and Yahoo all stopped accepting account passwords over IMAP; they want an
     * app-specific password, and the failure they return says only "authentication failed". Somebody
     * who has just pasted the password they type into the website has no way to tell that from
     * having typed it wrong, and will try again. Naming it is the difference between a setting that
     * works and one that appears broken.
     */
    fun explain(host: String, status: Status?, detail: String): String {
        val h = host.lowercase()
        val appPassword = "gmail" in h || "google" in h || "outlook" in h ||
            "office365" in h || "hotmail" in h || "yahoo" in h
        return when {
            status == Status.NO && appPassword ->
                "Sign-in refused. $host needs an app password, not the one you use on the website."
            status == Status.NO -> "Sign-in refused: ${detail.take(80)}"
            status == Status.BAD -> "The server did not understand the request: ${detail.take(80)}"
            else -> detail.take(120).ifBlank { "No answer from $host." }
        }
    }

    /**
     * Tags, in the order a session uses them.
     *
     * A tag only has to be unique within a connection, so a counter is enough; the letter is there
     * because a bare number is legal but unconventional and some servers are fussier than the
     * specification.
     */
    fun tag(n: Int): String = "a${n.coerceAtLeast(1)}"
}
