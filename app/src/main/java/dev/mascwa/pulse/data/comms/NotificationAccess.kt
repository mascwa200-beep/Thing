package dev.mascwa.pulse.data.comms

import android.content.Context
import android.provider.Settings

/**
 * Whether this app is allowed to read the notifications on the shade.
 *
 * ⚠️ **There is no runtime permission dialog for this, and there is no way to ask for one.** The
 * permission that matters — `BIND_NOTIFICATION_LISTENER_SERVICE` — is held by the *system* binding
 * to us, never requested by us, so the only route is the user finding the app in a system settings
 * page and switching it on. Anything that pretends otherwise would be a button that cannot work.
 *
 * ⚠️ **Matched by PACKAGE rather than by component**, deliberately. `ENABLED_NOTIFICATION_LISTENERS`
 * is a colon-separated list of flattened `ComponentName`s, and the question every caller here
 * actually asks is "may this app read notifications at all" — which the package answers without the
 * asker having to name a class. It also means this file compiles and can be reasoned about before
 * the listener it will eventually describe exists.
 */
object NotificationAccess {

    /**
     * True when the user has switched this app on in the notification-access settings page.
     *
     * Defensive: the setting is a free-form string written by the system, and a phone that has never
     * had a listener enabled stores null. Neither is an error, and both mean "no".
     */
    fun isGranted(context: Context): Boolean = runCatching {
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            ENABLED_LISTENERS,
        ).orEmpty()
        val us = context.packageName
        enabled.split(':')
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            // ⚠️ Not `contains(us)`: our package name is a prefix of nothing on this phone today, but
            // a substring test over an arbitrary list of other apps' component names is the kind of
            // match that is right until somebody installs something whose id contains ours.
            .any { it.substringBefore('/') == us }
    }.getOrDefault(false)

    /**
     * The settings key, spelled out rather than taken from the constant.
     *
     * ⚠️ `Settings.Secure.ENABLED_NOTIFICATION_LISTENERS` exists and is `@hide` — reachable through
     * reflection or a lint suppression, and neither is worth it for a string that has been
     * `"enabled_notification_listeners"` since the API was introduced and cannot change without
     * breaking every launcher and every accessibility tool on the platform.
     */
    private const val ENABLED_LISTENERS = "enabled_notification_listeners"
}
