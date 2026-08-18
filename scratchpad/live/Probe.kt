import dev.mascwa.pulse.core.telemetry.M3uCatalog
import java.io.File

fun main(args: Array<String>) {
    val text = File(args[0]).readText()
    val ch = M3uCatalog.parse(text, cap = 10_000)
    println("parsed: ${ch.size}")
    println("unique ids: ${ch.map { it.id }.toSet().size}")
    println("all https: ${ch.all { it.url.startsWith("https://") }}")
    println("all community+unverified: ${ch.all { it.provenance.name == "COMMUNITY" && it.verification.name == "UNVERIFIED" }}")
    println("names still carrying a resolution tag: ${ch.count { Regex("""\(\d{3,4}[pi]\)""").containsMatchIn(it.name) }}")
    println("names still carrying a bracket tag: ${ch.count { it.name.contains('[') }}")
    println("blank regions: ${ch.count { it.region.isBlank() }}  Unknown: ${ch.count { it.region == "Unknown" }}")
    println("sorted: ${ch.map { it.name.lowercase() } == ch.map { it.name.lowercase() }.sorted()}")
    println("first 6: ${ch.take(6).map { "${it.name} [${it.region}]" }}")
    println("regions: ${ch.groupingBy { it.region }.eachCount().entries.sortedByDescending { it.value }.take(6)}")
    println("cap honoured: ${M3uCatalog.parse(text, cap = 25).size}")
}
