package dev.mascwa.pulse.core.device

import android.app.AppOpsManager
import android.content.Context
import android.os.Process

/**
 * Whether LCARS holds Usage Access — the grant behind the data-use line, the security audit's
 * data-drain checks and the home console's recent apps.
 *
 * ⚠️ **One definition.** The same AppOps check was written out twice (`MobileData` and the security
 * audit), and the recent-apps strip would have been a third. Both older sites delegate here now.
 *
 * There is no permission dialog for this: Android grants it only in its own "Usage access" page,
 * which `openUsageAccessSettings` opens.
 */
object UsageAccess {

    fun isGranted(context: Context): Boolean = runCatching {
        val app = context.applicationContext
        val appOps = app.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), app.packageName,
        ) == AppOpsManager.MODE_ALLOWED
    }.getOrDefault(false)
}
