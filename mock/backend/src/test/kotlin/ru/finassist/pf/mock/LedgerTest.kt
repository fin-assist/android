package ru.finassist.pf.mock

import org.junit.Test
import ru.finassist.pf.core.api.model.Category
import ru.finassist.pf.core.api.model.SystemCategories
import ru.finassist.pf.mock.domain.Catalog
import ru.finassist.pf.mock.domain.Ledger
import ru.finassist.pf.mock.domain.Operation
import ru.finassist.pf.mock.domain.OwnTransferPair
import ru.finassist.pf.mock.ofx.OfxTransaction
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Accounting rules of the ledger: own-transfer pairs, refunds, manual categories, removal of uploads. */
class LedgerTest {
    private val catalog = Catalog()
    private val ledger = Ledger(catalog)

    private fun import(upload: String, vararg t: OfxTransaction) = ledger.importUpload(upload, Acc.ALL, t.toList())
    private fun op(fitId: String): Operation = ledger.all.single { it.fitId == fitId }
    private fun cat(name: String) = catalog.byName(name)

    private val toSavings = trn("d1", "2026-09-10T10:00", 5_000, "Между своими счетами", "Переводы")
    private val fromCurrent = trn("c1", "2026-09-11T10:00", 5_000, "Пополнение", "Переводы", debit = false, account = Acc.SAVINGS)

    // ---- own transfers ----

    @Test
    fun `a self transfer and a matching credit on another account within 3 days form one pair`() {
        import("u1", toSavings, fromCurrent)
        assertEquals(1, ledger.pairCount)
        val pair = assertNotNull(ledger.pairOf(op("d1")))
        assertSame(op("d1"), pair.debit)
        assertSame(op("c1"), pair.credit)
        assertSame(pair, ledger.pair(pair.id))
        // Both sides become «Между своими счетами» and are excluded from analytics as one record.
        assertTrue(op("d1").isOwnTransferCategory && op("c1").isOwnTransferCategory)
        assertFalse(op("c1").isRefund)
    }

    @Test
    fun `no pair across more than 3 days, on the same account, for another amount or with an income-only credit`() {
        import(
            "u1",
            toSavings,
            trn("late", "2026-09-14T10:01", 5_000, "Пополнение", "Переводы", debit = false, account = Acc.SAVINGS),
            trn("same-acc", "2026-09-10T11:00", 5_000, "Пополнение", "Переводы", debit = false),
            trn("other-amount", "2026-09-10T12:00", 4_999, "Пополнение", "Переводы", debit = false, account = Acc.SAVINGS),
            trn("salary", "2026-09-10T13:00", 5_000, "ООО Ромашка", "Зарплата", debit = false, account = Acc.SAVINGS),
        )
        assertEquals(0, ledger.pairCount)
        // A self transfer without a pair is still «Между своими счетами», as a plain operation (api.md 4).
        assertTrue(op("d1").isOwnTransferCategory)
        assertNull(op("d1").pairId)
        assertEquals("cat_transfers", op("late").category.id)
        assertEquals("cat_salary", op("salary").category.id)
    }

    @Test
    fun `a debit that is not a self transfer is never paired, even with a matching credit`() {
        import("u1", trn("d", "2026-09-10T10:00", 5_000, "Иван И.", "Переводы"), fromCurrent)
        assertEquals(0, ledger.pairCount)
        assertEquals("cat_transfers", op("d").category.id)
    }

    @Test
    fun `the closest credit in time wins, and each operation joins at most one pair`() {
        import(
            "u1",
            trn("d1", "2026-09-10T10:00", 1_000, "Между своими счетами", "Переводы"),
            trn("d2", "2026-09-10T18:00", 1_000, "Себе в другой банк", "Переводы"),
            trn("far", "2026-09-12T10:00", 1_000, "Пополнение", "Переводы", debit = false, account = Acc.SAVINGS),
            trn("near", "2026-09-10T11:00", 1_000, "Себе из другого банка", "Переводы", debit = false, account = Acc.OTHER_BANK),
        )
        assertEquals(2, ledger.pairCount)
        assertSame(op("near"), ledger.pairOf(op("d1"))!!.credit)
        assertSame(op("far"), ledger.pairOf(op("d2"))!!.credit)
    }

