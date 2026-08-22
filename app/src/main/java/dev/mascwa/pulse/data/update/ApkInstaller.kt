package dev.mascwa.pulse.data.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import dev.mascwa.pulse.security.DevicePolicyController
import java.io.File

/**
 * Installs an APK through [PackageInstaller] rather than by handing the file to the system
 * installer, so an update can complete with no tap at all where the platform permits it.
 *
 * ⚠️ **The tap was not only a formality — it was the last human in the loop, and removing it is a
 * deliberate trade.** CI compiles this app and runs its unit tests; it cannot draw a screen. A build
 * can go green and still fail on launch, and it will now replace a working one with nobody watching,
 * on a phone whose only recovery is a cable. Two things stand in for the tap: the green gate
 * (`UpdatePolicy`, which refuses a build whose CI run is not green or cancelled-after-publishing),
 * and the one-at-a-time guard in `MainActivity` that stops a failing install being retried in a loop.
 *
 * ## The ladder, and why all three rungs exist
 *
 * There are two independent ways to reach a no-dialog install and **neither may be assumed**:
 *
 *  1. **Device owner.** This app is provisioned as one on the owner's device, so a commit made with
 *     [PackageManager.INSTALL_REASON_POLICY] is not shown a confirmation. This is the strong route
 *     and it needs no new permission.
 *  2. **[PackageInstaller.SessionParams.setRequireUserAction] with `USER_ACTION_NOT_REQUIRED`**, plus
 *     `UPDATE_PACKAGES_WITHOUT_USER_ACTION` in the manifest. ⚠️ The platform grants this only to the
 *     **installer of record** for the package being updated, and we are not — every install so far
 *     arrived through the system installer UI. So this rung *arms itself one update late*: the first
 *     session we commit makes this app the installer of record for the next one. It is the fallback
 *     for a device that is never provisioned, not the primary.
 *  3. **[PackageInstaller.STATUS_PENDING_USER_ACTION].** When neither of the above applies the
 *     session asks for a confirmation and hands back the intent that shows it. Launching that is
 *     exactly the behaviour this file replaced, so a device without device-owner is no worse off
 *     than before rather than silently broken.
 *
 * Requesting rung 2 is harmless when it does not apply: an ignored preference, not an error.
 *
 * ⚠️ **Android tears the process down when its own package is replaced**, so the caller decides
 * *when*, not this file. Committing while somebody is reading a page makes the app vanish
 * mid-sentence, which reads as a crash and is worse than the tap it removed. `MainActivity` commits
 * from `onStop`.
 */
object ApkInstaller {

    private const val TAG = "ApkInstaller"

    /** Broadcast action for the session's own result. Package-scoped; the receiver is not exported. */
    private const val ACTION_RESULT = "dev.mascwa.pulse.INSTALL_RESULT"

    /**
     * Stage [file] and commit it. Returns false only when the session could not be created or
     * written — a commit that then needs a confirmation still returns true, because the install is
     * genuinely under way and the receiver carries it the rest of the way.
     *
     * ⚠️ Nothing here waits for the outcome. On a successful self-update this process is killed
     * during [PackageInstaller.Session.commit], so any code written after it may simply never run.
     */
    fun install(context: Context, file: File): Boolean {
        if (!file.isFile || file.length() <= 0L) return false
        val installer = context.packageManager.packageInstaller
        var sessionId = -1
        return runCatching {
            val params = PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL,
            ).apply {
                setAppPackageName(context.packageName)
                // Rung 1. A device owner's commit is not confirmed; on any other device this is
                // recorded and otherwise ignored.
                if (DevicePolicyController(context).isDeviceOwner()) {
                    runCatching { setInstallReason(PackageManager.INSTALL_REASON_POLICY) }
                }
                // Rung 2. Honoured only for the installer of record, so this arms itself one
                // update after the first session this app commits.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    runCatching {
                        setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                    }
                }
            }

            sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                // The declared length lets the platform reserve space up front; a wrong one is
                // rejected outright, so it is read from the file rather than guessed.
                session.openWrite("lcars", 0, file.length()).use { out ->
                    file.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }
                session.commit(resultSender(context, sessionId))
            }
            true
        }.getOrElse { err ->
            Log.w(TAG, "install failed to start: ${err.javaClass.simpleName}")
            // An abandoned session frees the staged copy; leaving it would hold the APK's worth of
            // storage until the platform reaped it.
            if (sessionId >= 0) runCatching { installer.abandonSession(sessionId) }
            false
        }
    }

    private fun resultSender(context: Context, sessionId: Int): android.content.IntentSender {
        val intent = Intent(ACTION_RESULT).setPackage(context.packageName)
        // ⚠️ MUTABLE, and it must be: the platform completes this intent with the status extras,
        // and an immutable one would arrive carrying nothing — the same trap the feed widget's
        // pending-intent template had. The session id is the request code so two overlapping
        // installs cannot share one PendingIntent (`Intent.filterEquals` ignores extras).
        val pending = PendingIntent.getBroadcast(
            context, sessionId, intent,
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return pending.intentSender
    }

    /**
     * Carries the session the rest of the way.
     *
     * ⚠️ On a successful self-update this receiver is usually never reached — the process is gone
     * before the broadcast lands — so its real job is rung 3 and reporting a failure. Nothing here
     * may assume it runs.
     */
    class ResultReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_RESULT) return
            when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    // Rung 3: neither silent route applied, so show the confirmation. This is the
                    // behaviour the whole file replaced, kept so an unprovisioned device still works.
                    val confirm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(Intent.EXTRA_INTENT) as? Intent
                    }
                    confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { confirm?.let(context::startActivity) }
                }
                PackageInstaller.STATUS_SUCCESS -> Log.i(TAG, "update installed")
                else -> Log.w(
                    TAG,
                    "install refused: " +
                        (intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "no reason given"),
                )
            }
        }
    }
}
