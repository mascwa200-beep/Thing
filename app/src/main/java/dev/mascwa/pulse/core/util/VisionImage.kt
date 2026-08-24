package dev.mascwa.pulse.core.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * Turning a picture on this device into something a vision model will accept.
 *
 * One rule, one place. Two surfaces send images to the same models — the console (a photograph, a
 * gallery pick, a rendered PDF page) and HEALTH's photograph-a-meal — and a second copy of the
 * downscale-and-encode step is how the two come to disagree about the size cap. That is a mistake
 * this project has corrected several times over with palettes; the cost of avoiding it here is one
 * small file.
 *
 * ⚠️ The long edge is capped because these go over the wire to a metered model, priced by image
 * area. A 12-megapixel photograph carries no more information about what is on a plate than a
 * 1024-pixel one does, and costs a great deal more to ask about.
 */
object VisionImage {

    /** The long edge, in pixels, that an image is reduced to before it is sent. */
    const val MAX_PX = 1024

    /** Read [uri], downscale it and JPEG-encode it as a `data:image/jpeg;base64,...` URL. */
    suspend fun encode(context: Context, uri: Uri): String? = withContext(Dispatchers.IO) {
        runCatching {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: return@runCatching null
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@runCatching null
            encodeBitmap(downscale(bmp))
        }.getOrNull()
    }

    /** Reduce [bmp] so its long edge is at most [MAX_PX]. Returned unchanged if already small. */
    fun downscale(bmp: Bitmap): Bitmap {
        val longest = maxOf(bmp.width, bmp.height)
        if (longest <= MAX_PX) return bmp
        val scale = MAX_PX.toFloat() / longest
        return Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true)
    }

    /** JPEG-encode [bmp] as a data URL, at the quality the vision path has always used. */
    fun encodeBitmap(bmp: Bitmap): String {
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
        return "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }
}
