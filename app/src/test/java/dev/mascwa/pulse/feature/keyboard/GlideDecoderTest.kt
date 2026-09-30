package dev.mascwa.pulse.feature.keyboard

import dev.mascwa.pulse.feature.keyboard.GlideDecoder.Point
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Glide decoding on the real letter layout at a Pixel's density, over a small dictionary chosen so each
 * word has competitors a thumb could plausibly draw instead. The whole bundled list is exercised by
 * `KeyboardWordListAssetTest`.
 */
class GlideDecoderTest {

    private val d = 2.625f
    private val metrics = KeyboardGeometry.Metrics(
        keyHeight = 50 * d, rowGap = 8 * d, keyGap = 6 * d, padTop = 6 * d, padBottom = 6 * d, padSide = 4 * d,
    )
    private val width = 1080f
    private val placed = KeyboardGeometry.place(KeyboardModel.rows(KeyboardModel.Layer.LETTERS, ",", false), width, metrics)
    private val centres = GlideDecoder.centres(placed)
    private val unit = (width - 2 * metrics.padSide) / KeyboardModel.ROW_WEIGHT

    private val dict = Dictionary.of(
        listOf(
            "the" to 222, "tie" to 140, "toe" to 110, "they" to 190, "then" to 180, "thee" to 60,
            "hello" to 120, "help" to 150, "held" to 140, "hell" to 110, "Jello" to 45,
            "keyboard" to 90, "keyboards" to 60, "keyed" to 70, "key" to 170,
        ),
    )

    /** A thumb's path through [word]: its key centres, each nudged by up to [noise] keys, joined up. */
    private fun path(word: String, noise: Float = 0f, seed: Int = 0): List<Point> {
        val rnd = Random(seed)
        val pts = GlideDecoder.letters(word)!!.map { centres.getValue(it) }.map {
            Point(it.x + (rnd.nextFloat() * 2 - 1) * noise * unit, it.y + (rnd.nextFloat() * 2 - 1) * noise * unit)
        }
        return GlideDecoder.resample(pts, 60)
    }

    private fun decoded(p: List<Point>, learned: List<String> = emptyList()) =
        GlideDecoder.decode(p, centres, unit, dict, learned).map { it.word }

    @Test
    fun `the letters are the ones on the board, and a layout without letters has none`() {
        assertEquals(('a'..'z').toSet(), centres.keys)
        val symbols = KeyboardGeometry.place(KeyboardModel.rows(KeyboardModel.Layer.SYMBOLS, ",", false), width, metrics)
        assertTrue(GlideDecoder.centres(symbols).isEmpty())
    }

    @Test
    fun `a letter pressed twice is one stop, and a word with no key for a letter is not a path`() {
        assertEquals("helo".toList(), GlideDecoder.letters("hello"))
        assertEquals("dont".toList(), GlideDecoder.letters("don't"))
        assertEquals("welknown".toList(), GlideDecoder.letters("Well-known"))
        assertNull(GlideDecoder.letters("café"))
    }

    @Test
    fun `a path is resampled evenly along its length, keeping its ends`() {
        val r = GlideDecoder.resample(listOf(Point(0f, 0f), Point(10f, 0f), Point(10f, 30f)), 5)
        assertEquals(Point(0f, 0f), r.first())
        assertEquals(Point(10f, 30f), r.last())
        // 40 long, so every 10: along the first leg, round the corner, and up the second.
        assertEquals(listOf(0f, 10f, 10f, 10f, 10f), r.map { it.x })
        assertEquals(listOf(0f, 0f, 10f, 20f, 30f), r.map { it.y })
        assertEquals(List(4) { Point(3f, 4f) }, GlideDecoder.resample(listOf(Point(3f, 4f), Point(3f, 4f)), 4))
    }

    @Test
    fun `a clean path spells the word it was drawn through`() {
        for (w in listOf("the", "hello", "keyboard", "they")) assertEquals(w, decoded(path(w)).first())
    }

    @Test
    fun `a thumb that misses every key by a quarter still spells the word`() {
        for (w in listOf("the", "keyboard", "they")) {
            for (seed in 1..5) assertEquals("'$w' seed $seed", w, decoded(path(w, noise = 0.25f, seed = seed)).first())
        }
    }

    @Test
    fun `where the path was drawn tells neighbouring keys apart`() {
        // Jello's first key is h's neighbour, and the two words are the same shape.
        assertFalse("Jello" == decoded(path("hello")).first())
        assertEquals("Jello", decoded(path("jello")).first())
    }

