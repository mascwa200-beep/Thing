package dev.mascwa.pulse.data.health

import android.content.Context
import android.net.Uri
import dev.mascwa.pulse.core.telemetry.FoodPortion
import dev.mascwa.pulse.core.telemetry.MealPhoto
import dev.mascwa.pulse.core.telemetry.NutritionDay
import dev.mascwa.pulse.data.food.Food

/**
 * Reading a photograph of a plate into proposals — as an interface, because only one of the two
 * applications can do it.
 *
 * ⚠️ **This exists so the shared view model can be shared at all.** The implementation reaches into
 * the inference module for a vision model, which the standalone nutrition app deliberately does not
 * carry: it is offline-first and has no cloud key to spend. So the capability is declared here, the
 * only implementation lives in the LCARS application, and the standalone one passes null. A view
 * model that referred to the reader directly would have dragged the whole inference stack across
 * with it.
 *
 * ⚠️ **The types live here rather than on the implementation** for the same reason. They appear in
 * the view model's own state and in what a screen renders, so both applications have to be able to
 * name them even though only one can produce them.
 */
interface MealPhotos {

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
     * Read the photograph at [uri], including whatever decoding and encoding that takes.
     *
     * ⚠️ The encoding is behind this call rather than in front of it, and that is deliberate: the
     * step is specific to how a particular vision model wants its images, and an application with no
     * vision model should not have to know that such a step exists.
     */
    suspend fun read(context: Context, uri: Uri): Result
}
