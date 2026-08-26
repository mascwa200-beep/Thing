package dev.mascwa.pulse.data.health

import android.content.Context
import androidx.activity.result.contract.ActivityResultContract
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.health.connect.client.units.Mass
import dev.mascwa.pulse.core.telemetry.BodyTrend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId

/**
 * The bridge to Health Connect, so a smart scale or a watch can fill the record in.
 *
 * Weight typed in by hand is the fallback this whole tab already works on. What this adds is the
 * automatic half: a scale that publishes to Health Connect fills the trend without anybody
 * remembering, and a weight recorded here reaches every other app the same way.
 *
 * ## Everything here is behind a capability check, and that is not defensiveness
 *
 * ⚠️ **Health Connect may simply not exist on this device, and the app has to be honest about
 * which.** Below Android 14 it is a separate installable app; from 14 it is part of the platform —
 * but a de-Googled or hardened build can ship without it, and this app's own device gate targets
 * GrapheneOS, where its presence is **unverified**. [availability] answers with the reason, and every
 * read and write returns a null or an empty list rather than throwing, so the tab keeps working
 * exactly as it did before with manual entry.
 *
 * ⚠️ **`getSdkStatus` resolves the provider BY PACKAGE**, and Android 11+ package visibility hides it
 * unless the manifest declares a `<queries><package>` entry for `com.google.android.apps.healthdata`.
 * Without it the call returns `SDK_UNAVAILABLE` on a phone that has Health Connect installed — a
 * silent false negative indistinguishable from a device that genuinely lacks it, on the one call the
 * whole integration is gated behind. That entry is in the manifest beside the permissions.
 *
 * ⚠️ **A permission not declared in the manifest cannot be requested at all**, so the three there are
 * the entire reach of this: read weight, write weight, read steps. Nothing about food, sleep, heart
 * rate or exercise is asked for, and adding one is a manifest change rather than a code change —
 * which is the right amount of friction for widening what a nutrition app can see about a body.
 */
class HealthConnectBridge(private val context: Context) {

    /** Whether this device can do any of it, and — when it cannot — why not, in a sentence. */
    sealed interface Availability {
        data object Ready : Availability

        /** Installed but too old to talk to. The one case where there is something to do about it. */
        data object UpdateNeeded : Availability

        /** No provider at all. Manual entry is the whole story here, and the surface says so. */
        data class Missing(val reason: String) : Availability
    }

    /**
     * ⚠️ Read fresh every time rather than cached. Health Connect can be installed, updated or
     * removed while this app is alive, and a status decided at construction would be wrong for the
     * rest of the process — with the visible symptom being a feature that stays greyed out after the
     * person did the exact thing the screen told them to.
     */
    fun availability(): Availability = runCatching {
        when (HealthConnectClient.getSdkStatus(context)) {
            HealthConnectClient.SDK_AVAILABLE -> Availability.Ready
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> Availability.UpdateNeeded
            else -> Availability.Missing(
                "Health Connect is not on this device. Everything here still works — weight is " +
                    "typed in and steps come from the phone's own pedometer.",
            )
        }
    }.getOrElse {
        Availability.Missing(
            "Health Connect could not be reached on this device — ${it.message ?: "no reason given"}. " +
                "Manual entry is unaffected.",
        )
    }

    private fun client(): HealthConnectClient? =
        if (availability() is Availability.Ready) {
            runCatching { HealthConnectClient.getOrCreate(context) }.getOrNull()
        } else {
            null
        }

    /**
     * Permission to write a weigh-in back out.
     *
     * ⚠️ Held as its own value rather than rebuilt at each use, because [canPublish] and
     * [permissions] must be asking about the same string. Declared ABOVE [permissions], since
     * property initialisers run in source order and a `val` read before its own initialiser is null.
     */
    private val writeWeight: String = HealthPermission.getWritePermission(WeightRecord::class)

    /**
     * The permissions this app asks for, in the form the request contract wants.
     *
     * Derived from the record types rather than written out as strings, so the set here and the
     * `<uses-permission>` entries cannot drift apart into a request that can never be granted.
     */
    val permissions: Set<String> = setOf(
        HealthPermission.getReadPermission(WeightRecord::class),
        writeWeight,
        HealthPermission.getReadPermission(StepsRecord::class),
    )

    /**
     * The contract a screen launches to ask for them.
     *
     * ⚠️ **Not gated on availability, deliberately.** A contract is a description of an intent, not a
     * live connection — building one costs nothing and needs no provider. Gating it would force the
     * caller to create its launcher conditionally, and a `rememberLauncherForActivityResult` that
     * exists in some compositions and not others is a fragile shape in a file that cannot be
     * type-checked locally. The gate belongs on the LAUNCH, which the surface already does.
     *
     * ⚠️ The return type is written out rather than inferred. A composable passes this straight to
     * the launcher, whose type parameters come from the contract — leaving it to inference puts the
     * shape of a public API at the mercy of a library's own generics.
     */
    fun permissionContract(): ActivityResultContract<Set<String>, Set<String>> =
        PermissionController.createRequestPermissionResultContract()

    suspend fun granted(): Set<String> = withContext(Dispatchers.IO) {
        runCatching { client()?.permissionController?.getGrantedPermissions().orEmpty() }
            .getOrDefault(emptySet())
    }

    suspend fun hasAll(): Boolean = granted().containsAll(permissions)

