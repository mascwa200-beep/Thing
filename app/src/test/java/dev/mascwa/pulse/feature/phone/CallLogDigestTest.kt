package dev.mascwa.pulse.feature.phone

import dev.mascwa.pulse.feature.phone.CallLogDigest.Entry
import dev.mascwa.pulse.feature.phone.CallLogDigest.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

class CallLogDigestTest {

    private val detroit = ZoneId.of("America/Detroit")

    // Epoch values from python `zoneinfo`, America/Detroit. The clocks go back at 02:00 on
    // 1 Nov 2026, so that local day is 25 hours long: 00:30 and 23:30 are 24 hours apart and still
    // one date — and 04:30Z on two different UTC days.
    private val nov1At0030 = 1_793_507_400_000L
    private val nov1At2330 = 1_793_593_800_000L
    private val oct31At2350 = 1_793_505_000_000L
    private val nov1At0010 = 1_793_506_200_000L

    private var nextId = 1L
    private fun e(
        number: String,
        at: Long,
        type: Int = CallLogDigest.TYPE_INCOMING,
        name: String? = null,
        presentation: Int = CallLogDigest.PRESENTATION_ALLOWED,
        unread: Boolean = false,
    ) = Entry(nextId++, number, name, type, at, 30, presentation, unread)

    @Test
    fun `consecutive calls from one number on one day are one row, newest first`() {
        val rows = CallLogDigest.rows(
            listOf(
                e("5550100", nov1At0030, CallLogDigest.TYPE_MISSED),
                e("(616) 555-0100", nov1At0030 + 60_000, CallLogDigest.TYPE_MISSED),
            ),
            detroit,
        )
        assertEquals(2, rows.size) // a seven-digit local number is not the same as a ten-digit one
        val same = CallLogDigest.rows(
            listOf(
                e("6165550100", nov1At0030, CallLogDigest.TYPE_MISSED),
                e("(616) 555-0100", nov1At0030 + 60_000, CallLogDigest.TYPE_INCOMING),
            ),
            detroit,
        )
        assertEquals(1, same.size)
        assertEquals(2, same[0].count)
        // The newest call decides the row: they called back and it was answered.
        assertEquals(Kind.INCOMING, same[0].kind)
        assertEquals(listOf(Kind.INCOMING, Kind.MISSED), same[0].kinds)
    }

    @Test
    fun `a day is the local date, even on the day the clocks go back`() {
        // ⚠️ 24 hours apart and on different UTC days, yet one local day: one row.
        val rows = CallLogDigest.rows(listOf(e("6165550100", nov1At0030), e("6165550100", nov1At2330)), detroit)
        assertEquals(1, rows.size)
        assertEquals(LocalDate.of(2026, 11, 1), rows[0].day)
    }

    @Test
    fun `twenty minutes apart across midnight is two days and two rows`() {
        val rows = CallLogDigest.rows(listOf(e("6165550100", oct31At2350), e("6165550100", nov1At0010)), detroit)
        assertEquals(listOf(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 10, 31)), rows.map { it.day })
    }

    @Test
    fun `a call from somebody else in between splits a caller's calls`() {
        val rows = CallLogDigest.rows(
            listOf(e("6165550100", nov1At0030), e("6165550199", nov1At0030 + 1_000), e("6165550100", nov1At0030 + 2_000)),
            detroit,
        )
        assertEquals(3, rows.size)
    }

    @Test
    fun `two withheld callers are never merged with a known one`() {
        val rows = CallLogDigest.rows(
            listOf(
                e("", nov1At0030, presentation = CallLogDigest.PRESENTATION_RESTRICTED),
                e("6165550100", nov1At0030 + 1_000),
            ),
            detroit,
        )
        assertEquals(2, rows.size)
        assertTrue(rows[1].withheld)
        assertFalse(rows[0].withheld)
    }

    @Test
    fun `withheld calls with the same reason are one row, and different reasons are not`() {
        val same = CallLogDigest.rows(
            listOf(
                e("", nov1At0030, presentation = CallLogDigest.PRESENTATION_RESTRICTED),
                e("", nov1At0030 + 1_000, presentation = CallLogDigest.PRESENTATION_RESTRICTED),
            ),
            detroit,
        )
        assertEquals(1, same.size)
        val mixed = CallLogDigest.rows(
            listOf(
                e("", nov1At0030, presentation = CallLogDigest.PRESENTATION_RESTRICTED),
                e("", nov1At0030 + 1_000, presentation = CallLogDigest.PRESENTATION_PAYPHONE),
            ),
            detroit,
        )
        assertEquals(2, mixed.size)
    }

    @Test
    fun `a number that is allowed but has no digits is still withheld`() {
        assertTrue(CallLogDigest.withheld(e("", nov1At0030)))
        assertEquals("Private number", CallLogDigest.withheldLabel(CallLogDigest.PRESENTATION_RESTRICTED))
        assertEquals("Unknown caller", CallLogDigest.withheldLabel(CallLogDigest.PRESENTATION_UNKNOWN))
    }

    @Test
    fun `a row is unread only when it holds an unseen missed call`() {
        val rows = CallLogDigest.rows(
            listOf(
                e("6165550100", nov1At0030, CallLogDigest.TYPE_MISSED, unread = true),
                e("6165550199", nov1At0030 + 1_000, CallLogDigest.TYPE_INCOMING, unread = true),
            ),
            detroit,
        )
        assertEquals(listOf(false, true), rows.map { it.unread })
    }

    @Test
    fun `a row takes a cached name from any of its calls`() {
        val rows = CallLogDigest.rows(
            listOf(e("6165550100", nov1At0030, name = "Ann"), e("6165550100", nov1At0030 + 1_000, name = null)),
            detroit,
        )
        assertEquals("Ann", rows.single().name)
    }

    @Test
    fun `unknown and answered-elsewhere types are never shown as missed`() {
        assertEquals(Kind.ELSEWHERE, CallLogDigest.kindOf(CallLogDigest.TYPE_ANSWERED_EXTERNALLY))
        assertEquals(Kind.ELSEWHERE, CallLogDigest.kindOf(99))
        assertEquals(Kind.MISSED, CallLogDigest.kindOf(3))
        assertEquals(Kind.BLOCKED, CallLogDigest.kindOf(6))
    }

    @Test
    fun `day headings read the way a phone reads them`() {
        val today = LocalDate.of(2026, 11, 4) // a Wednesday
        val us = Locale.US
        assertEquals("TODAY", CallLogDigest.dayLabel(today, today, us))
        assertEquals("YESTERDAY", CallLogDigest.dayLabel(today.minusDays(1), today, us))
        assertEquals("FRIDAY", CallLogDigest.dayLabel(today.minusDays(5), today, us))
        assertEquals("THURSDAY", CallLogDigest.dayLabel(today.minusDays(6), today, us))
        assertEquals("WED 28 OCT", CallLogDigest.dayLabel(today.minusDays(7), today, us))
        assertEquals("30 DEC 2025", CallLogDigest.dayLabel(LocalDate.of(2025, 12, 30), today, us))
        // A date in the future is a date, never today.
        assertEquals("THU 5 NOV", CallLogDigest.dayLabel(today.plusDays(1), today, us))
    }

    @Test
    fun `a call's length reads the way a phone reads it`() {
        assertEquals("0:42", CallLogDigest.duration(42))
        assertEquals("12:05", CallLogDigest.duration(725))
        assertEquals("1:02:03", CallLogDigest.duration(3723))
        assertEquals("0:00", CallLogDigest.duration(-5))
    }
}
