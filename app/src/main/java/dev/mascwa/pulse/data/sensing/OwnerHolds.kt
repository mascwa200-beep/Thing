package dev.mascwa.pulse.data.sensing

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.telecom.TelecomManager
import androidx.core.content.getSystemService
import dev.mascwa.pulse.core.telemetry.AmbientAction
import dev.mascwa.pulse.core.telemetry.AppSuspension
import dev.mascwa.pulse.security.DevicePolicyController

/**
 * The OWNER tier's one effect: pausing the apps that are not a way back out.
 *
 * ⚠️ **One, and the four others were removed rather than implemented** — see the note where they
 * used to be declared in `AmbientAction`. This is the most invasive thing in the whole design and it
 * earns its place by being the one with a real reason: a phone that stops offering you its apps
 * while you are driving is a thing people actively want, and the other four had no situation asking
 * for them.
 *
 * ## ⚠️ It decides nothing
 *
 * Which packages may be paused is [AppSuspension], in a module with no Android in it, because that
 * decision IS safety rule 5 — "no action may impede an emergency call" — and a rule that only exists
 * at a call site is a rule nothing can test. What is left here is the platform half: ask the system
 * who the dialler is, who the launcher is, what is launchable, and hand the answers over.
 *
 * ⚠️ Every one of those questions can come back empty, and the honest answer to not knowing where
 * the way back is, is to do nothing at all. The core refuses on a missing dialler or launcher; this
 * class does not second-guess it.
 *
 * ## ⚠️ Releasing has to work on a phone where asserting did not
 *
 * The release unsuspends **everything it can see**, not the list it remembers asserting — because it
 * may not be the process that asserted, and the list it would remember is the one thing a killed
 * process does not leave behind. An unsuspend of an app that was never suspended is a no-op, so the
 * broad sweep costs nothing and is the only version that works after a crash.
 */
class OwnerHolds(
    private val context: Context,
    private val policy: DevicePolicyController,
) : HoldCapability {

    fun run(action: AmbientAction, asserting: Boolean) {
        if (action != AmbientAction.SUSPEND_DISTRACTING_APPS) return
        runCatching { if (asserting) suspendThem() else releaseThem() }
    }

    /**
     * Why this phone cannot do it, or null when it can. Fed to the scanner's refusal list.
     *
     * ⚠️ **Almost every install lands on the first branch**, and that is the honest state rather
     * than a fault: being a device owner takes a factory reset and an adb command, so the switch
     * being on and nothing happening is the overwhelmingly likely outcome. Saying so is the whole
     * reason this returns a sentence — a switch somebody turned on that silently governs nothing is
     * indistinguishable from a broken one.
     */
    override fun cannotDo(action: AmbientAction): String? {
        if (action != AmbientAction.SUSPEND_DISTRACTING_APPS) return null
        if (!DevicePolicyController.isDeviceOwner(context)) {
            return "this app is not this device's owner"
        }
        return AppSuspension.whyCannot(dialerPackage(), launcherPackage())
    }

    private fun suspendThem() {
        val wanted = AppSuspension.suspendable(
            launchable = launchablePackages(),
            self = context.packageName,
            dialer = dialerPackage(),
            launcher = launcherPackage(),
            settings = settingsPackage(),
            keyboard = keyboardPackage(),
        )
        if (wanted.isEmpty()) return
        policy.setPackagesSuspended(wanted, suspended = true)
    }

    private fun releaseThem() {
        // ⚠️ Deliberately the same list the assert would build, NOT a remembered one — and it is
        // computed fresh so that a process which never asserted can still let go. The keep-list is
        // applied here too: unsuspending something we never suspend is harmless, but asking the
        // platform about the dialler when the core has refused to identify it is not a question
        // worth asking, and an empty list short-circuits.
        val all = AppSuspension.suspendable(
            launchable = launchablePackages(),
            self = context.packageName,
            dialer = dialerPackage(),
            launcher = launcherPackage(),
            settings = settingsPackage(),
            keyboard = keyboardPackage(),
        )
        if (all.isEmpty()) return
        policy.setPackagesSuspended(all, suspended = false)
    }

    private fun launchablePackages(): List<String> = runCatching {
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        context.packageManager.queryIntentActivities(main, 0)
            .mapNotNull { it.activityInfo?.packageName }
    }.getOrDefault(emptyList())

    private fun dialerPackage(): String? = runCatching {
        context.getSystemService<TelecomManager>()?.defaultDialerPackage
    }.getOrNull()

    private fun launcherPackage(): String? = runCatching {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        context.packageManager.resolveActivity(home, 0)?.activityInfo?.packageName
    }.getOrNull()

    private fun settingsPackage(): String? = runCatching {
        context.packageManager.resolveActivity(Intent(Settings.ACTION_SETTINGS), 0)
            ?.activityInfo?.packageName
    }.getOrNull()

    /**
     * ⚠️ `DEFAULT_INPUT_METHOD` is a flattened component — "com.example.ime/.Service" — not a
     * package. Taking it whole would put a string that matches nothing into the keep-list, which
     * fails silently by suspending the keyboard.
     */
    private fun keyboardPackage(): String? = runCatching {
        Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            ?.substringBefore('/')
            ?.takeIf { it.isNotBlank() }
    }.getOrNull()
}
