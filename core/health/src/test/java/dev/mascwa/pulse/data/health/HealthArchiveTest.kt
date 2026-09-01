package dev.mascwa.pulse.data.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class HealthArchiveTest {

    // ⚠️ Real archives, built by the JDK's own writer, not fixtures shaped to pass. The whole point
    // of pulling this out of `HealthImporter` was to be able to hand it the thing it actually reads.
    private fun zipOf(vararg sheets: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, body) in sheets) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(body)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun sheet(name: String, text: String) = name to text.toByteArray(Charsets.UTF_8)

    // ------------------------------------------------------------------------------ the ordinary case

    @Test
    fun `a real archive comes back as its sheets`() {
        val bytes = zipOf(
            sheet("food_log.csv", "date,food\n2026-08-24,Toast\n"),
            sheet("weighins.csv", "date,kg\n2026-08-24,82.4\n"),
        )
        val read = HealthArchive.sheetsFrom(bytes) as HealthArchive.Read.Ok
        assertEquals(2, read.sheets.size)
        assertTrue(read.sheets[0].startsWith("date,food"))
        assertTrue(read.sheets[1].contains("82.4"))
    }

    @Test
    fun `a bare CSV that is not a zip reads as one sheet`() {
        val csv = "date,food\n2026-08-24,Toast\n"
        val read = HealthArchive.sheetsFrom(csv.toByteArray(Charsets.UTF_8)) as HealthArchive.Read.Ok
        assertEquals(listOf(csv), read.sheets)
    }

    @Test
    fun `an empty file is nothing, not a failure`() {
        val read = HealthArchive.sheetsFrom(ByteArray(0)) as HealthArchive.Read.Ok
        assertEquals(emptyList<String>(), read.sheets)
    }

    @Test
    fun `an accented food name survives, whole`() {
        // ⚠️ The bug `PackArchive` shipped: decoding block by block splits a multi-byte character on
        // a read boundary. This corpus is full of these — "Crème brûlée", "Jalapeño", "Açaí" — so the
        // body is deliberately dense with them and long enough to be read in several chunks.
        //
        // ⚠️ **How likely that is depends on the block size, which is worth knowing before trusting
        // this.** Measured over this exact fixture: a block-decoding reader mangles 13 characters at
        // 8 kB, 21 at 4 kB and 82 at 1 kB — but ZERO at 64 kB, because `ZipInputStream.read` happens
        // to hand back six chunks (65536, 56311, 65536, 65536, 19858, 7223) that all land between
        // characters. So this test cannot tell "decoded once" from "chunked luckily at 64 kB"; what
        // it does hold is round-trip fidelity, and the guard was confirmed awake by perturbing the
        // reader to the 8 kB blocks the historical bug actually used.
        val one = "Crème brûlée, Jalapeño, Açaí, Gnocchi à la Romaine — 250 µg\n"
        val text = one.repeat(4_000)
        assertTrue("fixture must cross the read buffer", text.toByteArray(Charsets.UTF_8).size > 200_000)

        val read = HealthArchive.sheetsFrom(zipOf(sheet("food_log.csv", text))) as HealthArchive.Read.Ok
        assertEquals(text, read.sheets.single())
        assertTrue("a replacement character got in", '�' !in read.sheets.single())
    }

    // ----------------------------------------------------------------------------------- the bomb

    @Test
    fun `A ZIP BOMB IS REFUSED, NOT READ`() {
        // ⚠️ The one that matters, and it is a REAL bomb rather than a description of one: 48 MB of
        // zeros, which deflates to a few dozen kilobytes. Unbounded, the old code allocated all of it
        // and then a String twice that size, on a phone, on a "restore my data" tap.
        val payload = ByteArray(48_000_000)
        val bytes = zipOf("bomb.csv" to payload)
        assertTrue("the fixture must actually be small compressed", bytes.size < 200_000)

        val read = HealthArchive.sheetsFrom(bytes)
        assertTrue("read a $payload-sized entry: $read", read is HealthArchive.Read.TooBig)
    }

    @Test
    fun `many small entries are a bomb too`() {
        // ⚠️ The reason the cap is ACROSS entries and not per entry. Each of these is comfortably
        // inside any per-entry limit; together they are not, and a per-entry rule alone lets the whole
        // thing through.
        val one = ByteArray(1_000_000)
        val bytes = zipOf(*Array(40) { "sheet$it.csv" to one })
        val read = HealthArchive.sheetsFrom(bytes)
        assertTrue("40 MB across 40 entries was read: $read", read is HealthArchive.Read.TooBig)
    }

    @Test
    fun `an absurd number of entries is refused before they are read`() {
        val tiny = "date,food\n".toByteArray(Charsets.UTF_8)
        val bytes = zipOf(*Array(HealthArchive.MAX_ENTRIES + 10) { "sheet$it.csv" to tiny })
        val read = HealthArchive.sheetsFrom(bytes)
        assertTrue("$read", read is HealthArchive.Read.TooBig)
    }

    @Test
    fun `an archive the size this app really writes is not refused`() {
        // ⚠️ The other half, and the half that is easy to forget: a cap that refuses a real export is
        // a worse bug than no cap at all. Measured through the shipped exporter, a food-log row is
        // 418 bytes with everything on it, so twenty years of five entries a day is 15.26 MB. This
        // builds that much and requires it through.
        val row = "2026-08-24,18:30,Dinner,Chicken breast baked skin not eaten,,1 breast," +
            "142.5,235.4,43.2,5.1,0.0,0.0,0.0,1.4,104.3," + "12.3,".repeat(37) +
            "Offline,1756000000000,1756012345678,3f2a91c4-8b7d-4e15-9a0c-6d8e2f4b1a37\n"
        val rows = (15_260_000 / row.toByteArray().size) + 1
        val text = buildString { repeat(rows) { append(row) } }
        assertTrue("fixture must be about twenty years", text.toByteArray().size > 15_000_000)

        val read = HealthArchive.sheetsFrom(zipOf(sheet("food_log.csv", text)))
        assertTrue("a twenty-year export was refused: $read", read is HealthArchive.Read.Ok)
        assertEquals(text.length, (read as HealthArchive.Read.Ok).sheets.single().length)
    }

    // ------------------------------------------------------------------------------- readBounded

    @Test
    fun `readBounded gives back everything under the limit`() {
        val body = ByteArray(1_000) { (it % 251).toByte() }
        val got = HealthArchive.readBounded(body.inputStream(), 10_000)
        assertTrue(got != null && got.contentEquals(body))
    }

    @Test
    fun `readBounded refuses rather than truncating`() {
        // ⚠️ Null, never a short array. Half a CSV parses perfectly well — the header is at the top —
        // so a truncated read would put part of somebody's record back and report success.
        val body = ByteArray(10_000)
        assertEquals(null, HealthArchive.readBounded(body.inputStream(), 5_000))
    }

    @Test
    fun `exactly the limit is allowed and one more byte is not`() {
        // Computed from the rule as written — `total > limit` — not guessed at.
        assertEquals(100, HealthArchive.readBounded(ByteArray(100).inputStream(), 100)?.size)
        assertEquals(null, HealthArchive.readBounded(ByteArray(101).inputStream(), 100))
    }

    @Test
    fun `a limit of nothing reads nothing`() {
        assertEquals(null, HealthArchive.readBounded(ByteArray(1).inputStream(), 0))
        assertEquals(null, HealthArchive.readBounded(ByteArray(1).inputStream(), -1))
    }

    @Test
    fun `the refusal says what the limit is`() {
        val said = HealthArchive.tooBig("That file")
        assertTrue(said, said.startsWith("That file is larger"))
        assertTrue(said, said.contains("32 MB"))
    }
}
