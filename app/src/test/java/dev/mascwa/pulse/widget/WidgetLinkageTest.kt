package dev.mascwa.pulse.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The gate that stops the widget layer rotting again.
 *
 * ⚠️ **Every assertion here is a defect that actually shipped.** The widgets were last touched
 * functionally in July, before the app's rename, two palette rewrites, a wave of route deletions and
 * the notification rewrite — and none of it reached them, because nothing checked. Specifically:
 *
 *  - A widget rendered the literal text `J.A.R.V.I.S.` on the home screen **permanently**: its title
 *    was a view the renderer never wrote, so the layout's placeholder was what a person saw. Two
 *    separate rename commits claimed to have covered widget layouts and neither touched the file.
 *  - Every colour in every widget was a hardcoded hex from the retired NIGHTWIRE palette — sixteen
 *    values across seven variants of what should be five tokens, including the `positive`/`negative`
 *    pair that draws market direction.
 *  - Routes were raw string literals rather than `Routes` constants, so a rename would have broken
 *    the taps silently.
 *
 * Reads the source tree as files, in the shape of `BundledImagesTest`, and asserts registry
 * consistency in the shape of `NotifIdTest`. Runs under `:app:testDebugUnitTest`, which CI executes.
 *
 * It touches no Android class deliberately: everything here is text and XML, so it cannot fail for
 * an environmental reason and leave someone unable to tell a real break from a broken harness.
 */
class WidgetLinkageTest {

    private val layoutDir = File("src/main/res/layout")
    private val widgetSrc = File("src/main/java/dev/mascwa/pulse/widget")
    private val manifest = File("src/main/AndroidManifest.xml")

    /** Layouts a widget actually inflates at runtime. */
    private fun runtimeLayouts(): List<File> =
        layoutDir.listFiles { f -> f.name.startsWith("widget_") && f.name != "widget_feed_preview.xml" }
            ?.sortedBy { it.name }.orEmpty()

    private fun kotlinFiles(): List<File> =
        widgetSrc.listFiles { f -> f.extension == "kt" }?.sortedBy { it.name }.orEmpty()

    private fun kotlinText(): String = kotlinFiles().joinToString("\n") { it.readText() }

