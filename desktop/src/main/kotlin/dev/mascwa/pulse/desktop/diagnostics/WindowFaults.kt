package dev.mascwa.pulse.desktop.diagnostics

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.WindowExceptionHandler
import androidx.compose.ui.window.WindowExceptionHandlerFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.awt.Window
import javax.swing.JOptionPane
import javax.swing.SwingUtilities

/**
 * How a throwable is reduced to something a person can act on.
 *
 * ⚠️ **Shared on purpose, by two consumers that had drifted into the same bug.** The fault dialog and
 * the crash console's own list row each reduced a throwable to one line, and each independently took
 * `stackTrace.first()`. For the fault this was written for that frame is always
 * `InlineClassHelperKt.throwIllegalArgumentException` — Compose validates `Constraints` through an
 * internal helper — so *both* surfaces named the thrower and neither named the code that was wrong.
 * One rule, two callers, so they cannot disagree about what the answer is.
 */
object FaultTrace {

    /** Frames that only ever tell you *that* something threw, never *what* was wrong. */
    private val PLUMBING = listOf(
        "androidx.compose.ui.internal.",
        "androidx.compose.ui.unit.Constraints",
        "kotlin.",
        "java.",
        "jdk.",
    )
    private const val OURS = "dev.mascwa.pulse."

    /** The deepest cause: a wrapper is generic, the cause is the thing that went wrong. */
    fun rootCause(t: Throwable): Throwable {
        var cause: Throwable = t
        var guard = 0
        while (cause.cause != null && cause.cause !== cause && guard++ < 12) cause = cause.cause!!
        return cause
    }

    /** The one line that identifies a fault. No frames — see [WindowFaultHandler.announced]. */
    fun summary(t: Throwable): String = "${t::class.java.simpleName}: ${t.message ?: "no message"}"

    /**
     * Where it actually happened, as a few lines of stack in **stack order**.
     *
     * ⚠️ Frame zero is worthless for exactly the fault this was written for (see the class KDoc). So:
     * skip the throw plumbing, keep the order — a reordered stack is a lie about what called what —
     * and if none of the surviving frames is ours, append the nearest one that is. A layout fault
     * inside Compose's own measure pass can genuinely have no app frame near the top, and the closest
     * one is still the best pointer available.
     *
     * ⚠️ `androidx.compose.ui.node` and `.layout` are deliberately NOT skipped: for a layout fault
     * those frames name the measure policy that produced the bad value, which is the answer.
     */
    fun locate(t: Throwable, max: Int = MAX_FRAMES): List<String> {
        val stack = t.stackTrace
        if (stack.isEmpty()) return emptyList()

        val meaningful = stack.filterNot { f -> PLUMBING.any { f.className.startsWith(it) } }
        // Never print nothing: a trace made entirely of skipped packages beats silence.
        val head = (if (meaningful.isEmpty()) stack.toList() else meaningful).take(max)

        val ours = stack.firstOrNull { it.className.startsWith(OURS) }
        val shown = if (ours != null && head.none { it.className.startsWith(OURS) }) head + ours else head
        return shown.map(::render)
    }

    fun render(f: StackTraceElement): String {
        val where = f.fileName?.let { "($it:${f.lineNumber})" }.orEmpty()
        return "${f.className.substringAfterLast('.')}.${f.methodName}$where"
    }

    const val MAX_FRAMES = 5
}

/**
 * Someone answered a fault dialog with "show me the report".
 *
 * ⚠️ A flow rather than a callback because the handler is built in `Main.kt`, above the composition,
 * and the thing that can navigate lives inside it. Same shape as the phone's navigation bus, and for
 * the same reason: the producer cannot reach the consumer directly.
 *
 * ⚠️ It also gets you **off the screen that is failing**, which is the more useful half. A panel that
 * throws will throw again on the next frame, so "look at the report" and "stop looking at the broken
 * page" are the same action.
 */
object FaultReportRequest {
    private val _requested = MutableStateFlow(0)

    /** Bumped, not set: two faults in a row must each be able to ask. */
    val requested: StateFlow<Int> = _requested.asStateFlow()

    fun ask() {
        _requested.value = _requested.value + 1
    }
}

