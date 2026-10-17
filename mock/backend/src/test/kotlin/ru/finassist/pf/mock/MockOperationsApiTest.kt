package ru.finassist.pf.mock

import kotlinx.coroutines.runBlocking
import org.junit.Test
import ru.finassist.pf.core.api.model.ChangeCategoryRequest
import ru.finassist.pf.core.api.model.ErrorCodes
import ru.finassist.pf.core.api.model.OperationKind
import ru.finassist.pf.core.api.model.OperationKindFilter
import ru.finassist.pf.core.api.model.OperationsFilter
import ru.finassist.pf.core.api.model.OperationsQuery
import ru.finassist.pf.core.api.model.SystemCategories
import ru.finassist.pf.core.api.model.TransferMode
import ru.finassist.pf.core.common.error.AppError
import ru.finassist.pf.core.common.money.Money
import ru.finassist.pf.mock.api.MockOperationsApi
import ru.finassist.pf.mock.api.MockStatementsApi
import ru.finassist.pf.mock.domain.AnalyticsEngine
import java.time.OffsetDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Feed, search semantics (api.md 4.1), details and category change (4.3) over a small hand-made ledger. */
class MockOperationsApiTest {
    private val backend = testBackend().apply {
        load(
            statement(
                "2025-08-01", "2026-09-30",
                trn("old", "2025-08-15T10:00", 10, "Старая покупка", "Супермаркеты"),
                trn("buy", "2026-09-01T10:00", 1_000, "OZON", "Маркетплейсы"),
                trn("back", "2026-09-05T10:00", 400, "OZON", "Пополнения", debit = false),
                trn("salary", "2026-09-05T11:00", 90_000, "ООО Ромашка", "Зарплата", debit = false),
                trn("refund", "2026-09-06T10:00", 123, "Возврат средств", "Переводы", debit = false),
                trn("ivan", "2026-09-07T10:00", 2_000, "Иван И.", "Переводы"),
                trn("fee", "2026-09-08T10:00", 99, "Плата за обслуживание", "Услуги банка"),
                trn("tree", "2026-09-09T10:00", 0, "Ёлочные игрушки", "Подарки и творчество").copy(amountMinor = 120_050),
                trn("self", "2026-09-10T10:00", 5_000, "Между своими счетами", "Переводы"),
                trn("self-in", "2026-09-10T11:00", 5_000, "Пополнение", "Переводы", debit = false, account = Acc.SAVINGS),
                trn("abroad", "2026-09-11T10:00", 700, "Себе в другой банк", "Переводы"),
            ),
        )
    }
    private val api = MockOperationsApi(backend)

    private fun id(fitId: String) = backend.ledger.all.single { it.fitId == fitId }.id
    private val pairId get() = backend.ledger.pairOf(backend.ledger.byId(id("self"))!!)!!.id

    private suspend fun search(filter: OperationsFilter, allTime: Boolean = false) = api.listOperations(OperationsQuery(filter, allTime))

    /** Fit ids of the found records; a pair is shown as `pair`. */
    private suspend fun found(filter: OperationsFilter, allTime: Boolean = false): Set<String> =
        search(filter, allTime).items.map { item -> backend.ledger.byId(item.id)?.fitId ?: "pair".also { assertEquals(pairId, item.id) } }.toSet()

    // ---- search ----

    @Test
    fun `kind without transfer mode - refunds are plain credits, transfers to self are neither`() = runBlocking<Unit> {
        assertEquals(setOf("buy", "ivan", "fee", "tree"), found(OperationsFilter(kind = OperationKindFilter.EXPENSE)))
        assertEquals(setOf("back", "salary", "refund"), found(OperationsFilter(kind = OperationKindFilter.INCOME)))
    }

    @Test
    fun `kind with transfer mode follows analytics - refunds are expenses`() = runBlocking<Unit> {
        val with = TransferMode.WITH
        assertEquals(setOf("buy", "ivan", "fee", "tree", "back", "refund"), found(OperationsFilter(kind = OperationKindFilter.EXPENSE, transferMode = with)))
        assertEquals(setOf("salary"), found(OperationsFilter(kind = OperationKindFilter.INCOME, transferMode = with)))
        // `without` also drops «Переводы» in both directions; a refund is in «Возврат», not in «Переводы».
        val without = found(OperationsFilter(kind = OperationKindFilter.EXPENSE, transferMode = TransferMode.WITHOUT))
        assertEquals(setOf("buy", "fee", "tree", "back", "refund"), without)
    }

