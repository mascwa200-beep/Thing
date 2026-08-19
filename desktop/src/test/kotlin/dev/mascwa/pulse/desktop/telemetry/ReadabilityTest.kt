// MIRROR OF core/telemetry/src/test/java/dev/mascwa/pulse/core/telemetry/ReadabilityTest.kt — regenerate with tools/mirror_desktop_cores.py; MirrorDriftTest holds it
package dev.mascwa.pulse.desktop.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the DOM decimator.
 *
 * ⚠️ These are the *rules*. What proves the decimator actually works is a run over real fetched
 * pages — see `scratchpad/reader/`, which extracts real Associated Press, BBC, Wikipedia, LWN,
 * MDN and Project Gutenberg pages and checks the verdict against what each page really is. Every
 * defect worth having found this session was found there and not here: the byline that was a URL,
 * the table that arrived as one run-on paragraph, the grey placeholder standing in for the
 * photograph. Fixtures cannot find those, because a fixture is written by the same person who
 * wrote the rule.
 *
 * Fixture sizes are DERIVED from the shipped constants rather than guessed, so a change to
 * [Readability.MIN_ARTICLE_WORDS] cannot silently stop these tests from reaching the branch they
 * exist to cover.
 */
class ReadabilityTest {

    // Enough words to clear the article bar with room to spare, built rather than eyeballed.
    private val longSentence = "The committee met on Tuesday and agreed the revised terms without objection. "
    private fun prose(words: Int): String {
        val per = longSentence.trim().split(" ").size
        return longSentence.repeat(words / per + 1)
    }

    private fun page(body: String, head: String = ""): String =
        "<html><head>$head</head><body>$body</body></html>"

    // ---- The word bar ---------------------------------------------------------------------------

    @Test
    fun `a page with real prose is an article`() {
        val html = page("<article><p>${prose(200)}</p></article>")
        val e = Readability.extract(html, "https://example.com/story")
        assertEquals(Readability.Outcome.ARTICLE, e.outcome)
        assertEquals(Readability.Strategy.SEMANTIC, e.strategy)
        assertTrue("expected over the bar, got ${e.wordCount}", e.wordCount >= Readability.MIN_ARTICLE_WORDS)
        assertNull(e.note)
    }

    @Test
    fun `a page with almost no prose is thin, not an article`() {
        val html = page("<article><p>${prose(20)}</p></article>")
        val e = Readability.extract(html, "https://example.com/story")
        assertEquals(Readability.Outcome.THIN, e.outcome)
        assertNotNull(e.note)
    }

    @Test
    fun `an empty page is not an article`() {
        val e = Readability.extract("", "https://example.com/story")
        assertEquals(Readability.Outcome.NOT_ARTICLE, e.outcome)
        assertEquals(Readability.Strategy.NONE, e.strategy)
    }

    // ---- Link density ---------------------------------------------------------------------------

    @Test
    fun `a block that is mostly links is chrome, however much text it holds`() {
        // Every "paragraph" is a link, which is what a navigation rail or a most-read rail is.
        val nav = (1..40).joinToString("") {
            "<p><a href='/x$it'>Some reasonably long headline number $it about something</a></p>"
        }
        val e = Readability.extract(page("<div>$nav</div>"), "https://example.com/")
        assertFalse("a wall of links must not read as an article", e.isArticle)
    }

    @Test
    fun `a paragraph of pure link text is dropped from an accepted body`() {
        val html = page(
            "<article><p>${prose(200)}</p>" +
                "<p><a href='/a'>Read more of our coverage of this developing story here</a></p></article>",
        )
        val e = Readability.extract(html, "https://example.com/story")
        assertTrue(e.isArticle)
        assertFalse(
            "a link-only line survived",
            Readability.plainText(e).contains("Read more of our coverage"),
        )
    }

    // ---- The chrome word list -------------------------------------------------------------------

    @Test
    fun `attribute words split on separators and camelCase`() {
        assertEquals(setOf("related", "stories"), Readability.splitWords("related-stories"))
        assertEquals(setOf("related", "stories"), Readability.splitWords("relatedStories"))
        assertEquals(setOf("related", "stories"), Readability.splitWords("related_stories"))
    }

    @Test
    fun `words that merely contain a chrome word are not chrome`() {
        // ⚠️ THE TRAP. Asking whether a class *contains* "ad" also deletes anything classed header,
        // gadget, download, shadow, breadcrumb or leaderboard — and `header` can wrap the headline.
        // Each of these must reduce to words that are not in the drop list.
        for (name in listOf("header", "gadget", "download", "shadow", "leaderboard", "headline")) {
            val html = page("<div class='$name'><p>${prose(200)}</p></div>")
            val e = Readability.extract(html, "https://example.com/story")
            assertTrue("a div classed '$name' was deleted as chrome", e.isArticle)
        }
    }

    @Test
    fun `a block the page labels as chrome is removed`() {
        val html = page(
            "<article><p>${prose(200)}</p>" +
                "<div class='newsletter-signup'><p>${prose(60)} Sign up for our newsletter today.</p></div>" +
                "</article>",
        )
        val e = Readability.extract(html, "https://example.com/story")
        assertTrue(e.isArticle)
        assertFalse(
            "a newsletter block survived",
            Readability.plainText(e).contains("Sign up for our newsletter"),
        )
    }

