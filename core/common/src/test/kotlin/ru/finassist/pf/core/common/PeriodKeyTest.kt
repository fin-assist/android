package ru.finassist.pf.core.common

import org.junit.Test
import ru.finassist.pf.core.common.time.PeriodKey
import ru.finassist.pf.core.common.time.PeriodType
import ru.finassist.pf.core.common.time.pluralize
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.assertEquals

class PeriodKeyTest {
    @Test
    fun `parses month quarter and year keys`() {
        assertEquals(PeriodType.MONTH, PeriodKey("2026-09").type)
        assertEquals(PeriodType.QUARTER, PeriodKey("2026-Q3").type)
        assertEquals(PeriodType.YEAR, PeriodKey("2026").type)
        assertEquals(9, PeriodKey("2026-09").index)
        assertEquals(3, PeriodKey("2026-Q3").index)
    }

    @Test
    fun `ranges are half-open calendar periods in the given zone`() {
        val zone = ZoneId.of("Europe/Moscow")
        val range = PeriodKey("2026-Q3").toRange(zone)
        assertEquals("2026-07-01T00:00+03:00", range.from.toString())
        assertEquals("2026-10-01T00:00+03:00", range.to.toString())
    }

    @Test
    fun `neighbours wrap across year boundaries`() {
        assertEquals(PeriodKey("2027-01"), PeriodKey("2026-12").next())
        assertEquals(PeriodKey("2025-Q4"), PeriodKey("2026-Q1").previous())
        assertEquals(PeriodKey("2025"), PeriodKey("2026").previous())
    }

    @Test
    fun `key of a date`() {
        assertEquals(PeriodKey("2026-Q3"), PeriodKey.of(PeriodType.QUARTER, LocalDate.of(2026, 9, 25)))
    }

    @Test
    fun `russian plurals`() {
        assertEquals("операция", pluralize(1, "операция", "операции", "операций"))
        assertEquals("операции", pluralize(3, "операция", "операции", "операций"))
        assertEquals("операций", pluralize(11, "операция", "операции", "операций"))
        assertEquals("операций", pluralize(1086, "операция", "операции", "операций"))
        assertEquals("операция", pluralize(21, "операция", "операции", "операций"))
    }
}
