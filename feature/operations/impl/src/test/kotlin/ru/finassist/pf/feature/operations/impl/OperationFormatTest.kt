package ru.finassist.pf.feature.operations.impl

import ru.finassist.pf.core.api.model.Account
import ru.finassist.pf.core.api.model.Category
import ru.finassist.pf.core.api.model.CategoryKind
import ru.finassist.pf.core.api.model.OperationItem
import ru.finassist.pf.core.api.model.OperationKind
import ru.finassist.pf.core.api.model.OperationStatus
import ru.finassist.pf.core.common.money.Money
import ru.finassist.pf.feature.operations.impl.domain.OperationFormat
import ru.finassist.pf.feature.operations.impl.ui.CategoryGrouping
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OperationFormatTest {
    /** The formatter uses no-break spaces; compare with plain ones for readability. */
    private fun String.plain() = replace('\u00A0', ' ').replace('\u202F', ' ')

    private val moscow = ZoneId.of("Europe/Moscow")

    private fun item(
        id: String = "1",
        kind: OperationKind = OperationKind.EXPENSE,
        at: String = "2026-09-25T19:11:00+03:00",
        status: OperationStatus = OperationStatus.POSTED,
    ) = OperationItem(
        id = id, kind = kind, occurredAt = OffsetDateTime.parse(at), amount = Money(234000), currency = "RUB",
        title = "Пятёрочка", categoryId = "cat_supermarkets", categoryName = "Супермаркеты", categoryIcon = "cart",
        isCategoryManual = false, status = status,
        account = Account("Текущий", "··4821"),
        fromAccount = Account("Накопительный", "··0734"), toAccount = Account("Текущий", "··4821"),
    )

    @Test
    fun `expense has minus, income has plus and positive colour, transfer has no sign and is muted`() {
        val expense = OperationFormat.row(item())
        assertEquals("−2 340 ₽", expense.amount.plain())
        assertFalse(expense.income)

        val income = OperationFormat.row(item(kind = OperationKind.INCOME))
        assertEquals("+2 340 ₽", income.amount.plain())
        assertTrue(income.income)

        val transfer = OperationFormat.row(item(kind = OperationKind.OWN_TRANSFER))
        assertEquals("2 340 ₽", transfer.amount.plain())
        assertTrue(transfer.muted)
        assertEquals("Супермаркеты · Накопительный ··0734 → Текущий ··4821", transfer.subtitle.plain())

        assertEquals("2 340 ₽", OperationFormat.row(item(kind = OperationKind.UNKNOWN)).amount.plain())
    }

    @Test
    fun `foreign currency keeps its symbol`() {
        assertEquals("−2 340 $", OperationFormat.money(-Money(234000), "USD", Money.Sign.AUTO).plain())
        assertEquals("2 340 ₽", OperationFormat.money(Money(234000), "RUB", Money.Sign.AUTO).plain())
    }

    @Test
    fun `pending operations are marked as hold`() {
        assertEquals("Супермаркеты · холд", OperationFormat.row(item(status = OperationStatus.PENDING)).subtitle.plain())
    }

    @Test
    fun `groups by local day newest first with today and yesterday labels`() {
        val today = LocalDate.of(2026, 9, 25)
        val groups = OperationFormat.groupByDay(
            listOf(
                item("a", at = "2026-09-24T10:00:00+03:00"),
                item("b", at = "2026-09-25T09:00:00+03:00"),
                // 23:30 UTC on the 24th is the 25th in Moscow.
                item("c", at = "2026-09-24T23:30:00Z"),
                item("d", at = "2025-12-31T12:00:00+03:00"),
            ),
            moscow, today,
        )
        assertEquals(listOf("Сегодня", "Вчера", "31 декабря 2025"), groups.map { it.first.plain() })
        assertEquals(listOf("b", "c"), groups[0].second.map { it.id })
    }

    @Test
    fun `category picker groups system, expense and income without duplicates`() {
        fun cat(id: String, system: Boolean, vararg kinds: CategoryKind, assignable: Boolean = true) =
            Category(id, id, "tag", system, kinds.toList(), assignable)
        val all = listOf(
            cat("own", true, CategoryKind.EXPENSE, CategoryKind.INCOME),
            cat("refund", true, CategoryKind.INCOME),
            cat("food", false, CategoryKind.EXPENSE),
            cat("salary", false, CategoryKind.INCOME),
            cat("transfers", false, CategoryKind.EXPENSE, CategoryKind.INCOME),
            cat("hidden", false, CategoryKind.EXPENSE, assignable = false),
        )
        val both = CategoryGrouping.group(all, setOf(CategoryKind.EXPENSE, CategoryKind.INCOME), onlyAssignable = true, query = "")
        assertEquals(listOf("own", "refund"), both.system.map { it.id })
        assertEquals(listOf("food", "transfers"), both.expense.map { it.id })
        assertEquals(listOf("salary"), both.income.map { it.id })

        val credit = CategoryGrouping.group(all, setOf(CategoryKind.INCOME), onlyAssignable = true, query = "")
        assertEquals(listOf("salary", "transfers"), credit.income.map { it.id })
        assertTrue(credit.expense.isEmpty())

        val searched = CategoryGrouping.group(all, setOf(CategoryKind.EXPENSE), onlyAssignable = false, query = "HID")
        assertEquals(listOf("hidden"), searched.expense.map { it.id })
    }
}
