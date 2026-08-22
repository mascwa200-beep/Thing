package dev.mascwa.pulse.desktop.theme

import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.IntrinsicMeasurable
import androidx.compose.ui.layout.IntrinsicMeasureScope
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.Constraints
import dev.mascwa.pulse.desktop.diagnostics.IntrinsicClampWatch

/**
 * Stops a negative dimension reaching Compose's intrinsic measurement, and **says when it did**.
 *
 * ## Why this exists
 *
 * A panel on the Windows console dies with
 * `IllegalArgumentException: maxHeight(-12) must be >= than minHeight(0)`, thrown out of
 * `androidx.compose.ui.unit.Constraints`. Disassembling `NodeMeasuringIntrinsics` in the shipped
 * `ui-desktop-1.7.3.jar` and reading the default-argument masks pins the shape exactly:
 *
 * ```
 * minWidth$ui(..., h)  -> Constraints$default(0, 0, 0, h, mask=7)   => Constraints(maxHeight = h)
 * minHeight$ui(..., w) -> Constraints$default(0, w, 0, 0, mask=13)  => Constraints(maxWidth  = w)
 * ```
 *
 * So that message is an intrinsic **width** query run with a negative height, and nothing else in
 * Compose builds a `Constraints` of that shape. A node whose intrinsics are the framework defaults
 * throws the moment it is asked one, which is reproducible in three lines (see
 * `ClampIntrinsicsTest`) — a bare `Box` will do it.
 *
 * ## ⚠️ This is a containment, not a diagnosis, and it is built to say so
 *
 * An exhaustive sweep of every `min/maxIntrinsicWidth` caller in the shipped Compose stack found
 * 86 child queries, of which 27 compute their argument rather than passing it through — and **not
 * one of the 27 is reachable in the tree that fails**. The producer has not been identified.
 *
 * A clamp that silently absorbed the negative would therefore hide the only evidence there is.
 * So the first time it fires it **records a report** through the same [dev.mascwa.pulse.desktop.diagnostics.CrashReporter]
 * the fault dialog uses, carrying the live stack, and then goes quiet. The panel draws AND the next
 * occurrence still names itself.
 *
 * ⚠️ **One shot per process, and that is load-bearing.** This runs inside layout, which happens on
 * every frame; writing a report per frame would be far worse than the fault. [IntrinsicClampWatch]
 * holds the latch, and recording is skipped entirely until something installs a reporter, so tests
 * and any pre-startup composition cost nothing.
 *
 * ## Placement
 *
 * A modifier can only clamp a query that travels **through** it, so this belongs as the outermost
 * element on anything that might be asked for an intrinsic — in practice the two composables in
 * this kit that force an intrinsic pass, `LcarsDataRow` and `LcarsDialog`. It is layout-transparent:
 * [measure] measures the child with the constraints it was given and places it at the origin.
 */
fun Modifier.clampIntrinsics(label: String): Modifier = this.then(ClampIntrinsicsElement(label))

private data class ClampIntrinsicsElement(val label: String) : ModifierNodeElement<ClampIntrinsicsNode>() {
    override fun create(): ClampIntrinsicsNode = ClampIntrinsicsNode(label)

    override fun update(node: ClampIntrinsicsNode) {
        node.label = label
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "clampIntrinsics"
        properties["label"] = label
    }
}

private class ClampIntrinsicsNode(var label: String) : Modifier.Node(), LayoutModifierNode {

    /** Layout-transparent: the child gets exactly what we were given, at the origin. */
    override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
        val placeable = measurable.measure(constraints)
        return layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }

    override fun IntrinsicMeasureScope.minIntrinsicWidth(measurable: IntrinsicMeasurable, height: Int): Int =
        measurable.minIntrinsicWidth(guard(height, "minIntrinsicWidth", "height"))

    override fun IntrinsicMeasureScope.maxIntrinsicWidth(measurable: IntrinsicMeasurable, height: Int): Int =
        measurable.maxIntrinsicWidth(guard(height, "maxIntrinsicWidth", "height"))

    override fun IntrinsicMeasureScope.minIntrinsicHeight(measurable: IntrinsicMeasurable, width: Int): Int =
        measurable.minIntrinsicHeight(guard(width, "minIntrinsicHeight", "width"))

    override fun IntrinsicMeasureScope.maxIntrinsicHeight(measurable: IntrinsicMeasurable, width: Int): Int =
        measurable.maxIntrinsicHeight(guard(width, "maxIntrinsicHeight", "width"))

    /**
     * ⚠️ Reports **before** returning, so the recorded stack is the live one — the frames above this
     * node are exactly the chain that produced the bad value, which is the whole point of reporting
     * at all. Capturing it afterwards, or on a background thread, would lose them.
     */
    private fun guard(value: Int, query: String, axis: String): Int {
        if (value >= 0) return value
        IntrinsicClampWatch.clamped(label, query, axis, value)
        return 0
    }
}
