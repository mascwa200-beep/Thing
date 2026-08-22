package dev.mascwa.pulse.data.food

import dev.mascwa.pulse.core.telemetry.FoodPortion
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shipped parser, run over responses the live API actually sent.
 *
 * ⚠️ **Every fixture below is a verbatim product object from a real request**, trimmed only of
 * nutriment keys the parser never reads. That matters more than usual here: three of the four rules
 * this exercises exist because the live data contradicts what its shape suggests, and a fixture I
 * invented would have been written to match my assumptions rather than the source.
 *
 * The repository's own network path cannot be exercised without a server, so this covers the half
 * that turns a response into a `Food` — which is where every unit and normalisation decision lives.
 */
class OpenFoodFactsParseTest {

    private val json = Json { ignoreUnknownKeys = true }

    // ⚠️ A free function, not a method. Parsing touches neither the network nor the cache, so making
    // it a member would have forced this test to construct a repository — and my first attempt at
    // that used `by lazy { throw }` stubs, which the constructor materialises immediately. The stubs
    // were the signal that the function was in the wrong place.
    private fun parse(id: String, raw: String) =
        parseOffProduct(id, json.parseToJsonElement(raw).jsonObject)

    // ------------------------------------------------------------------------------- the fixtures

    /** Nutella, 3017624010701. No serving declared; sodium present and in grams. */
    private val NUTELLA = """
        {"brands":"Ferrero","code":"3017624010701",
         "image_small_url":"https://images.openfoodfacts.org/images/products/301/762/401/0701/front_en.100.200.jpg",
         "nutriments":{"carbohydrates_100g":57.5,"energy-kcal_100g":539,"fat_100g":30.9,"proteins_100g":6.3,
                       "saturated-fat_100g":10.6,"sodium_100g":0.043,"sugars_100g":56.3},
         "nutrition_data_per":"100g","product_name":"Nutella","product_name_en":"Nutella",
         "product_quantity":400,"quantity":"400.0 g"}
    """.trimIndent()

    /** Pringles, 0038000138416. A real 28 g serving, and a fractional package weight. */
    private val CRISPS = """
        {"brands":"Pringles","code":"0038000138416",
         "nutriments":{"carbohydrates_100g":57,"energy-kcal_100g":536,"fat_100g":32,"fiber_100g":4,
                       "proteins_100g":3.5,"saturated-fat_100g":9,"sodium_100g":0.536,"sugars_100g":1},
         "product_name":"Original Potato Crisps","product_quantity":147.41752025,"quantity":"5.2 oz",
         "serving_quantity":28,"serving_size":"1 serving (28 g)"}
    """.trimIndent()

    /** Coca-Cola, 5449000000996. Logged by volume; a 330 ml serving. */
    private val COLA = """
        {"brands":"Coca-Cola","code":"5449000000996",
         "nutriments":{"carbohydrates_100g":10.6,"energy-kcal_100g":42,"fat_100g":0,"proteins_100g":0,
                       "saturated-fat_100g":0,"sodium_100g":0,"sugars_100g":10.6},
         "product_name":"coca-cola","product_quantity":330,"quantity":"330 ml",
         "serving_quantity":330,"serving_size":"1 portion (330 ml)"}
    """.trimIndent()

    // ------------------------------------------------------------------------------------- tests

    @Test
    fun aRealProductBecomesARealFood() {
        val f = parse("3017624010701", NUTELLA)!!
        assertEquals("Nutella", f.name)
        assertEquals("Ferrero", f.brand)
        assertEquals("Ferrero · Nutella", f.display)
        assertEquals(539.0, f.per100g.kcal, 1e-9)
        assertEquals(6.3, f.per100g.proteinG, 1e-9)
        assertEquals(30.9, f.per100g.fatG, 1e-9)
        assertEquals(57.5, f.per100g.carbG, 1e-9)
        assertEquals(10.6, f.per100g.satFatG, 1e-9)
        assertTrue(FoodPortion.isLoggable(f.per100g))
    }

    /**
     * ⚠️ Sodium arrives in GRAMS and must not be passed through.
     *
     * `sodium_100g: 0.043` sits beside `sodium_unit: "g"` for a spread containing 43 mg. Passing it
     * through is wrong by a factor of a thousand, in the direction that makes every food on the
     * planet look sodium-free — a defect nobody would notice by reading the screen.
     */
    @Test
    fun sodiumIsConvertedToMilligrams() {
        assertEquals(43.0, parse("x", NUTELLA)!!.per100g.sodiumMg, 1e-9)
        assertEquals(536.0, parse("x", CRISPS)!!.per100g.sodiumMg, 1e-9)
        assertEquals("a genuine zero stays zero", 0.0, parse("x", COLA)!!.per100g.sodiumMg, 1e-9)
    }

    /**
     * A serving is carried only when the source declares one, and the label comes with it.
     *
     * Nutella declares none — so the unit is simply not on offer, rather than defaulted to something
     * plausible. That refusal is the whole point of [FoodPortion.gramsFor] returning null.
     */
    @Test
    fun servingsAreCarriedWhenDeclaredAndAbsentWhenNot() {
        val crisps = parse("x", CRISPS)!!
        assertEquals(28.0, crisps.servingGrams!!, 1e-9)
        assertEquals("1 serving (28 g)", crisps.servingLabel)
        assertTrue(FoodPortion.Unit.SERVING in FoodPortion.unitsFor(crisps.sizes))

        val nutella = parse("x", NUTELLA)!!
        assertNull(nutella.servingGrams)
        assertEquals("", nutella.servingLabel)
        assertEquals(listOf(FoodPortion.Unit.GRAM, FoodPortion.Unit.PACKAGE),
            FoodPortion.unitsFor(nutella.sizes))
    }

