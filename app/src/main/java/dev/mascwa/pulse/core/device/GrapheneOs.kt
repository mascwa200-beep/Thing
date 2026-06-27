package dev.mascwa.pulse.core.device

import android.content.Context
import android.os.Build

/**
 * Best-effort GrapheneOS detection.
 *
 * There is no official "am I GrapheneOS" API — GrapheneOS deliberately keeps stock-looking build
 * fingerprints for app-compatibility and hardware attestation. The reliable signal is the presence of
 * GrapheneOS's own system apps (Camera / PDF Viewer / Apps / Vanadium), so we look for those (the app
 * already holds QUERY_ALL_PACKAGES) and report exactly which signals matched. A fingerprint/host hint is
 * a weak secondary signal. This is a heuristic, surfaced honestly in Settings — not a security boundary.
 */
object GrapheneOs {

    /** GrapheneOS system packages (the strongest signal that this is GrapheneOS). */
    private val SYSTEM_PACKAGES = listOf(
        "app.grapheneos.camera",
        "app.grapheneos.pdfviewer",
        "app.grapheneos.apps",
        "app.grapheneos.info",
        "app.grapheneos.gmscompat",
        "app.vanadium.browser",
        "org.grapheneos.camera",
    )

    data class Status(val isGraphene: Boolean, val signals: List<String>) {
        val summary: String
            get() = if (isGraphene) "GrapheneOS (${signals.size} signal${if (signals.size == 1) "" else "s"})"
            else "GrapheneOS not detected"
    }

    /** Whether GrapheneOS's optional **sandboxed Google Play** is set up (the gmscompat layer and/or the
     *  user-profile Play Services package). Informational — GrapheneOS runs Play as a normal sandboxed app. */
    fun hasSandboxedPlay(context: Context): Boolean {
        val pm = context.packageManager
        return listOf("app.grapheneos.gmscompat", "com.google.android.gms").any { pkg ->
            runCatching {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(pkg, 0); true
            }.getOrDefault(false)
        }
    }

    fun detect(context: Context): Status {
        val signals = mutableListOf<String>()
        val pm = context.packageManager
        for (pkg in SYSTEM_PACKAGES) {
            val present = runCatching {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(pkg, 0)
                true
            }.getOrDefault(false)
            if (present) signals += pkg.substringAfterLast('.')
        }
        // Weak secondary hint: most GrapheneOS builds mimic stock, but some leak the name here.
        val finger = (Build.FINGERPRINT + " " + Build.HOST + " " + Build.PRODUCT).lowercase()
        if (finger.contains("graphene")) signals += "fingerprint"
        return Status(isGraphene = signals.isNotEmpty(), signals = signals)
    }
}
