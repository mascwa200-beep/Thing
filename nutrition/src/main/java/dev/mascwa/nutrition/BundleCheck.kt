package dev.mascwa.nutrition

import android.content.Context
import android.os.Build
import dev.mascwa.pulse.core.telemetry.NutrientSet
import dev.mascwa.pulse.data.food.db.FoodDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * What this install actually got, asked of the device rather than assumed.
 *
 * ## Why the first screen of a food app is a self-check
 *
 * This module bundles a third of a gigabyte of barcode data inside the APK, and the two things that
 * can go wrong with that are invisible from a build log: the asset never reached the package, or it
 * reached it and Room refused to unpack a copy. Both look identical from the outside — every scan
 * simply says "unknown barcode" — which is exactly the shape of failure this project has spent
 * whole sessions chasing elsewhere.
 *
 * ⚠️ So the numbers come from **the database on the phone**, never from anything written down at
 * build time. A count that was baked into a string resource would keep saying four and a half
 * million on an install that has no database at all.
 */
data class BundleReport(
    /** Whether the bundled database opened at all. */
    val present: Boolean,
    val products: Int?,
    val withNutrition: Int?,
    /** Rows in `food_extra` — the further nutrients beyond the sixteen on the product row. */
    val extraFigures: Int?,
    /** How many further nutrients this build knows about, from the shared declaration. */
    val extraNutrients: Int,
    val builtAt: String?,
    val attribution: String?,
    /**
     * Why it is not here, when it is not.
     *
     * ⚠️ Two absences that must not read the same. A locally-built APK genuinely has no database —
     * CI fetches and injects it, because it is far past what a repository will hold — and that is
     * expected. A published build that cannot open one is a fault. The sentence says which.
     */
    val failure: String?,
) {
    companion object {
        suspend fun read(context: Context): BundleReport = withContext(Dispatchers.IO) {
            // ⚠️ **On IO, and `FoodDatabase.open` is why.** The DAO calls below are Room `suspend`
            // functions, which Room dispatches to its own executor — so those were never on the main
            // thread. `open` is a plain function: it probes the asset (a read from a 118 MB deflated
            // zip entry) and builds the Room instance, both on whatever thread called it, and every
            // caller here is a `LaunchedEffect`, which runs on the main one. The comment at that
            // call site already asserted this must not happen on the main thread; now it does not.
            val nutrients = NutrientSet.Nutrient.entries.size
            val db = FoodDatabase.open(context)
                ?: return@withContext BundleReport(
                    present = false,
                    products = null, withNutrition = null, extraFigures = null,
                    extraNutrients = nutrients, builtAt = null, attribution = null,
                    failure = "No food database in this build. Continuous integration fetches and " +
                        "injects it, so a locally built copy never has one — everything else works, " +
                        "and a scanned barcode simply will not be recognised.",
                )
            return@withContext runCatching {
                val dao = db.dao()
                BundleReport(
                    present = true,
                    // ⚠️ **These two came from COUNT(*) over 4.5 million rows apiece, for numbers the
                    // builder had already written into the `meta` table.** `build_food_db.py` writes
                    // `rows` and `extra_rows` beside `with_nutrition`, which the line below was
                    // already reading — so the table was known to be there and two full table scans
                    // were run anyway, over a 424 MB database, on a phone this app exists to run on.
                    // `food_extra` is WITHOUT ROWID, so counting it walks the whole composite B-tree.
                    //
                    // ⚠️ Worse than slow: this is the FIRST thing that touches the database, so it is
                    // what triggers Room's 424 MB unpack — and it fires from a LaunchedEffect on the
                    // Plan tab, so it ran again on every visit to that tab.
                    //
                    // ⚠️ **The scan is kept as a fallback and that is not belt-and-braces.** A
                    // database built before those keys existed has no `rows` value, and answering
                    // "not recorded" for a database that is sitting right there would be worse than
                    // the cost. On every database this build ships, meta answers and nothing scans.
                    products = dao.meta("rows")?.toIntOrNull() ?: dao.count(),
                    withNutrition = dao.meta("with_nutrition")?.toIntOrNull(),
                    extraFigures = dao.meta("extra_rows")?.toIntOrNull() ?: dao.extraCount(),
                    extraNutrients = nutrients,
                    builtAt = dao.meta("built_at"),
                    attribution = dao.meta("attribution"),
                    failure = null,
                )
            }.getOrElse { t ->
                BundleReport(
                    present = false,
                    products = null, withNutrition = null, extraFigures = null,
                    extraNutrients = nutrients, builtAt = null, attribution = null,
                    // ⚠️ The message rather than a shrug. This is the branch that means something
                    // is genuinely wrong, and the exception's own words are the whole diagnosis.
                    failure = "The food database is packaged but would not open: " +
                        "${t::class.java.simpleName}: ${t.message ?: "no detail"}",
                )
            }
        }
    }
}

/**
 * The phone, in the terms that decide whether this app can run on it at all.
 *
 * ⚠️ Reported because the claim being made is "it runs anywhere", and the two things that would
 * falsify it are a native library built for one architecture and a minimum version nobody meets.
 * There is no native library here, so [abis] should list whatever the device is and none of it
 * should matter — which is a thing worth being able to see rather than believe.
 */
data class DeviceFacts(
    val model: String,
    val android: String,
    val sdk: Int,
    val abis: List<String>,
) {
    companion object {
        fun read(): DeviceFacts = DeviceFacts(
            model = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
            android = Build.VERSION.RELEASE ?: "unknown",
            sdk = Build.VERSION.SDK_INT,
            abis = Build.SUPPORTED_ABIS?.toList().orEmpty(),
        )
    }
}
