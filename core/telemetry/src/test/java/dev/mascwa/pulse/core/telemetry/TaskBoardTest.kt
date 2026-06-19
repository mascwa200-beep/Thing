package dev.mascwa.pulse.core.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskBoardTest {

    private fun task(
        title: String,
        status: TaskStatus = TaskStatus.OPEN,
        note: String = "",
        updated: Long = 0L,
    ) = Task(title, status, note, createdMs = 0L, updatedMs = updated)

    @Test
    fun addCreatesOpenTask() {
        val tasks = TaskBoard.add(emptyList(), "Finish the deck", nowMs = 10)
        assertEquals(1, tasks.size)
        assertEquals("Finish the deck", tasks.single().title)
        assertEquals(TaskStatus.OPEN, tasks.single().status)
    }

    @Test
    fun addTrimsAndCapsTitleLength() {
        val long = "x".repeat(TaskBoard.MAX_TITLE_LEN + 50)
        val tasks = TaskBoard.add(emptyList(), "  $long  ", nowMs = 1)
        assertEquals(TaskBoard.MAX_TITLE_LEN, tasks.single().title.length)
    }

    @Test
    fun addBlankIsIgnored() {
        assertTrue(TaskBoard.add(emptyList(), "   ", nowMs = 1).isEmpty())
    }

    @Test
    fun addDedupesByNormalizedTitleAndUpdatesNote() {
        val first = TaskBoard.add(emptyList(), "Buy milk", nowMs = 1)
        val again = TaskBoard.add(first, "  buy MILK. ", nowMs = 2, note = "from the corner shop")
        assertEquals(1, again.size)
        assertEquals("from the corner shop", again.single().note)
        assertEquals(2L, again.single().updatedMs)
    }

    @Test
    fun reAddingCompletedTaskReopensIt() {
        val done = listOf(task("Call the dentist", TaskStatus.DONE))
        val reopened = TaskBoard.add(done, "call the dentist", nowMs = 5)
        assertEquals(TaskStatus.OPEN, reopened.single().status)
    }

    @Test
    fun capEvictsCompletedBeforePending() {
        val existing = listOf(
            task("done-old", TaskStatus.DONE, updated = 1),
            task("open-old", TaskStatus.OPEN, updated = 2),
        )
        val result = TaskBoard.add(existing, "fresh", nowMs = 9, cap = 2)
        assertEquals(2, result.size)
        // The completed task is evicted; the older pending one survives.
        assertTrue(result.none { it.title == "done-old" })
        assertTrue(result.any { it.title == "open-old" })
        assertTrue(result.any { it.title == "fresh" })
    }

    @Test
    fun capEvictsStalestWhenNoneCompleted() {
        val existing = listOf(
            task("stale", TaskStatus.OPEN, updated = 1),
            task("recent", TaskStatus.ACTIVE, updated = 5),
        )
        val result = TaskBoard.add(existing, "fresh", nowMs = 9, cap = 2)
        assertEquals(2, result.size)
        assertTrue(result.none { it.title == "stale" })
    }

    @Test
    fun setStatusMatchesExactTitle() {
        val tasks = listOf(task("Renew passport"))
        val outcome = TaskBoard.setStatus(tasks, "renew passport", TaskStatus.ACTIVE, nowMs = 3)
        assertEquals(TaskStatus.ACTIVE, outcome.matched!!.status)
        assertEquals(TaskStatus.ACTIVE, outcome.tasks.single().status)
    }

    @Test
    fun setStatusMatchesBySubstringPreferringPending() {
        val tasks = listOf(
            task("Email the report", TaskStatus.DONE, updated = 1),
            task("Write the report", TaskStatus.OPEN, updated = 2),
        )
        val outcome = TaskBoard.setStatus(tasks, "report", TaskStatus.DONE, nowMs = 9)
        // The pending one is preferred over the already-done one.
        assertEquals("Write the report", outcome.matched!!.title)
    }

    @Test
    fun setStatusCanAttachNote() {
        val tasks = listOf(task("Renew passport"))
        val outcome = TaskBoard.setStatus(tasks, "passport", TaskStatus.BLOCKED, nowMs = 3, note = "need photos")
        assertEquals(TaskStatus.BLOCKED, outcome.matched!!.status)
        assertEquals("need photos", outcome.matched!!.note)
    }

    @Test
    fun setStatusNoMatchReturnsNullAndUnchangedList() {
        val tasks = listOf(task("Buy milk"))
        val outcome = TaskBoard.setStatus(tasks, "nonexistent", TaskStatus.DONE, nowMs = 3)
        assertNull(outcome.matched)
        assertEquals(tasks, outcome.tasks)
    }

    @Test
    fun removeDropsMatch() {
        val tasks = listOf(task("Buy milk"), task("Walk the dog"))
        val outcome = TaskBoard.remove(tasks, "dog")
        assertEquals("Walk the dog", outcome.matched!!.title)
        assertEquals(1, outcome.tasks.size)
        assertEquals("Buy milk", outcome.tasks.single().title)
    }

    @Test
    fun pendingExcludesDoneAndOrdersByStatus() {
        val tasks = listOf(
            task("open one", TaskStatus.OPEN, updated = 1),
            task("done one", TaskStatus.DONE, updated = 2),
            task("blocked one", TaskStatus.BLOCKED, updated = 3),
            task("active one", TaskStatus.ACTIVE, updated = 4),
        )
        val pending = TaskBoard.pending(tasks)
        assertEquals(listOf("active one", "blocked one", "open one"), pending.map { it.title })
    }

    @Test
    fun digestIsEmptyWhenNothingPending() {
        assertEquals("", TaskBoard.digest(emptyList()))
        assertEquals("", TaskBoard.digest(listOf(task("done", TaskStatus.DONE))))
    }

    @Test
    fun digestShowsStatusTagsAndNotes() {
        val tasks = listOf(
            task("Ship release", TaskStatus.ACTIVE, updated = 2),
            task("Renew passport", TaskStatus.BLOCKED, note = "need photos", updated = 1),
            task("Buy milk", TaskStatus.OPEN, updated = 0),
        )
        val digest = TaskBoard.digest(tasks)
        assertTrue(digest.contains("• Ship release [in progress]"))
        assertTrue(digest.contains("• Renew passport [blocked] — need photos"))
        assertTrue(digest.contains("• Buy milk"))
        // An open task carries no status tag.
        assertFalse(digest.contains("• Buy milk ["))
    }

    @Test
    fun digestRespectsItemCap() {
        val tasks = (1..10).map { task("task $it", TaskStatus.OPEN, updated = it.toLong()) }
        val digest = TaskBoard.digest(tasks, maxItems = 3)
        assertEquals(3, digest.lines().size)
    }

    @Test
    fun summaryListsPendingThenDone() {
        val tasks = listOf(
            task("Submit invoice", TaskStatus.DONE, updated = 1),
            task("Finish deck", TaskStatus.ACTIVE, updated = 2),
        )
        val summary = TaskBoard.summary(tasks)
        assertTrue(summary.startsWith("Tracking 2 tasks (1 open):"))
        assertTrue(summary.indexOf("Finish deck") < summary.indexOf("Submit invoice"))
        assertTrue(summary.contains("[in progress] Finish deck"))
        assertTrue(summary.contains("[done] Submit invoice"))
    }

    @Test
    fun summaryEmpty() {
        assertEquals("No tasks tracked yet.", TaskBoard.summary(emptyList()))
    }

    @Test
    fun detectCatchesSelfAssignedTasks() {
        assertEquals("finish the quarterly deck", TaskBoard.detect("I need to finish the quarterly deck"))
        assertEquals("call the dentist", TaskBoard.detect("i have to call the dentist"))
        assertEquals("renew my passport", TaskBoard.detect("todo: renew my passport"))
        // Leading filler is stripped.
        assertEquals("email the client back", TaskBoard.detect("Ok, I need to email the client back"))
        // Only the first clause is kept.
        assertEquals("submit the form", TaskBoard.detect("I have to submit the form. It's due Friday."))
    }

    @Test
    fun detectIgnoresNonTasks() {
        assertNull(TaskBoard.detect("What do I need to do today?")) // question
        assertNull(TaskBoard.detect("You need to restart the app")) // not first-person self-assignment
        assertNull(TaskBoard.detect("I need to know the capital of France")) // mental-state, not actionable
        assertNull(TaskBoard.detect("Tesla stock is up 3%"))
        assertNull(TaskBoard.detect(""))
        assertNull(TaskBoard.detect("i need to ")) // nothing after the cue
    }

    @Test
    fun focusSurfacesTopPendingWithCount() {
        assertNull(TaskBoard.focus(emptyList()))
        assertNull(TaskBoard.focus(listOf(task("done", TaskStatus.DONE))))
        val tasks = listOf(
            task("Ship release", TaskStatus.ACTIVE, updated = 2),
            task("Buy milk", TaskStatus.OPEN, updated = 1),
        )
        assertEquals("In progress: Ship release (+1 more)", TaskBoard.focus(tasks))
        assertEquals("To do: Buy milk", TaskBoard.focus(listOf(task("Buy milk", TaskStatus.OPEN))))
        assertEquals("Blocked: Renew passport", TaskBoard.focus(listOf(task("Renew passport", TaskStatus.BLOCKED))))
    }

    @Test
    fun normalizeStripsPunctuationAndCase() {
        assertEquals("buy milk", TaskBoard.normalize("  Buy Milk.  "))
        assertEquals("buy milk", TaskBoard.normalize("buy   milk"))
    }
}
