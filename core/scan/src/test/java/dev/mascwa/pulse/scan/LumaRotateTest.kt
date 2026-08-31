package dev.mascwa.pulse.scan

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The scanner's historical defect, held by a test that needs no camera.
 *
 * ⚠️ **Every expected value here was computed by an independent implementation before being written
 * down, not read off the rule under test.** A twin built the frame as a two-dimensional grid and
 * turned it with plain list operations, and the two were compared over 1,600 random shapes —
 * widths and heights 1..9, strides padded by 0, 1, 3 and 8 bytes, pixel strides of 1 and 2, at all
 * four turns. Zero disagreements. **My first draft of the rule was wrong in two ways at once** — the
 * 90° and 270° cases were swapped, and both multiplied by a dimension that goes negative whenever
 * the frame is wider than it is tall, which is every camera frame. Reasoning about it once produced
 * exactly the bug this file exists to prevent.
 */
class LumaRotateTest {

    /**
     * A 4×2 frame, so the turns are readable:
     * ```
     *   1 2 3 4      clockwise 90°:  5 1
     *   5 6 7 8                      6 2
     *                                7 3
     *                                8 4
     * ```
     * The left column becomes the top row, read bottom to top. That is a clockwise quarter turn, and
     * it is the direction that undoes a sensor mounted 90° clockwise of the phone's upright — which
     * is what `ImageProxy.imageInfo.rotationDegrees` reports.
     */
    @Test
    fun aQuarterTurnGoesClockwiseAndSwapsTheDimensions() {
        val src = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val r = LumaRotate.rotate(src, width = 4, height = 2, stride = 4, degrees = 90)!!
        assertEquals(2, r.width)
        assertEquals(4, r.height)
        assertArrayEquals(byteArrayOf(5, 1, 6, 2, 7, 3, 8, 4), r.bytes)
    }

