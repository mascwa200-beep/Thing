package dev.mascwa.pulse.desktop.update

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The two guards that stop a quit request outliving the pass that made it.
 *
 * ⚠️ **The failure being prevented is an application that can never be opened again.** The hourly
 * update pass writes a flag asking the console to close so Windows Installer can replace its files.
 * If that flag can survive — because the updater was killed, or the machine lost power between the
 * request and the console reading it — then every subsequent launch reads it, quits at once, and the
 * user has no way in by any means available to them. Both guards are tested on their own so that
 * removing either one fails something.
 */
class SingleInstanceTest {

    private lateinit var dir: File

    private fun requestFile() = File(dir, "quit-for-update.request")

    @Before
    fun useOwnDirectory() {
        // ⚠️ Never the real path. It is shared with a console that may be running on this machine,
        // and a genuine request written there would close somebody's window mid-sentence.
        dir = Files.createTempDirectory("lcars-instance-test").toFile()
        SingleInstance.root = dir
        SingleInstance.clearQuitRequest()
    }

    @After
    fun tidy() {
        SingleInstance.release()
        SingleInstance.clearQuitRequest()
        SingleInstance.root = null
        dir.deleteRecursively()
    }

    @Test
    fun `a fresh request is honoured`() {
        assertTrue("writing the request should succeed", SingleInstance.requestQuit())
        assertTrue("the console must act on a request just made", SingleInstance.quitRequested())
    }

    @Test
    fun `a stale request is ignored`() {
        // Guard one. An updater that was killed after writing this leaves it behind; hours later it
        // must mean nothing. Written at a past instant by hand rather than by waiting.
        val old = System.currentTimeMillis() - SingleInstance.REQUEST_FRESH_MS - 1_000
        requestFile().writeText(old.toString())
        assertFalse(
            "a request older than the freshness window must not quit the app",
            SingleInstance.quitRequested(),
        )
    }

    @Test
    fun `a request is consumed the moment it is read`() {
        // Guard two, and the one that matters most: even a request that IS honoured must not be
        // honoured twice. Otherwise a console killed between reading and quitting leaves the flag in
        // place and the next launch quits immediately, for as long as the freshness window lasts.
        SingleInstance.requestQuit()
        assertTrue("the first read acts on it", SingleInstance.quitRequested())
        assertFalse("reading a request must delete it", SingleInstance.quitRequested())
    }

    @Test
    fun `a request from the future is ignored`() {
        // A clock that moved backwards — daylight saving, an NTP correction, a dual-boot machine
        // writing local time to the hardware clock. A negative age must not read as "very fresh".
        requestFile().writeText((System.currentTimeMillis() + 60 * 60 * 1000L).toString())
        assertFalse("a future timestamp is not a fresh request", SingleInstance.quitRequested())
    }

    @Test
    fun `an unreadable request is ignored rather than throwing`() {
        // The file is written by another process and can be caught half-written or corrupted. The
        // honest reading of "I cannot tell what this says" is to do nothing.
        requestFile().writeText("not a timestamp")
        assertFalse("an unparseable request means nothing", SingleInstance.quitRequested())
    }

    @Test
    fun `no request at all is not a request`() {
        assertFalse("nothing was asked", SingleInstance.quitRequested())
    }

    @Test
    fun `clearing throws a request away without acting on it`() {
        // What a console does at startup: consume anything left over so it cannot quit the instant
        // it finishes starting.
        SingleInstance.requestQuit()
        SingleInstance.clearQuitRequest()
        assertFalse("a cleared request is gone", SingleInstance.quitRequested())
    }

    @Test
    fun `the lock is claimed once and reports itself as running`() {
        assertFalse("nothing holds the lock before it is claimed", SingleInstance.isRunning())
        assertTrue("claiming should succeed", SingleInstance.claim())
        assertTrue("a claimed lock reads as running", SingleInstance.isRunning())
        // Claiming again is the ordinary case of a second call, not a second console.
        assertTrue("a second claim is a no-op, not a failure", SingleInstance.claim())
        SingleInstance.release()
        assertFalse("releasing must let the updater proceed", SingleInstance.isRunning())
    }

    @Test
    fun `waiting for an exit gives up rather than installing over a live process`() {
        // ⚠️ Returning false on timeout is the whole point: an MSI run over a running program is the
        // failure this mechanism exists to avoid, and being one build behind for an hour is by far
        // the cheaper outcome. A short budget so the test does not sit for ninety seconds.
        SingleInstance.claim()
        assertFalse(
            "a held lock must never be waited past",
            SingleInstance.awaitExit(timeoutMs = 150, sleepMs = 25),
        )
    }

    @Test
    fun `waiting returns at once when nothing is running`() {
        assertTrue(
            "an unheld lock means the upgrade may proceed",
            SingleInstance.awaitExit(timeoutMs = 150, sleepMs = 25),
        )
    }
}
