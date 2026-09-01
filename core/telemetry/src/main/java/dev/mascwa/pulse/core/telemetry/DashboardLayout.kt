package dev.mascwa.pulse.core.telemetry

/**
 * Which cards a page shows, and in what order.
 *
 * A page decided by whoever wrote the screen puts the same thing at the top for everybody. Somebody
 * eating to a calorie target wants the target first; somebody who weighs in every morning wants the
 * trend; somebody who has just started wants neither and would rather see the log. All three are
 * looking at the same screen and only one of them is being served by a fixed order.
 *
 * ⚠️ **The ids are opaque on purpose.** The two applications draw different cards under different
 * names, and a core that knew about either would have to be edited every time a card was added. It
 * knows about a LIST, and each surface maps its own ids to its own composables.
 *
 * ⚠️ **The rule that matters most is what happens to a card the saved order has never heard of.** An
 * arrangement is recorded once and then outlives every release after it, so a card added later is
 * absent from it — and the obvious implementation, "show the saved order", makes that card
 * permanently invisible on exactly the devices that have been used the longest. It renders
 * perfectly and nobody can report it, because nothing is missing that they know about. New cards
 * are appended in the order the surface declares them, which is where a considered default lives.
 */
object DashboardLayout {

    /**
     * The most ids an arrangement will hold.
     *
     * ⚠️ Unknown ids are KEPT rather than pruned — see [arrange]. That is right, and it means the
     * stored list would otherwise grow every time a card is renamed, for ever. Generous enough that
     * no real page comes close, small enough to bound the blob.
     */
    const val MAX_REMEMBERED: Int = 64

    /**
     * The cards to draw, in order.
     *
     * [available] is what the surface can actually draw, in its own declared order — the default a
     * page ships with. [saved] is what was arranged, which may name cards that no longer exist and
     * may omit cards that did not exist when it was written. [hidden] is what was put away.
     *
     * ⚠️ An id in [saved] that is not in [available] is dropped from the RESULT and kept in
     * STORAGE. A card can disappear for a release — behind a capability, a permission, an
     * unfinished migration — and pruning the arrangement the first time it does would silently
     * discard an arrangement somebody made deliberately, with no way to tell it had happened.
     */
    fun arrange(
        available: List<String>,
        saved: List<String> = emptyList(),
        hidden: Set<String> = emptySet(),
    ): List<String> {
        val real = available.toSet()
        val ordered = saved.filter { it in real }.distinct()
        val seen = ordered.toSet()
        // Anything the arrangement has never heard of, in the order the page declares it.
        val fresh = available.filter { it !in seen }
        return (ordered + fresh).filter { it !in hidden }
    }

    /** Every card the surface can draw, arranged, including the ones put away. Order for editing. */
    fun editable(available: List<String>, saved: List<String> = emptyList()): List<String> =
        arrange(available, saved, emptySet())

    /**
     * [order] with [id] moved [delta] places, clamped.
     *
     * ⚠️ Returns the list unchanged rather than throwing when the id is absent or the move would
     * run off either end. A reorder control is held down and repeated; refusing the last one loudly
     * would mean every arrangement ends in an error nobody caused.
     */
    fun move(order: List<String>, id: String, delta: Int): List<String> {
        val from = order.indexOf(id)
        if (from < 0 || delta == 0) return order
        val to = (from + delta).coerceIn(0, order.size - 1)
        if (to == from) return order
        val out = order.toMutableList()
        out.removeAt(from)
        out.add(to, id)
        return out
    }

    /**
     * The arrangement to store after a move.
     *
     * ⚠️ The ids the surface cannot currently draw are carried through, for the reason [arrange]
     * gives — dropping them here would turn a temporary absence into a permanent loss the first
     * time somebody nudged a card. They keep their relative order at the end, and the whole thing
     * is capped.
     */
    fun remember(visibleOrder: List<String>, saved: List<String>): List<String> {
        val visible = visibleOrder.toSet()
        val carried = saved.filter { it !in visible }
        return (visibleOrder + carried).distinct().take(MAX_REMEMBERED)
    }

    /** Put a card away, or bring it back. Hiding something absent is a no-op, not an error. */
    fun hide(hidden: Set<String>, id: String): Set<String> = hidden + id

    fun show(hidden: Set<String>, id: String): Set<String> = hidden - id

    /**
     * Has this page been arranged at all?
     *
     * ⚠️ Compares against what the page would show UNTOUCHED, not against an empty saved list. An
     * arrangement that happens to match the default is not an arrangement, and offering to reset it
     * would be offering to change nothing. The surface uses this to decide whether a reset control
     * is worth showing.
     */
    fun isDefault(available: List<String>, saved: List<String>, hidden: Set<String>): Boolean =
        hidden.none { it in available } && arrange(available, saved, emptySet()) == available

    /**
     * A sentence for the editing surface.
     *
     * Says how many are showing and how many are put away, or says the page is as it came. Absent
     * counts are not rendered as zeroes: "0 cards hidden" is a fact nobody needs.
     */
    fun describe(available: List<String>, saved: List<String>, hidden: Set<String>): String {
        if (isDefault(available, saved, hidden)) return "Arranged as it came."
        val shown = arrange(available, saved, hidden).size
        val away = available.count { it in hidden }
        val cards = if (shown == 1) "1 card" else "$shown cards"
        return if (away == 0) "$cards, arranged." else "$cards, arranged · $away put away."
    }
}
