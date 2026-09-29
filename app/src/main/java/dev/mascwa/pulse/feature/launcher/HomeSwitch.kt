package dev.mascwa.pulse.feature.launcher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import dev.mascwa.pulse.security.DevicePolicyController

/**
 * Whether the LCARS home screen is the phone's Home, and the one place that changes it.
 *
 * ## The switch is the alias, not a setting
 *
 * ⚠️ There is no `AppSettings` field for this, deliberately. What makes LCARS a Home is the enabled
 * state of the `.LcarsHome` activity-alias, and Android keeps that across updates and reboots on its
 * own. A second copy in the settings blob would be a statement of the same fact that can disagree
 * with it — the crash-loop guard switches the alias off without ever reading settings, and a stale
 * `true` there would then switch it back on or show a switch that lies. So the alias IS the switch.
 *
 * ## Why the alias is named by its full class
 *
 * The alias resolves against the manifest's namespace, `dev.mascwa.pulse`, while the installed
 * PACKAGE carries the `.debug` suffix. A `ComponentName(context, ".LcarsHome")` would resolve against
 * the package and name a component that does not exist — the mistake the device-owner provisioning
 * hint once made. So the class is spelled out and the package is the context's.
 */
object HomeSwitch {

    private const val ALIAS_CLASS = "dev.mascwa.pulse.LcarsHome"

    fun alias(context: Context): ComponentName = ComponentName(context.packageName, ALIAS_CLASS)

    /** Whether the home screen is switched on — the alias is enabled. */
    fun isOn(context: Context): Boolean = runCatching {
        context.packageManager.getComponentEnabledSetting(alias(context)) ==
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
    }.getOrDefault(false)

    /** Whether pressing Home right now would open LCARS without asking. */
    fun isDefault(context: Context): Boolean = runCatching {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        context.packageManager.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.packageName == context.packageName
    }.getOrDefault(false)

    enum class Result {
        /** LCARS is Home: it answered the Home picker as device owner. */
        DEFAULT_SET,

        /** Offered as a Home; the person has to pick it, because only a device owner may pick for them. */
        CHOOSE_YOURSELF,

        OFF,

        /** Android refused to change the alias at all. */
        FAILED,
    }

    /** Offer LCARS as a Home and, as device owner, make it THE Home. */
    fun turnOn(context: Context): Result {
        // Switched on by hand: whatever the guard recorded before is not evidence against this start.
        HomeStore(context).clearGuard()
        if (!setAlias(context, PackageManager.COMPONENT_ENABLED_STATE_ENABLED)) return Result.FAILED
        return if (DevicePolicyController(context).setPersistentHome(alias(context))) {
            Result.DEFAULT_SET
        } else {
            Result.CHOOSE_YOURSELF
        }
    }

    /**
     * Hand Home back. The persistent preference is cleared FIRST, so there is never a moment when
     * the preferred Home is a disabled component; the alias goes back to its manifest state, which is
     * disabled, rather than being pinned disabled — so the manifest stays the one statement of the
     * default.
     */
    fun turnOff(context: Context): Result {
        DevicePolicyController(context).setPersistentHome(null)
        return if (setAlias(context, PackageManager.COMPONENT_ENABLED_STATE_DEFAULT)) Result.OFF else Result.FAILED
    }

    /** The guard's way out: hand Home back and say when, so Settings can explain it. */
    fun standDown(context: Context, nowMs: Long) {
        turnOff(context)
        HomeStore(context).recordStandDown(nowMs)
    }

    /**
     * ⚠️ `DONT_KILL_APP`: without it Android kills this app to apply the change — which, turning the
     * switch from Settings, would close the screen the switch is on.
     */
    private fun setAlias(context: Context, state: Int): Boolean = runCatching {
        context.packageManager.setComponentEnabledSetting(alias(context), state, PackageManager.DONT_KILL_APP)
    }.isSuccess
}