    // ---- Metadata -------------------------------------------------------------------------------

    @Test
    fun `a byline that is a profile URL is passed over, not shown`() {
        // ⚠️ Real behaviour, not hypothetical: `article:author` is a profile URL by OpenGraph's own
        // specification and the Associated Press returns exactly this shape.
        val head = "<meta property='article:author' content='https://apnews.com/author/mark-kennedy'>"
        val e = Readability.extract(page("<article><p>${prose(200)}</p></article>", head), "https://ap.com/a")
        assertNull("a URL was rendered as the byline", e.meta.byline)
    }

    @Test
    fun `the byline is read from linked data when the meta tags only have a URL`() {
        val head = """
            <meta property='article:author' content='https://apnews.com/author/mark-kennedy'>
            <script type="application/ld+json">
            {"@type":"NewsArticle",
             "author":[{"@type":"Person","jobTitle":"Entertainment writer","name":"Mark Kennedy",
                        "url":"https://apnews.com/author/mark-kennedy"}],
             "publisher":{"@type":"Organization","name":"The Associated Press"}}
            </script>
        """.trimIndent()
        val e = Readability.extract(page("<article><p>${prose(200)}</p></article>", head), "https://ap.com/a")
        assertEquals("Mark Kennedy", e.meta.byline)
    }

    @Test
    fun `linked data reads the author's own name and never the publisher's`() {
        // ⚠️ THE RULE THE BRACKET MATCHING EXISTS FOR. A fixed search window wide enough to clear
        // AP's nested job title and image URL also reaches into `publisher`, which would attribute
        // every story to the wire service. Here the author object deliberately has no `name` at
        // all, so any implementation that keeps scanning past it returns "Reuters".
        val blob = """
            {"author":[{"@type":"Person","url":"https://x.example/a","sameAs":"https://y.example/a"}],
             "publisher":{"@type":"Organization","name":"Reuters"}}
        """.trimIndent()
        assertNull(Readability.jsonLdName(blob, "author"))
        assertEquals("Reuters", Readability.jsonLdName(blob, "publisher"))
    }

    @Test
    fun `a name is a name and a link is not`() {
        assertTrue(Readability.looksLikeAName("Mark Kennedy"))
        assertTrue(Readability.looksLikeAName("BBC News"))
        assertTrue(Readability.looksLikeAName("Dr. A. N. Other"))
        assertFalse(Readability.looksLikeAName("https://apnews.com/author/mark-kennedy"))
        assertFalse(Readability.looksLikeAName("www.example.com"))
        assertFalse(Readability.looksLikeAName("mark-kennedy-writer"))
    }

    @Test
    fun `a trailing site name is dropped only when the page told us the site name`() {
        assertEquals("Espresso", Readability.stripSiteSuffix("Espresso - Wikipedia", "Wikipedia"))
        assertEquals("Espresso", Readability.stripSiteSuffix("Espresso | Wikipedia", "Wikipedia"))
        // ⚠️ Cutting at the last separator unconditionally truncates real headlines. Without a
        // declared site name, nothing is cut.
        assertEquals(
            "Ceasefire talks stall - what happens next",
            Readability.stripSiteSuffix("Ceasefire talks stall - what happens next", null),
        )
        assertEquals(
            "Ceasefire talks stall - what happens next",
            Readability.stripSiteSuffix("Ceasefire talks stall - what happens next", "The Guardian"),
        )
    }

    // ---- Images ---------------------------------------------------------------------------------

    @Test
    fun `the widest srcset candidate wins and relative entries resolve`() {
        val set = "/img/small.jpg 320w, /img/big.jpg 1536w, /img/mid.jpg 800w"
        assertEquals(
            "https://www.bbc.com/img/big.jpg",
            Readability.largestInSrcSet(set, "https://www.bbc.com/news/articles/abc"),
        )
    }

    @Test
    fun `a placeholder is recognised by its filename, not by its path`() {
        assertTrue(Readability.looksLikePlaceholder("https://x.example/web/grey-placeholder.png"))
        assertTrue(Readability.looksLikePlaceholder("https://x.example/blank.gif"))
        // ⚠️ Bounded to the last segment: a real photograph filed under such a directory stays.
        assertFalse(Readability.looksLikePlaceholder("https://x.example/placeholder/real-photo.jpg"))
    }

    @Test
    fun `a figure takes the first image that has a real address`() {
        // The lazy-loading shape: a placeholder img, then the real one.
        val html = page(
            "<article><p>${prose(200)}</p><figure>" +
                "<img src='https://x.example/grey-placeholder.png'>" +
                "<img srcset='https://x.example/real.jpg 1536w'>" +
                "<figcaption>The band in 1979</figcaption></figure></article>",
        )
        val e = Readability.extract(html, "https://x.example/story")
        val img = e.blocks.filterIsInstance<Readability.Block.Image>().firstOrNull()
        assertNotNull("the picture was dropped with the placeholder", img)
        assertEquals("https://x.example/real.jpg", img!!.url)
        assertEquals("The band in 1979", img.caption)
    }

