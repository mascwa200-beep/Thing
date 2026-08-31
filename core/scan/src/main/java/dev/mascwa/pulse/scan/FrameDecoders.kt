package dev.mascwa.pulse.scan

import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageProxy
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import dev.mascwa.pulse.core.telemetry.BarcodeScan
import java.util.concurrent.TimeUnit

/**
 * One camera frame in, one product barcode or nothing out.
 *
 * ⚠️ **Whatever comes out has already been through `BarcodeScan.canonical`**, so a decoder is not
 * free to hand back the eight compressed digits of a UPC-E and leave the caller to notice. Both
 * implementations here go through the same call, which is the point of it living in the shared pure
 * core rather than in either of them.
 */
internal interface FrameDecoder {
    /** @return the code to look up, or null if this frame had nothing readable in it. */
    fun decode(proxy: ImageProxy): String?

    /** Release anything native. Called once, when the scanner is put down. */
    fun close() {}
}

/**
 * The decoder the scanner leads with.
 *
 * ⚠️ **It is a trained detector rather than a scanline algorithm, and that is the whole reason it is
 * worth twenty megabytes.** ZXing walks rows of pixels looking for a bar pattern, which works when a
 * barcode is flat, sharp, well lit and roughly level. A phone pointed at a curved can, a crinkly
 * bag, a barcode at an angle or a kitchen at dusk is none of those. This finds the symbol in the
 * frame first and reads it second, which is why a supermarket scanner app feels instant and a
 * scanline one feels like it is refusing to work.
 *
 * ⚠️ **The bundled model, not the Play Services one.** Both are published; the unbundled variant
 * downloads its model through Play Services, which the phone this repository targets does not have,
 * and would report itself unavailable for ever. The bundled artifact carries `libbarhopper_v3.so`
 * and asks nothing of the system. Its POM still names the unbundled coordinate, because that is
 * where the API classes live — a dependency tree showing `play-services-mlkit-barcode-scanning` is
 * expected and is not evidence that Play Services is in the runtime path.
 *
 * ⚠️ **[Tasks.await] blocks, deliberately, and it is safe only because of where it runs.** The
 * analyser has its own single-threaded executor and `STRATEGY_KEEP_ONLY_LATEST`, so blocking it is
 * exactly the backpressure that stops frames piling up: the camera simply drops what arrives while
 * this is working. **The one thing that would turn this into a deadlock is handing ML Kit that same
 * executor** through `BarcodeScannerOptions.Builder.setExecutor` — it would then be waiting on the
 * thread that is waiting on it. It is not given one, and this note is why.
 *
 * ⚠️ **The timeout is not decoration.** Without it a detector that never completes takes the
 * analyser thread with it and the viewfinder freezes with no error anywhere, which is the same shape
 * of failure as a frame that is never closed. A second is far longer than a real decode.
 */
internal class MlKitDecoder : FrameDecoder {

    private val client = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder()
            // The formats a retail product carries, and nothing else. Narrowing is not only speed:
            // left open, the decoder happily reads the QR code on a leaflet or the CODE-128 on a
            // shipping label, and every one of those is a decode the scanner has to recognise and
            // throw away.
            .setBarcodeFormats(
                Barcode.FORMAT_EAN_13,
                Barcode.FORMAT_EAN_8,
                Barcode.FORMAT_UPC_A,
                Barcode.FORMAT_UPC_E,
            )
            .build(),
    )

    /**
     * ⚠️ **The rotation is handed to the library rather than applied to the pixels**, which is the
     * defect in the previous scanner gone rather than worked around. `ImageProxy` arrives in the
     * sensor's orientation and reports how far that is from upright; a decoder never told is reading
     * a picture lying on its side. See [LumaRotate] for what that does to a scanline reader.
     */
    @OptIn(ExperimentalGetImage::class)
    override fun decode(proxy: ImageProxy): String? {
        val media = proxy.image ?: return null
        val input = runCatching {
            InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
        }.getOrNull() ?: return null
        val found = runCatching {
            Tasks.await(client.process(input), DECODE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        }.getOrNull().orEmpty()
        for (barcode in found) {
            val text = barcode.rawValue ?: continue
            val code = BarcodeScan.canonical(text, symbologyOf(barcode.format))
            if (code != null) return code
        }
        return null
    }

    override fun close() {
        runCatching { client.close() }
    }

    private companion object {
        const val DECODE_TIMEOUT_MS = 1_000L

        /**
         * ⚠️ **UPC-E has to survive this mapping or every small packet misses.** It is the one
         * symbology whose printed digits are not the product's number, and the only thing that can
         * tell it from an EAN-8 is this constant. Every value read out of the shipped
         * `barcode-scanning-common` classes with `javap`, not recalled.
         */
        fun symbologyOf(format: Int): BarcodeScan.Symbology = when (format) {
            Barcode.FORMAT_EAN_13 -> BarcodeScan.Symbology.EAN_13
            Barcode.FORMAT_EAN_8 -> BarcodeScan.Symbology.EAN_8
            Barcode.FORMAT_UPC_A -> BarcodeScan.Symbology.UPC_A
            Barcode.FORMAT_UPC_E -> BarcodeScan.Symbology.UPC_E
            else -> BarcodeScan.Symbology.OTHER
        }
    }
}

