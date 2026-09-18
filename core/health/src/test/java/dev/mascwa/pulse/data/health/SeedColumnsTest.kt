package dev.mascwa.pulse.data.health

import dev.mascwa.pulse.core.telemetry.NutrientSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The bundled generic-food seed is a positional file, and nothing else checks its shape.
 *
 * ⚠️ **A column offset that slips is a WRONG number, not a missing one** — magnesium read as
 * phosphorus, micrograms read as milligrams — across thirteen thousand foods, on a screen somebody
 * eats to. `tools/food/build_seed.py` asserts the width as it writes; this asserts it as shipped,
 * which is the half that survives a hand edit, a bad merge, or an editor that strips the trailing
 * tabs a row of unrecorded nutrients ends in.
 *
 * ⚠️ **This test used to live in `:app`, and its own note said it had to**, because the seed was an
 * asset of that application rather than of the library. It is not any more: the seed sits beside
 * `FoodRepository`, the code that reads it, and reaches both applications because an Android
 * library's assets are merged into every app that depends on it. So the test moved with the file it
 * validates, and reads it by a path relative to this module.
 *
 * ⚠️ The offsets below are restated from `FoodRepository`, which is the authority, because they are
 * private to that file. That restatement is exactly what [waterLandsInTheWaterColumn] exists to
 * catch: it does not trust the arithmetic, it reads a physical quantity and checks it is physical.
 */
class SeedColumnsTest {

    private val seed = File("src/main/assets/food/seed.tsv")

    /** 13 head/macro/serving fields, then the 8 micronutrients, then every further nutrient. */
    private val head = 13
    private val micros = 8
    private val width get() = head + micros + NutrientSet.Nutrient.entries.size

    private fun lines(): List<String> = seed.readLines().filter { it.isNotBlank() }

    @Test
    fun everyLineIsExactlyAsWideAsTheParserExpects() {
        assertTrue("the seed is missing: ${seed.absolutePath}", seed.isFile)
        val rows = lines()
        assertTrue("suspiciously few foods: ${rows.size}", rows.size > 10_000)
        rows.forEachIndexed { i, line ->
            val n = line.split('\t').size
            assertEquals("line ${i + 1} has $n columns, not $width: ${line.take(60)}", width, n)
        }
    }

    /**
     * ⚠️ **The offset check that does not depend on the offsets.**
     *
     * Water is the one further nutrient USDA records on every food, and it is bounded by physics:
     * nothing edible is more than 100 g of water per 100 g. If [head] + [micros] were wrong by even
     * one, this column would hold beta-carotene (micrograms, into the hundreds) or vitamin K1, and
     * the range test fails. It is the cheapest possible proof that the builder and the parser agree.
     */
    @Test
    fun waterLandsInTheWaterColumn() {
        val order = NutrientSet.Nutrient.entries.sortedBy { it.id }
        val col = head + micros + order.indexOfFirst { it == NutrientSet.Nutrient.WATER }
        var seen = 0
        var worst = 0.0
        for (line in lines()) {
            val cell = line.split('\t')[col]
            if (cell.isBlank()) continue
            val v = cell.toDoubleOrNull()
            assertTrue("water column holds $cell, which is not a number", v != null)
            assertTrue("water $v g per 100 g is not physical", v!! in 0.0..100.0)
            seen++
            if (v > worst) worst = v
        }
        assertTrue("only $seen foods state their water content", seen > 12_000)
        // A corpus of foods with no water above about half would mean the column is something else.
        assertTrue("the wettest food is only $worst g/100 g", worst > 90.0)
    }

    /**
     * Every recorded figure is a real, non-negative number.
     *
     * ⚠️ A cell that does not parse is **silently dropped** by `toDoubleOrNull` in the parser — the
     * right behaviour at runtime, and the reason a corrupted column could ship unnoticed. This is
     * where it gets noticed.
     */
    @Test
    fun everyRecordedFurtherNutrientIsAFiniteNonNegativeNumber() {
        val base = head + micros
        for ((i, line) in lines().withIndex()) {
            val f = line.split('\t')
            for (c in base until width) {
                val cell = f[c]
                if (cell.isBlank()) continue
                val v = cell.toDoubleOrNull()
                assertTrue("line ${i + 1} column $c holds ${'"'}$cell${'"'}", v != null)
                assertTrue("line ${i + 1} column $c is $v", v!!.isFinite() && v >= 0.0)
            }
        }
    }

    /**
     * The ids are contiguous from one, which is what makes "sorted by id" a stable column order.
     *
     * ⚠️ A gap would not break anything by itself — both sides sort the same way — but a DUPLICATE
     * id would silently collapse two columns, and that is worth failing the build over.
     */
    @Test
    fun theNutrientIdsAreUniqueAndContiguous() {
        val ids = NutrientSet.Nutrient.entries.map { it.id }
        assertEquals("duplicate ids", ids.size, ids.toSet().size)
        assertEquals((1..ids.size).toList(), ids.sorted())
    }
}