    /**
     * Whether a weigh-in may be published, which is a NARROWER question than [hasAll].
     *
     * ⚠️ Publishing needs one permission, and asking [hasAll] instead would refuse to publish on a
     * phone where the write permission was granted and read-steps was not — a real combination,
     * since Health Connect lets somebody grant them one at a time. The import path asks the wider
     * question because it genuinely reads two record types; this one asks only what it uses.
     */
    suspend fun canPublish(): Boolean = granted().contains(writeWeight)

    // ------------------------------------------------------------------------------------ reading

    /**
     * Weigh-ins recorded by anything else, since [sinceMs].
     *
     * ⚠️ Returns an EMPTY list for "nothing there" and for "could not ask", which the caller must not
     * conflate — importing nothing is safe either way, but a surface that says "no new readings"
     * when the truth is "permission was refused" is the silent-failure shape this repo keeps
     * correcting. The caller checks [hasAll] first and reports the difference itself.
     *
     * ⚠️ **"Anything else" is enforced, not merely intended.** `readRecords` returns records from
     * every origin including this app's own, so once [publishWeight] has a caller the import would
     * read back what this app itself wrote — reporting "brought in 3 weigh-ins" about its own data,
     * and resurrecting a reading deleted here the moment it was no longer the newest one held. The
     * origin is compared against `context.packageName` rather than a literal, because the shipped
     * build carries an `applicationIdSuffix` and a hardcoded id would match nothing.
     */
    suspend fun weighinsSince(sinceMs: Long): List<BodyTrend.Weighin> = withContext(Dispatchers.IO) {
        runCatching {
            val c = client() ?: return@runCatching emptyList()
            c.readRecords(
                ReadRecordsRequest(
                    recordType = WeightRecord::class,
                    timeRangeFilter = TimeRangeFilter.after(Instant.ofEpochMilli(sinceMs)),
                ),
            // ⚠️ `inKilograms`, not `kilograms`. `javap` on Mass shows `getKilograms()` because the
            // property carries a `@get:JvmName`, so the JVM accessor and the Kotlin property have
            // DIFFERENT names and the disassembly alone gives the wrong one — the Kotlin name lives
            // in the class file's @Metadata, which `strings` will show. Energy.inKilocalories too.
            ).records
                .filter { it.metadata.dataOrigin.packageName != context.packageName }
                .map { BodyTrend.Weighin(it.time.toEpochMilli(), it.weight.inKilograms) }
        }.getOrDefault(emptyList())
    }

    /** Steps between two instants, summed. Null when it cannot be established — never a stand-in 0. */
    suspend fun stepsBetween(fromMs: Long, toMs: Long): Long? = withContext(Dispatchers.IO) {
        runCatching {
            val c = client() ?: return@runCatching null
            c.readRecords(
                ReadRecordsRequest(
                    recordType = StepsRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(
                        Instant.ofEpochMilli(fromMs),
                        Instant.ofEpochMilli(toMs),
                    ),
                ),
            ).records.sumOf { it.count }
        }.getOrNull()
    }

    // ------------------------------------------------------------------------------------ writing

    /**
     * Publish a weigh-in so other apps can see it.
     *
     * ⚠️ `Metadata.manualEntry()` and not `autoRecorded`, because that is what it is — somebody stood
     * on a scale and typed the number in here. Recording it as automatically captured would tell
     * every other app on the phone that this device measured it, which is a claim about provenance
     * and not a formality.
     */
    suspend fun publishWeight(atMs: Long, kg: Double): Boolean = withContext(Dispatchers.IO) {
        if (!kg.isFinite() || kg <= 0.0) return@withContext false
        runCatching {
            val c = client() ?: return@runCatching false
            c.insertRecords(
                listOf(
                    WeightRecord(
                        time = Instant.ofEpochMilli(atMs),
                        zoneOffset = ZoneId.systemDefault().rules.getOffset(Instant.ofEpochMilli(atMs)),
                        weight = Mass.kilograms(kg),
                        metadata = Metadata.manualEntry(),
                    ),
                ),
            )
            true
        }.getOrDefault(false)
    }

    /**
     * Take back weigh-ins this app published between [fromMs] inclusive and [toMs] exclusive.
     *
     * Two callers, and the second is the one that makes deletion honest: correcting a reading
     * withdraws the day's earlier publication before writing the new one, matching `BodyStore`'s own
     * replace-per-day rule, and deleting a reading takes it out of Health Connect too — otherwise
     * "delete" would mean "delete here but leave it visible to every other app on the phone".
     *
     * ⚠️ **This cannot touch another app's data, and that is the library's own guarantee rather than
     * an assumption.** `deleteRecords`' KDoc in the shipped 1.1.0-beta01 sources reads: *"Deletes any
     * Record of the given recordType in the given timeRangeFilter (automatically filtered to Record
     * belonging to the calling application)."* So a smart scale's reading at the same instant is
     * untouched even though the window covers it.
     *
     * Returns whether it went through. A false is worth knowing but never worth blocking on — the
     * failure mode is a stale copy in Health Connect, not a lost reading here.
     */
    suspend fun withdrawWeightBetween(fromMs: Long, toMs: Long): Boolean = withContext(Dispatchers.IO) {
        if (toMs <= fromMs) return@withContext false
        runCatching {
            val c = client() ?: return@runCatching false
            c.deleteRecords(
                recordType = WeightRecord::class,
                timeRangeFilter = TimeRangeFilter.between(
                    Instant.ofEpochMilli(fromMs),
                    Instant.ofEpochMilli(toMs),
                ),
            )
            true
        }.getOrDefault(false)
    }
}
