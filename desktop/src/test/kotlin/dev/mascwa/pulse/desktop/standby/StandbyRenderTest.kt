package dev.mascwa.pulse.desktop.standby

import dev.mascwa.pulse.core.telemetry.Insight
import dev.mascwa.pulse.core.telemetry.MarketMood
import dev.mascwa.pulse.core.telemetry.InsightKind
import dev.mascwa.pulse.core.telemetry.Oracle
import dev.mascwa.pulse.core.telemetry.Urgency
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import javax.imageio.ImageIO

/**
 * The standby display really draws, at HUD size and at lock-screen size, from one composable.
 *
 * ⚠️ **This is the test that makes the lock-screen half of the feature verifiable at all.** Nothing
 * else about desktop rendering can be checked away from a real machine — Skiko cannot get a GL
 * context in CI or in a container. `renderComposeScene` uses a raster surface and asks for none, so
 * the exact code path that produces the Windows wallpaper runs here.
 *
 * If this ever fails on a native-library or headless error rather than an assertion, the *renderer*
 * has become unavailable and the lock-screen rung is what stops working. Say that rather than
 * deleting the test.
 */
class StandbyRenderTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun sample() = StandbyState(
        stardate = "STARDATE 26621.5",
        clock = "21:47",
        dateLine = "FRIDAY 21 AUGUST",
        placeName = "LONDON",
        insights = listOf(
            Insight(
                id = "heat",
                family = "heat",
                kind = InsightKind.PREPARATION,
                urgency = Urgency.IMPORTANT,
                title = "Leave ten minutes early — rain at 08:20",
                detail = "The drive is 22 minutes and it starts before you would normally go.",
                score = 0.9,
                actionRoute = "nav",
                sources = listOf("calendar", "weather"),
            ),
            Insight(
                id = "review",
                family = "study",
                kind = InsightKind.REMINDER,
                urgency = Urgency.AMBIENT,
                title = "Four reviews are due",
                detail = "",
                score = 0.4,
                actionRoute = "study",
                sources = listOf("study"),
            ),
        ),
        briefing = "One thing worth knowing.",
        temperature = "17°C",
        condition = "Light rain",
        feelsLike = "Feels like 15°C",
        weatherDetail = "↑19°C ↓11°C   ·   78% RH   ·   AQI 34",
        hourlyTemps = listOf(17.0, 16.4, 15.9, 15.1, 14.8, 15.3, 16.9, 18.2, 19.0, 18.4),
        mood = MarketMood.summarize(listOf(1.2, -0.4, 0.9, 2.1, -1.7, 0.3)),
        movers = listOf("Oil" to 2.14, "Gold" to -1.70, "Small Caps" to 0.93),
        headlines = listOf("REUTERS · Something happened", "AP · Something else happened"),
        reviewsDue = 4,
        studyStreakDays = 6,
        spaceWeather = "KP 3.0 · QUIET",
        issLine = "ISS 62° UP · SSW",
        nowPlaying = "Groove Salad — Something Ambient",
        machine = MachineVitals(cpuLoadPct = 12, memoryUsedPct = 47, diskUsedPct = 68, uptime = "3h 21m", build = "1.0.21"),
        freshness = "updated 4 minutes ago",
    )

    @Test
    fun `the display renders at HUD size`() {
        val png = StandbyRender.renderToPng(sample(), 460, 560)
        assertNotNull("the HUD did not render at all", png)
        assertPng(png!!, 460, 560)
    }

    @Test
    fun `the same composable renders at lock-screen size`() {
        // ⚠️ The point of the whole design: one layout, eight times the size, no second composable.
        val png = StandbyRender.renderToPng(sample(), 2560, 1440)
        assertNotNull("the wallpaper did not render at all", png)
        assertPng(png!!, 2560, 1440)
    }

    @Test
    fun `an empty display still renders rather than throwing`() {
        // A machine that has just started, knows where it is not, and has reached no feed. This is
        // the ordinary case on a cold boot, not an exception — and it must still produce a picture,
        // because the alternative is a lock screen that silently keeps yesterday's.
        val png = StandbyRender.renderToPng(StandbyState(), 800, 600)
        assertNotNull("a blank state must still draw the chrome", png)
        assertPng(png!!, 800, 600)
    }

    @Test
    fun `the picture actually has the console drawn on it, not just a background`() {
        // ⚠️ A blank surface encodes to a perfectly valid PNG, so "it produced a PNG" proves
        // nothing. The rail is a solid vertical band down the left edge in the lead insight's
        // urgency colour — if it is there, layout and drawing both ran.
        val png = StandbyRender.renderToPng(sample(), 600, 400)!!
        val image = ImageIO.read(png.inputStream())
        val railPixel = image.getRGB(6, 40) and 0xFFFFFF
        val expected = Oracle.urgencyArgb(Urgency.IMPORTANT).toInt() and 0xFFFFFF
        assertEquals("the rail is not drawn in the lead insight's urgency colour", expected, railPixel)
    }

    @Test
    fun `rendering to a file leaves no partial artefact behind`() {
        val target = File(temp.root, "sub/standby.png")
        val out = StandbyRender.renderToFile(sample(), 640, 400, target)
        assertNotNull("renderToFile produced nothing", out)
        assertTrue("the picture was not written", target.isFile && target.length() > 0)
        // The temp file is written beside the target and moved; a leftover means a failed move that
        // nothing noticed, and Windows would then accumulate one per refresh.
        assertTrue(
            "a .part file survived the render",
            temp.root.walkTopDown().none { it.name.endsWith(".part") },
        )
    }

    @Test
    fun `an impossible size is refused rather than crashing the pass`() {
        // Every caller is a background pass. A zero-sized display returns null so the reason can be
        // recorded; throwing would take the whole refresh down with it.
        assertNull(StandbyRender.renderToPng(sample(), 0, 400))
        assertNull(StandbyRender.renderToPng(sample(), 400, -1))
    }

    @Test
    fun `a bigger canvas shows MORE, not the same thing bigger`() {
        // ⚠️ The defect this replaced, and it was found by LOOKING at a render rather than by any
        // test: scale was linear in width, so a 1280x800 window drew the 460px HUD at 2.8x and
        // clipped two of the five panels clean off the bottom — every assertion still green.
        val hud = StandbyLayout.forCanvas(460, 560)
        val wide = StandbyLayout.forCanvas(1280, 800)
        val wall = StandbyLayout.forCanvas(2560, 1440)

        assertTrue("a wallpaper should show more insights than a HUD", wall.insights > hud.insights)
        assertTrue("a wallpaper should show more headlines", wall.headlines > hud.headlines)
        assertTrue("the panels a HUD gives up should be back", wide.showMarkets && wall.showWire)

        // Type still grows — just far more slowly than the canvas, which is the whole point.
        assertTrue(wall.scale > wide.scale && wide.scale > hud.scale)
        assertTrue("scale must grow sub-linearly with the canvas", wall.scale < hud.scale * 3f)
    }

    @Test
    fun `an absurd canvas is still legible and still bounded`() {
        // Dragged to nothing, and spanned across a wall of monitors.
        assertEquals(StandbyLayout.MIN_SCALE, StandbyLayout.forCanvas(1, 1).scale, 0.001f)
        assertEquals(StandbyLayout.MAX_SCALE, StandbyLayout.forCanvas(100_000, 100_000).scale, 0.001f)
        // ⚠️ Keyed on the SMALLER side. A display spanning two monitors is enormously wide and no
        // taller than one screen; scaling from width would make the type unreadable for its height.
        assertEquals(
            StandbyLayout.forCanvas(800, 800).scale,
            StandbyLayout.forCanvas(6000, 800).scale,
            0.001f,
        )
        // And a canvas too narrow for two columns stacks instead of ellipsising both.
        assertTrue(StandbyLayout.forCanvas(420, 900).twoColumn.not())
        assertTrue(StandbyLayout.forCanvas(1600, 900).twoColumn)
    }

    private fun assertPng(bytes: ByteArray, width: Int, height: Int) {
        val sig = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)
        assertEquals("not a PNG", sig.toList(), bytes.take(8))
        val image = ImageIO.read(bytes.inputStream())
        assertNotNull("the PNG does not decode", image)
        assertEquals(width, image.width)
        assertEquals(height, image.height)
    }
}