    private fun elements(f: File): List<Element> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(f)
        val out = mutableListOf<Element>()
        fun walk(e: Element) {
            out += e
            val kids = e.childNodes
            for (i in 0 until kids.length) (kids.item(i) as? Element)?.let(::walk)
        }
        walk(doc.documentElement)
        return out
    }

    private fun idOf(e: Element): String? =
        e.getAttribute("android:id").takeIf { it.isNotBlank() }?.substringAfterLast('/')

    // ---------------------------------------------------------------------------------------------

    @Test
    fun `the fixture finds the widget sources it is meant to police`() {
        // Guards the whole file: every assertion below is vacuously true against an empty list, so a
        // moved directory would turn this suite green while checking nothing at all.
        assertTrue("no widget layouts found — has res/layout moved?", runtimeLayouts().size >= 3)
        assertTrue("no widget sources found — has the package moved?", kotlinFiles().size >= 4)
        assertTrue("manifest not found at ${manifest.absolutePath}", manifest.isFile)
    }

    @Test
    fun `every view id the widgets write exists in a widget layout`() {
        val declared = runtimeLayouts().flatMap { f -> elements(f).mapNotNull { idOf(it) } }.toSet() +
            // The preview layout is not inflated at runtime but may still declare ids.
            (layoutDir.listFiles { f -> f.name == "widget_feed_preview.xml" }
                ?.flatMap { elements(it).mapNotNull { idOf(it) } }.orEmpty())

        val used = Regex("""R\.id\.(\w+)""").findAll(kotlinText()).map { it.groupValues[1] }.toSet()
        val missing = used - declared
        assertEquals("widget code references view ids that no widget layout declares: $missing", emptySet<String>(), missing)
    }

    @Test
    fun `no widget layout carries static text that no code can ever replace`() {
        // ⚠️ THE ONE THAT CATCHES THE ORIGINAL BUG, and the rule is precisely the shape of it:
        // a TextView carrying literal `android:text` whose id the widget code never mentions at all.
        // That is not a placeholder — it is what a person sees on their home screen, forever. The
        // widget this replaced had exactly that, and two rename passes walked straight past it.
        //
        // A TextView with NO static text is not checked: unwritten, it shows nothing, so it cannot
        // go stale. And "mentioned anywhere in the widget code" is deliberately broader than
        // "passed to setTextViewText" — the lock widget writes every line through a `line()` helper,
        // and a rule that could not see one level of indirection would force the helper out of
        // existence to satisfy the test rather than for any reason to do with the code.
        val mentioned = Regex("""R\.id\.(\w+)""").findAll(kotlinText()).map { it.groupValues[1] }.toSet()

        val offenders = mutableListOf<String>()
        runtimeLayouts().forEach { f ->
            elements(f).filter { it.tagName == "TextView" }.forEach { e ->
                val staticText = e.getAttribute("android:text").takeIf { it.isNotBlank() } ?: return@forEach
                when (val id = idOf(e)) {
                    null -> offenders +=
                        "${f.name}: a TextView reading \"$staticText\" has no id, so nothing can ever replace it"
                    !in mentioned -> offenders +=
                        "${f.name}: $id reads \"$staticText\" and is never referenced by any widget code"
                }
            }
        }
        assertEquals("permanent stale text in widget layouts: $offenders", emptyList<String>(), offenders)
    }

    @Test
    fun `manifest widget receivers and provider classes agree exactly`() {
        val manifestText = manifest.readText()
        val declared = Regex("""android:name="\.widget\.(\w+)"""")
            .findAll(manifestText).map { it.groupValues[1] }.toSet()

        val classes = Regex("""^\s*class\s+(\w+)\s*:\s*(AppWidgetProvider|RemoteViewsService)""", RegexOption.MULTILINE)
            .findAll(kotlinText()).map { it.groupValues[1] }.toSet()

        // Config activities are declared too but are not providers, so compare in one direction each:
        // every provider/service class must be declared, and every declared name must exist as a class.
        val undeclared = classes - declared
        assertEquals("widget classes with no manifest entry (dead code): $undeclared", emptySet<String>(), undeclared)

        val sourceNames = kotlinFiles().flatMap { f ->
            Regex("""^\s*class\s+(\w+)""", RegexOption.MULTILINE).findAll(f.readText()).map { it.groupValues[1] }
        }.toSet()
        val phantom = declared - sourceNames
        assertEquals("manifest names a .widget class that does not exist (crash on placement): $phantom", emptySet<String>(), phantom)
    }

    @Test
    fun `widgets deep-link through Routes constants, never string literals`() {
        // A literal compiles fine and breaks silently when a route is renamed — which is exactly how
        // four widgets ended up pointing at strings nobody was maintaining. A constant makes the
        // compiler responsible for it.
        val literal = Regex("""EXTRA_ROUTE\s*,\s*"""").findAll(kotlinText()).count()
        assertEquals("a widget passes a string literal as EXTRA_ROUTE; use a Routes constant", 0, literal)
    }

    @Test
    fun `every route a widget opens is still reachable from the app's own inventory`() {
        // Textual on purpose: the real inventories initialise Compose types, and a gate that can fail
        // for an environmental reason is worse than one that cannot.
        val nav = File("src/main/java/dev/mascwa/pulse/navigation")
        val inventory = (nav.listFiles { f -> f.name == "Directory.kt" || f.name == "Destinations.kt" }
            ?.joinToString("\n") { it.readText() }).orEmpty()
        assertTrue("route inventory not found under ${nav.absolutePath}", inventory.length > 100)

        val used = Regex("""Routes\.(\w+)""").findAll(kotlinText()).map { it.groupValues[1] }.toSet()
        assertTrue("no Routes constants found in the widget sources — has the deep link been dropped?", used.isNotEmpty())

        val orphaned = used.filterNot { inventory.contains("Routes.$it") }.toSet()
        assertEquals("widgets open routes the menu/top-destination inventory no longer lists: $orphaned", emptySet<String>(), orphaned)
    }

    @Test
    fun `no widget file hardcodes a colour`() {
        // ⚠️ What makes the palette convergence durable instead of a one-off correction. This project
        // has corrected a drifted duplicate palette five times; the widgets were the fifth and the
        // last. Colours belong in res/values/colors.xml, beside the notification tokens and under the
        // same rule — when the palette moves, it moves there in the same commit.
        val offenders = mutableListOf<String>()

        (runtimeLayouts() + layoutDir.listFiles { f -> f.name == "widget_feed_preview.xml" }.orEmpty() +
            listOfNotNull(File("src/main/res/drawable/widget_bg.xml").takeIf { it.isFile }))
            .forEach { f ->
                Regex("""#[0-9A-Fa-f]{6,8}""").findAll(f.readText()).forEach {
                    offenders += "${f.name}: ${it.value}"
                }
            }

        kotlinFiles().forEach { f ->
            // ARGB literals: the form the widgets used, e.g. 0xFF46F9A0.toInt().
            Regex("""0x[0-9A-Fa-f]{8}\s*\.toInt\(\)""").findAll(f.readText()).forEach {
                offenders += "${f.name}: ${it.value}"
            }
            Regex("""Color\.parseColor""").findAll(f.readText()).forEach {
                offenders += "${f.name}: Color.parseColor"
            }
        }

        assertEquals("hardcoded colours in widget files — use @color/ tokens: $offenders", emptyList<String>(), offenders)
    }
}
