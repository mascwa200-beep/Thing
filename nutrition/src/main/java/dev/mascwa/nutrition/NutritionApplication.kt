package dev.mascwa.nutrition

import android.app.Application
import dev.mascwa.nutrition.data.NutritionContainer
import dev.mascwa.pulse.crash.Breadcrumbs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The process, and the two things that have to happen before anything else does.
 *
 * ⚠️ **This class exists because a crash handler installed from an activity is installed too late.**
 * Until now the manifest named no application class, so the earliest code this app ran was
 * `MainActivity.onCreate` — and the failures worth catching most are the ones that happen before a
 * first frame is ever drawn: a database that will not open, a store whose file is corrupt, a
 * dependency that throws while it is being constructed. Every one of those killed the app with
 * nothing recorded and nothing to read afterwards.
 *
 * ⚠️ **It also owns the container, and that is not a style choice.** Two `NutritionContainer`s in one
 * process means two of every store, and several of them hold a DataStore over a fixed file — which
 * throws outright on a second instance. The activity used to build its own; now it reads this one,
 * so there is exactly one.
 */
class NutritionApplication : Application() {

    /**
     * Built eagerly here, which costs nothing: every member of the container is `by lazy`, so
     * constructing it allocates one object and opens no file, no database and no socket.
     */
    val container: NutritionContainer by lazy { NutritionContainer(this) }

    /**
     * ⚠️ The application's own scope, not a coroutine tied to a screen. Sending a report that was
     * recorded last launch has nothing to do with whether anybody is looking at the app, and a
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
    }
}