    @Test
    fun `a pair spread over two uploads forms on the second one and is not duplicated on re-import`() {
        val (added1, dup1) = import("u1", toSavings)
        assertEquals(1 to 0, added1.size to dup1)
        assertEquals(0, ledger.pairCount)
        import("u2", fromCurrent)
        assertEquals(1, ledger.pairCount)
        val (added3, dup3) = import("u3", toSavings, fromCurrent)
        assertEquals(0 to 2, added3.size to dup3)
        assertEquals(1, ledger.pairCount)
        assertEquals(setOf("u1", "u3"), op("d1").uploadIds)
    }

    // ---- removal of an upload ----

    @Test
    fun `removing an upload keeps operations another upload also contains`() {
        import("u1", toSavings, trn("x", "2026-09-01T10:00", 100, "Магазин", "Супермаркеты"))
        import("u2", toSavings)
        ledger.removeUpload("u1")
        assertEquals(listOf("d1"), ledger.all.map { it.fitId })
        ledger.removeUpload("u2")
        assertTrue(ledger.all.isEmpty())
    }

    @Test
    fun `the surviving side of a removed pair is a plain operation again`() {
        import("u1", toSavings)
        import("u2", fromCurrent)
        val pairId = ledger.pairOf(op("d1"))!!.id
        ledger.removeUpload("u2")
        assertEquals(0, ledger.pairCount)
        assertNull(ledger.pair(pairId))
        assertNull(op("d1").pairId)
        // The debit is a self transfer by its name; it keeps the category without a pair.
        assertTrue(op("d1").isOwnTransferCategory)

        // The other way round: the plain «Переводы» credit loses the pair and gets its bank category back.
        ledger.clear()
        import("u1", toSavings)
        import("u2", fromCurrent)
        ledger.removeUpload("u1")
        assertNull(op("c1").pairId)
        assertEquals("cat_transfers", op("c1").category.id)
    }

    @Test
    fun `clear forgets operations, pairs and manual categories`() {
        import("u1", toSavings, fromCurrent)
        val shop = trn("s", "2026-09-01T10:00", 100, "Магазин", "Супермаркеты")
        import("u1", shop)
        ledger.changeCategory(op("s").id, cat("Рестораны"))
        ledger.clear()
        assertTrue(ledger.all.isEmpty())
        assertEquals(0, ledger.pairCount)
        import("u2", shop)
        assertEquals("cat_supermarkets", op("s").category.id)
        assertFalse(op("s").isManual)
    }

    // ---- manual categories ----

    @Test
    fun `a manual category survives deleting and re-importing the statement`() {
        val shop = trn("s", "2026-09-01T10:00", 100, "Магазин", "Супермаркеты")
        import("u1", shop)
        val (items, replaced) = ledger.changeCategory(op("s").id, cat("Рестораны"))
        assertEquals(listOf(op("s")), items)
        assertNull(replaced)
        assertTrue(op("s").isManual)

        ledger.removeUpload("u1")
        import("u2", shop)
        assertEquals("cat_restaurants", op("s").category.id)
        assertTrue(op("s").isManual)
    }

    @Test
    fun `a category of the wrong kind or not assignable is rejected`() {
        import("u1", trn("s", "2026-09-01T10:00", 100, "Магазин", "Супермаркеты"), trn("in", "2026-09-02T10:00", 100, "ООО", "Зарплата", debit = false))
        assertFailsWith<IllegalArgumentException> { ledger.changeCategory(op("s").id, cat("Зарплата")) }
        assertFailsWith<IllegalArgumentException> { ledger.changeCategory(op("in").id, cat("Супермаркеты")) }
        val locked = cat("Супермаркеты").copy(assignable = false)
        assertFailsWith<IllegalArgumentException> { ledger.changeCategory(op("s").id, locked) }
        assertFailsWith<NoSuchElementException> { ledger.changeCategory("missing", cat("Рестораны")) }
        assertEquals("cat_supermarkets", op("s").category.id)
        assertFalse(op("s").isManual)
    }

    @Test
    fun `marking a debit as own transfer pairs it with a matching credit and replaces the single record`() {
        val debit = trn("d", "2026-09-10T10:00", 3_000, "Иван И.", "Переводы")
        import("u1", debit, trn("c", "2026-09-10T12:00", 3_000, "Пополнение", "Переводы", debit = false, account = Acc.SAVINGS))
        assertEquals(0, ledger.pairCount)
        val (items, replaced) = ledger.changeCategory(op("d").id, catalog.ownTransfer)
        val pair = items.single() as OwnTransferPair
        assertSame(op("d"), pair.debit)
        assertSame(op("c"), pair.credit)
        assertEquals(op("d").id, replaced)
    }

