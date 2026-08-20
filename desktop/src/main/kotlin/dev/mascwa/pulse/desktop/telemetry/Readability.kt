// MIRROR OF core/telemetry/src/main/java/dev/mascwa/pulse/core/telemetry/Readability.kt — regenerate with tools/mirror_desktop_cores.py; MirrorDriftTest holds it
package dev.mascwa.pulse.desktop.telemetry

import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/**
 * The DOM decimator — a web page in, the article out, and nothing else.
 *
 * Strips a page down to what was actually written: the headline, who wrote it, when, and the body as
 * typed blocks. Navigation, "related stories", newsletter boxes, share rails, comment threads,
 * consent banners and advertising are removed rather than styled away, so what reaches the screen is
 * text this app laid out itself.
 *
 * ⚠️ **THE MOST IMPORTANT OUTPUT IS NOT THE ARTICLE — IT IS THE VERDICT.** A great many pages are
 * not going to yield one, and they all fail in the same shape: something came back, it parsed
 * perfectly, and it is not the article. A paywall, a consent wall, a login gate, a page rendered
 * entirely by script, an index page, and a redirect interstitial are indistinguishable from each
 * other by tag structure alone. A reader that quietly presents the wrong half of such a page is
 * worse than one that says it could not get it and offers the browser — so every extraction carries
 * an [Outcome] and, where it failed, a [Extraction.note] naming the reason in plain English. This is
 * the same discipline as `Rebuttal.Provenance`: say which rung produced the answer.
 *
 * ⚠️ **GOOGLE NEWS LINKS CANNOT BE READ, AND THAT IS A PROPERTY OF THE FEED.** Most of this app's
 * news arrives from Google News RSS, whose `<link>` is not the publisher's URL but an opaque token
 * (`.../rss/articles/CBMi…`). Measured: the token carries no plaintext URL — it decodes to a short
 * protobuf holding an opaque `AU_yqL…` id — and following the link does not redirect to the
 * publisher either; it lands on Google's own shell page. Resolving it needs a signed call to an
 * undocumented endpoint, which would break silently the day it changed. So those links are named as
 * unreadable rather than half-handled, and the caller is expected to offer the browser instead.
 *
 * ⚠️ **AN INDEX PAGE MADE OF REAL PROSE IS ACCEPTED, AND NO RULE HERE SEPARATES IT.** The obvious
 * discriminator is that an index carries a headline link per item where an article carries few — so
 * it was measured across a spread of real pages before being written, and the ranges *overlap
 * completely*: a real Associated Press article scored 3.33 headline links per paragraph, higher than
 * every index page in the set, while LWN's index scored 0.22, lower than every article but one. A
 * threshold would have been fitted to nothing. Declaring itself an article (`og:type`, JSON-LD,
 * `article:published_time`) was true for every non-article in the set — but also false for a
 * Gutenberg book and an LWN piece, so it is a positive signal and can never be a gate. What actually
 * did the work is [MIN_ARTICLE_WORDS]: every index page that should be refused is script-rendered
 * and yields almost nothing. The one that survives is a page of genuine prose blurbs, which is not a
 * terrible thing to be handed — and the reader shows the extracted title, so it is visibly not the
 * story that was tapped.
 *
 * Everything here is pure: HTML and a base URL in, a value out. No I/O, no Android, no clock.
 */
object Readability {

    // ---- The numbers, all in one place so they can be argued with -------------------------------

    /**
     * Below this, what was found is reported as [Outcome.THIN] rather than as an article.
     *
     * Chosen against real pages rather than picked: a genuine news story is rarely under 200 words,
     * while a consent wall, a login gate or a redirect stub is nearly always under 80. 120 sits in
     * the gap, and errs toward refusing — a wrongly-refused short article costs one tap to the
     * browser, where a wrongly-accepted wall puts "Accept all cookies" on screen as the news.
     */
    const val MIN_ARTICLE_WORDS = 120

    /**
     * Above this share of a container's text sitting inside links, it is chrome, not prose.
     *
     * The single most valuable rule here. A navigation block, a "most read" rail and a tag cloud are
     * all essentially 100% link text; running prose almost never exceeds a third even when heavily
     * cited. Applied to whole candidates.
     */
    const val MAX_LINK_DENSITY = 0.45

    /** The looser bar for a single paragraph inside an accepted body — a link-only line is dropped. */
    const val MAX_PARAGRAPH_LINK_DENSITY = 0.8

    /** A text run shorter than this is not counted as prose when scoring. Captions and labels. */
    const val MIN_PROSE_CHARS = 25

    /**
     * Descend into a child container while it still holds this share of its parent's score.
     *
     * Scores are cumulative, so the highest-scoring container is nearly always `<body>` itself. What
     * is wanted is the *tightest* wrapper that still holds essentially all the article, which is
     * what walking down this way finds.
     */
    const val DESCEND_RATIO = 0.85

