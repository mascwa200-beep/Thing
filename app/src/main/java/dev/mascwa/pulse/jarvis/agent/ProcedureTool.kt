package dev.mascwa.pulse.jarvis.agent

import dev.mascwa.pulse.data.procedure.ProcedureStore
import kotlin.math.roundToInt

/**
 * J.A.R.V.I.S.'s window into its learned **procedures** ("skills") — reusable multi-step tool recipes that
 * worked before. Consultative, like [ReflexTool]: it reports what it knows; it never acts on its own.
 * (Procedures are also injected into the persona each turn, so this is for explicit introspection/recall.)
 *
 * Usage:
 *  - `procedure` — list the procedures learned so far, most reliable first.
 *  - `procedure <goal>` — recall the procedure that fits <goal>, if one is trusted.
 */
class ProcedureTool(
    private val store: ProcedureStore,
) : JarvisTool {
    override val name = "procedure"
    override val usage =
        "procedure [<goal>] — list the multi-step tool procedures you've learned, or recall the one that fits <goal>"

    override suspend fun run(arg: String): String = runCatching {
        val a = arg.trim()
        if (a.isNotEmpty()) {
            val hit = store.recall(a)
            if (hit != null) {
                "For \"$a\", the procedure that works: ${hit.steps.joinToString(" → ")}  " +
                    "(${pct(hit.reliability)} over ${hit.timesApplied})"
            } else {
                "No reliable procedure learned for \"$a\" yet — plan it deliberately."
            }
        } else {
            val all = store.all().sortedByDescending { it.reliability }
            if (all.isEmpty()) {
                "No procedures learned yet — I plan each multi-step task fresh for now."
            } else {
                "Procedures I've learned:\n" + all.take(10).joinToString("\n") { p ->
                    "• ${p.name}: ${p.steps.joinToString(" → ")} " +
                        "(${pct(p.reliability)} over ${p.timesApplied}${if (!p.practiced()) ", learning" else ""})"
                }
            }
        }
    }.getOrElse { "Procedure read failed: ${it.message}" }

    private fun pct(v: Double): String = "${(v * 100).roundToInt()}%"
}