    @Test
    fun `own transfer chosen for an operation without a match stays a single own-transfer operation`() {
        import("u1", trn("d", "2026-09-10T10:00", 3_000, "Иван И.", "Переводы"))
        val (items, replaced) = ledger.changeCategory(op("d").id, catalog.ownTransfer)
        assertEquals(listOf(op("d")), items)
        assertNull(replaced)
        assertTrue(op("d").isOwnTransferCategory && op("d").isManual)
    }

    @Test
    fun `own transfer chosen for a pair changes nothing`() {
        import("u1", toSavings, fromCurrent)
        val pair = ledger.pairOf(op("d1"))!!
        val (items, replaced) = ledger.changeCategory(pair.id, catalog.ownTransfer)
        assertEquals(listOf<Any>(pair), items)
        assertNull(replaced)
        assertEquals(1, ledger.pairCount)
    }

    @Test
    fun `an expense category splits a pair - the debit takes it, the credit gets its bank category back`() {
        import("u1", toSavings, fromCurrent)
        val pair = ledger.pairOf(op("d1"))!!
        // Addressed by one side's id: the whole pair is meant.
        val (items, replaced) = ledger.changeCategory(op("c1").id, cat("Рестораны"))
        assertEquals(listOf(op("d1"), op("c1")), items)
        assertEquals(pair.id, replaced)
        assertEquals(0, ledger.pairCount)
        assertEquals("cat_restaurants", op("d1").category.id)
        assertTrue(op("d1").isManual)
        assertEquals("cat_transfers", op("c1").category.id)
        assertFalse(op("c1").isManual)
        // The split is remembered: a later import does not pair them again.
        import("u2", trn("x", "2026-09-20T10:00", 1, "Магазин", "Супермаркеты"))
        assertEquals(0, ledger.pairCount)
        assertNull(op("c1").pairId)
    }

    @Test
    fun `an income category splits a pair onto the credit, and the split side is not re-paired by its name`() {
        import("u1", toSavings, fromCurrent)
        val pair = ledger.pairOf(op("d1"))!!
        ledger.changeCategory(pair.id, cat("Зарплата"))
        assertEquals("cat_salary", op("c1").category.id)
        assertTrue(op("c1").isManual)
        // «Между своими счетами» by name, but the user split it: back to the bank category.
        assertEquals("cat_transfers", op("d1").category.id)
        assertTrue(op("d1").unpaired)
        assertEquals(0, ledger.pairCount)
    }

    @Test
    fun `choosing own transfer again for a split side pairs it once more`() {
        import("u1", toSavings, fromCurrent)
        ledger.changeCategory(op("d1").id, cat("Рестораны"))
        val (items, replaced) = ledger.changeCategory(op("d1").id, catalog.ownTransfer)
        val pair = items.single() as OwnTransferPair
        assertEquals(op("d1").id, replaced)
        assertSame(op("c1"), pair.credit)
        assertFalse(op("d1").unpaired)
    }

    // ---- refunds ----

    @Test
    fun `a credit in an expense bank category is a refund of that category`() {
        import("u1", trn("r", "2026-09-05T10:00", 300, "Аптека 36,6", "Аптеки", debit = false))
        assertTrue(op("r").isRefund)
        assertEquals("cat_pharmacy", op("r").refundTarget?.id)
    }

    @Test
    fun `a refund reduces the final category of an earlier debit with the same description`() {
        import(
            "u1",
            trn("buy", "2026-09-01T10:00", 1_000, "OZON", "Маркетплейсы"),
            trn("back", "2026-09-05T10:00", 400, "ozon", "Пополнения", debit = false),
        )
        assertTrue(op("back").isRefund)
        assertEquals("cat_marketplaces", op("back").refundTarget?.id)

        // The purchase was recategorised by hand: the refund follows its final category.
        ledger.changeCategory(op("buy").id, cat("Одежда и обувь"))
        assertEquals("cat_clothes", op("back").refundTarget?.id)
    }