    @Test
    fun `transfer mode alone hides pairs, an unknown mode counts as with`() = runBlocking<Unit> {
        val with = found(OperationsFilter(transferMode = TransferMode.WITH))
        assertFalse("pair" in with)
        assertTrue("ivan" in with && "abroad" in with)
        assertEquals(with, found(OperationsFilter(transferMode = TransferMode.UNKNOWN)))
        assertFalse("ivan" in found(OperationsFilter(transferMode = TransferMode.WITHOUT)))
    }

    @Test
    fun `a category finds refunds of it only in analytics mode, own transfer finds pairs`() = runBlocking<Unit> {
        assertEquals(setOf("buy"), found(OperationsFilter(categoryId = "cat_marketplaces")))
        assertEquals(setOf("buy", "back"), found(OperationsFilter(categoryId = "cat_marketplaces", transferMode = TransferMode.WITH)))
        assertEquals(setOf("pair", "abroad"), found(OperationsFilter(categoryId = SystemCategories.OWN_TRANSFER)))
        assertEquals(setOf("back", "refund"), found(OperationsFilter(categoryId = SystemCategories.REFUND)))
    }

    @Test
    fun `amount bounds are inclusive`() = runBlocking<Unit> {
        assertEquals(setOf("buy", "ivan", "tree"), found(OperationsFilter(amountFrom = Money(100_000), amountTo = Money(200_000))))
    }

    @Test
    fun `text query matches names and categories ignoring case and yo, amounts and the last 4 digits of an account`() = runBlocking<Unit> {
        assertEquals(setOf("tree"), found(OperationsFilter(q = "ЕЛОЧНЫЕ")))
        assertEquals(setOf("buy"), found(OperationsFilter(q = "маркетплейс")))
        assertEquals(setOf("tree"), found(OperationsFilter(q = "1 200,50")))
        assertEquals(setOf("ivan"), found(OperationsFilter(q = "2000")))
        assertEquals(setOf("pair"), found(OperationsFilter(q = "5 000")))
        assertEquals(setOf("pair"), found(OperationsFilter(q = "перевод между")))
        assertEquals(setOf("pair"), found(OperationsFilter(q = "2222")))
        // A blank query is no filter at all: the feed answers.
        assertNull(search(OperationsFilter(q = "  ")).totalCount)
    }

    @Test
    fun `selections - bank fees, uncategorised refunds and a merchant`() = runBlocking<Unit> {
        assertEquals(setOf("fee"), found(OperationsFilter(selection = AnalyticsEngine.SELECTION_BANK_FEES)))
        assertEquals(setOf("refund"), found(OperationsFilter(selection = AnalyticsEngine.SELECTION_UNCATEGORIZED)))
        assertEquals(setOf("buy", "back"), found(OperationsFilter(selection = "s_merchant_ozon")))
    }

    @Test
    fun `an unknown or malformed selection is SELECTION_NOT_FOUND`() = runBlocking<Unit> {
        val smallSelections = listOf(
            "s_small_bad", "s_small_merchant_2026-13_with_x", "s_small_merchant_2026-09_sideways_x",
            "s_small_merchant_2026-09_with_00000000-0000-0000-0000-000000000000",
        )
        for (selection in listOf("s_whatever", "s_merchant_nobody", "s_regular_rp_nobody", "s_regular_all_bad", "s_regular_all_2026-13_with") + smallSelections) {
            val e = assertFailsWith<AppError.Api>(selection) { search(OperationsFilter(selection = selection)) }
            assertEquals(ErrorCodes.SELECTION_NOT_FOUND to 422, e.code to e.httpStatus, selection)
        }
        // A well-formed regular selection whose card has no payments matches nothing rather than failing.
        assertTrue(search(OperationsFilter(selection = "s_regular_all_2026-09_with")).items.isEmpty())
    }

