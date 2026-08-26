package dev.mascwa.pulse.data.health

import kotlinx.coroutines.flow.Flow

/**
 * Everything the health view model needs, as one value each application assembles for itself.
 *
 * ⚠️ **The property names deliberately match the LCARS application's container member-for-member,
 * and that is the whole trick.** The view model reads `c.foodLogStore`, `c.bodyStore` and so on in
 * forty-four places; shaping this like the thing it replaces meant the constructor's type changed
 * and not one of those call sites did. It is the same move as keeping the package name when these
 * files were carved out of `:app` — make the new thing look like the old one and the diff collapses
 * to the handful of lines that genuinely differ.
 *
 * ⚠️ **The last four are the reason this type exists at all.** The nine stores above them already
 * live in this module and could have been passed individually, but settings, connectivity and meal
 * photographs are supplied differently by the two applications — one keeps its preferences in a
 * DataStore blob alongside forty other sections and has a vision model, the other has neither — so
 * they arrive as a flow, a lambda, a lambda and a nullable interface rather than as concrete types
 * the shared code would then have to depend on.
 */
class HealthDeps(
    val foodLogStore: FoodLogStore,
    val bodyStore: BodyStore,
    val progressPhotoStore: ProgressPhotoStore,
    val healthConnect: HealthConnectBridge,
    val customFoodStore: CustomFoodStore,
    val recipeStore: RecipeStore,
    val trainingStore: TrainingStore,
    val foodRepository: FoodRepository,
    val healthExporter: HealthExporter,
    val healthImporter: HealthImporter,

    /** The health section of whatever this application calls its settings. */
    val healthSettings: Flow<HealthSettings>,

    /**
     * Apply an edit to that section and persist it.
     *
     * ⚠️ A read-modify-write handed to the caller rather than a plain setter, because in the LCARS
     * application this section is one field of a much larger blob and the write has to preserve the
     * other forty. What that costs the shared code is nothing; what a setter would have cost is a
     * dependency on the whole settings type.
     */
    val updateHealth: suspend ((HealthSettings) -> HealthSettings) -> Unit,

    /**
     * Whether the device currently has a network.
     *
     * ⚠️ A function rather than a Boolean: it is read at the moment a lookup is about to go out, and
     * a value captured when the screen opened would answer for a network that has since gone.
     */
    val isOnline: () -> Boolean,

    /**
     * Reading a photograph of a plate, or **null** where the application cannot.
     *
     * ⚠️ Null is a real state and not a defect — see [MealPhotos]. The standalone nutrition app
     * carries no vision model, so the surface has to say the feature is unavailable rather than
     * offer a button that silently does nothing.
     */
    val mealPhotoReader: MealPhotos?,
)
