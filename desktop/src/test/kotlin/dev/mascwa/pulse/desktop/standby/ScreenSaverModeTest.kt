package dev.mascwa.pulse.desktop.standby

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How Windows asks, and every form it has been known to ask in.
 *
 * ⚠️ Worth a test of its own because getting it wrong is silent: a saver that does not recognise
 * `/s` simply opens the console instead of going full screen, and a saver that mistakes
 * `--update-only` for a screensaver flag opens a window on a machine nobody is at. Neither is an
 * error anything would report.
 */
class ScreenSaverModeTest {

    @Test
    fun `the three conventions are recognised`() {
        assertEquals(ScreenSaver.Mode.FULL_SCREEN, ScreenSaver.modeOf(arrayOf("/s")))
        assertEquals(ScreenSaver.Mode.CONFIGURE, ScreenSaver.modeOf(arrayOf("/c")))
        assertEquals(ScreenSaver.Mode.PREVIEW, ScreenSaver.modeOf(arrayOf("/p", "1234")))
    }

    @Test
    fun `case and the dash form are both accepted`() {
        // Windows has passed upper case, and a user running it by hand will type a dash.
        assertEquals(ScreenSaver.Mode.FULL_SCREEN, ScreenSaver.modeOf(arrayOf("/S")))
        assertEquals(ScreenSaver.Mode.FULL_SCREEN, ScreenSaver.modeOf(arrayOf("-s")))
        assertEquals(ScreenSaver.Mode.CONFIGURE, ScreenSaver.modeOf(arrayOf("/C")))
    }

    @Test
    fun `the colon form of preview is understood`() {
        // ⚠️ Windows has passed BOTH `/p 1234` and `/p:1234` over the years, and a saver that only
        // understands one is one that mysteriously misbehaves on somebody's machine. `/c:1234`
        // likewise, which is how the settings dialog asks on some builds.
        assertEquals(ScreenSaver.Mode.PREVIEW, ScreenSaver.modeOf(arrayOf("/p:1234")))
        assertEquals(ScreenSaver.Mode.CONFIGURE, ScreenSaver.modeOf(arrayOf("/c:5678")))
    }

    @Test
    fun `an ordinary launch is not a screensaver`() {
        assertEquals(ScreenSaver.Mode.NONE, ScreenSaver.modeOf(emptyArray()))
        assertEquals(ScreenSaver.Mode.NONE, ScreenSaver.modeOf(arrayOf("")))
        // ⚠️ The one collision worth pinning. The hourly task launches this same executable with
        // `--update-only`; if that were read as a screensaver flag the update pass would open a
        // window instead of upgrading, on a machine nobody is looking at.
        assertEquals(
            ScreenSaver.Mode.NONE,
            ScreenSaver.modeOf(arrayOf(dev.mascwa.pulse.desktop.update.ScheduledUpdate.UPDATE_ONLY_FLAG)),
        )
    }

    @Test
    fun `only the first argument decides`() {
        // A screensaver flag is always argv[0]. Scanning the whole line would let a stray word
        // elsewhere put the app into a mode nobody asked for.
        assertEquals(ScreenSaver.Mode.NONE, ScreenSaver.modeOf(arrayOf("--something", "/s")))
    }
}
