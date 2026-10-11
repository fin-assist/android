package ru.finassist.pf.feature.operations.impl.domain

import ru.finassist.pf.core.api.model.OperationItem
import ru.finassist.pf.core.api.model.OperationKind
import ru.finassist.pf.core.api.model.OperationStatus
import ru.finassist.pf.core.api.model.OperationsFilter
import ru.finassist.pf.core.common.money.Money
import ru.finassist.pf.core.common.time.DateRange
import ru.finassist.pf.feature.operations.impl.ui.searchSummary
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OperationFormatTest {
    private val zone = ZoneId.of("Europe/Moscow")
    private val today = LocalDate.of(2026, 9, 28)

    private fun at(y: Int, m: Int, d: Int, h: Int = 12) = OffsetDateTime.of(y, m, d, h, 0, 0, 0, ZoneOffset.ofHours(3))

    private fun op(id: String, at: OffsetDateTime, status: OperationStatus = OperationStatus.POSTED) = OperationItem(
        id = id, kind = OperationKind.EXPENSE, occurredAt = at, amount = Money(820_00), currency = "RUB", title = "Самокат",
        categoryId = "c", categoryName = "Супермаркеты", categoryIcon = "cart", isCategoryManual = false, status = status,
    )

    private fun String.plain() = replace(' ', ' ')

    @Test fun `search groups by month newest first with the day as the subtitle`() {
        val groups = OperationFormat.searchGroups(
            listOf(op("a", at(2026, 8, 2)), op("b", at(2026, 9, 11)), op("c", at(2026, 9, 24)), op("d", at(2026, 8, 29))),
            zone, today,
        )
        assertEquals(listOf("Сентябрь", "Август"), groups.map { it.first })
        assertEquals(listOf("c", "b"), groups[0].second.map { it.id })
        assertEquals(listOf("d", "a"), groups[1].second.map { it.id })
        assertEquals("24 сентября", groups[0].second[0].subtitle.plain())
    }

    @Test fun `another year is named in the group and the day`() {
        val groups = OperationFormat.searchGroups(listOf(op("a", at(2025, 10, 3))), zone, today)
        assertEquals("Октябрь 2025", groups.single().first)
        assertEquals("3 октября 2025", groups.single().second.single().subtitle.plain())
    }

    @Test fun `a pending hold keeps its mark next to the day`() {
        val row = OperationFormat.searchGroups(listOf(op("a", at(2026, 9, 24), OperationStatus.PENDING)), zone, today).single().second.single()
        assertEquals("24 сентября · холд", row.subtitle.plain())
    }

    @Test fun `the day follows the local zone`() {
        val lateUtc = OffsetDateTime.of(2026, 9, 30, 22, 30, 0, 0, ZoneOffset.UTC) // 1 October in Moscow
        val groups = OperationFormat.searchGroups(listOf(op("a", lateUtc)), zone, today)
        assertEquals("Октябрь", groups.single().first)
        assertEquals("1 октября", groups.single().second.single().subtitle.plain())
    }

    @Test fun `window start is known only from a default search`() {
        val range = DateRange(at(2025, 10, 1, 0), at(2026, 9, 29, 0))
        assertEquals(LocalDate.of(2025, 10, 1), OperationFormat.defaultWindowStart(range, OperationsFilter(q = "x"), allTime = false))
        assertNull(OperationFormat.defaultWindowStart(range, OperationsFilter(q = "x"), allTime = true))
        assertNull(OperationFormat.defaultWindowStart(range, OperationsFilter(q = "x", from = at(2026, 1, 1, 0)), allTime = false))
        assertNull(OperationFormat.defaultWindowStart(range, OperationsFilter(q = "x", to = at(2026, 2, 1, 0)), allTime = false))
        assertNull(OperationFormat.defaultWindowStart(null, OperationsFilter(q = "x"), allTime = false))
    }

    @Test fun `window start label`() = assertEquals("с октября 2025", OperationFormat.windowStart(LocalDate.of(2025, 10, 1)))

    @Test fun `kind labels of the operation card`() {
        assertEquals("Списание", OperationFormat.kindLabel(OperationKind.EXPENSE))
        assertEquals("Зачисление", OperationFormat.kindLabel(OperationKind.INCOME))
        assertEquals("Перевод", OperationFormat.kindLabel(OperationKind.OWN_TRANSFER))
        assertEquals("Операция", OperationFormat.kindLabel(OperationKind.UNKNOWN))
    }

    @Test fun `numeric date and time in the local zone`() {
        assertEquals("25.09.2026, 19:11", OperationFormat.numericDateTime(OffsetDateTime.of(2026, 9, 25, 16, 11, 0, 0, ZoneOffset.UTC), zone))
        assertEquals("01.10.2026, 01:30", OperationFormat.numericDateTime(OffsetDateTime.of(2026, 9, 30, 22, 30, 0, 0, ZoneOffset.UTC), zone))
    }

    @Test fun `search summary agrees with the count`() {
        assertEquals("Найдена 1 операция", searchSummary(1).plain())
        assertEquals("Найдено 3 операции", searchSummary(3).plain())
        assertEquals("Найдено 9 операций", searchSummary(9).plain())
        assertEquals("Найдена 21 операция", searchSummary(21).plain())
        assertEquals("Найдено 1 086 операций", searchSummary(1086).plain())
    }
}
