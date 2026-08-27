package dev.mascwa.pulse.core.telemetry

/**
 * Whether a number field should adopt what the model says, or keep what is being typed.
 *
 * ## Why this exists
 *
 * ⚠️ **A fully controlled numeric field records a number a hundred times too large.** When the text
 * a field shows is re-derived from the model on every frame, a half-typed number has to survive a
 * round trip through a `Double` — and it does not. Measured by running the shipped parser and
 * formatter over real keystrokes, before this rule was written:
 *
 *     typing 1.25   recorded 125     '1'->"1"  '.'->"1"  '2'->"12"  '5'->"125"
 *     typing 2.5    recorded  25
 *     typing 0.5    recorded   5
 *     typing 102.5  recorded 1025
 *
 * [Decimals.parse] of `"1."` is 1.0 and rendering 1.0 gives `"1"`, so the decimal point is destroyed
 * on the very next frame and the following digit appends to the whole number instead. It is the same
 * shape as the comma defect [Decimals] itself was written for: not a refusal, which is visible, but a
 * wrong number that looks like it worked. The training fields it was found in feed the progression
 * advice, and plates go in 1.25 kg steps — the display could hold a fraction and the input path could
 * not produce one.
 *
 * ## Why the rule lives here rather than in a composable
 *
 * ⚠️ Both applications have number fields with this shape, and `:core:telemetry` is a plain
 * Kotlin/JVM module with no Compose in it — so a shared `@Composable` helper is not available. What
 * is worth sharing is the DECISION, which is pure; the `remember` plumbing around it is two lines and
 * belongs to each app. That also makes the rule testable, which as a composable it would not be.
 */
object TypedNumber {

    /**
     * The text the field should show now.
     *
     * [typed] is the field's own buffer, [lastSeen] is the model value it was shown on the PREVIOUS
     * frame, and [value] is what the model says on this one. Returns [typed] unchanged whenever
     * there is nothing to adopt.
     *
     * ⚠️ **The caller stores [lastSeen] unconditionally, every frame** — not only when this returns
     * something new. That is why this returns a string rather than a nullable "should I re-seed":
     * two unconditional assignments are much harder to get wrong than a conditional pair, and
     * getting it wrong here is not visible. Updating [lastSeen] only on a re-seed lets it go stale,
     * and a stale one makes the second rule below fire on a value the field itself produced — which
     * in the servings case means the box can be cleared once and then never again.
     *
     * ⚠️ **Three rules, and each answers a case the others get wrong.**
     *
     *  - **The model already agrees with the number in the box: keep the typed spelling.** There is
     *    nothing to adopt, and adopting anyway would rewrite the separator under the finger of
     *    somebody on a comma keyboard — they type `1`, `,`, `2` and the field answers `1.2`. Not a
     *    wrong number, but the whole point of [Decimals] is that a comma is a decimal point rather
     *    than something to be corrected.
     *  - **The model moved to something new: follow it.** A different row came into this slot, or
     *    the value was set from elsewhere. Tested against the PREVIOUS model value rather than
     *    against the text, which is what lets an intermediate the model cannot express — an empty
     *    box on a field whose model is a non-null `Int` — survive being typed. That case is not
     *    hypothetical: a recipe's servings field could not be cleared at all, because its handler
     *    was `s.toIntOrNull()?.let { … }` and a blank string simply did nothing, so the model kept
     *    saying 4 and the field kept re-rendering "4". The only way to change it was to append
     *    digits.
     *  - **The box holds a real number and the model still disagrees: snap back.** Then the model
     *    clamped or rounded what was typed — a fraction into an integer field — and the refusal has
     *    to be on screen, rather than leaving the field saying 1.5 while 1 is what was recorded.
     *
     * Anything else is an intermediate — an empty box, a lone separator, a leading minus — and is
     * left exactly as it was typed.
     */
    fun textFor(typed: String, lastSeen: String, value: String): String {
        val here = Decimals.parse(typed)
        if (here != null && here == Decimals.parse(value)) return typed
        if (value != lastSeen) return value
        if (here != null) return value
        return typed
    }
}
