package dev.mascwa.sky

import android.app.Application
import dev.mascwa.pulse.crash.Breadcrumbs
import dev.mascwa.pulse.data.update.UpdateRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The process, and the three things that have to happen before anything else does.
 *
 * ⚠️ **Named in the manifest so that the container is built once**, which is the reason the
 * nutrition application gives for having one: two containers in a process means two of every reader,
 * and [SkyContainer] documents what that costs here. The activity reads this one.
 *
 * ⚠️ **The earlier version of this class deliberately did no work here, and said the honest version
 * would arrive with the updater. This is it.** The argument then was that a crash reporter would
 * record faults this application could never deliver — true, because there was no network permission
 * and no token. Both arrived in the same commit as the self-updater, and the token that makes an
 * update possible is the same token that makes a report deliverable, which is why the two belong
 * together rather than one being hinted at ahead of the other.
 *
 * ⚠️ **Constructing the container still opens nothing.** Every member of it is `by lazy`, so this
 * allocates one object; the twenty-five-megabyte catalogue is mapped when the view model asks for
 * it, off the main thread.
 */
class SkyApplication : Application() {

    val container: SkyContainer by lazy { SkyContainer(this) }

    /**
     * ⚠️ The application's own scope, not a coroutine tied to a screen. Sending a report that was
     * recorded last launch has nothing to do with whether anybody is looking at the map, and a
     * launch dispatched from an activity would be cancelled the moment it was backgrounded — which
     * is exactly when an upload gets the time to finish.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()

        // ⚠️ FIRST, before anything that could itself fail. A handler installed after the thing it
        // would have caught is worth nothing, and this is the only ordering that cannot be got wrong
        // by something added below it later.
        container.crashReporter.install()
        Breadcrumbs.drop("app", "process started")

        // Anything recorded before this launch goes now — never at fault time, when the JVM is
        // unstable and the process is about to be killed. A no-op without a token, and it says so.
        scope.launch { container.crashUploader.uploadPending() }

        // Give back the disk the last self-update borrowed. ⚠️ Here rather than after the install,
        // because `PackageInstaller.commit()` usually kills this process on a successful update, so
        // any line written after it may never run. Launch is the point that is always reached, and
        // after a successful install there always IS one.
        scope.launch { UpdateRepository.pruneCache(this@SkyApplication) }
    }
}
