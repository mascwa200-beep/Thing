package dev.mascwa.pulse.feature.dial

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * Invisible launch trampoline for the Reactor Deck widget. A StackView card taps here carrying the target
 * package; we start that app and finish at once. Going through a (foreground) activity sidesteps the
 * background-activity-launch limits a broadcast receiver would hit.
 */
class DialLaunchTrampolineActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pkg = intent?.getStringExtra(EXTRA_PKG)
        if (!pkg.isNullOrEmpty()) {
            runCatching {
                packageManager.getLaunchIntentForPackage(pkg)
                    ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ?.let { startActivity(it) }
            }
        }
        finish()
    }

    companion object {
        const val EXTRA_PKG = "dev.mascwa.pulse.dial.extra.pkg"
    }
}
