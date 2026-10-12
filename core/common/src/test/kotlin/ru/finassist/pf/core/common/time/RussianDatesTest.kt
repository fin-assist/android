package ru.finassist.pf.core.common.time

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class RussianDatesTest {
    private fun d(m: Int, day: Int, y: Int = 2026) = LocalDate.of(y, m, day)

    /** Labels use non-breaking spaces; compare them as plain ones. */
    private fun range(from: LocalDate, to: LocalDate, withYear: Boolean = false) =
        RussianDates.dayRange(from, to, withYear).replace('\u00A0', ' ')

    @Test fun `a range inside one month uses an en dash without spaces`() =
        assertEquals("1–25 сентября", range(d(9, 1), d(9, 25)))

    @Test fun `a range across months uses an em dash with spaces`() =
        assertEquals("1 апреля — 25 сентября", range(d(4, 1), d(9, 25)))

    @Test fun `a range across years names both years`() {
        val expected = "1 октября 2025 — 25 сентября 2026"
        assertEquals(expected, range(d(10, 1, 2025), d(9, 25)))
        assertEquals(expected, range(d(10, 1, 2025), d(9, 25), withYear = true))
    }

    @Test fun `a single day`() = assertEquals("25 сентября", range(d(9, 25), d(9, 25)))

    @Test fun `with the year a range inside one year ends with it`() {
        assertEquals("1–25 сентября 2026", range(d(9, 1), d(9, 25), withYear = true))
        assertEquals("1 апреля — 25 сентября 2026", range(d(4, 1), d(9, 25), withYear = true))
        assertEquals("25 сентября 2026", range(d(9, 25), d(9, 25), withYear = true))
    }
}