    @Test
    fun `without a same-name debit the refund reduces a same-amount debit on that account within 60 days`() {
        import(
            "u1",
            trn("old", "2026-06-01T10:00", 700, "Кино", "Развлечения"),
            trn("buy", "2026-08-20T10:00", 700, "Магазин обуви", "Одежда и обувь"),
            trn("other-acc", "2026-09-01T10:00", 700, "Цветы", "Цветы", account = Acc.SAVINGS),
            trn("back", "2026-09-05T10:00", 700, "Возврат покупки", "Переводы", debit = false),
        )
        assertTrue(op("back").isRefund)
        assertEquals("cat_clothes", op("back").refundTarget?.id)

        // 61 days before is too far: nothing to reduce.
        ledger.clear()
        import(
            "u1",
            trn("buy", "2026-07-06T09:00", 800, "Магазин обуви", "Одежда и обувь"),
            trn("back", "2026-09-05T10:00", 800, "Возврат покупки", "Переводы", debit = false),
        )
        assertTrue(op("back").isRefund)
        assertEquals(SystemCategories.UNCATEGORIZED, op("back").refundTarget?.id) // «Без категории» (docs/calc.md 3.3)
    }

    @Test
    fun `a refund that matches nothing is uncategorised`() {
        import("u1", trn("back", "2026-09-05T10:00", 123, "ВОЗВРАТ средств", "Переводы", debit = false))
        assertTrue(op("back").isRefund)
        assertEquals(SystemCategories.UNCATEGORIZED, op("back").refundTarget?.id) // «Без категории» (docs/calc.md 3.3)
    }

    @Test
    fun `income-only and transfer credits without a matching purchase are plain income`() {
        import(
            "u1",
            trn("pay", "2026-09-05T10:00", 50_000, "ООО Ромашка", "Зарплата", debit = false),
            trn("gift", "2026-09-06T10:00", 1_000, "Иван И.", "Переводы", debit = false),
            // An earlier transfer debit with the same name does not make a transfer back a refund.
            trn("lend", "2026-09-01T10:00", 1_000, "Петр П.", "Переводы"),
            trn("repay", "2026-09-07T10:00", 1_000, "Петр П.", "Переводы", debit = false),
        )
        assertFalse(op("pay").isRefund)
        assertFalse(op("gift").isRefund)
        assertFalse(op("repay").isRefund)
    }

    @Test
    fun `a refund stops being one when the purchase that explained it is removed`() {
        import("u1", trn("buy", "2026-09-01T10:00", 1_000, "OZON", "Маркетплейсы"))
        import("u2", trn("back", "2026-09-05T10:00", 400, "OZON", "Пополнения", debit = false))
        assertTrue(op("back").isRefund)
        ledger.removeUpload("u1")
        assertFalse(op("back").isRefund)
        assertNull(op("back").refundTarget)
        assertEquals("cat_top_ups", op("back").category.id)
    }

    @Test
    fun `a refund recategorised by hand is no longer a refund and stays so`() {
        import("u1", trn("r", "2026-09-05T10:00", 300, "Аптека", "Аптеки", debit = false))
        ledger.changeCategory(op("r").id, cat("Бонусы"))
        assertFalse(op("r").isRefund)
        assertNull(op("r").refundTarget)
        import("u2", trn("x", "2026-09-06T10:00", 1, "Магазин", "Супермаркеты"))
        assertEquals("cat_bonuses", op("r").category.id)
    }

    @Test
    fun `a refund of a refund or of a transfer to self has no target`() {
        import(
            "u1",
            trn("self", "2026-09-01T10:00", 900, "Между своими счетами", "Переводы"),
            trn("back", "2026-09-03T10:00", 900, "Возврат", "Наличные", debit = false),
        )
        // The same-amount debit on the account is an own transfer: it is not a category to reduce.
        assertTrue(op("back").isRefund)
        assertEquals(SystemCategories.UNCATEGORIZED, op("back").refundTarget?.id) // «Без категории» (docs/calc.md 3.3)
    }

    @Test
    fun `an unknown bank category gets a stable slug and is an expense one`() {
        import("u1", trn("x", "2026-09-01T10:00", 100, "Кафе", "Новая категория!"))
        val c: Category = op("x").category
        assertEquals("cat_novaya_kategoriya", c.id)
        assertEquals(c, catalog.byId("cat_novaya_kategoriya"))
        assertEquals(SystemCategories.OWN_TRANSFER, catalog.ownTransfer.id)
    }