/**
 * What happens when a Compose window throws while composing, measuring or drawing.
 *
 * ## Why we do not use the framework's
 *
 * ⚠️ Disassembling `ui-desktop-1.7.3.jar`, `DefaultWindowExceptionHandlerFactory.onException` does
 * exactly three things:
 *
 * ```
 * showErrorDialog(window, throwable)    // JOptionPane, title "Error", body = throwable.message ONLY
 * window.dispatchEvent(WINDOW_CLOSING)  // asks that window to close
 * athrow                                // rethrows
 * ```
 *
 * Two of those are actively unhelpful in this program:
 *
 * - **The dialog carries `throwable.message` and nothing else.** A real fault of this kind reached the
 *   owner as the single line `maxHeight(-12) must be >= than minHeight(0)` — true, and almost useless
 *   without the frame it came from. Meanwhile [CrashReporter] had written the whole trace to disk and
 *   nothing on screen said so.
 * - **It closes the window.** One panel failing to lay out should not take the console down. Every
 *   other screen in the app was fine.
 *
 * ## What this does instead
 *
 * Records through [CrashReporter], says on screen *what* failed, **where in the code**, and where the
 * rest of the detail is; leaves the window open; then rethrows.
 *
 * ⚠️ **The rethrow is kept deliberately.** Compose has already abandoned this frame; swallowing the
 * throwable here would leave the composition mid-flight rather than letting the AWT event loop unwind
 * it the way the default does. Rethrowing is what makes "the window survives" safe rather than
 * reckless — the event loop catches it, keeps pumping, and the next frame is attempted cleanly.
 *
 * ⚠️ **One dialog per distinct fault, because a failing frame usually fails again.** Compose will
 * re-render, hit the same bad layout and call this again, and a dialog per frame is a machine nobody
 * can use. Repeats are still recorded; they are just not re-announced.
 */
@OptIn(ExperimentalComposeUiApi::class)
class WindowFaultHandler(
    private val reporter: CrashReporter,
    private val buildLabel: String,
) : WindowExceptionHandlerFactory {

    /**
     * Faults already announced.
     *
     * ⚠️ Keyed on the **message alone**, deliberately — not on the frames. The same broken layout can
     * report a slightly different stack between passes (a different measure path reaches it), and
     * keying on the frames would let one fault announce itself several times over.
     */
    private val announced = HashSet<String>()

    override fun exceptionHandler(window: Window): WindowExceptionHandler =
        WindowExceptionHandler { throwable ->
            runCatching { reporter.record(Thread.currentThread(), throwable, buildLabel) }

            val root = FaultTrace.rootCause(throwable)
            val key = FaultTrace.summary(root)
            val first = synchronized(announced) { announced.add(key) }
            if (first) {
                val where = FaultTrace.locate(root)
                // Off the current stack: we are mid-throw, and a modal dialog opened from here would
                // run a nested event loop inside a frame Compose has already given up on.
                SwingUtilities.invokeLater {
                    runCatching {
                        val choice = JOptionPane.showOptionDialog(
                            window.takeIf { it.isDisplayable },
                            body(key, where),
                            "LCARS \u00b7 a panel failed",
                            JOptionPane.DEFAULT_OPTION,
                            JOptionPane.ERROR_MESSAGE,
                            null,
                            arrayOf(SHOW_REPORT, DISMISS),
                            SHOW_REPORT,
                        )
                        if (choice == 0) FaultReportRequest.ask()
                    }
                }
            }
            // Deliberately NO `window.dispatchEvent(WindowEvent(window, WINDOW_CLOSING))`.
            throw throwable
        }

    /** The one line that identifies this fault, and the dedupe key. */
    internal fun summary(t: Throwable): String = FaultTrace.summary(t)

    /** Where the fault actually happened, as a few lines of stack. */
    internal fun locate(t: Throwable): List<String> = FaultTrace.locate(t)

    internal fun body(key: String, where: List<String>): String = buildString {
        append("A panel could not be drawn. The rest of the console is still running.\n\n")
        append(key).append('\n')
        where.forEach { append("    at ").append(it).append('\n') }
        append("\nThe full stack trace has been recorded. \u201c")
        append(SHOW_REPORT)
        append("\u201d opens it and takes you off the page that is failing;\n")
        append("it is also under MENU \u2192 CRASH CONSOLE, and on disk as diagnostics\\fault-*.txt.")
    }

    private companion object {
        /**
         * ⚠️ First in the array and the default, so the obvious action is the useful one. A dialog
         * whose only button is OK teaches people to dismiss it without reading, and the report is
         * the entire point of announcing anything.
         */
        const val SHOW_REPORT = "SHOW THE REPORT"
        const val DISMISS = "DISMISS"
    }
}
