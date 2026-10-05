package ru.finassist.pf.core.common

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.finassist.pf.core.common.time.PeriodKey
import ru.finassist.pf.core.common.time.RussianDates
import java.time.LocalDate
import java.time.YearMonth

class RussianDatesTest {
    private val nb = " "

    @Test fun sameMonthRange() =
        assertEquals("1–25${nb}сентября", RussianDates.dayRange(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 26)))

    @Test fun crossMonthRange() =
        assertEquals("29${nb}июня — 25${nb}сентября", RussianDates.dayRange(LocalDate.of(2026, 6, 29), LocalDate.of(2026, 9, 26)))

    @Test fun crossYearRange() =
        assertEquals(
            "1${nb}октября${nb}2025 — 30${nb}сентября${nb}2026",
            RussianDates.dayRange(LocalDate.of(2025, 10, 1), LocalDate.of(2026, 10, 1)),
        )

    @Test fun monthRangeSameYear() =
        assertEquals("апрель — сентябрь${nb}2026", RussianDates.monthRange(YearMonth.of(2026, 4), YearMonth.of(2026, 9)))

    @Test fun plurals() {
        assertEquals("1${nb}операция", RussianDates.plural(1, "операция", "операции", "операций"))
        assertEquals("3${nb}файла", RussianDates.plural(3, "файл", "файла", "файлов"))
        assertEquals("1${nb}086${nb}операций", RussianDates.plural(1086, "операция", "операции", "операций"))
        assertEquals("11${nb}строк", RussianDates.plural(11, "строка", "строки", "строк"))
    }

    @Test fun periodKeys() {
        assertEquals(PeriodKey.Month(YearMonth.of(2026, 9)), PeriodKey.parse("2026-09"))
        assertEquals(PeriodKey.Quarter(2026, 3), PeriodKey.parse("2026-Q3"))
        assertEquals(PeriodKey.Year(2026), PeriodKey.parse("2026"))
        assertEquals(PeriodKey.Unknown("2026-W40"), PeriodKey.parse("2026-W40"))
        assertEquals("2026-09", PeriodKey.parse("2026-09").wire)
    }
}