    /** Hard ceiling on blocks emitted, so a pathological page cannot produce an unbounded list. */
    const val MAX_BLOCKS = 400

    // ---- Shape ----------------------------------------------------------------------------------

    /** Which rung produced the body. */
    enum class Strategy {
        /** The page said where its article was: `<article>`, `<main>`, or `[role=main]`. */
        SEMANTIC,

        /** Nothing said, so containers were scored by text density and the best one taken. */
        SCORED,

        /** Nothing usable was found. */
        NONE,
    }

    /** What happened, in the caller's terms. */
    enum class Outcome {
        /** A real article. [Extraction.blocks] is worth showing. */
        ARTICLE,

        /** Something was found, but too little to call an article. */
        THIN,

        /** Thin, and the page says why: a consent, subscription or login wall. */
        BLOCKED,

        /** This is not an article page at all — an index, a redirect stub, an interstitial. */
        NOT_ARTICLE,
    }

    /** What the page says about itself. Every field is optional because every field often is. */
    data class Meta(
        val title: String? = null,
        val byline: String? = null,
        val publishedIso: String? = null,
        val siteName: String? = null,
        val leadImage: String? = null,
        val canonicalUrl: String? = null,
    )

    /** A piece of the article, already typed so the renderer never sees markup. */
    sealed interface Block {
        data class Paragraph(val text: String) : Block

        /** [level] is 1..6 as the page stated it, clamped. */
        data class Heading(val text: String, val level: Int) : Block
        data class Quote(val text: String) : Block
        data class Bullets(val items: List<String>, val ordered: Boolean) : Block
        data class Image(val url: String, val caption: String? = null) : Block
        data class Code(val text: String) : Block
    }

    data class Extraction(
        val outcome: Outcome,
        val strategy: Strategy,
        val meta: Meta,
        val blocks: List<Block>,
        val wordCount: Int,
        /** Why it is not an article, in a sentence, when it is not. Null when [outcome] is ARTICLE. */
        val note: String? = null,
        /**
         * The body hit [MAX_BLOCKS] and what is here is the beginning of it.
         *
         * ⚠️ Carried out to the caller rather than left implicit. A cap that silently truncates
         * reads as "this is the whole thing" — a book chapter that simply stops has no way to say
         * it stopped, and this is exactly the "no silent caps" rule the rest of the app follows.
         */
        val truncated: Boolean = false,
    ) {
        val isArticle: Boolean get() = outcome == Outcome.ARTICLE
    }

    // ---- Entry point ----------------------------------------------------------------------------

    /**
     * Decimate [html] into an article.
     *
     * @param baseUrl the URL the HTML came from, used to make links and images absolute and to
     *   recognise the redirect interstitials this app's own feed produces.
     */
    fun extract(html: String, baseUrl: String): Extraction {
        if (html.isBlank()) {
            return Extraction(
                Outcome.NOT_ARTICLE, Strategy.NONE, Meta(), emptyList(), 0,
                "The page came back empty.",
            )
        }

        val interstitial = unreadableReason(baseUrl)
        val doc = runCatching { Jsoup.parse(html, baseUrl) }.getOrNull()
            ?: return Extraction(
                Outcome.NOT_ARTICLE, Strategy.NONE, Meta(), emptyList(), 0,
                "The page could not be parsed as HTML.",
            )

        // ⚠️ Metadata FIRST. It lives in <head>, in exactly the tags the strip pass removes, so
        // reading it afterwards would find nothing.
        val meta = readMeta(doc)

        val body = doc.body()
        strip(body)

        val (candidate, strategy) = chooseBody(body)
        val blocks = candidate?.let { collectBlocks(it) }.orEmpty()
        val words = blocks.sumOf { wordsIn(it) }

        if (candidate == null || words < MIN_ARTICLE_WORDS) {
            // ⚠️ WALL DETECTION RUNS ONLY ON A THIN RESULT, AND THE ORDERING IS THE WHOLE POINT.
            // Asking "does this page mention cookies or subscribing" of a page that yielded a full
            // article would flag every piece written *about* cookie law or newspaper subscriptions.
            // Asking it only of a page that yielded almost nothing cannot make that mistake: the
            // question is no longer "what is this about" but "why is there nothing here".
            val wall = if (interstitial == null) wallNote(body) else null
            val outcome = when {
                interstitial != null -> Outcome.NOT_ARTICLE
                wall != null -> Outcome.BLOCKED
                candidate == null -> Outcome.NOT_ARTICLE
                else -> Outcome.THIN
            }
            val note = interstitial
                ?: wall
                ?: if (candidate == null) {
                    "No article body was found on this page."
                } else {
                    "Only $words words came through — the page may need a browser to render."
                }
            return Extraction(outcome, strategy, meta, blocks, words, note)
        }

        return Extraction(
            Outcome.ARTICLE, strategy, meta, blocks, words,
            truncated = blocks.size >= MAX_BLOCKS,
        )
    }

