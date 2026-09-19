package dev.mascwa.pulse.testing

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The shared gate plumbing has its own gate.
 *
 * ⚠️ Four coverage gates now trust [SourceGate.stripComments] to tell a comment from code, and all
 * four go quietly green if it stops working — a stripper that returns its input unchanged makes
 * every "is this dead?" question answer "no", for ever. This repository's own recurring lesson is
 * that **a harness needs the same care as the thing it checks**: four separate harnesses here have
 * accused correct code because the harness itself was wrong, and one reported "0 findings" for a
 * run that compiled nothing.
 *
 * The two documented limitations are pinned as *behaviour* rather than left implicit, so changing
 * either is a deliberate act with a failing test in front of it.
 */
class SourceGateTest {

    @Test
    fun `a comment cannot be mistaken for code`() {
        val src = """
            val real = Thing.ALIVE
            // val commented = Thing.DEAD_LINE
            /* val blocked = Thing.DEAD_BLOCK */
            /**
             * [Thing.DEAD_KDOC] is documented here and used nowhere.
             */
        """.trimIndent()
        val out = SourceGate.stripComments(src)
        assertTrue("real code was stripped", "Thing.ALIVE" in out)
        assertTrue("a line comment survived", "DEAD_LINE" !in out)
        assertTrue("a block comment survived", "DEAD_BLOCK" !in out)
        // ⚠️ The KDoc case is the one that actually bites: every dead symbol in this codebase has a
        // paragraph explaining it, so a gate that counts KDoc mentions passes on the documentation
        // of the defect it exists to find.
        assertTrue("a KDoc mention survived", "DEAD_KDOC" !in out)
    }

    @Test
    fun `block comments are stripped first, so a slash-slash inside one cannot swallow the file`() {
        // With the order reversed the `//` on the second line strips to end-of-line, the block's
        // closing `*/` is gone, and the non-greedy block pass then runs to the NEXT `*/` in the
        // file — taking everything between with it. That is a silent false pass over real code.
        val src = "/* a note with a // in it */\nval real = Thing.ALIVE\n"
        val out = SourceGate.stripComments(src)
        assertTrue("the block comment survived", "a note" !in out)
        assertTrue("real code after a block comment was eaten", "Thing.ALIVE" in out)
    }

    @Test
    fun `the two documented limitations behave as documented`() {
        // 1. A nested block comment leaves residue. Pinned so the KDoc's claim stays true and so a
        //    fix is a deliberate act rather than a surprise.
        val nested = SourceGate.stripComments("/* outer /* inner */ RESIDUE */\n")
        assertTrue("nested block comments no longer leave residue — update the KDoc", "RESIDUE" in nested)

        // 2. A `//` inside a string literal takes the rest of its line. Fails LOUD (a real reference
        //    disappears and its gate goes red), which is why it is tolerated.
        val inString = SourceGate.stripComments("""val u = "https://x"; val real = Thing.ALIVE""")
        assertTrue("string-embedded // no longer over-strips — update the KDoc", "Thing.ALIVE" !in inString)
    }

    @Test
    fun `the nesting detector finds one and does not invent one`() {
        assertNull(SourceGate.nestedBlockComment("/* plain */ /* another */"))
        assertNull(SourceGate.nestedBlockComment("val a = 1 / 2 * 3"))
        val found = SourceGate.nestedBlockComment("before /* outer /* inner */ tail */ after")
        assertTrue("a real nested comment was missed", found != null && "inner" in found)

        // ⚠️ Its documented blind spot, pinned so it stays documented: the scanner does not lex, so
        // delimiters written as TEXT are read as delimiters. Here the whole "comment" is a string
        // literal and there is no comment at all, and it is reported anyway. That is why the sweep
        // below covers the three main trees and not `app/src/test`, where SourceGate itself lives
        // and writes those delimiters as data.
        //
        // ⚠️ My first fixture here was `"/*"` and `"*/"` in sequence, which does NOT demonstrate it:
        // one opens and one closes, the depth returns to zero with nothing between them, and the
        // scanner correctly answers null. It failed, and the scanner was right.
        assertTrue(
            "the string-literal blind spot is gone — update SourceGate.nestedBlockComment's KDoc",
            SourceGate.nestedBlockComment("""val s = "/* outer /* inner */ tail */"; val x = 1""") != null,
        )
    }

    /**
     * ⚠️ The assumption [SourceGate.stripComments] rests on, swept rather than asserted once.
     *
     * Measured at the time of writing: zero. If this ever fails it is not this test's problem to
     * solve — it means a file has a block-comment opener inside a comment, which this repository
     * has already had once (it ate the rest of a file and produced an unresolved-reference cascade
     * that looked like a dozen unrelated bugs). Fix the comment.
     */
    @Test
    fun `no source any gate reads contains a nested block comment`() {
        val trees = listOf(
            File("src/main/java/dev/mascwa/pulse"),
            File("../core/telemetry/src/main"),
            File("../desktop/src/main/kotlin"),
        )
        val offenders = trees.flatMap { SourceGate.kotlinFilesUnder(it) }
            .mapNotNull { f -> SourceGate.nestedBlockComment(f.readText())?.let { "${f.name}: $it" } }
        assertTrue("nested block comments defeat the stripper: $offenders", offenders.isEmpty())
        // ⚠️ And the sweep must have found files to sweep. A tree that moved would produce zero
        // offenders and pass — the vacuous green this whole family of gates is written against.
        assertTrue(
            "the sweep read no files at all",
            trees.sumOf { SourceGate.kotlinFilesUnder(it).size } > 500,
        )
    }

    @Test
    fun `the file walk refuses a tree that is not there rather than sweeping nothing`() {
        // The vacuous-green shape: a gate whose path has moved finds no problems and passes.
        val moved = File("src/main/java/dev/mascwa/pulse/this-directory-does-not-exist")
        val thrown = runCatching { SourceGate.kotlinFilesUnder(moved) }.exceptionOrNull()
        assertTrue("a missing tree was swept as empty", thrown is IllegalArgumentException)
        // And it says which path, so the failure is actionable rather than only loud.
        assertTrue(
            "the refusal does not name the path",
            thrown!!.message?.contains("this-directory-does-not-exist") == true,
        )
    }
}