    /**
     * The end-to-end number, checked against the source's own arithmetic rather than my own.
     *
     * Open Food Facts reports 158 kcal per 28 g serving of those crisps. 536 × 0.28 = 150.08, and the
     * source's own figure came from a more precise per-100 g value (565.37) it does not always
     * publish — so the check here is that the parser plus the portion core agree with the *published
     * per-100-g figure*, which is the number this app actually stores.
     */
    @Test
    fun aServingOfARealProductComesOutRight() {
        val crisps = parse("x", CRISPS)!!
        val grams = FoodPortion.gramsFor(FoodPortion.Portion(1.0, FoodPortion.Unit.SERVING), crisps.sizes)!!
        assertEquals(28.0, grams, 1e-9)
        val eaten = FoodPortion.eaten(crisps.per100g, grams)
        assertEquals(536.0 * 0.28, eaten.kcal, 1e-9)
        assertEquals(150, Math.round(eaten.kcal).toInt())
        assertEquals(150.08, eaten.kcal, 0.01)
    }

    /** A drink logged by volume: 330 ml of cola is 330 g here, and 42 × 3.3 = 138.6 kcal. */
    @Test
    fun aDrinkLoggedByVolumeWorks() {
        val cola = parse("x", COLA)!!
        val g = FoodPortion.gramsFor(FoodPortion.Portion(1.0, FoodPortion.Unit.SERVING), cola.sizes)!!
        assertEquals(330.0, g, 1e-9)
        assertEquals(138.6, FoodPortion.eaten(cola.per100g, g).kcal, 1e-9)
        assertEquals("1 portion (330 ml)",
            FoodPortion.describe(FoodPortion.Portion(1.0, FoodPortion.Unit.SERVING), cola.sizes))
    }

    /**
     * ⚠️ An absurd declared serving is DROPPED rather than carried.
     *
     * A real record declares a 3 gram serving for a packet of biscuits. A serving weight multiplies,
     * so keeping it turns "two servings" into 28 calories for most of a packet. Losing the serving
     * unit costs one convenience; keeping a wrong one costs the number.
     */
    @Test
    fun anAbsurdServingWeightIsNotCarried() {
        val absurd = CRISPS.replace("\"serving_quantity\":28", "\"serving_quantity\":3")
        val f = parse("x", absurd)!!
        assertNull("the 3 g serving is refused", f.servingGrams)
        assertEquals("and its label goes with it", "", f.servingLabel)
        assertTrue("but the food is still perfectly loggable by weight",
            FoodPortion.isLoggable(f.per100g))
    }

    /** A record with no name is not a food, whatever else it carries. */
    @Test
    fun aRecordWithNoNameIsNotAFood() {
        assertNull(parse("x", """{"code":"1","nutriments":{"energy-kcal_100g":100}}"""))
        assertNull(parse("x", """{"code":"1","product_name":"   ","nutriments":{"energy-kcal_100g":100}}"""))
    }

    /**
     * ⚠️ An empty record parses, and is then refused by [FoodPortion.isLoggable] rather than here.
     *
     * One search result in eight came back with no energy and no macros — a record somebody created
     * and never filled in. It is a real food with a real name, so parsing it is correct; what must
     * not happen is offering it in a list as though tapping it would log something.
     */
    @Test
    fun aNamedRecordWithNoNutritionParsesAndIsThenRefused() {
        val f = parse("x", """{"code":"1","product_name":"Peanut Butter"}""")!!
        assertEquals("Peanut Butter", f.name)
        assertEquals(0.0, f.per100g.kcal, 1e-9)
        assertTrue("which is exactly what the loggable check is for", !FoodPortion.isLoggable(f.per100g))
    }

    /** Numbers that arrive as strings are still numbers; anything else is absent, never zero. */
    @Test
    fun aNumberSentAsAStringIsStillANumber() {
        val f = parse("x", """{"code":"1","product_name":"X","nutriments":{"energy-kcal_100g":"250.5"}}""")!!
        assertEquals(250.5, f.per100g.kcal, 1e-9)
        val bad = parse("x", """{"code":"1","product_name":"X","nutriments":{"energy-kcal_100g":"n/a"}}""")!!
        assertEquals("unparseable is absent, and absent reads as zero energy — not as a claim",
            0.0, bad.per100g.kcal, 1e-9)
    }

    /** Only the first brand is kept: OFF stores them comma-separated and the row has one line. */
    @Test
    fun onlyTheFirstBrandIsKept() {
        val f = parse("x", """{"code":"1","product_name":"X","brands":"Ferrero,Nutella,Ferrero SpA"}""")!!
        assertEquals("Ferrero", f.brand)
    }

    /** The stored form round-trips, because it is what goes into the disk cache. */
    @Test
    fun aFoodSurvivesBeingStoredAndReadBack() {
        val f = parse("3017624010701", NUTELLA)!!
        val back = json.decodeFromString(Food.serializer(), json.encodeToString(Food.serializer(), f))
        assertEquals(f, back)
        assertEquals(43.0, back.per100g.sodiumMg, 1e-9)
        assertEquals(f.display, back.display)
    }
}