    @Test
    fun `with every statement deleted a merchant selection is SELECTION_NOT_FOUND, a fixed one matches nothing`() = runBlocking<Unit> {
        // FIN-76: an assistant chip about a merchant, opened after the user deleted all statements.
        assertEquals(setOf("buy", "back"), found(OperationsFilter(selection = "s_merchant_ozon")))
        MockStatementsApi(backend).deleteStatement(backend.uploads.single().id)
        assertTrue(backend.ledger.all.isEmpty())

        for (selection in listOf("s_merchant_ozon", "s_regular_rp_ozon", "s_whatever")) {
            val e = assertFailsWith<AppError.Api>(selection) { search(OperationsFilter(selection = selection)) }
            assertEquals(ErrorCodes.SELECTION_NOT_FOUND to 422, e.code to e.httpStatus, selection)
        }
        // Selections that do not name anything in the ledger stay valid: nothing to show.
        for (selection in listOf(AnalyticsEngine.SELECTION_BANK_FEES, AnalyticsEngine.SELECTION_UNCATEGORIZED)) {
            assertEquals(0, search(OperationsFilter(selection = selection)).totalCount, selection)
        }
    }

    @Test
    fun `search covers the last 12 months by default and says if there is older data`() = runBlocking<Unit> {
        val result = search(OperationsFilter(q = "покупка"))
        assertTrue(result.items.isEmpty())
        assertEquals(true, result.hasOlderData)
        assertEquals(OffsetDateTime.parse("2025-10-06T00:00:00+03:00"), result.range?.from)
        assertEquals(OffsetDateTime.parse("2026-10-07T00:00:00+03:00"), result.range?.to)

        val allTime = search(OperationsFilter(q = "покупка"), allTime = true)
        assertEquals(1, allTime.totalCount)
        assertNull(allTime.range)
        assertNull(allTime.hasOlderData)

        val explicit = search(OperationsFilter(from = OffsetDateTime.parse("2025-08-01T00:00:00+03:00")))
        assertTrue(explicit.items.any { it.title == "Старая покупка" })
        assertNull(explicit.range)
        assertNull(explicit.hasOlderData)
    }

    @Test
    fun `before cannot be combined with filters, all time cannot be combined with a period`() = runBlocking<Unit> {
        val at = OffsetDateTime.parse("2026-09-01T00:00:00+03:00")
        val before = assertFailsWith<AppError.Api> { api.listOperations(OperationsQuery(OperationsFilter(q = "x"), before = at)) }
        assertEquals(ErrorCodes.VALIDATION_ERROR to 400, before.code to before.httpStatus)
        val allTime = assertFailsWith<AppError.Api> { api.listOperations(OperationsQuery(OperationsFilter(to = at), allTime = true)) }
        assertEquals(ErrorCodes.VALIDATION_ERROR, allTime.code)
    }

    // ---- feed ----

    @Test
    fun `the feed shows three months up to the last data, a pair as one record, newest first`() = runBlocking<Unit> {
        val feed = api.listOperations(OperationsQuery())
        assertEquals(OffsetDateTime.parse("2026-07-01T00:00:00+03:00"), feed.range?.from)
        assertEquals(OffsetDateTime.parse("2026-10-01T00:00:00+03:00"), feed.range?.to)
        assertEquals(feed.items.sortedByDescending { it.occurredAt }, feed.items)
        assertEquals(9, feed.items.size)
        val pair = feed.items.single { it.kind == OperationKind.OWN_TRANSFER && it.id == pairId }
        assertEquals("Перевод между счетами", pair.title)
        assertEquals("··1111", pair.fromAccount?.mask)
        assertEquals("··2222", pair.toAccount?.mask)
        assertNull(pair.fromAccount?.spoken)
        assertEquals(feed.range!!.from, feed.nextBefore)
        val state = assertNotNull(feed.state)
        assertFalse(state.stale)
        assertEquals(OffsetDateTime.parse("2026-09-11T10:00:00+03:00"), state.lastOperationAt)
        assertEquals(listOf("2026-09", "2026-08", "2026-07"), feed.summary?.map { it.month })

        val older = api.listOperations(OperationsQuery(before = feed.nextBefore))
        assertEquals(OffsetDateTime.parse("2026-04-01T00:00:00+03:00"), older.range?.from)
        assertTrue(older.items.isEmpty())
        assertEquals(older.range!!.from, older.nextBefore)
    }