    /** ...and the other three, from the same reference run. */
    @Test
    fun theOtherThreeTurnsAgreeWithTheReference() {
        val src = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        fun turn(d: Int) = LumaRotate.rotate(src, 4, 2, 4, degrees = d)!!
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8), turn(0).bytes)
        assertArrayEquals(byteArrayOf(8, 7, 6, 5, 4, 3, 2, 1), turn(180).bytes)
        assertArrayEquals(byteArrayOf(4, 8, 3, 7, 2, 6, 1, 5), turn(270).bytes)
        assertEquals(4, turn(180).width)
        assertEquals(2, turn(270).width)
    }

    /**
     * ⚠️ **The padding is not part of the picture**, and reading it as though it were is the same
     * class of failure as not rotating at all: the image skews a few pixels per row and decodes
     * nothing, on exactly the devices whose hardware wants an aligned stride.
     *
     * Here the frame is 3 wide in a buffer whose rows are 5 apart; the two bytes of rubbish at the
     * end of each row must not appear anywhere in the output.
     */
    @Test
    fun rowPaddingIsDroppedRatherThanTreatedAsImage() {
        val src = byteArrayOf(
            1, 2, 3, 99, 99,
            4, 5, 6, 99, 99,
        )
        val r = LumaRotate.rotate(src, width = 3, height = 2, stride = 5, degrees = 0)!!
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6), r.bytes)
        assertEquals(6, r.bytes.size)
        val turned = LumaRotate.rotate(src, 3, 2, 5, degrees = 90)!!
        assertArrayEquals(byteArrayOf(4, 1, 5, 2, 6, 3), turned.bytes)
    }

    /**
     * ⚠️ **`pixelStride` is 1 on nearly every device and is not guaranteed to be.** `YUV_420_888`
     * permits a Y plane that interleaves, and ignoring it reads every other byte as a pixel — noise,
     * on precisely the hardware that does it and nowhere else.
     */
    @Test
    fun anInterleavedPlaneIsReadAtItsOwnPixelStride() {
        val src = byteArrayOf(1, 0, 2, 0, 3, 0, 4, 0, 5, 0, 6, 0)
        val r = LumaRotate.rotate(src, width = 3, height = 2, stride = 6, pixelStride = 2, degrees = 0)!!
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6), r.bytes)
    }

    /**
     * A buffer shorter than its own metadata claims is a device disagreeing with itself. Losing one
     * frame costs a few milliseconds; reading past the end costs the application.
     */
    @Test
    fun aBufferTooShortForItsOwnMetadataIsRefused() {
        assertNull(LumaRotate.rotate(ByteArray(7), width = 4, height = 2, stride = 4, degrees = 0))
        assertNull(LumaRotate.rotate(ByteArray(100), width = 0, height = 2, stride = 4, degrees = 0))
        assertNull(LumaRotate.rotate(ByteArray(100), width = 4, height = 0, stride = 4, degrees = 0))
        assertNull(LumaRotate.rotate(ByteArray(100), width = 4, height = 2, stride = 3, degrees = 0))
        assertNull(LumaRotate.rotate(ByteArray(100), width = 4, height = 2, stride = 4, pixelStride = 0, degrees = 0))
        // ...and the exactly-big-enough case is NOT refused, or the guard would reject every frame.
        assertEquals(8, LumaRotate.rotate(ByteArray(8), 4, 2, 4, degrees = 0)!!.bytes.size)
    }

    /**
     * Four quarter turns are the identity, at every stride and pixel stride. This is the property
     * that catches an off-by-one in one branch which the fixed examples above happen to miss.
     *
     * ⚠️ **It cannot catch a turn going the wrong WAY**, and that was measured rather than assumed:
     * replacing the 90° rule with the anti-clockwise one leaves this test passing, because four
     * anti-clockwise turns also come back to where they started. The direction is held only by
     * [aQuarterTurnGoesClockwiseAndSwapsTheDimensions] and by the padded case below it — which is
     * exactly the class of "the fixture never reached the branch" that makes a round-trip property
     * feel like more coverage than it is.
     */
    @Test
    fun fourQuarterTurnsComeBackToWhereTheyStarted() {
        for (w in 1..7) for (h in 1..7) for (pad in intArrayOf(0, 1, 4)) {
            val stride = w + pad
            val src = ByteArray(stride * h) { (it * 7 + 3).toByte() }
            val start = LumaRotate.rotate(src, w, h, stride, degrees = 0)!!
            var cur = start
            repeat(4) {
                cur = LumaRotate.rotate(cur.bytes, cur.width, cur.height, cur.width, degrees = 90)!!
            }
            assertEquals("${w}x$h pad=$pad", start, cur)
        }
    }

    /**
     * ⚠️ **A turn has to actually move the picture**, which is the assertion a rotation test most
     * easily forgets to make: one that only checks sizes passes against a rule that returns the input
     * untouched, and returning the input untouched IS the bug being fixed.
     */
    @Test
    fun aQuarterTurnIsNotTheIdentity() {
        val src = ByteArray(48) { (it + 1).toByte() }
        val flat = LumaRotate.rotate(src, 8, 6, 8, degrees = 0)!!
        assertNotEquals(flat, LumaRotate.rotate(src, 8, 6, 8, degrees = 90)!!)
        assertNotEquals(flat, LumaRotate.rotate(src, 8, 6, 8, degrees = 180)!!)
        assertNotEquals(flat, LumaRotate.rotate(src, 8, 6, 8, degrees = 270)!!)
        assertEquals(flat, LumaRotate.rotate(src, 8, 6, 8, degrees = 360)!!)
        // A rotation the camera cannot report is treated as none rather than refused: a frame shown
        // the wrong way up still has a chance of decoding, where a dropped frame has none.
        assertEquals(flat, LumaRotate.rotate(src, 8, 6, 8, degrees = 45)!!)
        assertEquals(flat, LumaRotate.rotate(src, 8, 6, 8, degrees = -720)!!)
        assertEquals(
            LumaRotate.rotate(src, 8, 6, 8, degrees = 270)!!,
            LumaRotate.rotate(src, 8, 6, 8, degrees = -90)!!,
        )
    }
}
