package dev.mascwa.pulse.core.telemetry

import java.io.File

/**
 * Runs the SHIPPED Fallacies.screen() over real neutral prose — the bundled guide corpus, ~159k
 * sentences / 4.0M words of expository English. Any cue firing often here is noise: a reference
 * work makes very few arguments, so almost every hit is a false positive by construction.
 */
fun main(args: Array<String>) {
    val lines = File(args[0]).readLines()
    val perFallacy = HashMap<String, Int>()
    val perCue = HashMap<String, Int>()
    val examples = HashMap<String, MutableList<String>>()
    for (t in lines) {
        if (t.isBlank()) continue
        for (c in Fallacies.screen(t)) {
            perFallacy[c.fallacy.id] = (perFallacy[c.fallacy.id] ?: 0) + 1
            val key = c.fallacy.id + " :: " + c.trigger.lowercase()
            perCue[key] = (perCue[key] ?: 0) + 1
            val ex = examples.getOrPut(c.fallacy.id) { mutableListOf() }
            if (ex.size < 3) ex += "[${c.trigger}] $t"
        }
    }
    val total = perFallacy.values.sum()
    println("sentences: ${lines.size}   hits: $total   rate: %.4f%%".format(100.0 * total / lines.size))
    println()
    for ((id, n) in perFallacy.entries.sortedByDescending { it.value }) {
        println("%-24s %6d  %.4f%%".format(id, n, 100.0 * n / lines.size))
        for (e in examples[id]!!) println("      " + e.take(130))
    }
    println()
    println("NOISIEST INDIVIDUAL TRIGGERS")
    for ((k, n) in perCue.entries.sortedByDescending { it.value }.take(20)) println("  %6d  %s".format(n, k))
    println()
    println("silent on neutral prose (good): " +
        Fallacies.ALL.map { it.id }.filter { it !in perFallacy }.joinToString(", "))
}