    // ---- Metadata -------------------------------------------------------------------------------

    private fun readMeta(doc: org.jsoup.nodes.Document): Meta {
        fun prop(vararg names: String): String? {
            for (n in names) {
                val v = doc.selectFirst("meta[property=$n]")?.attr("content")
                    ?: doc.selectFirst("meta[name=$n]")?.attr("content")
                if (!v.isNullOrBlank()) return clean(v)
            }
            return null
        }

        val siteName = prop("og:site_name")

        val rawTitle = prop("og:title", "twitter:title")
            ?: doc.selectFirst("h1")?.text()?.takeIf { it.isNotBlank() }?.let(::clean)
            ?: doc.title().takeIf { it.isNotBlank() }?.let(::clean)
        val title = rawTitle?.let { stripSiteSuffix(it, siteName) }

        // ⚠️ `article:author` is an OpenGraph *profile URL* by specification, not a name, and real
        // publishers use it that way — the Associated Press returns
        // "https://apnews.com/author/mark-kennedy" here. Rendering that as the byline puts a URL
        // where a person's name belongs, so a value that fails [looksLikeAName] is passed over and
        // the next source is tried rather than being cleaned up into something plausible.
        val linkedData = doc.select("script[type=application/ld+json]").joinToString("\n") { it.data() }

        val byline = listOfNotNull(
            prop("author", "byl", "dc.creator", "article:author"),
            jsonLdName(linkedData, "author"),
            doc.selectFirst("[rel=author]")?.text()?.takeIf { it.isNotBlank() }?.let(::clean),
            doc.selectFirst("[itemprop=author]")?.text()?.takeIf { it.isNotBlank() }?.let(::clean),
        ).firstOrNull { looksLikeAName(it) }

        val published = prop(
            "article:published_time", "datePublished", "pubdate",
            "publish-date", "date", "dc.date",
        )
            ?: jsonLdValue(linkedData, "datePublished")
            ?: doc.selectFirst("time[datetime]")?.attr("datetime")?.takeIf { it.isNotBlank() }

        val image = doc.selectFirst("meta[property=og:image]")?.attr("abs:content")
            ?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("meta[name=twitter:image]")?.attr("abs:content")?.takeIf { it.isNotBlank() }

        val canonical = doc.selectFirst("link[rel=canonical]")?.attr("abs:href")?.takeIf { it.isNotBlank() }

        return Meta(
            title = title,
            byline = byline?.removePrefix("By ")?.removePrefix("by ")?.trim()?.takeIf { it.isNotBlank() },
            publishedIso = published,
            siteName = siteName,
            leadImage = image,
            canonicalUrl = canonical,
        )
    }

    /**
     * Read one string value out of a page's `application/ld+json`.
     *
     * ⚠️ **Deliberately a scanner, not a JSON parser.** This module carries no serialization
     * dependency by design, and the job is narrow enough not to need one: find a key, take the
     * quoted string after it. It is used only for metadata that is worth having and safe to miss —
     * never for anything the extraction depends on.
     */
    internal fun jsonLdValue(blob: String, key: String): String? {
        val at = blob.indexOf("\"$key\"")
        if (at < 0) return null
        val m = Regex("\"$key\"\\s*:\\s*\"([^\"]{1,200})\"").find(blob, at) ?: return null
        return clean(m.groupValues[1]).takeIf { it.isNotBlank() }
    }

    /**
     * Read a nested `name` out of a JSON-LD object or array value, e.g. `"author"`.
     *
     * ⚠️ This is where the Associated Press keeps the byline, and it is why the plain meta tags are
     * not enough: `article:author` is a *profile URL* by OpenGraph's own specification, so AP
     * returns "https://apnews.com/author/mark-kennedy" there and the writer's name only in
     * `"author":[{"@type":"Person",…,"name":"Mark Kennedy"}]`.
     *
     * The value's extent is found by matching brackets rather than by a fixed window, because a
     * window wide enough to clear AP's nested image URL and job title also reaches into the
     * `publisher` object that follows — which would attribute every story to the wire service.
     */
    internal fun jsonLdName(blob: String, key: String): String? {
        var at = blob.indexOf("\"$key\"")
        while (at >= 0) {
            val colon = blob.indexOf(':', at + key.length + 2)
            if (colon < 0) return null
            val start = blob.drop(colon + 1).indexOfFirst { !it.isWhitespace() }.let {
                if (it < 0) return null else colon + 1 + it
            }
            when (blob[start]) {
                '"' -> {
                    val end = blob.indexOf('"', start + 1)
                    if (end > start) {
                        clean(blob.substring(start + 1, end)).takeIf { it.isNotBlank() }
                            ?.let { return it }
                    }
                }
                '{', '[' -> {
                    val value = balanced(blob, start) ?: return null
                    Regex("\"name\"\\s*:\\s*\"([^\"]{1,200})\"").find(value)?.let {
                        return clean(it.groupValues[1]).takeIf { n -> n.isNotBlank() }
                    }
                }
            }
            at = blob.indexOf("\"$key\"", at + 1)
        }
        return null
    }

