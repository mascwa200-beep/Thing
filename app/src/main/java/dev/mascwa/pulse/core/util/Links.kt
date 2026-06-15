package dev.mascwa.pulse.core.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast

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
