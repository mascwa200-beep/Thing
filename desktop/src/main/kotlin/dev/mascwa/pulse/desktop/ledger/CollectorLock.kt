package dev.mascwa.pulse.desktop.ledger

import dev.mascwa.pulse.desktop.AppPaths
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException

/**
 * Only one collection pass at a time, across every process on this machine.
 *
 * Two things collect: the console's own timer while it is open, and the scheduled task while it is
 * not. Those can overlap — the task fires on the quarter hour whether or not somebody has just
 * opened the window — and two processes appending to one ledger is how a series ends up with
 * duplicate readings that quietly inflate every sample count.
 *
 * ⚠️ **A file lock, not a PID file, for the reason [dev.mascwa.pulse.desktop.update.SingleInstance]
 * already gives**: the operating system releases a lock when the process dies, however it dies. A
 * PID file survives a crash and a power cut, and the classic failure of that design is a collector
 * that never runs again because it believes a long-dead copy of itself is still working.
 *
 * Whoever takes the lock collects; the other gives up immediately rather than queuing. Waiting would
 * mean a scheduled task sitting on a pass it was never going to contribute to, and nothing is lost:
 * the next pass is minutes away.
 *
 * ⚠️ Deliberately **not** the console's own instance lock. That one answers "is the window open",
 * which is a different question — the console holds it for its whole lifetime, so a collector
 * sharing it could only ever run when the app was shut.
 */
object CollectorLock {

    @Volatile
    private var held: FileLock? = null

    /** Kept open on purpose — closing the channel releases the lock. */
    @Volatile
    private var channel: RandomAccessFile? = null

    /** Overridable so tests get a directory of their own rather than racing a real collector. */
    internal var root: File? = null

    private fun lockFile(): File =
        (root ?: AppPaths.dataDir.toFile())
            .apply { runCatching { mkdirs() } }
            .let { File(it, "collector.lock") }

    /**
     * Take the lock, or return false because somebody else is already collecting.
     *
     * ⚠️ Returns **false** when the lock file itself is unreachable, which is the opposite choice to
     * `SingleInstance.claim`. That one lets the app start anyway because refusing to open a window
     * over a lock file would turn a convenience into an outage. Here the failure mode of guessing
     * wrong is two processes writing one ledger, so an unanswerable question is answered "no".
     */
    fun tryAcquire(): Boolean {
        if (held != null) return false // this process is already inside a pass
        return runCatching {
            val raf = RandomAccessFile(lockFile(), "rw")
            val lock = try {
                raf.channel.tryLock()
            } catch (_: OverlappingFileLockException) {
                raf.close()
                return false
            }
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

    fun release() {
        runCatching { held?.release() }
        runCatching { channel?.close() }
        held = null
        channel = null
    }

    /** Run [block] only if nothing else is collecting. Returns null when the lock was not free. */
    inline fun <T> withLock(block: () -> T): T? {
        if (!tryAcquire()) return null
        return try {
            block()
        } finally {
            release()
        }
    }
}