    /** The substring from [start] (a `{` or `[`) through its matching close, string-aware. */
    private fun balanced(s: String, start: Int): String? {
        val open = s[start]
        val close = if (open == '{') '}' else ']'
        var depth = 0
        var i = start
        var inString = false
        var escaped = false
        while (i < s.length) {
            val c = s[i]
            when {
                escaped -> escaped = false
                c == '\\' && inString -> escaped = true
                c == '"' -> inString = !inString
                inString -> Unit
                c == open -> depth++
                c == close -> {
                    depth--
                    if (depth == 0) return s.substring(start, i + 1)
                }
            }
            i++
        }
        return null
    }

    /**
     * Is this a person or an organisation, rather than a link or a slug?
     *
     * Deliberately permissive about *what* a name looks like — bylines are "Mark Kennedy", "Reuters
     * Staff", "Dr. A. N. Other", and in scripts this code cannot enumerate — and strict only about
     * the things that are definitely not one.
     */
    internal fun looksLikeAName(raw: String): Boolean {
        val v = raw.trim()
        if (v.length !in 2..120) return false
        if (v.contains("://") || v.startsWith("www.")) return false
        // A bare slug or handle: no spaces and punctuation doing the work of them.
        if (!v.contains(' ') && (v.contains('/') || v.contains('_') || v.count { it == '-' } > 1)) {
            return false
        }
        if (v.contains('@') && !v.contains(' ')) return false
        return v.any { it.isLetter() }
    }

    /**
     * Drop a trailing " | Site" or " - Site" when the page itself told us the site's name.
     *
     * ⚠️ Only when it matches [siteName]. Cutting at the last separator unconditionally is the
     * tempting version and it truncates real headlines — "Espresso - Wikipedia" would survive it,
     * but so would any story whose headline contains a dash.
     */
    internal fun stripSiteSuffix(title: String, siteName: String?): String {
        val site = siteName?.trim().orEmpty()
        if (site.isEmpty()) return title
        for (sep in listOf(" | ", " - ", " — ", " – ", " :: ")) {
            val suffix = sep + site
            // Leave at least a short word behind, so a page titled exactly after its own site does
            // not become an empty string. ⚠️ Measured against the smallest real case in the corpus:
            // "Espresso - Wikipedia" leaves 8 characters, and a guard of `suffix + 8` rejected it.
            if (title.endsWith(suffix, ignoreCase = true) && title.length - suffix.length >= 4) {
                return title.dropLast(suffix.length).trim()
            }
        }
        return title
    }

    // ---- Decimation -----------------------------------------------------------------------------

    /** Tags that never carry article prose, whatever the page thinks. */
    private val DROP_TAGS = listOf(
        "script", "style", "noscript", "template", "svg", "canvas", "iframe", "object", "embed",
        "form", "button", "input", "select", "textarea", "label", "nav", "aside", "dialog",
    )

    /**
     * Class and id words that mean chrome.
     *
     * ⚠️ **MATCHED AS WHOLE WORDS, NEVER AS SUBSTRINGS, AND THAT IS LOAD-BEARING.** The obvious
     * implementation asks whether the class attribute *contains* "ad", which also deletes anything
     * classed `head`, `header`, `gadget`, `download`, `shadow`, `breadcrumb` or `leaderboard` — and
     * `header` in particular can wrap the headline. Attributes are split on non-alphanumerics *and*
     * at camelCase boundaries, so `related-stories`, `relatedStories` and `related_stories` all
     * reduce to the same words and `leaderboard` reduces to one word that matches nothing.
     */
    private val DROP_WORDS = setOf(
        "nav", "navbar", "navigation", "menu", "sidebar", "breadcrumb", "breadcrumbs",
        "comment", "comments", "disqus", "share", "sharing", "social", "follow",
        "related", "recirculation", "recommended", "recommendation", "promo", "promoted",
        "newsletter", "subscribe", "subscription", "signup", "paywall", "meter",
        "ad", "ads", "adsense", "advert", "adverts", "advertisement", "advertising", "sponsor",
        "sponsored", "banner", "popup", "modal", "overlay", "cookie", "cookies", "consent", "gdpr",
        "footer", "masthead", "skip", "toolbar", "widget", "trending", "mostread", "popular",
        "tags", "taglist", "pagination", "pager", "hidden", "offscreen", "screenreader",
    )

