package dev.mascwa.pulse.shortcuts

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import dev.mascwa.pulse.MainActivity
import dev.mascwa.pulse.R
import dev.mascwa.pulse.navigation.Routes

/**
 * Launcher integration — works on Nova and any standard Android launcher.
 *
 * [install] registers dynamic app shortcuts (long-press the Pulse icon) that deep-link straight into the
 * app via [MainActivity.EXTRA_ROUTE]; [UnreadBadge] broadcasts an unread count to Nova / TeslaUnread so a
 * badge shows on the icon. Both are best-effort and fully defensive — a launcher that ignores them is a no-op.
 */
object AppShortcuts {

    fun install(context: Context) {
        runCatching {
            val shortcuts = listOf(
                shortcut(context, "jarvis", "J.A.R.V.I.S.", "Ask J.A.R.V.I.S.", Routes.JARVIS, R.drawable.ic_shortcut_jarvis),
                shortcut(context, "markets", "Markets", "Markets", Routes.MARKETS, R.drawable.ic_shortcut_markets),
                shortcut(context, "nav", "Navigate", "Navigation map", Routes.NAV, R.drawable.ic_shortcut_nav),
                shortcut(context, "sos", "SOS", "Emergency SOS", Routes.SOS, R.drawable.ic_shortcut_sos),
            )
            ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts)
        }
    }

    private fun shortcut(
        context: Context, id: String, short: String, long: String, route: String,
        @androidx.annotation.DrawableRes iconRes: Int,
    ): ShortcutInfoCompat {
        // A shortcut intent MUST declare an action; the route extra drives the in-app deep link.
        val intent = Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .putExtra(MainActivity.EXTRA_ROUTE, route)
        return ShortcutInfoCompat.Builder(context, id)
            .setShortLabel(short)
            .setLongLabel(long)
            .setIcon(IconCompat.createWithResource(context, iconRes))
            .setIntent(intent)
            .build()
    }
}

/** Nova / TeslaUnread unread-count badge for the Pulse launcher icon. No-op on launchers that don't listen. */
object UnreadBadge {
    private const val ACTION = "com.teslacoilsw.notifier.action.UNREAD_CHANGED"
    private const val EXTRA_COMPONENT = "com.teslacoilsw.notifier.extra.COMPONENT"
    private const val EXTRA_COUNT = "com.teslacoilsw.notifier.extra.COUNT"

    fun publish(context: Context, count: Int) {
        runCatching {
            val component = ComponentName(context, MainActivity::class.java).flattenToString()
            val intent = Intent(ACTION)
                .putExtra(EXTRA_COMPONENT, component)
                .putExtra(EXTRA_COUNT, count.coerceAtLeast(0))
            context.applicationContext.sendBroadcast(intent)
        }
    }
}
