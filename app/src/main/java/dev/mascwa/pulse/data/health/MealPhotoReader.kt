package dev.mascwa.pulse.data.health

import dev.mascwa.pulse.core.telemetry.FoodPortion
import dev.mascwa.pulse.core.telemetry.MealPhoto
import dev.mascwa.pulse.core.telemetry.NutritionDay
import dev.mascwa.pulse.data.food.Food
import dev.mascwa.pulse.jarvis.inference.VisionEngine
import kotlinx.coroutines.flow.toList

/**
 * A photograph of a plate, turned into a list of proposals with real numbers behind them.
 *
 * ⚠️ **The model names the foods; the numbers come from food records.** That split is the whole
 * point and it is enforced here rather than trusted: [MealPhoto.PROMPT] never asks for nutrition
 * (a test guards that), and every figure a [Proposal] carries was looked up in the bundled corpus
 * by name. A model that answered "320 kcal" would have weighed nothing and read no label, and that
 * number would sit in the log beside laboratory analyses looking exactly like one of them.
 *
 * ⚠️ **Nothing here logs anything.** These are proposals for somebody to correct and confirm. A
 * photograph is the least certain input this app takes — the portion especially — and the surface
 * has to treat it that way.
 */
class MealPhotoReader(
    private val vision: VisionEngine,
    private val foods: FoodRepository,
) {

    /**
     * One food the model saw, paired with the record that will supply its numbers.
     *
     * ⚠️ [match] is nullable and the null case is real, not defensive: the corpus has no entry for
     * "grandma's casserole". Such an item is offered with its name and weight and **no nutrition**,
     * so a person can rename it into something findable or drop it — which is far better than
     * quietly attaching whatever the ranker liked third best.
     */
    data class Proposal(
        val item: MealPhoto.Item,
        val match: Food?,
    ) {
        /** What this portion contributes, or null when nothing was matched to it. */
        val eaten: NutritionDay.Nutrients?
            get() = match?.let { FoodPortion.eaten(it.per100g, item.grams) }

        val loggable: Boolean get() = (eaten?.kcal ?: 0.0) > 0.0
    }

    sealed interface Result {
        data class Plate(val proposals: List<Proposal>, val summary: String) : Result
        data object NotFood : Result

        /** ⚠️ Not a failure — the one honest "this feature needs a network and a key" state. */
        data object NoVision : Result
        data class Failed(val reason: String) : Result
    }

    /**
     * Read [imageDataUrl] — a `data:image/jpeg;base64,...` string, the same shape the console's
     * image path already produces.
     */
    suspend fun read(imageDataUrl: String): Result {
        if (!runCatching { vision.supportsVision() }.getOrDefault(false)) return Result.NoVision

        val answer = runCatching {
            vision.generateWithImages(MealPhoto.PROMPT, listOf(imageDataUrl)).toList().joinToString("")
        }.getOrElse { return Result.Failed(it.message ?: "the model could not be reached") }

        return when (val outcome = MealPhoto.parse(answer)) {
            is MealPhoto.Outcome.NotFood -> Result.NotFood
            is MealPhoto.Outcome.Unreadable -> Result.Failed(
                "The model answered, but not with a list of foods."
            )
            is MealPhoto.Outcome.NoVision -> Result.NoVision
            is MealPhoto.Outcome.Unreachable -> Result.Failed(outcome.reason)
            is MealPhoto.Outcome.Read -> Result.Plate(
                proposals = outcome.items.map { Proposal(it, bestMatch(it.name)) },
                summary = MealPhoto.summary(outcome.items),
            )
        }
    }

    /**
     * The bundled record that best answers a name the model wrote.
     *
     * ⚠️ **The seed only, and deliberately not the barcode table.** "scrambled eggs" is a generic
     * food and the seed is 13,186 laboratory analyses of exactly that kind of thing; the 4.5-million
     * row product table is packaged goods, answers name queries by an unranked prefix scan, and
     * would offer a branded ready meal whose name happens to start the same way. The right record
     * for a plate of food is the generic one.
     *
     * ⚠️ A match with no energy is treated as no match. `FoodPortion.isLoggable` is the same bar the
     * rest of the food half uses, and a row that cannot contribute a calorie would show a proposal
     * that silently adds nothing when confirmed.
     */
    private suspend fun bestMatch(name: String): Food? =
        foods.searchSeed(name, limit = 1).firstOrNull()
            ?.takeIf { FoodPortion.isLoggable(it.per100g) }
}
