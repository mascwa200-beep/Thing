package dev.mascwa.pulse.scan

/**
 * Turning a camera's luminance plane the right way up.
 *
 * ⚠️ **This is the whole of the bug that made the scanner look broken, and it is arithmetic over a
 * byte array — no camera, no device, no emulator.** That is the only reason it lives in its own file
 * rather than inside the decoder: the thing that was wrong is the thing CI can now hold.
 *
 * **What was wrong.** An `ImageProxy` arrives in the *sensor's* orientation, and a phone's sensor is
 * mounted sideways: held upright, the frame the analyser sees is rotated 90°. `ImageProxy` reports
 * that as `imageInfo.rotationDegrees` and the scanner never read it, so ZXing was handed a picture
 * lying on its side. ZXing's one-dimensional readers scan **rows**, and a barcode a person carefully
 * lines up horizontally on screen is lying **vertically** in that buffer — perpendicular to every
 * line the decoder looks along. It cannot read it. It succeeds only when the phone or the packet
 * happens to be turned, which is exactly the intermittency that got reported as "it doesn't
 * understand what the barcode says".
 *
 * ⚠️ **And the retry that looks like it covers this is dead code.** `OneDReader.decode` will try a
 * rotated copy, but only `if (tryHarder && image.isRotateSupported())` — and
 * `PlanarYUVLuminanceSource` does not override `isRotateSupported`, so it inherits `false` from
 * `LuminanceSource`. Read out of the shipped 3.5.3 classes, not recalled. `TRY_HARDER` was buying
 * nothing on this path, which is why the code looked as though it had already handled the case.
 *
 * ⚠️ **The Y plane's rows are padded and the padding is not part of the picture.** CameraX aligns
 * each row to a hardware boundary, so `rowStride` is usually larger than `width` and the tail of
 * every row is whatever was in memory. Rotating the buffer as though it were `width` wide skews the
 * image by a few pixels per row — the same class of failure the rotation itself causes, and a very
 * plausible way to "fix" the rotation and still decode nothing. The output here is tightly packed,
 * so its stride IS its width and there is no second padding to get wrong.
 *
 * ⚠️ **`pixelStride` too.** It is 1 on nearly every device and is not guaranteed to be: some formats
 * interleave, and `YUV_420_888` explicitly permits a Y plane with a stride of 2. Ignoring it reads
 * every other byte as a pixel and the image comes out as noise on exactly the devices that do it.
 */
object LumaRotate {

    /**
     * A packed luminance image: [width] × [height] bytes, one per pixel, no padding.
     *
     * ⚠️ [stride] is deliberately not a field. That is the point of this type — everything past the
     * rotation deals in a picture with no padding in it, so nothing downstream can forget.
     */
    data class Luma(val bytes: ByteArray, val width: Int, val height: Int) {
        // Generated equals/hashCode on a data class holding an array compare by identity, which is
        // never what a caller means. Written out so a test can compare two rotations by value.
        override fun equals(other: Any?): Boolean =
            other is Luma && width == other.width && height == other.height &&
                bytes.contentEquals(other.bytes)

        override fun hashCode(): Int = (width * 31 + height) * 31 + bytes.contentHashCode()
    }

    /**
     * Read a padded plane out into a packed one, turned by [degrees] clockwise.
     *
     * @param src the raw plane, at least `stride * height` bytes for the 0° case
     * @param stride bytes between the starts of two rows — `Plane.rowStride`, NOT the width
     * @param pixelStride bytes between two horizontally adjacent pixels — `Plane.pixelStride`
     * @param degrees 0, 90, 180 or 270, clockwise; anything else is treated as 0
     * @return the packed image, its width and height swapped for the quarter turns
     */
    fun rotate(
        src: ByteArray,
        width: Int,
        height: Int,
        stride: Int,
        pixelStride: Int = 1,
        degrees: Int,
    ): Luma? {
        if (width <= 0 || height <= 0 || stride < width || pixelStride < 1) return null
        // The last byte the source rows reach. A short buffer is a device or a mock disagreeing with
        // its own metadata; returning null loses one frame, where reading past the end loses the app.
        val needed = (height - 1).toLong() * stride + (width - 1).toLong() * pixelStride + 1
        if (needed > src.size) return null

        val turn = ((degrees % 360) + 360) % 360
        val quarter = turn == 90 || turn == 270
        val outW = if (quarter) height else width
        val outH = if (quarter) width else height
        val out = ByteArray(outW * outH)

        for (y in 0 until height) {
            val row = y * stride
            for (x in 0 until width) {
                val v = src[row + x * pixelStride]
                // Where this pixel lands after the turn. Clockwise, so at 90° the LEFT column of the
                // source becomes the TOP row of the result — which is the direction that undoes a
                // sensor mounted 90° clockwise from the phone's upright.
                val i = when (turn) {
                    90 -> (height - 1 - y) + x * outW
                    180 -> (width - 1 - x) + (height - 1 - y) * outW
                    270 -> y + (width - 1 - x) * outW
                    else -> x + y * outW
                }
                out[i] = v
            }
        }
        return Luma(out, outW, outH)
    }
}
