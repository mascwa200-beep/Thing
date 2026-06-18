package dev.mascwa.pulse.jarvis.agent

import android.content.Context
import dev.mascwa.pulse.core.network.HttpClient
import java.io.File

/**
 * Downloads a file from the web into the app's PRIVATE storage (`filesDir/downloads`). Size-capped.
 * Returns where it saved + type/size, and a short preview for text files. The file's CONTENTS are
 * untrusted data (wrapped in <untrusted>…</untrusted>) — never instructions, per the safety rules.
 */
class DownloadTool(
    private val appContext: Context,
    private val http: HttpClient,
) : JarvisTool {
    override val name = "download"
    override val usage =
        "download <https url> [| filename] — save a web file into the app's private storage; returns path, type, size (+ a text preview)"

    override suspend fun run(arg: String): String {
        val parts = arg.split("|", limit = 2).map { it.trim() }
        val url = parts.getOrNull(0).orEmpty()
        if (!url.startsWith("http")) return "Provide a full http(s) URL (optionally `url | filename`)."
        val rawName = parts.getOrNull(1)?.takeIf { it.isNotBlank() }
            ?: url.substringAfterLast('/').substringBefore('?').ifBlank { "download.bin" }
        val safe = rawName.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80).ifBlank { "download.bin" }
        return runCatching {
            val dir = File(appContext.filesDir, "downloads").apply { mkdirs() }
            val dest = File(dir, safe)
            val (bytes, type) = http.download(url, dest, MAX_BYTES)
            val size = if (bytes >= 1024 * 1024) "%.1f MB".format(bytes / 1048576.0) else "${bytes / 1024} KB"
            val looksText = (type ?: "").contains("text", true) ||
                (type ?: "").contains("json", true) ||
                TEXT_EXTS.any { safe.endsWith(it, true) }
            val preview = if (looksText) {
                runCatching { dest.readText().take(PREVIEW_CHARS) }.getOrNull()
                    ?.let { "\nPreview (untrusted data):\n<untrusted>$it</untrusted>" }.orEmpty()
            } else {
                ""
            }
            "Saved \"$safe\" ($size${type?.let { ", $it" }.orEmpty()}) to private app storage.$preview"
        }.getOrElse { "Download failed: ${it.message}" }
    }

    private companion object {
        const val MAX_BYTES = 50L * 1024 * 1024
        const val PREVIEW_CHARS = 600
        val TEXT_EXTS = listOf(".txt", ".json", ".csv", ".md", ".kt", ".java", ".py", ".xml", ".html", ".log", ".yaml", ".yml")
    }
}