    @Test
    fun `a path that starts or ends far from a word's keys never spells it`() {
        val startsAtB = listOf(centres.getValue('b')) + path("the")
        assertFalse("the" in decoded(startsAtB))
        val endsAtZ = path("the") + centres.getValue('z')
        assertFalse("the" in decoded(endsAtZ))
        // The same holds for a learned word, which is not found by its first letter.
        val learned = GlideDecoder.decode(startsAtB, centres, unit, Dictionary.of(emptyList()), learned = listOf("the"))
        assertTrue(learned.isEmpty())
    }

    @Test
    fun `the letters must be passed in order`() {
        val reach = GlideDecoder.PASS_REACH_KEYS * unit
        val fine = GlideDecoder.resample(path("teh"), 128)
        assertFalse(GlideDecoder.passes("the".toList(), centres, fine, reach))
        assertTrue(GlideDecoder.passes("teh".toList(), centres, fine, reach))
    }

    @Test
    fun `a common word is preferred to a rare one that fits as well`() {
        // "thee" and "the" draw the same path; only how common they are can decide — either way round.
        fun first(d: Dictionary) = GlideDecoder.decode(path("the"), centres, unit, d).first().word
        assertEquals("the", first(Dictionary.of(listOf("the" to 255, "thee" to 1))))
        assertEquals("thee", first(Dictionary.of(listOf("the" to 1, "thee" to 255))))
    }

    @Test
    fun `a learned word can be glided`() {
        assertFalse("zork" in decoded(path("zork")))
        assertEquals("zork", decoded(path("zork"), learned = listOf("zork")).first())
    }

    @Test
    fun `nothing to decode, nothing decoded`() {
        assertTrue(decoded(listOf(centres.getValue('t'))).isEmpty())
        assertTrue(GlideDecoder.decode(path("the"), emptyMap(), unit, dict).isEmpty())
        assertTrue(GlideDecoder.decode(path("the"), centres, 0f, dict).isEmpty())
    }

    @Test
    fun `the shape score ignores where a path was drawn, the location score does not`() {
        val a = path("the")
        val moved = a.map { Point(it.x + 3 * unit, it.y) }
        assertEquals(0f, GlideDecoder.shape(a, moved), 1e-4f)
        assertEquals(3 * unit, GlideDecoder.location(a, moved), 1e-2f)
    }

    // ── what the keyboard asks of it while a finger is down ────────────────────────────────────

    @Test
    fun `only a letter can start a glide`() {
        assertTrue(GlideDecoder.startsOn(KeyboardModel.Key.Text("q")))
        assertTrue(GlideDecoder.startsOn(KeyboardModel.Key.Text("Q")))
        for (k in listOf(KeyboardModel.Key.Text("1"), KeyboardModel.Key.Text("."), KeyboardModel.Key.Text("é"), KeyboardModel.Key.Space, KeyboardModel.Key.Delete, KeyboardModel.Key.Shift)) {
            assertFalse("$k", GlideDecoder.startsOn(k))
        }
    }

    @Test
    fun `a finger is gliding once it is a key's width from where it came down, and not before`() {
        val from = centres.getValue('e')
        assertFalse(GlideDecoder.isGlide(from, Point(from.x + 0.6f * unit, from.y), unit))
        assertFalse(GlideDecoder.isGlide(from, Point(from.x + 0.99f * unit, from.y), unit))
        assertTrue(GlideDecoder.isGlide(from, Point(from.x + 1.01f * unit, from.y), unit))
        // w to e, lifted on e: a word, as Gboard has it.
        assertTrue(GlideDecoder.isGlide(centres.getValue('w'), Point(centres.getValue('e').x + 0.05f * unit, centres.getValue('e').y), unit))
        assertFalse(GlideDecoder.isGlide(from, Point(from.x + 5 * unit, from.y), 0f))
    }

    @Test
    fun `a path keeps a point per sixth of a key, and no more than it can hold`() {
        val path = mutableListOf(Point(0f, 0f))
        assertFalse(GlideDecoder.extend(path, Point(unit / 10, 0f), unit))
        assertTrue(GlideDecoder.extend(path, Point(unit / 5, 0f), unit))
        assertEquals(2, path.size)
        val full = MutableList(GlideDecoder.MAX_POINTS) { Point(it * unit, 0f) }
        assertFalse(GlideDecoder.extend(full, Point(1e6f, 0f), unit))
        assertEquals(GlideDecoder.MAX_POINTS, full.size)
    }
}
