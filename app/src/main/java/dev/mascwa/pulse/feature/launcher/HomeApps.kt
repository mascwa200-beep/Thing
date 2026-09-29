package dev.mascwa.pulse.feature.launcher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import android.util.LruCache
import androidx.core.graphics.drawable.toBitmap
import java.util.concurrent.ConcurrentHashMap

/**
 * One app the home screen can open: a launchable activity in one profile.
 *
 * [key] is what a pin is stored under. It carries the profile's serial number as well as the
 * component, because the same app installed in a work profile is a different thing to open — and
 * the SERIAL rather than the `UserHandle`, which is only an id for this boot.
 */
data class HomeApp(
    val key: String,
    val label: String,
    val component: ComponentName,
    val user: UserHandle,
    val work: Boolean,
)

/**
 * The apps on this phone, as the home screen lists them — read, drawn and opened through
 * `LauncherApps`, the service Android gives launchers for exactly this.
 *
 * ⚠️ **Every call here is fallible, and each failure costs only itself.** A profile that cannot be
 * read is skipped rather than emptying the directory; an icon that will not draw is a blank tile
 * beside a readable label; a launch the service refuses falls back to an ordinary start. A home
 * screen is the one screen that must never come up empty because one app was odd.
 *
 * Blocking where it has to be — [load] and [icon] read the package manager, so callers put them on
 * a background thread.
 */
class HomeApps(private val context: Context) {

    private val launcherApps: LauncherApps? = context.getSystemService(LauncherApps::class.java)
    private val users: UserManager? = context.getSystemService(UserManager::class.java)

    /** What [load] last found, so an icon can be asked of the same record the row was built from. */
    private val infos = ConcurrentHashMap<String, LauncherActivityInfo>()

    /** Icons are the expensive part of a directory, and the same few are drawn again on every scroll. */
    private val icons = LruCache<String, Bitmap>(ICON_CACHE)

    /**
     * Every launchable activity, in every profile this app may see.
     *
     * Falls back to asking the package manager for launcher activities if `LauncherApps` answers
     * nothing at all — the directory would otherwise be blank on a phone where that service
     * misbehaves, with nothing to say why.
     */
    fun load(): List<HomeApp> {
        val self = Process.myUserHandle()
        val found = mutableListOf<HomeApp>()
        val la = launcherApps
        if (la != null) {
            val profiles = runCatching { la.profiles }.getOrNull() ?: listOf(self)
            for (user in profiles) {
                val list = runCatching { la.getActivityList(null, user) }.getOrNull() ?: continue
                for (info in list) {
                    val app = runCatching { info.toApp(user != self) }.getOrNull() ?: continue
                    infos[app.key] = info
                    found += app
                }
            }
        }
        val apps = found.ifEmpty { fallback(self) }
        return apps.distinctBy { it.key }
    }

    /**
     * [app]'s icon at [sizePx], with the work badge where it has one. Null when it cannot be drawn,
     * and the row shows its label alone.
     */
    fun icon(app: HomeApp, sizePx: Int): Bitmap? {
        icons.get(app.key)?.let { return it }
        val drawable = runCatching { infos[app.key]?.getBadgedIcon(0) }.getOrNull()
            ?: runCatching { context.packageManager.getActivityIcon(app.component) }.getOrNull()
            ?: return null
        val bitmap = runCatching { drawable.toBitmap(sizePx, sizePx) }.getOrNull() ?: return null
        icons.put(app.key, bitmap)
        return bitmap
    }

    /** Open [app]. False when neither route would open it, so the screen can say so. */
    fun launch(app: HomeApp, bounds: Rect? = null): Boolean {
        val viaService = runCatching {
            launcherApps?.startMainActivity(app.component, app.user, bounds, null) ?: error("no LauncherApps")
        }.isSuccess
        if (viaService) return true
        // Only an app in THIS profile can be started the ordinary way; one in another profile
        // would open the copy in this one, which is not the app that was tapped.
        if (app.work) return false
        return runCatching {
            context.startActivity(
                Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LAUNCHER)
                    .setComponent(app.component)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED),
            )
        }.isSuccess
    }

    /** Android's own page for [app] — permissions, storage, uninstall. */
    fun openInfo(app: HomeApp): Boolean = runCatching {
        launcherApps?.startAppDetailsActivity(app.component, app.user, null, null) ?: error("no LauncherApps")
    }.isSuccess

    /**
     * Calls [onChange] whenever an app is installed, removed, updated, or its profile comes and goes,
     * and returns the way to stop. An updated app can change its icon, so its cached ones go too.
     */
    fun watch(onChange: () -> Unit): () -> Unit {
        val la = launcherApps ?: return {}
        val callback = object : LauncherApps.Callback() {
            override fun onPackageRemoved(packageName: String?, user: UserHandle?) = changed(packageName)
            override fun onPackageAdded(packageName: String?, user: UserHandle?) = changed(packageName)
            override fun onPackageChanged(packageName: String?, user: UserHandle?) = changed(packageName)

            override fun onPackagesAvailable(packageNames: Array<out String>?, user: UserHandle?, replacing: Boolean) =
                changed(*packageNames.orEmpty())

            override fun onPackagesUnavailable(packageNames: Array<out String>?, user: UserHandle?, replacing: Boolean) =
                changed(*packageNames.orEmpty())

            private fun changed(vararg packages: String?) {
                val prefixes = packages.filterNotNull().map { "$it/" }
                icons.snapshot().keys.filter { k -> prefixes.any(k::startsWith) }.forEach { icons.remove(it) }
                onChange()
            }
        }
        val registered = runCatching { la.registerCallback(callback, Handler(Looper.getMainLooper())) }.isSuccess
        return { if (registered) runCatching { la.unregisterCallback(callback) } }
    }

    private fun LauncherActivityInfo.toApp(work: Boolean): HomeApp {
        val cn = componentName
        return HomeApp(
            key = keyOf(cn, user),
            label = label?.toString()?.trim()?.ifBlank { null } ?: cn.packageName,
            component = cn,
            user = user,
            work = work,
        )
    }

    private fun fallback(self: UserHandle): List<HomeApp> {
        val pm = context.packageManager
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val found = runCatching { pm.queryIntentActivities(query, 0) }.getOrNull().orEmpty()
        return found.mapNotNull { ri ->
            runCatching {
                val cn = ComponentName(ri.activityInfo.packageName, ri.activityInfo.name)
                HomeApp(
                    key = keyOf(cn, self),
                    label = ri.loadLabel(pm)?.toString()?.trim()?.ifBlank { null } ?: cn.packageName,
                    component = cn,
                    user = self,
                    work = false,
                )
            }.getOrNull()
        }
    }

    private fun keyOf(component: ComponentName, user: UserHandle): String {
        val serial = runCatching { users?.getSerialNumberForUser(user) }.getOrNull() ?: 0L
        return "${component.flattenToString()}#$serial"
    }

    private companion object {
        /** A phone's worth of icons: the whole directory of a typical phone fits, so a scroll back is free. */
        const val ICON_CACHE = 400
    }
}
