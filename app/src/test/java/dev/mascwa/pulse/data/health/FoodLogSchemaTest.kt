package dev.mascwa.pulse.data.health

import dev.mascwa.pulse.core.network.HttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The food log's on-disk shape, gated where it actually matters: **a blob written by an older build
 * must still decode.**
 *
 * ⚠️ The whole log is one JSON document per month. A field added without a default does not corrupt
 * one entry — it makes every entry of every prior month unreadable at once, and there is no second
 * copy of somebody's food log anywhere. That is the one failure this file exists to catch, and it is
 * silent: the code compiles, the app starts, and the history is simply gone.
 *
 * ⚠️ It decodes through the REAL `StoredEntry` with the REAL `Json` configuration, which is why
 * those were widened from `private`. A test against a hand-written copy of the same shape would
 * prove nothing, because the copy is the thing that would change alongside the source.
 */
class FoodLogSchemaTest {

    private val json = HttpClient.defaultJson()

    /** Exactly what a shard entry looked like before micronutrients existed. */
    private val preMicros = """
        {"id":"abc","day":1756000000000,"at":1756003600000,"name":"Porridge","grams":250.0,
         "n":{"kcal":170.0,"p":6.0,"f":3.5,"c":28.0,"fibre":4.0,"sugar":1.0,"satFat":0.7,"sodium":9.0},
         "brand":"","serving":"1 bowl","meal":"BREAKFAST","source":"OFFLINE","foodId":"seed_1"}
    """.trimIndent()

    @Test
    fun anEntryWrittenBeforeMicronutrientsExistedStillDecodes() {
        val e = json.decodeFromString(FoodLogStore.StoredEntry.serializer(), preMicros)
        assertEquals("abc", e.id)
        assertEquals("Porridge", e.name)
        assertEquals(250.0, e.grams, 1e-9)
        assertEquals(170.0, e.n.kcal, 1e-9)
        assertEquals(9.0, e.n.sodium, 1e-9)
        assertEquals("BREAKFAST", e.meal)
        // ⚠️ The point: the new field is absent from the document and the entry is still whole.
        // Empty is also the right value — nothing recorded this food's micronutrients.
        assertTrue(e.micros.isEmpty())
    }

    /** And an entry written now round-trips, micronutrients included. */
    @Test
    fun anEntryWithMicronutrientsSurvivesARoundTrip() {
        val original = FoodLogStore.StoredEntry(
            id = "x", day = 1L, at = 2L, name = "Milk", grams = 200.0,
            n = FoodLogStore.StoredNutrients(kcal = 128.0, p = 6.8),
            micros = mapOf("CALCIUM" to 240.0, "VITAMIN_D" to 2.0),
        )
        val back = json.decodeFromString(
            FoodLogStore.StoredEntry.serializer(),
            json.encodeToString(FoodLogStore.StoredEntry.serializer(), original),
        )
        assertEquals(original, back)
        assertEquals(240.0, back.micros["CALCIUM"]!!, 1e-9)
    }

    /**
     * ⚠️ A micronutrient name this build does not know is DROPPED, not fatal.
     *
     * Keys are stored as strings for exactly this reason: an enum-keyed serializer throws on a name
     * it cannot resolve, so renaming or removing one would make a year of logs undecodable. Here the
     * unknown key survives the decode as data and is discarded when it becomes an `Amounts`.
     */
    @Test
    fun aMicronutrientNameThisBuildDoesNotKnowIsDroppedRatherThanFatal() {
        val withGhost = """
            {"id":"g","day":1,"at":2,"name":"X","grams":10.0,
             "n":{"kcal":1.0},"micros":{"CALCIUM":100.0,"SELENIUM_FROM_THE_FUTURE":5.0}}
        """.trimIndent()
        val e = json.decodeFromString(FoodLogStore.StoredEntry.serializer(), withGhost)
        assertEquals(2, e.micros.size)
        val resolved = e.micros.mapNotNull { (k, v) ->
            runCatching { dev.mascwa.pulse.core.telemetry.Micronutrients.Micro.valueOf(k) }
                .getOrNull()?.let { it to v }
        }.toMap()
        assertEquals(1, resolved.size)
        assertEquals(
            100.0,
            resolved[dev.mascwa.pulse.core.telemetry.Micronutrients.Micro.CALCIUM]!!,
            1e-9,
        )
    }
}
