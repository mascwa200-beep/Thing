package dev.mascwa.pulse.data.health

import dev.mascwa.pulse.core.telemetry.BarcodeScan
import dev.mascwa.pulse.core.telemetry.NutritionDay
import dev.mascwa.pulse.data.food.Food
import dev.mascwa.pulse.data.food.db.FoodDatabase
import dev.mascwa.pulse.data.food.db.FoodRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The bundled barcode database, in the app's own shape.
 *
 * One job: turn a scanned code into a [Food] with no network at all. Everything downstream — the
 * portion arithmetic, the log, the coach — is untouched, because what comes out of here is the same
 * [Food] the network path has always produced.
 *
 * ⚠️ **The scaled integers are unpacked here and nowhere else.** The database stores nutrients as
 * integers because a REAL costs eight bytes whether it holds 0.0 or π, and at 4.4 million rows that
 * decides whether the thing fits in an application at all. The scales are recorded on [FoodRow] and
 * the multiplications below are their only inverse; a second copy of them anywhere would be a second
 * chance to divide somebody's sodium by ten.
 */
class OfflineFoodStore(private val db: FoodDatabase) {

    /**
     * The product with this barcode, or null if the bundle has never heard of it.
     *
     * ⚠️ **A row with no nutrition is still returned.** Measured on the real export, only about a
     * fifth of products carry numbers, so returning null for the rest would throw away four scans in
     * five. Naming the product and saying nobody recorded its numbers is a completely different
     * experience from not recognising it, and [FoodRepository] draws that distinction with
     * `FoodLookup.NoNutrition`.
     */
    suspend fun byBarcode(barcode: String): Food? = withContext(Dispatchers.IO) {
        val key = BarcodeScan.normalize(barcode) ?: return@withContext null
        runCatching { db.dao().byBarcode(key) }.getOrNull()?.let { toFood(it, barcode) }
    }

    /**
     * Bundled products whose names begin with [query].
     *
     * ⚠️ Deliberately the weakest of the three search paths, and deliberately last. There is no
     * full-text index on 4.4 million product names — one would cost more than the table — so this is
     * a prefix scan and it is here so that a product you have *scanned* before can also be found by
     * typing. The seed and your own foods answer typed searches properly.
     */
    suspend fun searchByName(query: String, limit: Int = SEARCH_LIMIT): List<Food> =
        withContext(Dispatchers.IO) {
            val q = query.trim()
            if (q.length < MIN_PREFIX) return@withContext emptyList()
            runCatching { db.dao().searchByNamePrefix(q, limit) }
                .getOrDefault(emptyList())
                .map { toFood(it, it.barcode.toString()) }
        }

    /** How many products are bundled, for the attribution line. Null if the database will not answer. */
    suspend fun count(): Int? = withContext(Dispatchers.IO) {
        runCatching { db.dao().count() }.getOrNull()
    }

    /** A value from the database's own `meta` table — the build date, the source, the licence. */
    suspend fun meta(key: String): String? = withContext(Dispatchers.IO) {
        runCatching { db.dao().meta(key) }.getOrNull()
    }

    private companion object {
        const val SEARCH_LIMIT = 25

        /**
         * ⚠️ A prefix scan on two characters over 4.4M rows visits an enormous slice of the B-tree
         * for a result nobody can read. Three is where it stops being a scan of the whole alphabet.
         */
        const val MIN_PREFIX = 3

        /** Grams are stored x10, so 12.3 g is 123. */
        const val G_SCALE = 10.0
    }

    /**
     * ⚠️ **Sodium is already in milligrams and must NOT go through `FoodPortion.sodiumMgFromGrams`.**
     * Open Food Facts publishes that field in grams and USDA publishes it in milligrams; the builder
     * converts OFF's at ingest so exactly one unit reaches this table. Applying the OFF conversion
     * again here would divide every bundled product's sodium by a thousand — which does not look like
     * an error on screen, it looks like a low-salt food, and the coach would act on it.
     *
     * ⚠️ The barcode as scanned is kept as the id rather than the normalised key. The id is what the
     * log stores and what a later lookup uses, and a US packet's printed UPC-A is what somebody would
     * recognise if they ever saw it. `normalize` resolves the spellings; it does not replace them.
     */
    private fun toFood(row: FoodRow, scanned: String): Food = Food.of(
        id = scanned.filter { it.isDigit() }.ifEmpty { row.barcode.toString() },
        name = row.name?.takeIf { it.isNotBlank() } ?: "Product ${row.barcode}",
        brand = row.brand.orEmpty(),
        per100g = NutritionDay.Nutrients(
            kcal = row.kcal?.toDouble() ?: 0.0,
            proteinG = scaled(row.prot, G_SCALE),
            fatG = scaled(row.fat, G_SCALE),
            carbG = scaled(row.carb, G_SCALE),
            fibreG = scaled(row.fib, G_SCALE),
            sugarG = scaled(row.sug, G_SCALE),
            satFatG = scaled(row.sat, G_SCALE),
            sodiumMg = row.sod?.toDouble() ?: 0.0,
        ),
        servingGrams = row.servingGrams?.toDouble()?.takeIf { it > 0.0 },
        // The corpus records a serving mass, never a name for it. A blank label lets
        // FoodPortion render "1 serving (30 g)" rather than inventing a description of the portion.
        servingLabel = "",
        packageGrams = row.packageGrams?.toDouble()?.takeIf { it > 0.0 },
        source = when (row.src) {
            FoodRow.SOURCE_USDA -> NutritionDay.Source.USDA
            else -> NutritionDay.Source.OPEN_FOOD_FACTS
        },
    )

    /** A scaled integer back to the real number, keeping null as "nobody recorded this" → 0.0. */
    private fun scaled(v: Int?, scale: Double): Double = if (v == null) 0.0 else v / scale
}
