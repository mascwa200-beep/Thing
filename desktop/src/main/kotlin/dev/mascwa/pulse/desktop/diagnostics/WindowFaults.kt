package dev.mascwa.pulse.desktop.diagnostics

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.WindowExceptionHandler
import androidx.compose.ui.window.WindowExceptionHandlerFactory
import java.awt.Window
import javax.swing.JOptionPane
import javax.swing.SwingUtilities

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
 * Records through [CrashReporter], says on screen *what* failed **and where the detail is**, leaves the
 * window open, and then rethrows.
 *
 * ⚠️ **The rethrow is kept deliberately.** Compose has already abandoned this frame; swallowing the
 * throwable here would leave the composition mid-flight rather than letting the AWT event loop unwind
 * it the way the default does. Rethrowing is what makes "the window survives" safe rather than
 * reckless — the event loop catches it, keeps pumping, and the next frame is attempted cleanly.
 *
 * ⚠️ **One dialog per distinct fault, because a failing frame usually fails again.** Compose will
 * re-render, hit the same bad layout and call this again, and a dialog per frame is a machine nobody
 * can use — the first one would be buried under hundreds and the app would be unusable for the entirely
 * separate reason that it is shouting. Repeats are still recorded; they are just not re-announced.
 */
@OptIn(ExperimentalComposeUiApi::class)
class WindowFaultHandler(
    private val reporter: CrashReporter,
    private val buildLabel: String,
) : WindowExceptionHandlerFactory {

    /** Faults already announced, keyed by the message the dialog would show. */
    private val announced = HashSet<String>()

    override fun exceptionHandler(window: Window): WindowExceptionHandler =
        WindowExceptionHandler { throwable ->
            runCatching { reporter.record(Thread.currentThread(), throwable, buildLabel) }

            val key = describe(throwable)
            val first = synchronized(announced) { announced.add(key) }
            if (first) {
                // Off the current stack: we are mid-throw, and a modal dialog opened from here would
                // run a nested event loop inside a frame Compose has already given up on.
                SwingUtilities.invokeLater {
                    runCatching {
                        JOptionPane.showMessageDialog(
                            window.takeIf { it.isDisplayable },
                            body(key),
                            "LCARS · a panel failed",
                            JOptionPane.ERROR_MESSAGE,
                        )
                    }
                }
            }
            // Deliberately NO `window.dispatchEvent(WindowEvent(window, WINDOW_CLOSING))`.
            throw throwable
        }

    private fun describe(t: Throwable): String {
        var cause: Throwable = t
        var guard = 0
        while (cause.cause != null && cause.cause !== cause && guard++ < 12) cause = cause.cause!!
        val where = cause.stackTrace.firstOrNull()
            ?.let { " at ${it.className.substringAfterLast('.')}.${it.methodName}" }
            .orEmpty()
        return "${cause::class.java.simpleName}: ${cause.message ?: "no message"}$where"
    }

    private fun body(key: String): String = buildString {
        append("A panel could not be drawn. The rest of the console is still running.\n\n")
        append(key).append("\n\n")
        append("The full stack trace has been recorded. Open MENU → CRASH CONSOLE to read it,\n")
        append("or find it on disk under diagnostics\\fault-*.txt.")
    }
}