    /** Words that mean "this is probably the article", used to break ties rather than to decide. */
    private val KEEP_WORDS = setOf(
        "article", "articlebody", "story", "storybody", "content", "postcontent", "entry",
        "entrycontent", "main", "body", "prose", "text", "post",
    )

    private fun strip(body: Element) {
        for (t in DROP_TAGS) body.select(t).forEach { it.remove() }
        body.select("[aria-hidden=true], [hidden], [role=navigation], [role=banner]")
            .forEach { it.remove() }
        // Elements the page itself labels as chrome.
        body.allElements.toList().forEach { el ->
            if (el === body) return@forEach
            if (el.parent() == null) return@forEach // already removed with an ancestor
            if (attrWords(el).any { it in DROP_WORDS }) el.remove()
        }
    }

    private fun attrWords(el: Element): Set<String> =
        splitWords(el.className()) + splitWords(el.id()) + splitWords(el.attr("data-testid"))

    /** Split an attribute into lowercase words on non-alphanumerics and camelCase boundaries. */
    internal fun splitWords(raw: String): Set<String> {
        if (raw.isBlank()) return emptySet()
        val spaced = StringBuilder()
        var prev = ' '
        for (ch in raw) {
            if (ch.isUpperCase() && prev.isLowerCase()) spaced.append(' ')
            spaced.append(ch)
            prev = ch
        }
        return spaced.toString()
            .split(Regex("[^A-Za-z0-9]+"))
            .filter { it.isNotBlank() }
            .map { it.lowercase() }
            .toSet()
    }

    private fun chooseBody(body: Element): Pair<Element?, Strategy> {
        // Rung 1 — the page said so. Several <article> elements is common on index pages, so the
        // one with the most prose wins, and it still has to clear the link-density bar.
        val declared = body.select("article, main, [role=main], [itemprop=articleBody]")
            .filter { linkDensity(it) <= MAX_LINK_DENSITY }
            .maxByOrNull { proseChars(it) }
        if (declared != null && proseChars(declared) >= MIN_PROSE_CHARS * 4) {
            return descend(declared) to Strategy.SEMANTIC
        }

        // Rung 2 — score every container and take the best, then tighten.
        val best = body.select("div, section, td, article, main")
            .plus(body)
            .maxByOrNull { score(it) }
            ?.takeIf { score(it) > 0.0 }
            ?: return null to Strategy.NONE
        return descend(best) to Strategy.SCORED
    }

    /**
     * Walk down while a single child still holds essentially all of the parent's score.
     *
     * Without this the answer is almost always `<body>`, because score is cumulative — every wrapper
     * scores at least as much as the thing it wraps. Requiring a *single* child to clear the bar is
     * what stops the descent at the point where the article stops being alone in its container.
     */
    private fun descend(from: Element): Element {
        var cur = from
        var guard = 0
        while (guard++ < 12) {
            val parentScore = score(cur)
            if (parentScore <= 0.0) return cur
            val next = cur.children().firstOrNull { score(it) >= parentScore * DESCEND_RATIO }
                ?: return cur
            // ⚠️ NEVER STEP INTO SOMETHING THAT IS ITSELF CONTENT. Those are the document's leaves,
            // not wrappers, and descending into one throws away everything beside it — an article
            // whose opening paragraph carries most of the words loses its subheadings, its list and
            // its pictures, and an LWN page whose `<main>` holds one table opens with
            // "Dist. ID Release Package Date Debian…", every cell run together. Both were real.
            if (next.tagName().lowercase() in OPAQUE_TO_DESCENT) return cur
            cur = next
        }
        return cur
    }

    /** The tags [emitElement] treats as content in their own right rather than as containers. */
    private val HANDLED_TAGS = setOf(
        "p", "h1", "h2", "h3", "h4", "h5", "h6", "blockquote", "ul", "ol", "pre", "figure", "img",
        "table",
    )

    /**
     * Tags [descend] must not step past.
     *
     * ⚠️ Derived from [HANDLED_TAGS] rather than listed again — they are the same fact ("this is
     * content, not a container"), and the first version stated it twice and immediately drifted:
     * `<table>` was in one and `<p>` in neither. The extras are the internals of those structures,
     * which are not content on their own but must never be landed on either.
     */
    private val OPAQUE_TO_DESCENT: Set<String> =
        HANDLED_TAGS + setOf("thead", "tbody", "tfoot", "tr", "li", "dl", "dt", "dd", "figcaption")