/**
 * The decoder behind it.
 *
 * ⚠️ **Not a redundant one, and not only insurance.** ML Kit's bundled model is strong evidence of
 * working without Play Services rather than proof — it is an obfuscated artifact whose runtime
 * behaviour on a de-Googled phone cannot be established from a build machine. If it ever reports
 * itself unavailable, this is the difference between a scanner that is worse and a scanner that is
 * dead. It is also cheap: the artifact was already in both applications.
 *
 * ⚠️ **The rotation is why this now works at all.** `PlanarYUVLuminanceSource` is handed a packed,
 * upright buffer by [LumaRotate]; before that it was handed the sensor's own sideways frame, and
 * ZXing's one-dimensional readers scan rows, so a barcode lined up horizontally on screen lay
 * vertically in the buffer, perpendicular to every line the decoder looks along.
 *
 * ⚠️ **`TRY_HARDER` was buying nothing on this path and the code read as though it were.**
 * `OneDReader.decode` retries a rotated copy only `if (tryHarder && image.isRotateSupported())`, and
 * `PlanarYUVLuminanceSource` never overrides `isRotateSupported`, so it inherits `false` from
 * `LuminanceSource`. Read out of the shipped 3.5.3 classes. The hint is kept because it does still
 * buy the harder *scanline* search, which is worth it on a frame that is already upright.
 */
internal class ZxingDecoder : FrameDecoder {

    private val reader = MultiFormatReader().apply {
        setHints(
            mapOf(
                DecodeHintType.POSSIBLE_FORMATS to PRODUCT_FORMATS,
                DecodeHintType.TRY_HARDER to true,
            ),
        )
    }

    override fun decode(proxy: ImageProxy): String? {
        val plane = proxy.planes.firstOrNull() ?: return null
        // ⚠️ A rewound DUPLICATE. ML Kit runs first and is handed the underlying `Image`, whose
        // planes can be the very same `ByteBuffer` objects; reading the original here would start
        // from wherever it left the position and take a frame that is missing its top.
        val buffer = plane.buffer.duplicate().apply { rewind() }
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        val luma = LumaRotate.rotate(
            src = bytes,
            width = proxy.width,
            height = proxy.height,
            stride = plane.rowStride,
            pixelStride = plane.pixelStride,
            degrees = proxy.imageInfo.rotationDegrees,
        ) ?: return null

        // ⚠️ The whole frame first, then the middle band. A barcode somebody has lined up is in the
        // middle, and cropping to it is what stops the neighbouring product on the shelf being read
        // instead — but a crop that misses costs the decode entirely, so it is the second attempt
        // rather than the only one.
        readFrom(luma, 0, 0, luma.width, luma.height)?.let { return it }
        val bandTop = luma.height / 4
        val bandHeight = luma.height - 2 * bandTop
        if (bandHeight <= 0) return null
        return readFrom(luma, 0, bandTop, luma.width, bandHeight)
    }

    /**
     * ⚠️ **The buffer is packed by now, so its stride IS its width.** That was the other half of the
     * old failure: `Plane.rowStride` is padded to a hardware boundary and is not the picture's width,
     * and reading it as though it were skews every row by a few pixels. Doing the rotation and
     * getting this wrong would look exactly like not doing the rotation at all.
     */
    private fun readFrom(luma: LumaRotate.Luma, left: Int, top: Int, w: Int, h: Int): String? {
        val source = PlanarYUVLuminanceSource(
            luma.bytes, luma.width, luma.height, left, top, w, h, false,
        )
        val result = runCatching {
            reader.decodeWithState(BinaryBitmap(HybridBinarizer(source)))
        }.getOrNull() ?: return null
        return BarcodeScan.canonical(result.text.orEmpty(), symbologyOf(result.barcodeFormat))
    }

    private companion object {
        val PRODUCT_FORMATS = listOf(
            BarcodeFormat.EAN_13,
            BarcodeFormat.EAN_8,
            BarcodeFormat.UPC_A,
            BarcodeFormat.UPC_E,
            // ⚠️ **ITF is gone, and dropping it is a fix rather than a narrowing.** It is
            // variable-length and carries no check digit that ZXing enforces, so with TRY_HARDER on
            // it will read a run of bars off almost anything — a folded label, a shelf edge, the
            // stripe on a packet — and hand back a plausible fourteen digits. Those confirm, look
            // like a scan that worked, and find nothing. A retail carton code is not what a person
            // holding a phone in a kitchen is pointing at.
        )

        fun symbologyOf(format: BarcodeFormat): BarcodeScan.Symbology = when (format) {
            BarcodeFormat.EAN_13 -> BarcodeScan.Symbology.EAN_13
            BarcodeFormat.EAN_8 -> BarcodeScan.Symbology.EAN_8
            BarcodeFormat.UPC_A -> BarcodeScan.Symbology.UPC_A
            BarcodeFormat.UPC_E -> BarcodeScan.Symbology.UPC_E
            else -> BarcodeScan.Symbology.OTHER
        }
    }
}