    @Test
    fun `an empty ledger gives an empty, stale feed`() = runBlocking<Unit> {
        val feed = MockOperationsApi(testBackend()).listOperations(OperationsQuery())
        assertTrue(feed.items.isEmpty())
        assertEquals(emptyList(), feed.summary)
        assertNull(feed.range)
        assertNull(feed.nextBefore)
        assertEquals(true, feed.state?.stale)
    }

    // ---- details ----

    @Test
    fun `details explain refunds and transfers to self`() = runBlocking<Unit> {
        val back = api.getOperation(id("back"))
        assertEquals(OperationKind.INCOME, back.kind)
        assertEquals("возврат · Маркетплейсы", back.note)
        assertEquals("Уменьшает расходы «Маркетплейсы» в сентябре", back.noteDetails)
        assertEquals("текущий счёт, последние цифры 1111", back.account?.spoken)

        val refund = api.getOperation(id("refund"))
        assertEquals("возврат", refund.note)
        assertEquals("Уменьшает общие расходы в сентябре", refund.noteDetails)

        val abroad = api.getOperation(id("abroad"))
        assertEquals(OperationKind.EXPENSE, abroad.kind)
        assertEquals("не в тратах", abroad.note)
        assertEquals("Не считаем тратой и доходом", abroad.noteDetails)

        val plain = api.getOperation(id("buy"))
        assertNull(plain.note)
        assertNull(plain.noteDetails)
        assertEquals("OZON", plain.description)
        assertEquals("Маркетплейсы", plain.bankCategory)
    }

    @Test
    fun `a pair is found by its own id and by the id of either side`() = runBlocking<Unit> {
        val pair = api.getOperation(pairId)
        assertEquals(OperationKind.OWN_TRANSFER, pair.kind)
        assertEquals(Money(500_000), pair.amount)
        assertEquals("текущий счёт, последние цифры 1111", pair.fromAccount?.spoken)
        assertEquals("накопительный счёт, последние цифры 2222", pair.toAccount?.spoken)
        assertEquals(pair, api.getOperation(id("self")))
        assertEquals(pair, api.getOperation(id("self-in")))

        val e = assertFailsWith<AppError.Api> { api.getOperation("missing") }
        assertEquals(ErrorCodes.NOT_FOUND to 404, e.code to e.httpStatus)
    }

    @Test
    fun `an account of another bank is spoken as such`() = runBlocking<Unit> {
        val backend = testBackend().apply {
            load(statement("2026-09-01", "2026-09-30", trn("x", "2026-09-05T10:00", 100, "Магазин", "Супермаркеты", account = Acc.OTHER_BANK)))
        }
        val api = MockOperationsApi(backend)
        val op = api.getOperation(backend.ledger.all.single().id)
        assertEquals("счёт другого банка", op.account?.spoken)
        assertNull(op.account?.mask)
        assertEquals("Счёт другого банка", op.account?.typeName)
    }

    // ---- category change ----

    @Test
    fun `changing a category returns the changed record and is kept in the feed`() = runBlocking<Unit> {
        val response = api.changeCategory(id("buy"), ChangeCategoryRequest("cat_clothes"))
        assertNull(response.replacedId)
        val item = response.items.single()
        assertEquals("cat_clothes", item.categoryId)
        assertTrue(item.isCategoryManual)
        // The refund of that purchase follows it.
        assertEquals("возврат · Одежда и обувь", api.getOperation(id("back")).note)
    }

    @Test
    fun `splitting a pair returns both operations and the id that disappeared`() = runBlocking<Unit> {
        val pair = pairId
        val response = api.changeCategory(pair, ChangeCategoryRequest("cat_restaurants"))
        assertEquals(pair, response.replacedId)
        assertEquals(listOf(OperationKind.EXPENSE, OperationKind.INCOME), response.items.map { it.kind })
        assertEquals(listOf("cat_restaurants", "cat_transfers"), response.items.map { it.categoryId })
        assertEquals(ErrorCodes.NOT_FOUND, assertFailsWith<AppError.Api> { api.getOperation(pair) }.code)
    }