    private fun score(el: Element): Double {
        val prose = proseChars(el)
        if (prose < MIN_PROSE_CHARS) return 0.0
        val density = linkDensity(el)
        if (density > MAX_LINK_DENSITY) return 0.0
        val words = attrWords(el)
        val bonus = if (words.any { it in KEEP_WORDS }) 1.25 else 1.0
        val paragraphs = el.select("p").count { it.text().length >= MIN_PROSE_CHARS }
        return prose * (1.0 - density) * bonus * (1.0 + 0.05 * paragraphs.coerceAtMost(20))
    }

    /** Text length counting only runs long enough to be prose, so a wall of labels scores nothing. */
    private fun proseChars(el: Element): Int {
        val ps = el.select("p, blockquote, pre, li")
        if (ps.isEmpty()) return el.text().takeIf { it.length >= MIN_PROSE_CHARS }?.length ?: 0
        return ps.sumOf { p -> p.text().length.takeIf { it >= MIN_PROSE_CHARS } ?: 0 }
    }

    private fun linkDensity(el: Element): Double {
        val total = el.text().length
        if (total == 0) return 1.0
        val linked = el.select("a").sumOf { it.text().length }
        return (linked.toDouble() / total).coerceIn(0.0, 1.0)
    }

    // ---- Blocks ---------------------------------------------------------------------------------

    /**
     * Every tag [walk] handles specially — the test for "is this container a leaf".
     *
     * ⚠️ ONE LIST, because it is the same fact stated twice and the two drifted the moment they
     * were separate: `table` was in the `when` and missing from the leaf test, so a `<div>` wrapping
     * a table looked childless, and LWN's security-advisory grid arrived as the article's opening
     * paragraph — "Dist. ID Release Package Date Debian…" — every cell collapsed onto one line.
     */
    private const val BLOCK_CHILDREN =
        "p, h1, h2, h3, h4, h5, h6, blockquote, ul, ol, pre, figure, table, img, div, section"

    /**
     * ⚠️ [walk] reads an element's CHILDREN, so a root that is *itself* content is never seen as
     * such and yields nothing at all. [descend] makes that ordinary rather than exotic: an article
     * that is one long `<p>` inside one `<article>` descends onto the paragraph, and the reader gets
     * a blank page. Dispatching the root through the same handler as any other element is the
     * general fix — the first version special-cased `<table>` and left `<p>`, `<ul>` and `<figure>`
     * broken in exactly the same way.
     */
    private fun collectBlocks(root: Element): List<Block> {
        val out = mutableListOf<Block>()
        val seenImages = mutableSetOf<String>()
        if (root.tagName().lowercase() in HANDLED_TAGS) {
            emitElement(root, out, seenImages, depth = 0)
        } else {
            walk(root, out, seenImages, depth = 0)
        }
        return out
    }

    private fun walk(el: Element, out: MutableList<Block>, images: MutableSet<String>, depth: Int) {
        if (out.size >= MAX_BLOCKS || depth > 24) return
        for (child in el.children()) {
            if (out.size >= MAX_BLOCKS) return
            emitElement(child, out, images, depth)
        }
    }

    private fun emitElement(
        child: Element,
        out: MutableList<Block>,
        images: MutableSet<String>,
        depth: Int,
    ) {
        when (val tag = child.tagName().lowercase()) {
            "p" -> emitParagraph(child, out)
            "h1", "h2", "h3", "h4", "h5", "h6" -> {
                val text = clean(child.text())
                if (text.isNotBlank()) out += Block.Heading(text, tag.substring(1).toInt())
            }
            "blockquote" -> {
                val text = clean(child.text())
                if (text.length >= MIN_PROSE_CHARS) out += Block.Quote(text)
            }
            "ul", "ol" -> {
                val items = child.select("> li").map { clean(it.text()) }.filter { it.isNotBlank() }
                if (items.isNotEmpty()) out += Block.Bullets(items, ordered = tag == "ol")
            }
            "pre" -> {
                val text = child.wholeText().trimEnd()
                if (text.isNotBlank()) out += Block.Code(text)
            }
            "figure" -> {
                // Handled whole and not descended into, so the image is not emitted twice.
                // ⚠️ The FIRST `<img>` that yields a usable address, not simply the first one:
                // a lazy-loading page emits a placeholder `<img>` and then the real one beside
                // it, so taking `selectFirst` drops the picture entirely once the placeholder
                // is (correctly) rejected.
                val src = child.select("img").firstNotNullOfOrNull(::imageSrc)
                val caption = child.selectFirst("figcaption")?.text()?.let(::clean)
                    ?.takeIf { it.isNotBlank() }
                if (src != null && images.add(src)) out += Block.Image(src, caption)
            }
            "img" -> {
                val src = imageSrc(child)
                if (src != null && images.add(src)) out += Block.Image(src, null)
            }
            "table" -> emitTable(child, out)
            "br", "hr", "script", "style" -> Unit
            else -> {
                // A container with no block-level children is still prose — the very common
                // `<div>` used as a paragraph. Emit it rather than recursing into nothing.
                if (child.select(BLOCK_CHILDREN).isEmpty()) {
                    emitParagraph(child, out)
                } else {
                    walk(child, out, images, depth + 1)
                }
            }
        }
    }

