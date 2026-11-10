package dev.mascwa.pulse.feature.phone

import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.telecom.TelecomManager
import dev.mascwa.pulse.security.DevicePolicyController

/**
 * Whether LCARS is the phone app, and the one place that changes it.
 *
 * ## The role is the truth, never a stored flag
 *
 * ⚠️ [isOn] asks `RoleManager` every time. There is no `AppSettings` field, for the reason
 * [PhoneStore] and `HomeSwitch` give: the owner can change the default dialler from Android's own
 * settings, and the crash-loop guard stands the call screen down without touching settings — a stored
 * `true` would then disagree with the phone's actual state and show a switch that lies.
 *
 * ## Emergency calls never depend on this
 *
 * Whoever the phone app is, Telecom hands every EMERGENCY call to the built-in dialler's own screen
 * (`LcarsInCallService` says why), so turning this on never puts LCARS between the owner and an
 * emergency call. `AppSuspension` keeps the built-in dialler un-pausable for the same reason.
 */
object PhoneSwitch {

    enum class Result {
        /** LCARS is the phone app now — set as device owner, with no chooser. */
        ROLE_SET,

        /** The owner must grant the role: the caller launches [roleRequestIntent]. */
        NEEDS_REQUEST,

        /** Handed back to another dialler as device owner. */
        OFF,

        /** The owner must change it in Android's settings: the caller opens [defaultAppsSettings]. */
        NEEDS_SETTINGS,

        FAILED,
    }

    /** Whether LCARS currently holds the dialler role. */
    fun isOn(context: Context): Boolean = runCatching {
        context.getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_DIALER) == true
    }.getOrDefault(false)

    /**
     * Become the phone app. As device owner it is done here with no chooser; otherwise the caller
     * launches [roleRequestIntent] and Android asks. Either way the call screen and missed-call
     * receiver are re-enabled first (a prior stand-down may have disabled them) and the guard's record
     * is cleared — switched on by hand, the past is not evidence against this start.
     */
    fun turnOn(context: Context): Result {
        PhoneStore(context).clearGuard()
        setComponentsEnabled(context, true)
        rememberCurrentDialer(context)
        val owned = DevicePolicyController(context).setDefaultDialer(context.packageName)
        MissedCallReceiver.reconcile(context)
        return if (owned) Result.ROLE_SET else Result.NEEDS_REQUEST
    }

    /**
     * Hand the phone app back. As device owner, to the dialler that held it before LCARS, or — if none
     * was remembered — to the built-in system dialler (the platform wants a package, never null).
     * Otherwise the caller sends the owner to Android's Default apps settings.
     */
    fun turnOff(context: Context): Result {
        val target = PhoneStore(context).previousDialer() ?: systemDialer(context)
        val result = if (target != null && DevicePolicyController(context).setDefaultDialer(target)) {
            Result.OFF
        } else {
            Result.NEEDS_SETTINGS
        }
        MissedCallReceiver.reconcile(context)
        return result
    }

    /** The built-in dialler's package — where the role goes back to when nothing else was remembered. */
    private fun systemDialer(context: Context): String? = runCatching {
        context.getSystemService(TelecomManager::class.java)?.systemDialerPackage
    }.getOrNull()

    /** The system prompt that asks the owner to make LCARS the phone app. */
    fun roleRequestIntent(context: Context): Intent? = runCatching {
        context.getSystemService(RoleManager::class.java)?.createRequestRoleIntent(RoleManager.ROLE_DIALER)
    }.getOrNull()

    /** Where to send the owner to change the default phone app by hand. */
    fun defaultAppsSettings(): Intent = Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)

    /**
     * The crash-loop guard's way out: stop being the call screen and the missed-call poster, and say
     * when, so Settings can explain it. Android falls back to the built-in dialler, so calls still work.
     */
    fun standDown(context: Context, nowMs: Long) {
        setComponentsEnabled(context, false)
        PhoneStore(context).recordStandDown(nowMs)
    }

    /** Remember who held the role before LCARS — once, and never LCARS itself. */
    private fun rememberCurrentDialer(context: Context) {
        val current = runCatching {
            @Suppress("DEPRECATION")
            context.getSystemService(TelecomManager::class.java)?.defaultDialerPackage
        }.getOrNull()
        if (current != null && current != context.packageName) PhoneStore(context).rememberPreviousDialer(current)
    }

    /**
     * Turn the call screen's service and the missed-call receiver on or off together.
     *
     * ⚠️ `DONT_KILL_APP`: without it Android kills the app to apply the change, closing the Settings
     * screen the switch was flipped on. The InCallService is named by its full class against the
     * context's package — the `.debug` suffix makes a relative name resolve to a component that does
     * not exist, the provisioning-hint mistake `HomeSwitch` records.
     */
    private fun setComponentsEnabled(context: Context, on: Boolean) {
        val state = if (on) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }
        runCatching {
            context.packageManager.setComponentEnabledSetting(
                ComponentName(context.packageName, "dev.mascwa.pulse.feature.phone.LcarsInCallService"),
                state,
                PackageManager.DONT_KILL_APP,
            )
        }
        // The missed-call receiver follows, but only reconcile may turn it ON (it has its own
        // conditions); here it is only ever turned off, so the OFF case is safe to set directly.
        if (!on) {
            runCatching {
                context.packageManager.setComponentEnabledSetting(
                    ComponentName(context.packageName, "dev.mascwa.pulse.feature.phone.MissedCallReceiver"),
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP,
                )
            }
        }
    }
}
