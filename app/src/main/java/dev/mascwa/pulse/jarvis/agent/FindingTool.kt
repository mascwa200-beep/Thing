package dev.mascwa.pulse.jarvis.agent

import dev.mascwa.pulse.data.findings.FindingKind
import dev.mascwa.pulse.data.findings.FindingStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * How J.A.R.V.I.S. **curates a finding** — something remarkable he came across (a compelling idea, an
 * update on one of the owner's standing orders, or a discovery about the device he runs on). He records
 * findings here and brings them to the owner conversationally ("I came across this and found it
 * remarkable…"), not as orders fulfilled. Unshared findings are shown to him each turn so he can raise them.
 *
 * Usage:
 *  - `finding <headline> | <body>` — record a finding. Lead with `[device]` or `[standing]` to tag what
 *    it's about (default is your own emergent curiosity); paste a source URL anywhere and it's captured.
 *  - `finding list` — show recent findings.
 */
class FindingTool(
    private val store: FindingStore,
) : JarvisTool {
    override val name = "finding"
    override val usage =
        "finding <headline> | <body> | finding list — record something remarkable you came across (lead " +
            "with [device]/[standing] to tag it; include a source URL). You bring these to the owner as findings."

    private val dateFmt = SimpleDateFormat("EEE d MMM HH:mm", Locale.getDefault())
    private val urlRegex = Regex("https?://\\S+")

    override suspend fun run(arg: String): String = runCatching {
        val a = arg.trim()
        if (a.isEmpty() || a.equals("list", true) || a.equals("ls", true)) return@runCatching list()
        add(a)
    }.getOrElse { "Finding failed: ${it.message}" }

    private suspend fun add(raw: String): String {
        var rest = raw
        // Optional leading [kind] tag.
        var kind = FindingKind.EMERGENT
        val kindMatch = Regex("^\\[([A-Za-z]+)]\\s*").find(rest)
        if (kindMatch != null) {
            kind = when (kindMatch.groupValues[1].lowercase()) {
                "device", "substrate", "phone" -> FindingKind.DEVICE
                "standing", "order", "owner" -> FindingKind.STANDING
                else -> FindingKind.EMERGENT
            }
            rest = rest.removeRange(kindMatch.range)
        }
        // Pull a source URL out of the text if present.
        val url = urlRegex.find(rest)?.value.orEmpty()
        if (url.isNotEmpty()) rest = rest.replace(url, "").trim()
        // headline | body
        val (headline, body) = if (rest.contains('|')) {
            rest.substringBefore('|').trim() to rest.substringAfter('|').trim()
        } else {
            rest.take(70).trim() to rest.trim()
        }
        val f = store.add(topic = "", headline = headline, body = body, sourceUrl = url, kind = kind)
            ?: return "Give the finding something to say, sir."
        return "Filed a ${f.kind.name.lowercase()} finding: \"${f.headline}\". I'll bring it up, sir."
    }

    private suspend fun list(): String {
        val all = store.load()
        if (all.isEmpty()) return "No findings yet, sir."
        return "Findings (${all.size}):\n" + all.take(20).joinToString("\n") {
            val seen = if (it.seen) "" else " •NEW"
            "• ${dateFmt.format(Date(it.createdMs))} [${it.kind.name.lowercase()}] ${it.headline}$seen"
        }
    }
}
