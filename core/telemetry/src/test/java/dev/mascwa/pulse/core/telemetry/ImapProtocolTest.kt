package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImapProtocolTest {

    // ---- quoting, which is the part that has to be right -----------------------------------

    @Test
    fun `an ordinary password goes out as an ordinary quoted string`() {
        assertEquals("\"abcd efgh ijkl mnop\"", ImapProtocol.quote("abcd efgh ijkl mnop"))
        assertEquals("\"\"", ImapProtocol.quote(""))
    }

    @Test
    fun `a quote in a password does not end the string`() {
        // ⚠️ Unescaped, `pa"ss` would close the argument after `pa` and leave `ss"` being read as
        // further IMAP arguments — the injection this function exists to prevent.
        assertEquals("\"pa\\\"ss\"", ImapProtocol.quote("pa\"ss"))
    }

    @Test
    fun `backslashes are escaped before quotes, not after`() {
        // ⚠️ The order is load-bearing. Escaping quotes first turns `a"b` into `a\"b`, and the
        // backslash pass would then double THAT backslash and leave the quote bare again.
        assertEquals("\"a\\\\b\"", ImapProtocol.quote("a\\b"))
        assertEquals("\"a\\\\\\\"b\"", ImapProtocol.quote("a\\\"b"))
    }

    @Test
    fun `a value quoting cannot cover is refused rather than mangled`() {
        assertTrue(ImapProtocol.quotable("ordinary"))
        assertTrue(ImapProtocol.quotable("with \"quotes\" and \\slashes\\"))
        assertFalse(ImapProtocol.quotable("two\nlines"))
        assertFalse(ImapProtocol.quotable("carriage\rreturn"))
        assertNull(ImapProtocol.login("a1", "user", "pass\nword"))
        assertNull(ImapProtocol.login("a1", "us\rer", "password"))
        assertNull(ImapProtocol.statusUnseen("a2", "IN\nBOX"))
    }

    @Test
    fun `the two commands read as the specification writes them`() {
        assertEquals("""a1 LOGIN "me@example.com" "hunter2"""", ImapProtocol.login("a1", "me@example.com", "hunter2"))
        assertEquals("""a2 STATUS "INBOX" (UNSEEN)""", ImapProtocol.statusUnseen("a2"))
        assertEquals("""a2 STATUS "Archive" (UNSEEN)""", ImapProtocol.statusUnseen("a2", "Archive"))
    }

    // ---- reading what came back ------------------------------------------------------------

    @Test
    fun `a tagged response is only this command's answer when it carries this tag`() {
        assertEquals(ImapProtocol.Status.OK, ImapProtocol.taggedStatus("a2 OK STATUS completed", "a2"))
        assertEquals(ImapProtocol.Status.NO, ImapProtocol.taggedStatus("a1 NO [AUTHENTICATIONFAILED] Invalid credentials", "a1"))
        assertEquals(ImapProtocol.Status.BAD, ImapProtocol.taggedStatus("a3 BAD Missing argument", "a3"))
        // Another command's tag is not an answer to this one.
        assertNull(ImapProtocol.taggedStatus("a1 OK LOGIN completed", "a2"))
    }

    @Test
    fun `the greeting is not mistaken for an answer`() {
        // ⚠️ Every session opens with `* OK …` before a single command has been sent. Reading that
        // as success would report the login complete before it had been attempted.
        assertNull(ImapProtocol.taggedStatus("* OK [CAPABILITY IMAP4rev1] Ready", "a1"))
        assertNull(ImapProtocol.taggedStatus("* STATUS \"INBOX\" (UNSEEN 3)", "a2"))
        assertNull(ImapProtocol.taggedStatus("+ go ahead", "a1"))
        assertNull(ImapProtocol.taggedStatus("", "a1"))
    }

    @Test
    fun `a tag that is a prefix of another tag is not this one`() {
        // a1 and a12 are both legal tags in one session, and `startsWith("a1")` alone would take
        // a12's answer as a1's.
        assertNull(ImapProtocol.taggedStatus("a12 OK done", "a1"))
        assertEquals(ImapProtocol.Status.OK, ImapProtocol.taggedStatus("a12 OK done", "a12"))
    }

    @Test
    fun `the unread count comes out of a status line`() {
        assertEquals(3, ImapProtocol.unseen("""* STATUS "INBOX" (UNSEEN 3)"""))
        assertEquals(0, ImapProtocol.unseen("""* STATUS "INBOX" (UNSEEN 0)"""))
        assertEquals(1247, ImapProtocol.unseen("""* STATUS "INBOX" (MESSAGES 8801 UNSEEN 1247)"""))
        // The specification does not fix the order, and servers do differ.
        assertEquals(4, ImapProtocol.unseen("""* STATUS "INBOX" (UNSEEN 4 MESSAGES 90 RECENT 2)"""))
        // An unquoted mailbox name is legal too.
        assertEquals(7, ImapProtocol.unseen("* STATUS INBOX (UNSEEN 7)"))
        assertEquals(2, ImapProtocol.unseen("*  status  \"INBOX\"  (unseen 2)"))
    }

    @Test
    fun `a mailbox named after the word does not become the count`() {
        // ⚠️ THE reason the attributes are read out of the trailing group rather than by searching
        // the line. Scanning for "UNSEEN" and taking the next token would answer null here — or on
        // a differently-named box, a number out of the name.
        assertEquals(4, ImapProtocol.unseen("""* STATUS "Old UNSEEN mail" (MESSAGES 4 UNSEEN 4)"""))
        assertNull(ImapProtocol.unseen("""* STATUS "Old UNSEEN mail" (MESSAGES 4)"""))
        assertEquals(9, ImapProtocol.unseen("""* STATUS "Notes (drafts)" (UNSEEN 9)"""))
    }

    @Test
    fun `anything that is not a status line yields nothing`() {
        assertNull(ImapProtocol.unseen("a2 OK STATUS completed"))
        assertNull(ImapProtocol.unseen("* OK [CAPABILITY IMAP4rev1] Ready"))
        assertNull(ImapProtocol.unseen("""* STATUS "INBOX" (UNSEEN)"""))       // no number
        assertNull(ImapProtocol.unseen("""* STATUS "INBOX" (UNSEEN many)"""))  // not a number
        assertNull(ImapProtocol.unseen("""* STATUS "INBOX" UNSEEN 3"""))       // no group at all
        assertNull(ImapProtocol.unseen(""))
        // A negative count is not a count.
        assertNull(ImapProtocol.unseen("""* STATUS "INBOX" (UNSEEN -1)"""))
    }

    // ---- saying why -------------------------------------------------------------------------

    @Test
    fun `a refused sign-in at a provider that wants an app password says so`() {
        // ⚠️ These providers all answer "authentication failed" whether the password is wrong or
        // merely the wrong KIND, and somebody who has just pasted their website password cannot
        // tell those apart. Naming it is the difference between a setting that works and one that
        // looks broken.
        val msg = ImapProtocol.explain("imap.gmail.com", ImapProtocol.Status.NO, "[AUTHENTICATIONFAILED] Invalid credentials")
        assertTrue(msg, "app password" in msg)
        assertTrue(msg, "imap.gmail.com" in msg)
        assertTrue("app password" in ImapProtocol.explain("outlook.office365.com", ImapProtocol.Status.NO, "x"))
        assertTrue("app password" in ImapProtocol.explain("imap.mail.yahoo.com", ImapProtocol.Status.NO, "x"))
    }

    @Test
    fun `a refused sign-in anywhere else does not invent an explanation`() {
        val msg = ImapProtocol.explain("mail.example.org", ImapProtocol.Status.NO, "Invalid credentials")
        assertFalse(msg, "app password" in msg)
        assertTrue(msg, "Invalid credentials" in msg)
    }

    @Test
    fun `no answer at all is reported as no answer`() {
        val msg = ImapProtocol.explain("mail.example.org", null, "")
        assertTrue(msg, "mail.example.org" in msg)
        assertEquals(
            "The server did not understand the request: Missing argument",
            ImapProtocol.explain("mail.example.org", ImapProtocol.Status.BAD, "Missing argument"),
        )
    }

    @Test
    fun `tags are unique within a session and never empty`() {
        assertEquals("a1", ImapProtocol.tag(1))
        assertEquals("a3", ImapProtocol.tag(3))
        assertEquals("a1", ImapProtocol.tag(0))
        assertEquals("a1", ImapProtocol.tag(-4))
        assertEquals(3, listOf(1, 2, 3).map(ImapProtocol::tag).toSet().size)
    }
}
