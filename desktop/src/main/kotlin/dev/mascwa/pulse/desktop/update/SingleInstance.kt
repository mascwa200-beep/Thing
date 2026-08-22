package dev.mascwa.pulse.desktop.update

import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException

/**
 * Whether the console is running, and how an outside process asks it to stand down.
 *
 * The scheduled update pass has to answer two questions that nothing here could answer before: *is
 * the app open right now?* and, if it is, *can it be persuaded to close so Windows Installer can
 * replace its files?* Windows Installer cannot write over a file a running program holds open, so
 * without this the hourly pass could only ever upgrade a machine whose app happened to be shut —
 * which is precisely the "it updates itself the next time you close it" limit the whole scheduled
 * task exists to remove.
 *
 * ## Why a file lock rather than a PID file
 *
 * ⚠️ **The operating system releases a file lock when the process dies, however it dies.** A PID
 * file survives a crash, a kill and a power cut, and the classic failure of that design is an app
 * that refuses to start because it believes a long-dead copy of itself is still running. Nothing
 * here has to clean up after a crash because there is nothing left behind to clean up.
 *
 * ## Why the quit request is timestamped and deleted on sight
 *
 * ⚠️ A bare flag file is a brick waiting to happen: if the console is killed between reading the
 * flag and deleting it, the flag outlives it and **every subsequent launch quits immediately**, so
 * the app can never be opened again by any means the user has. Two independent guards against that:
 * the request is deleted the moment it is read, and it is ignored entirely once it is older than
 * [REQUEST_FRESH_MS]. A stale request costs nothing; a permanent one would cost the application.
 */
object SingleInstance {

    /** The lock this process holds while it is the running console. Held for the process lifetime. */
    @Volatile
    private var held: FileLock? = null

    /** Kept open deliberately — closing the channel releases the lock. */
    @Volatile
    private var channel: RandomAccessFile? = null

    /**
     * Where the lock and the request live.
     *
     * ⚠️ Overridable only so the tests get a directory of their own, and that is not a nicety: the
     * real path is shared with a console that may be running on the same machine, and a test writing
     * a genuine quit request there would close somebody's window in the middle of their work.
     */
    internal var root: File? = null

    private fun dir(): File =
        (root ?: File(System.getenv("LOCALAPPDATA") ?: System.getProperty("java.io.tmpdir"), "LCARS"))
            .apply { runCatching { mkdirs() } }

    private fun lockFile(): File = File(dir(), "instance.lock")

    private fun requestFile(): File = File(dir(), "quit-for-update.request")

    /**
     * Take the running-console lock. Called once at startup.
     *
     * Returns false when it could not be taken — either another console is already running, or the
     * lock file itself is unreachable. ⚠️ **The caller starts anyway.** This lock exists to tell the
     * updater whether to wait, not to police how many windows a person may open, and refusing to
     * launch because a lock file could not be created on somebody's locked-down machine would turn
     * a convenience into an outage.
     */
    fun claim(): Boolean {
        if (held != null) return true
        return runCatching {
            val raf = RandomAccessFile(lockFile(), "rw")
            val lock = raf.channel.tryLock()
            if (lock == null) {
                raf.close()
                false
            } else {
                channel = raf
                held = lock
                true
            }
        }.getOrDefault(false)
    }

    /** Give the lock back. Only useful at shutdown; the OS does this anyway if the process dies. */
    fun release() {
        runCatching { held?.release() }
        runCatching { channel?.close() }
        held = null
        channel = null
    }

    /**
     * Whether a console is running right now.
     *
     * Answered by trying to take the lock and immediately giving it back: if it can be taken, nobody
     * holds it. ⚠️ The overlapping-lock case is the *same JVM* asking, which happens only if this is
     * ever called from the running console itself — and the honest answer there is yes.
     */
    fun isRunning(): Boolean {
        if (held != null) return true
        return runCatching {
            RandomAccessFile(lockFile(), "rw").use { raf ->
                val lock = try {
                    raf.channel.tryLock()
                } catch (_: OverlappingFileLockException) {
                    return true
                }
                if (lock == null) {
                    true
                } else {
                    lock.release()
                    false
                }
            }
        }.getOrDefault(false)
    }

    /** Ask a running console to close so an upgrade can replace its files. */
    fun requestQuit(): Boolean = runCatching {
        requestFile().writeText(System.currentTimeMillis().toString())
        true
    }.getOrDefault(false)

    /**
     * Whether an upgrade is waiting for this console to close — read, and consumed, by the console.
     *
     * ⚠️ Deletes the request before returning true, and ignores one older than [REQUEST_FRESH_MS].
     * See the class note: those are the two guards that stop a request outliving the pass that made
     * it and quitting the app on every launch afterwards.
     */
    fun quitRequested(now: Long = System.currentTimeMillis()): Boolean {
        val file = requestFile()
        if (!file.isFile) return false
        val stamp = runCatching { file.readText().trim().toLong() }.getOrDefault(0L)
        runCatching { file.delete() }
        return stamp > 0L && now - stamp in 0..REQUEST_FRESH_MS
    }

    /** Throw away any pending request without acting on it. */
    fun clearQuitRequest() {
        runCatching { requestFile().delete() }
    }

    /**
     * Wait for the running console to let go, polling until [timeoutMs].
     *
     * Returns true once nothing holds the lock. ⚠️ Returns **false** on timeout rather than
     * installing anyway: an MSI run over a live process is the failure this whole mechanism exists
     * to avoid, and being one build behind for an hour is the cheaper of the two outcomes.
     */
    fun awaitExit(timeoutMs: Long = DEFAULT_WAIT_MS, sleepMs: Long = POLL_MS): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (!isRunning()) return true
            runCatching { Thread.sleep(sleepMs) }.getOrElse { return false }
        }
        return !isRunning()
    }

    /**
     * How long a quit request stays valid.
     *
     * Five minutes: long enough to survive a console busy flushing several stores, short enough that
     * a request left behind by a killed updater is dead well before the next hourly pass.
     */
    const val REQUEST_FRESH_MS = 5 * 60 * 1000L

    /** How long the updater waits for the console to close before giving up until the next pass. */
    const val DEFAULT_WAIT_MS = 90_000L

    private const val POLL_MS = 1_000L
}