    @Test
    fun `marking a transfer as own pairs it and replaces the single record`() = runBlocking<Unit> {
        val backend = testBackend().apply {
            load(
                statement(
                    "2026-09-01", "2026-09-30",
                    trn("d", "2026-09-10T10:00", 3_000, "Иван И.", "Переводы"),
                    trn("c", "2026-09-10T12:00", 3_000, "Пополнение", "Переводы", debit = false, account = Acc.SAVINGS),
                ),
            )
        }
        val api = MockOperationsApi(backend)
        val debit = backend.ledger.all.single { it.fitId == "d" }.id
        val response = api.changeCategory(debit, ChangeCategoryRequest(SystemCategories.OWN_TRANSFER))
        assertEquals(debit, response.replacedId)
        assertEquals(OperationKind.OWN_TRANSFER, response.items.single().kind)
    }

    @Test
    fun `an unknown, wrong-kind category or an unknown operation is rejected`() = runBlocking<Unit> {
        val unknown = assertFailsWith<AppError.Api> { api.changeCategory(id("buy"), ChangeCategoryRequest("cat_nope")) }
        assertEquals(ErrorCodes.CATEGORY_NOT_ASSIGNABLE to 422, unknown.code to unknown.httpStatus)
        val wrongKind = assertFailsWith<AppError.Api> { api.changeCategory(id("buy"), ChangeCategoryRequest("cat_salary")) }
        assertEquals(ErrorCodes.CATEGORY_NOT_ASSIGNABLE to 422, wrongKind.code to wrongKind.httpStatus)
        val missing = assertFailsWith<AppError.Api> { api.changeCategory("missing", ChangeCategoryRequest("cat_clothes")) }
        assertEquals(ErrorCodes.NOT_FOUND to 404, missing.code to missing.httpStatus)
        assertEquals("cat_marketplaces", api.getOperation(id("buy")).categoryId)
    }

    @Test
    fun `categories include the system ones`() = runBlocking<Unit> {
        val ids = api.listCategories().categories.map { it.id }
        assertTrue(SystemCategories.OWN_TRANSFER in ids && SystemCategories.REFUND in ids && "cat_supermarkets" in ids)
    }

    @Test
    fun `operations need a session`() = runBlocking<Unit> {
        val signedOut = MockOperationsApi(testBackend(userId = ""))
        assertFailsWith<AppError.Unauthorized> { signedOut.listOperations(OperationsQuery()) }
        assertFailsWith<AppError.Unauthorized> { signedOut.getOperation("x") }
        assertFailsWith<AppError.Unauthorized> { signedOut.changeCategory("x", ChangeCategoryRequest("cat_clothes")) }
        assertFailsWith<AppError.Unauthorized> { signedOut.listCategories() }
    }

    @Test
    fun `the fake server's own unknown kind searches like expense (FIN-83)`() = runBlocking<Unit> {
        val known = found(OperationsFilter(kind = OperationKindFilter.EXPENSE, transferMode = TransferMode.WITH))
        assertEquals(known, found(OperationsFilter(kind = MockCodes.EXPENSE_UNKNOWN, transferMode = TransferMode.WITH)))
    }

    @Test
    fun `before is the start of a month in the profile zone, another moment is VALIDATION_ERROR`() = runBlocking<Unit> {
        backend.profile = backend.profile.copy(timezone = "Asia/Vladivostok")
        val ok = api.listOperations(OperationsQuery(before = java.time.OffsetDateTime.parse("2026-09-01T00:00:00+10:00")))
        assertEquals(java.time.OffsetDateTime.parse("2026-06-01T00:00:00+10:00").toInstant(), ok.range?.from?.toInstant())
        // The start of September in Moscow is 07:00 on 1 September in Vladivostok: not a month start of the profile.
        for (before in listOf("2026-09-01T00:00:00+03:00", "2026-09-15T00:00:00+10:00")) {
            val e = assertFailsWith<AppError.Api>(before) { api.listOperations(OperationsQuery(before = java.time.OffsetDateTime.parse(before))) }
            assertEquals(ErrorCodes.VALIDATION_ERROR to 400, e.code to e.httpStatus, before)
        }
    }
}