    /**
     * A table, row by row.
     *
     * ⚠️ Without this a table falls to the generic branch, where `text()` collapses every cell into
     * one line and a security-advisory grid arrives as "Dist. ID Release Package Date Debian…" —
     * measured on a real LWN page, where it became the article's opening paragraph. One row per
     * bullet with a visible separator keeps the reading order that made the table make sense.
     */
    private fun emitTable(table: Element, out: MutableList<Block>) {
        val rows = table.select("tr").mapNotNull { tr ->
            val cells = tr.select("th, td").map { clean(it.text()) }.filter { it.isNotBlank() }
            if (cells.isEmpty()) null else cells.joinToString("  ·  ")
        }
        // A single-cell "table" is a layout wrapper, which was the commonest use of one for years.
        if (rows.size < 2) {
            rows.firstOrNull()?.takeIf { it.length >= MIN_PROSE_CHARS }
                ?.let { out += Block.Paragraph(it) }
            return
        }
        out += Block.Bullets(rows.take(60), ordered = false)
    }

    private fun emitParagraph(el: Element, out: MutableList<Block>) {
        val text = clean(el.text())
        if (text.length < MIN_PROSE_CHARS) return
        // A "paragraph" that is mostly link text is a rail that survived the strip pass.
        if (linkDensity(el) > MAX_PARAGRAPH_LINK_DENSITY) return
        out += Block.Paragraph(text)
    }

    /**
     * The real address of an image, past the placeholder the page shows first.
     *
     * ⚠️ **`src` IS TRIED LAST, WHICH IS THE OPPOSITE OF THE OBVIOUS ORDER.** On a lazy-loading page
     * — which is most of them — `src` holds a spacer and the real address is in `srcset` or a data
     * attribute. Reading `src` first put a grey rectangle at the top of every BBC article: the page
     * emits one `<img>` whose `src` is `grey-placeholder.png` and a second carrying the real
     * `srcset`. [looksLikePlaceholder] catches the same thing by filename for pages that use only
     * one `<img>`.
     */
    private fun imageSrc(img: Element): String? {
        val fromSet = largestInSrcSet(img.attr("srcset").ifBlank { img.attr("data-srcset") }, img.baseUri())
        val candidates = listOfNotNull(
            fromSet,
            img.attr("abs:data-src").takeIf { it.isNotBlank() },
            img.attr("abs:data-original").takeIf { it.isNotBlank() },
            img.attr("abs:data-lazy-src").takeIf { it.isNotBlank() },
            img.attr("abs:src").takeIf { it.isNotBlank() },
        )
        return candidates.firstOrNull { !it.startsWith("data:") && !looksLikePlaceholder(it) }
    }

    /** A `srcset` is "url 320w, url 640w" — take the widest, since the reader is full-bleed. */
    internal fun largestInSrcSet(srcset: String, baseUri: String): String? {
        if (srcset.isBlank()) return null
        var best: String? = null
        var bestWidth = -1
        for (part in srcset.split(',')) {
            val bits = part.trim().split(Regex("\\s+"))
            val url = bits.firstOrNull()?.takeIf { it.isNotBlank() } ?: continue
            val width = bits.getOrNull(1)?.removeSuffix("w")?.removeSuffix("x")?.toIntOrNull() ?: 0
            if (width >= bestWidth) {
                bestWidth = width
                best = url
            }
        }
        return best?.let { resolve(baseUri, it) }
    }

    /** Enough URL resolution for a `srcset` entry; jsoup's `abs:` only works on a whole attribute. */
    internal fun resolve(baseUri: String, url: String): String? = when {
        url.startsWith("http://") || url.startsWith("https://") -> url
        url.startsWith("//") -> (baseUri.substringBefore("://").ifBlank { "https" }) + ":" + url
        url.startsWith("/") -> {
            val root = baseUri.substringBefore("://") + "://" + hostFull(baseUri)
            if (hostFull(baseUri).isBlank()) null else root + url
        }
        else -> null
    }

    private fun hostFull(url: String): String =
        url.substringAfter("://", "").substringBefore('/').substringBefore('?')

    /**
     * A spacer, not a picture.
     *
     * Matched on the filename because that is where pages say it — `grey-placeholder.png`,
     * `blank.gif`, `spacer.png`, `1x1.png`. Bounded to the last path segment so a legitimate photo
     * filed under a directory containing one of these words is not thrown away.
     */
    internal fun looksLikePlaceholder(url: String): Boolean {
        val name = url.substringAfterLast('/').substringBefore('?').lowercase()
        return listOf("placeholder", "blank.", "spacer", "transparent.", "1x1.", "pixel.", "dummy")
            .any { it in name }
    }