    // ---- docs/calc.md 3.2, 3.3: branches the calculation fixtures do not reach ----

    @Test
    fun `at an equal distance the earlier credit pairs, at an equal time the smaller account and FITID`() {
        import(
            "u1",
            trn("d", "2026-09-10T12:00", 5_000, "Между своими счетами", "Переводы"),
            trn("late", "2026-09-10T12:01", 5_000, "Пополнение", "Переводы", debit = false, account = Acc.SAVINGS),
            trn("early", "2026-09-10T11:59", 5_000, "Пополнение", "Переводы", debit = false, account = Acc.OTHER_BANK),
        )
        assertSame(op("early"), ledger.pairOf(op("d"))?.credit)

        ledger.clear()
        import(
            "u1",
            trn("d", "2026-09-10T12:00", 5_000, "Между своими счетами", "Переводы"),
            trn("b", "2026-09-10T12:05", 5_000, "Пополнение", "Переводы", debit = false, account = Acc.SAVINGS),
            trn("a", "2026-09-10T12:05", 5_000, "Пополнение", "Переводы", debit = false, account = Acc.SAVINGS),
        )
        assertSame(op("a"), ledger.pairOf(op("d"))?.credit)
    }

    @Test
    fun `a transfer to self pairs only with a credit in the same currency`() {
        import(
            "u1",
            trn("d", "2026-09-10T12:00", 5_000, "Между своими счетами", "Переводы"),
            trn("usd", "2026-09-10T12:01", 5_000, "Пополнение", "Переводы", debit = false, account = Acc.SAVINGS).copy(currency = "USD"),
        )
        assertNull(ledger.pairOf(op("d")))
        assertEquals(SystemCategories.OWN_TRANSFER, op("d").category.id) // a transfer to self by its description
        assertEquals("cat_transfers", op("usd").category.id)
    }

    @Test
    fun `a manual category other than own transfer makes a debit no transfer to self, so it does not pair`() {
        import("u1", trn("d", "2026-09-10T12:00", 5_000, "Себе в другой банк", "Переводы"))
        ledger.changeCategory(op("d").id, cat("Подарки и творчество"))
        import("u2", trn("c", "2026-09-10T12:01", 5_000, "Себе из другого банка", "Переводы", debit = false, account = Acc.SAVINGS))
        assertNull(ledger.pairOf(op("d")))
        assertEquals("cat_gifts", op("d").category.id)
        assertEquals(SystemCategories.OWN_TRANSFER, op("c").category.id)
    }

    @Test
    fun `when the last same-name debit is no category to reduce, the refund falls to the same-amount debit`() {
        import(
            "u1",
            trn("first", "2026-09-01T10:00", 1_000, "Магазин", "Супермаркеты"),
            trn("pharmacy", "2026-09-02T10:00", 500, "Аптека", "Аптеки"),
            trn("last", "2026-09-03T10:00", 2_000, "Магазин", "Супермаркеты"),
        )
        ledger.changeCategory(op("last").id, catalog.ownTransfer)
        import("u2", trn("back", "2026-09-05T10:00", 500, "Магазин", "Финансы", debit = false))
        assertTrue(op("back").isRefund)
        assertEquals("cat_pharmacy", op("back").refundTarget?.id)
    }

    @Test
    fun `the same-amount rule skips debits of the bank category Переводы`() {
        import(
            "u1",
            trn("sent", "2026-09-01T10:00", 300, "Петр П.", "Переводы"),
            trn("back", "2026-09-05T10:00", 300, "Возврат денег", "Финансы", debit = false),
        )
        assertTrue(op("back").isRefund)
        assertEquals(SystemCategories.UNCATEGORIZED, op("back").refundTarget?.id)
    }

    @Test
    fun `MEMO Возврат - a credit is a refund, a debit is in Без категории, no category Возврат is added`() {
        import(
            "u1",
            trn("buy", "2026-09-01T10:00", 3_000, "Ozon", "Маркетплейсы"),
            trn("back", "2026-09-03T10:00", 1_200, "Ozon", "Возврат", debit = false),
            trn("fee", "2026-09-04T10:00", 500, "Банк", "Возврат"),
        )
        assertEquals("cat_marketplaces", op("back").refundTarget?.id)
        assertEquals(SystemCategories.UNCATEGORIZED, op("fee").category.id)
        assertEquals(1, catalog.categories.count { it.name == "Возврат" })
    }
}