    // ---- Structure ------------------------------------------------------------------------------

    @Test
    fun `a table becomes rows, not one collapsed line`() {
        // ⚠️ Measured on a real LWN page, where the article opened with
        // "Dist. ID Release Package Date Debian…" — every cell of a security grid run together.
        val rows = (1..30).joinToString("") {
            "<tr><td>AlmaLinux</td><td>ALSA-2026:$it</td><td>9</td><td>package $it</td></tr>"
        }
        val html = page("<main><table><tr><th>Dist.</th><th>ID</th><th>Release</th><th>Package</th></tr>$rows</table></main>")
        val e = Readability.extract(html, "https://lwn.net/Articles/1/")
        assertTrue(e.isArticle)
        val bullets = e.blocks.filterIsInstance<Readability.Block.Bullets>()
        assertTrue("the table did not become rows", bullets.isNotEmpty())
        assertTrue("rows were not kept separate", bullets.first().items.size > 10)
        assertTrue(
            "a row lost its cell boundaries",
            bullets.first().items.any { it.contains("·") },
        )
    }

    @Test
    fun `headings, quotes and lists keep their kind`() {
        val html = page(
            "<article><p>${prose(200)}</p>" +
                "<h2>What happens next</h2>" +
                "<blockquote>We will not comment while the inquiry is open, the spokesman said.</blockquote>" +
                "<ul><li>The first thing that was agreed</li><li>The second thing that was agreed</li></ul>" +
                "</article>",
        )
        val e = Readability.extract(html, "https://example.com/story")
        // Counts derived from the fixture above — one <h2>, one <blockquote>, one <ul> of two <li>.
        val headings = e.blocks.filterIsInstance<Readability.Block.Heading>()
        assertEquals(1, headings.size)
        assertEquals(2, headings.single().level)
        assertEquals("What happens next", headings.single().text)
        assertEquals(1, e.blocks.filterIsInstance<Readability.Block.Quote>().size)
        val bullets = e.blocks.filterIsInstance<Readability.Block.Bullets>().single()
        assertEquals(2, bullets.items.size)
        assertFalse(bullets.ordered)
    }

    @Test
    fun `a body longer than the cap says so rather than stopping silently`() {
        val paras = (1..(Readability.MAX_BLOCKS + 50)).joinToString("") { "<p>${prose(30)}</p>" }
        val e = Readability.extract(page("<article>$paras</article>"), "https://example.com/book")
        assertTrue(e.isArticle)
        assertTrue("the cap truncated without saying so", e.truncated)
        assertEquals(Readability.MAX_BLOCKS, e.blocks.size)
    }

    // ---- Verdicts -------------------------------------------------------------------------------

    @Test
    fun `a Google News link is named as a redirect rather than half-read`() {
        val e = Readability.extract(
            page("<main><p>Redirecting</p></main>"),
            "https://news.google.com/rss/articles/CBMiabc",
        )
        assertEquals(Readability.Outcome.NOT_ARTICLE, e.outcome)
        assertTrue(e.note!!.contains("redirect"))
    }

    @Test
    fun `a thin page behind a subscription says so`() {
        val html = page("<article><p>Subscribe to continue reading this article and get full access.</p></article>")
        val e = Readability.extract(html, "https://paper.example/story")
        assertEquals(Readability.Outcome.BLOCKED, e.outcome)
        assertTrue(e.note!!.contains("subscription"))
    }

    @Test
    fun `an article ABOUT cookies is not mistaken for a cookie wall`() {
        // ⚠️ THE ORDERING RULE. Wall detection runs only on a thin result. Asked of a page that
        // yielded a full article, "does this mention cookies" flags every piece written about
        // cookie law — so the question is never asked there.
        val html = page(
            "<article><p>${prose(200)} We use cookies to make this work, the regulator said, " +
                "and readers are asked to accept all cookies before they can continue reading.</p></article>",
        )
        val e = Readability.extract(html, "https://example.com/cookie-law")
        assertEquals(Readability.Outcome.ARTICLE, e.outcome)
        assertNull(e.note)
    }

    @Test
    fun `hosts are read without their scheme, port or leading www`() {
        assertEquals("bbc.com", Readability.hostOf("https://www.bbc.com/news/articles/x?y=1"))
        assertEquals("news.google.com", Readability.hostOf("https://news.google.com/rss/articles/CBMi"))
        assertNull(Readability.hostOf("not a url"))
    }

    // ---- Plain text -----------------------------------------------------------------------------

    @Test
    fun `plain text carries every kind of block`() {
        val html = page(
            "<article><h2>Heading here</h2><p>${prose(200)}</p>" +
                "<ul><li>A bullet worth keeping</li></ul></article>",
        )
        val text = Readability.plainText(Readability.extract(html, "https://example.com/story"))
        assertTrue(text.contains("Heading here"))
        assertTrue(text.contains("- A bullet worth keeping"))
    }
}
