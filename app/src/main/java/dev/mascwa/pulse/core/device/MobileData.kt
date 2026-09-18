package dev.mascwa.pulse.core.device

import android.app.AppOpsManager
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.net.ConnectivityManager
import android.os.Process
import android.telephony.TelephonyManager
import dev.mascwa.pulse.core.telemetry.BillingCycle
import java.util.Calendar

/**
 * How much mobile data this phone has used since the allowance last reset.
 *
 * Wi-Fi is deliberately not counted. A data cap applies to the radio, so folding Wi-Fi in would
 * report a number three times too large against a limit it has nothing to do with — the reading
 * would look alarming and mean nothing.
 *
 * ## Why the answer is a sealed type
 *
 * ⚠️ There are four genuinely different things this can say and only one of them is a number:
 * *"you have used 4.2 GB"*, *"you have not granted Usage Access"*, *"this phone has no mobile
 * radio"*, and *"the query threw"*. Returning `null` or `0L` for the last three is the defect this
 * repository keeps finding — a reading of zero on a tablet with no SIM looks like a very frugal
 * month, and one on a phone whose permission was refused looks like a broken feature. The caller
 * decides what to draw; this only reports what happened.
 *
 * ## The permission
 *
 * `querySummaryForDevice` needs **Usage Access** — `PACKAGE_USAGE_STATS`, which is declared in the
 * manifest but is a *special access* the user grants in Settings rather than a runtime prompt. The
 * app already asks for it for the Security Audit's per-app breakdown, so on a phone where that
 * screen works this works too; [hasUsageAccess] is the same AppOps check that screen uses.
 */
class MobileData(private val appContext: Context) {

    /** What was found, or why nothing was. */
    sealed interface Reading {
        /**
         * [bytes] since [since], which is [daysInto] of [lengthDays] days through the cycle.
         *
         * A cycle carries its length so a caller can say "day 12 of 30" — the fraction is what
         * turns a number into a judgement about whether you are on course.
         */
        data class Used(
            val bytes: Long,
            val since: BillingCycle.Date,
            val daysInto: Int,
            val lengthDays: Int,
        ) : Reading

        /** Usage Access has not been granted. Nothing is wrong; the app simply may not look. */
        data object NoAccess : Reading

        /** No mobile radio, or no stats service — a tablet, or a phone with the SIM out. */
        data object NoRadio : Reading

        /** The query threw. [reason] is the exception's class and message. */
        data class Failed(val reason: String) : Reading
    }

    /** Whether the app currently holds Usage Access. The same AppOps check the Security Audit uses. */
    fun hasUsageAccess(): Boolean = runCatching {
        val appOps = appContext.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), appContext.packageName,
        ) == AppOpsManager.MODE_ALLOWED
    }.getOrDefault(false)

    /**
     * Mobile bytes since the cycle that [cycleDay] describes last began.
     *
     * ⚠️ The cycle boundary is a LOCAL midnight, which is why the calendar work happens here and
     * the arithmetic happens in [BillingCycle]. A phone in Auckland whose allowance resets on the
     * 1st resets thirteen hours before one in London, and computing the boundary in UTC would count
     * a third of a day into the wrong month. [Calendar] is used rather than `java.time` only
     * because this file already holds a `Calendar` for the current date and one is enough.
     */
    @Suppress("DEPRECATION")
    fun sinceCycleStart(cycleDay: Int): Reading {
        if (!hasUsageAccess()) return Reading.NoAccess
        val nsm = appContext.getSystemService(Context.NETWORK_STATS_SERVICE) as? NetworkStatsManager
            ?: return Reading.NoRadio
        // A device with no telephony has no mobile allowance to report against, and the query would
        // answer zero — which reads as "used nothing" rather than "has no radio".
        val tm = appContext.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        if (tm == null || tm.phoneType == TelephonyManager.PHONE_TYPE_NONE) return Reading.NoRadio

        val now = Calendar.getInstance()
        val start = BillingCycle.startOf(
            year = now.get(Calendar.YEAR),
            month = now.get(Calendar.MONTH) + 1, // Calendar counts months from zero; nothing else does
            dayOfMonth = now.get(Calendar.DAY_OF_MONTH),
            cycleDay = cycleDay,
        )
        val from = Calendar.getInstance().apply {
            clear()
            set(start.year, start.month - 1, start.day, 0, 0, 0)
        }.timeInMillis

        return runCatching {
            // ⚠️ `subscriberId = null` means "every subscriber", and it is the only value an
            // ordinary app can pass: the real one has been privileged since API 29, so asking for
            // it would return null and silently scope the query to nothing.
            val bucket = nsm.querySummaryForDevice(
                ConnectivityManager.TYPE_MOBILE, null, from, System.currentTimeMillis(),
            )
            Reading.Used(
                bytes = bucket.rxBytes + bucket.txBytes,
                since = start,
                daysInto = BillingCycle.daysInto(
                    now.get(Calendar.YEAR), now.get(Calendar.MONTH) + 1,
                    now.get(Calendar.DAY_OF_MONTH), cycleDay,
                ),
                lengthDays = BillingCycle.lengthDays(
                    now.get(Calendar.YEAR), now.get(Calendar.MONTH) + 1,
                    now.get(Calendar.DAY_OF_MONTH), cycleDay,
                ),
            )
        }.getOrElse { e ->
            Reading.Failed("${e::class.java.simpleName}: ${e.message.orEmpty().take(80)}".trim(':', ' '))
        }
    }
}
