package dev.mascwa.pulse.core.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast

/** Opens a maps app at the given coordinates. */
fun openMaps(context: Context, lat: Double, lon: Double, label: String? = null) {
    val q = if (label != null) "$lat,$lon(${Uri.encode(label)})" else "$lat,$lon"
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:$lat,$lon?q=$q"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (_: Exception) {
        openUrl(context, "https://maps.google.com/?q=$lat,$lon")
    }
}

/** Opens the dialer pre-filled with a number (no CALL permission needed). */
fun dialNumber(context: Context, number: String) {
    if (number.isBlank()) return
    val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(number)}"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}

/** Opens a URL in the user's browser (or any handler). Safe no-op on failure. */
fun openUrl(context: Context, url: String) {
    if (url.isBlank()) return
    try {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (_: Exception) {
        Toast.makeText(context, "No app can open this link", Toast.LENGTH_SHORT).show()
    }
}
