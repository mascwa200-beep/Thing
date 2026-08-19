package dev.mascwa.pulse.data.python

import android.content.Context
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The embedded CPython interpreter, behind one door.
 *
 * ⚠️ **Everything that touches Python goes through here.** Chaquopy's own API is a set of static
 * methods with a hard rule attached — [Python.start] may be called exactly once in a process and
 * throws if called again — so a second entry point is a crash waiting for a race. Centralising it
 * also means the "is Python even here" question has one answer rather than one per caller.
 *
 * ⚠️ **Starting it is not free and it is not instant.** Chaquopy extracts the standard library and
 * any packaged requirements out of the APK's assets on first run, which is disk work measured in
 * hundreds of milliseconds to seconds depending on what was bundled. So [ensureStarted] suspends
 * and dispatches to IO, and nothing calls it from a composition or a click handler directly.
 *
 * ⚠️ **Fully defensive, and the reason is a real failure mode rather than caution.** Chaquopy is a
 * Gradle plugin that packages a native interpreter; a build where its ABI does not match the device,
 * or where the plugin silently produced nothing, links and installs perfectly and only fails when
 * the first `Python.start` runs. [available] answers that honestly instead of taking the process
 * down, and every accessor returns null on failure so a caller degrades rather than crashes — the
 * same shape as `LlamaEngine` when the adjudicator did not link.
 */
class PythonRuntime(private val appContext: Context) {

    private val mutex = Mutex()

    /**
     * Why Python is unavailable, or null if it started.
     *
     * Held rather than recomputed: a failed start is permanent for the process (the native library
     * is either loadable or it is not), and retrying it on every call would pay the same failure
     * repeatedly for a fixed answer.
     */
    @Volatile
    private var failure: String? = null

    @Volatile
    private var started = false

    /**
     * Start the interpreter if it is not already running.
     *
     * Returns true when Python is usable. Idempotent and safe to call from anywhere; concurrent
     * callers are serialised by the mutex so two coroutines cannot both reach [Python.start].
     */
    suspend fun ensureStarted(): Boolean {
        if (started) return true
        if (failure != null) return false
        return mutex.withLock {
            if (started) return@withLock true
            if (failure != null) return@withLock false
            withContext(Dispatchers.IO) {
                try {
                    // `isStarted` is checked as well as our own flag: the platform is a process-wide
                    // singleton, and something else in the process (a future library, a test
                    // harness) could legitimately have started it first. Calling start twice throws.
                    if (!Python.isStarted()) {
                        Python.start(AndroidPlatform(appContext.applicationContext))
                    }
                    started = true
                    true
                } catch (t: Throwable) {
                    // Throwable, not Exception. A missing or mismatched native library surfaces as
                    // UnsatisfiedLinkError, which is an Error — catching only Exception would let
                    // exactly the failure this guard exists for through.
                    failure = t.message?.takeIf { it.isNotBlank() } ?: t::class.java.simpleName
                    false
                }
            }
        }
    }

    /** True once the interpreter has started in this process. */
    val isRunning: Boolean get() = started

    /** Why Python is unavailable, or null if it has not failed. */
    val unavailableReason: String? get() = failure

    /**
     * Call a top-level function in a bundled module and get its result back as a string.
     *
     * ⚠️ String in, string out, deliberately. Chaquopy will marshal a great deal more than that, but
     * every additional type is a conversion whose failure mode is a runtime exception on a device —
     * and the extractor that lands on top of this returns JSON, which is a string. When something
     * genuinely needs a richer type, [module] is there and the conversion is that caller's problem
     * to state explicitly.
     *
     * Returns null on any failure, including a Python-side exception, which arrives as a
     * `PyException`. The reason is not swallowed silently: it comes back through [lastError].
     */
    suspend fun callString(module: String, function: String, vararg args: Any?): String? {
        if (!ensureStarted()) return null
        return withContext(Dispatchers.IO) {
            try {
                val result: PyObject? = Python.getInstance()
                    .getModule(module)
                    .callAttr(function, *args)
                lastError = null
                result?.toString()
            } catch (t: Throwable) {
                lastError = "$module.$function: ${t.message ?: t::class.java.simpleName}"
                null
            }
        }
    }

    /**
     * The bundled module itself, for a caller that needs more than one string out of it.
     *
     * Suspends and dispatches because importing a module runs its top-level code, which for anything
     * substantial is real work — the extractor's import is not a lookup.
     */
    suspend fun module(name: String): PyObject? {
        if (!ensureStarted()) return null
        return withContext(Dispatchers.IO) {
            try {
                lastError = null
                Python.getInstance().getModule(name)
            } catch (t: Throwable) {
                lastError = "import $name: ${t.message ?: t::class.java.simpleName}"
                null
            }
        }
    }

    /** The reason the last call returned null, or null if it succeeded. */
    @Volatile
    var lastError: String? = null
        private set

    /**
     * Exercise the interpreter on this device and say what actually works.
     *
     * ⚠️ **This is the owner-verify path for the half CI cannot prove.** The workflow asserts that
     * the interpreter and its standard-library asset were packaged into the APK, which is a fact
     * about a zip file; whether that interpreter starts on a real phone, under this device's ABI and
     * this OS's restrictions, is answerable only by running it. Both halves are needed, and neither
     * substitutes for the other — exactly the split between the CMake symbol check and the ledger
     * self-test.
     *
     * Deliberately reports each finding separately rather than one boolean: "Python did not start"
     * and "Python started but has no standard library" are different builds to fix.
     */
    suspend fun selfTest(): Report {
        val started = ensureStarted()
        if (!started) {
            return Report(
                running = false,
                interpreter = null,
                roundTrip = null,
                stdlib = null,
                error = failure ?: "The interpreter did not start.",
            )
        }
        val interpreter = callString(PROBE_MODULE, "report")
        // A palindrome would pass a round-trip that silently dropped the argument, so the fixture is
        // asymmetric and the expectation is computed here rather than written as a literal.
        val probe = "lcars-probe"
        val echoed = callString(PROBE_MODULE, "echo", probe)
        val stdlib = callString(PROBE_MODULE, "stdlib_check")
        return Report(
            running = true,
            interpreter = interpreter,
            roundTrip = when {
                echoed == null -> null
                echoed == probe.reversed() -> echoed
                else -> null
            },
            stdlib = stdlib,
            error = lastError,
        )
    }

    /**
     * What the interpreter did when asked.
     *
     * Every field is nullable and a null means "this did not work", which is why the fields are
     * separate: a report where [interpreter] is set and [stdlib] is null names a specific packaging
     * failure that a single pass/fail could not.
     */
    data class Report(
        val running: Boolean,
        val interpreter: String?,
        val roundTrip: String?,
        val stdlib: String?,
        val error: String?,
    ) {
        val allOk: Boolean
            get() = running && interpreter != null && roundTrip != null && stdlib != null
    }

    companion object {
        /**
         * The bundled proof module.
         *
         * Named as a constant because two things reference it — the self-test and, when it lands,
         * whatever replaces it — and a module name misspelt in one of two places fails at runtime
         * with an import error rather than at compile time.
         */
        const val PROBE_MODULE = "lcars_probe"
    }
}