    // ---- Verdicts -------------------------------------------------------------------------------

    /**
     * Why this URL can never yield an article, before a byte is fetched — or null if it might.
     *
     * Named specifically rather than guessed at: Google News RSS is where most of this app's stories
     * come from, and its `<link>` can never be read (see the class KDoc). Saying so precisely is
     * worth more than a generic failure, because the user can act on it — the browser resolves the
     * redirect perfectly well.
     *
     * ⚠️ **Public because the caller needs the same answer BEFORE deciding where a tap goes.** Most
     * of the news feed is Google links, so sending every tap to the reader would replace a browser
     * that works with a polite refusal. One definition, two consumers: the screen picks the
     * destination with it, and [extract] explains itself with it. Two copies of this rule would
     * drift the first time a shortener was added to one of them.
     */
    fun canRead(url: String): Boolean = unreadableReason(url) == null


    fun unreadableReason(baseUrl: String): String? {
        val host = hostOf(baseUrl) ?: return null
        return when {
            host == "news.google.com" ->
                "Google News links point at a redirect, not the article — open it in the browser."
            host.endsWith("t.co") || host == "bit.ly" ->
                "This is a shortened link, not the article itself."
            else -> null
        }
    }

    /** The host, without scheme, port, credentials or a leading `www.`. Public — callers label with it. */
    fun hostOf(url: String): String? {
        val afterScheme = url.substringAfter("://", "").ifEmpty { return null }
        val host = afterScheme.substringBefore('/').substringBefore('?').substringBefore('#')
            .substringAfterLast('@').substringBefore(':')
        return host.lowercase().removePrefix("www.").takeIf { it.isNotBlank() }
    }

    /**
     * Why a thin page is thin, when the page says so.
     *
     * ⚠️ Only ever called on a thin result — see the ordering note at the call site. The phrases are
     * matched against the whole stripped body because a wall is usually all that is left after the
     * strip pass, and they are deliberately specific: "cookies" alone would match an article about
     * baking.
     */
    private fun wallNote(body: Element): String? {
        val text = body.text().lowercase()
        val subscription = listOf(
            "subscribe to continue", "subscribe to read", "this article is for subscribers",
            "subscribers only", "become a subscriber", "start your subscription",
            "to continue reading", "continue reading this article",
        )
        val login = listOf(
            "sign in to read", "log in to read", "sign in to continue", "please sign in",
            "create a free account", "register to continue",
        )
        val consent = listOf(
            "accept all cookies", "we use cookies", "manage your cookie", "cookie preferences",
            "before you continue", "your privacy choices",
        )
        val robot = listOf(
            "enable javascript", "javascript is disabled", "are you a robot",
            "verify you are human", "access denied", "unusual traffic",
        )
        return when {
            subscription.any { it in text } -> "This article is behind a subscription."
            login.any { it in text } -> "This page wants you signed in."
            consent.any { it in text } -> "A cookie or consent wall is in the way."
            robot.any { it in text } -> "The site refused this request or needs scripting."
            else -> null
        }
    }

    // ---- Small helpers --------------------------------------------------------------------------

    /** Collapse whitespace and strip the non-breaking spaces and zero-widths pages are full of. */
    internal fun clean(raw: String): String =
        raw.replace(' ', ' ')
            .replace("​", "")
            .replace("﻿", "")
            .replace(Regex("\\s+"), " ")
            .trim()

    /** Whitespace tokens, the same count the knowledge base uses for a "full page". */
    internal fun wordsIn(block: Block): Int = when (block) {
        is Block.Paragraph -> tokens(block.text)
        is Block.Heading -> tokens(block.text)
        is Block.Quote -> tokens(block.text)
        is Block.Bullets -> block.items.sumOf { tokens(it) }
        is Block.Code -> tokens(block.text)
        is Block.Image -> block.caption?.let { tokens(it) } ?: 0
    }

    private fun tokens(s: String): Int = s.split(Regex("\\s+")).count { it.isNotBlank() }

    /** Plain text of an extraction, for the assistant and for search. */
    fun plainText(e: Extraction): String = buildString {
        for (b in e.blocks) {
            when (b) {
                is Block.Paragraph -> appendLine(b.text).appendLine()
                is Block.Heading -> appendLine(b.text).appendLine()
                is Block.Quote -> appendLine(b.text).appendLine()
                is Block.Bullets -> {
                    b.items.forEach { appendLine("- $it") }
                    appendLine()
                }
                is Block.Code -> appendLine(b.text).appendLine()
                is Block.Image -> b.caption?.let { appendLine(it).appendLine() }
            }
        }
    }.trim()
}
