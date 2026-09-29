package dev.mascwa.pulse.feature.lcarsboard

import dev.mascwa.pulse.testing.SourceGate
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every field of the widget's board is drawn by the LCARS console.
 *
 * ## Why this exists
 *
 * The lock console and the home screen draw the SAME `WidgetBoard.Board` the widget does, but with
 * their own renderer — the widget is `RemoteViews`, the consoles are Compose, and there is no sharing
 * a layout between the two. Two renderers of one data class is exactly how a field goes quietly
 * missing: somebody adds a region to the board, the widget grows it, and the console simply never
 * learns it exists. The owner asked for screens that do not HIDE anything, and a field the widget
 * shows that the lock screen drops is precisely that.
 *
 * So every property declared on `Board` must be read somewhere in this package. Reads the source as
 * text, in the shape of `OracleSignalCoverageTest`, so it cannot fail for an environmental reason.
 *
 * ⚠️ What it cannot catch: a field read and then not actually drawn, or drawn somewhere nobody looks.
 * "Named in the renderer" is not "visible on the screen" — that is a question for the owner's eyes
 * on the phone, and pretending a textual gate could answer it would be worse than saying so.
 */
class LcarsBoardCoverageTest {

    /** ⚠️ Relative to the MODULE directory — Gradle resolves a test's relative paths from there. */
    private val declaration = File("src/main/java/dev/mascwa/pulse/widget/WidgetBoard.kt")
    private val renderer = File("src/main/java/dev/mascwa/pulse/feature/lcarsboard")

    @Test
    fun `every field of the board is drawn by the console`() {
        val fields = boardFields()
        // Self-check: a parser that found nothing would pass the real assertion vacuously.
        assertTrue("found only ${fields.size} board fields: $fields", fields.size >= 20)
        assertTrue("the parser must see a known field", "upNext" in fields)

        val code = SourceGate.kotlinFilesUnder(renderer).joinToString("\n") { SourceGate.stripComments(it.readText()) }
        assertTrue("renderer sources not found under ${renderer.absolutePath}", code.isNotBlank())

        val missing = fields.filterNot { Regex("""\.$it\b""").containsMatchIn(code) }
        assertTrue(
            "these board fields are shown on the widget and read nowhere by the LCARS console, so the " +
                "lock screen and home screen silently drop them: $missing",
            missing.isEmpty(),
        )
    }

    /** The property names of `WidgetBoard.Board`, read from its declaration's parameter list. */
    private fun boardFields(): List<String> {
        assertTrue("board declaration not found from ${File(".").absolutePath}", declaration.isFile)
        val src = SourceGate.stripComments(declaration.readText())
        val open = src.indexOf("data class Board(")
        assertTrue("Board declaration not found", open >= 0)
        var depth = 0
        var i = src.indexOf('(', open)
        val start = i + 1
        while (i < src.length) {
            when (src[i]) {
                '(' -> depth++
                ')' -> if (--depth == 0) break
            }
            i++
        }
        return Regex("""\bval\s+(\w+)\s*:""").findAll(src.substring(start, i)).map { it.groupValues[1] }.toList()
    }
}
