package dev.mascwa.pulse.data.selfcode

/**
 * A tiny, bounded line-level diff for self-code previews — enough to SHOW what a proposed change does
 * before the user approves it. Standard LCS (not Myers), with cost + output guards and collapsed runs of
 * unchanged context so the result stays readable. Lines are unified-style: " " context, "-" removed,
 * "+" added; `---`/`+++` headers name the file.
 */
object LineDiff {
    private const val MAX_SIDE = 600   // beyond this many lines, skip the LCS and show a summary
    private const val MAX_OUT = 300    // cap emitted diff lines per file
    private const val CONTEXT = 2      // unchanged lines kept around each change

    /** Unified-style diff of [old] (null = brand-new file) → [new] for [path]. */
    fun diff(old: String?, new: String, path: String): String {
        if (old == null) {
            val lines = new.split("\n")
            val body = lines.take(MAX_OUT).joinToString("\n") { "+$it" }
            val more = if (lines.size > MAX_OUT) "\n… (+${lines.size - MAX_OUT} more)" else ""
            return "+++ b/$path (new file)\n$body$more"
        }
        if (old == new) return "--- a/$path\n+++ b/$path\n  (no changes)"
        val a = old.split("\n")
        val b = new.split("\n")
        val header = "--- a/$path\n+++ b/$path"
        if (a.size > MAX_SIDE || b.size > MAX_SIDE) {
            return "$header\n  (${a.size} → ${b.size} lines; large file — see the PR for the full diff)"
        }
        val ops = lcsDiff(a, b)
        // Keep changed lines plus a little surrounding context; collapse the rest to a marker.
        val keep = BooleanArray(ops.size)
        ops.forEachIndexed { idx, s ->
            if (s.startsWith("+") || s.startsWith("-")) {
                for (k in (idx - CONTEXT)..(idx + CONTEXT)) if (k in ops.indices) keep[k] = true
            }
        }
        val sb = StringBuilder(header)
        var emitted = 0
        var gap = false
        for (idx in ops.indices) {
            if (emitted >= MAX_OUT) { sb.append("\n… (truncated)"); break }
            if (keep[idx]) { sb.append('\n').append(ops[idx]); emitted++; gap = false }
            else if (!gap) { sb.append("\n  ⋯"); gap = true }
        }
        return sb.toString()
    }

    /** Unified op lines (" ctx" / "-removed" / "+added") via an LCS backtrack. */
    private fun lcsDiff(a: List<String>, b: List<String>): List<String> {
        val n = a.size
        val m = b.size
        val dp = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) {
            for (j in m - 1 downTo 0) {
                dp[i][j] = if (a[i] == b[j]) dp[i + 1][j + 1] + 1 else maxOf(dp[i + 1][j], dp[i][j + 1])
            }
        }
        val out = ArrayList<String>(n + m)
        var i = 0
        var j = 0
        while (i < n && j < m) {
            when {
                a[i] == b[j] -> { out.add(" " + a[i]); i++; j++ }
                dp[i + 1][j] >= dp[i][j + 1] -> { out.add("-" + a[i]); i++ }
                else -> { out.add("+" + b[j]); j++ }
            }
        }
        while (i < n) { out.add("-" + a[i]); i++ }
        while (j < m) { out.add("+" + b[j]); j++ }
        return out
    }
}
