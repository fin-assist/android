package ru.finassist.pf.mock

import kotlinx.coroutines.runBlocking
import org.junit.Test
import ru.finassist.pf.core.common.time.Clock
import ru.finassist.pf.core.api.model.ImportNoticeCode
import ru.finassist.pf.core.api.model.ChangeCategoryRequest
import ru.finassist.pf.core.api.model.OperationKind
import ru.finassist.pf.core.api.model.OperationsQuery
import ru.finassist.pf.core.api.model.PeriodTypeCode
import ru.finassist.pf.core.api.model.SystemCategories
import ru.finassist.pf.core.api.model.TransferMode
import ru.finassist.pf.core.common.time.PeriodKey
import ru.finassist.pf.mock.api.MockAnalyticsApi
import ru.finassist.pf.mock.api.MockOperationsApi
import ru.finassist.pf.mock.api.MockStatementsApi
import ru.finassist.pf.mock.ofx.OfxCheck
import ru.finassist.pf.mock.ofx.OfxParser
import java.io.File
import java.time.OffsetDateTime
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The files the UI tests pick in the e2e build (app/src/mockE2e/assets/e2e/statements, docs/e2e.md) give the
 * results the Maestro flows expect — checked here on the JVM instead of first failing on an emulator.
 */
class E2eStatementsTest {
    private val dir = File("../../app/src/mockE2e/assets/e2e/statements")
    private fun read(name: String) = File(dir, name).readText()
    private val fixture = File("src/main/assets/statements/fixture.ofx").readText()

    private class FixedClock(private val at: OffsetDateTime) : Clock {
        override fun now() = at
        override val zone: ZoneId = Clock.MOSCOW
    }

    @Test
    fun `refund UI fixture separates income from refunds and exposes exact category effects`() = runBlocking {
        val backend = testBackend(TestClock("2026-10-08T12:00:00+03:00"))
        val doc = OfxParser.parse(read("refund-categories.ofx"))
        assertEquals(4, doc.transactions.size)
        assertTrue(doc.unreadLines.isEmpty())
        backend.load(doc, "refund-categories.ofx")
        val operations = MockOperationsApi(backend)
        val automatic = backend.ledger.all.single { it.fitId == "fin442-refund-auto-400" }
        val manual = backend.ledger.all.single { it.fitId == "fin442-refund-manual-300" }
        val automaticDetails = operations.getOperation(automatic.id, automatic.occurredAt.toString())
        assertEquals(SystemCategories.REFUND, automaticDetails.categoryId)
        assertEquals("возврат · Маркетплейсы", automaticDetails.note)
        assertEquals("Уменьшает расходы «Маркетплейсы» в сентябре", automaticDetails.noteDetails)
        val incoming = operations.getOperation(manual.id, manual.occurredAt.toString())
        assertEquals("Пополнения", incoming.categoryName)
        assertNull(incoming.note)
        val before = backend.analytics.analytics(PeriodTypeCode.MONTH, PeriodKey("2026-09"), TransferMode.WITH)
        assertEquals(90_000L, before.tiles!!.expense.value!!.minor)
        assertEquals(30_000L, before.tiles!!.income.value!!.minor)

        val response = operations.changeCategory(manual.id, ChangeCategoryRequest(SystemCategories.REFUND, manual.occurredAt.toString()))
        assertNull(response.replacedId)
        val changed = response.items.single()
        assertEquals(manual.id, changed.id)
        assertTrue(changed.isCategoryManual)
        assertEquals("возврат · Рестораны", changed.note)
        assertEquals("Уменьшает расходы «Рестораны» в сентябре", changed.noteDetails)
        assertEquals(changed, operations.getOperation(changed.id, changed.occurredAt.toString()))
        val after = backend.analytics.analytics(PeriodTypeCode.MONTH, PeriodKey("2026-09"), TransferMode.WITH)
        assertEquals(60_000L, after.tiles!!.expense.value!!.minor)
        assertEquals(0L, after.tiles!!.income.value!!.minor)
    }

    @Test
    fun `own-transfer UI fixture gives the expense category to the debit`() = runBlocking {
        assertSplitFixture(100_000L, "cat_restaurants", OperationKind.EXPENSE)
    }

    @Test
    fun `own-transfer UI fixture gives the income category to the credit`() = runBlocking {
        assertSplitFixture(200_000L, "cat_salary", OperationKind.INCOME)
    }

    @Test
    fun `a category for both directions splits the UI fixture onto the debit`() = runBlocking {
        assertSplitFixture(100_000L, "cat_cash", OperationKind.EXPENSE)
    }

    private suspend fun assertSplitFixture(amountMinor: Long, categoryId: String, chosenKind: OperationKind) {
        val backend = testBackend(TestClock("2026-10-08T12:00:00+03:00"))
        val doc = OfxParser.parse(read("own-transfer-split.ofx"))
        assertEquals(4, doc.transactions.size)
        assertEquals(2, doc.accounts.size)
        assertTrue(doc.unreadLines.isEmpty())
        backend.load(doc, "own-transfer-split.ofx")
        val operations = MockOperationsApi(backend)
        val before = operations.listOperations(OperationsQuery(allTime = true)).items
        assertEquals(2, before.size)
        assertTrue(before.all { it.kind == OperationKind.OWN_TRANSFER })
        val pair = before.single { it.amount.minor == amountMinor }
        val response = operations.changeCategory(pair.id, ChangeCategoryRequest(categoryId, pair.occurredAt.toString()))
        assertEquals(pair.id, response.replacedId)
        assertEquals(setOf(OperationKind.EXPENSE, OperationKind.INCOME), response.items.map { it.kind }.toSet())
        val chosen = response.items.single { it.kind == chosenKind }
        val other = response.items.single { it.kind != chosenKind }
        assertEquals(categoryId, chosen.categoryId)
        assertTrue(chosen.isCategoryManual)
        assertEquals("cat_transfers", other.categoryId)
        assertFalse(other.isCategoryManual)
        for (item in response.items) {
            assertEquals(amountMinor, item.amount.minor)
            assertNull(item.fromAccount)
            assertNull(item.toAccount)
            assertEquals(item, operations.getOperation(item.id, item.occurredAt.toString()))
        }
        val after = operations.listOperations(OperationsQuery(allTime = true)).items
        assertEquals(3, after.size)
        assertFalse(after.any { it.id == pair.id })
        assertEquals(response.items.map { it.id }.toSet(), after.filter { it.amount.minor == amountMinor }.map { it.id }.toSet())
        // The exact file is safe to re-import: the user's split must not silently form a pair again.
        backend.load(doc, "own-transfer-split.ofx")
        assertEquals(response.items.map { it.id }.toSet(), operations.listOperations(OperationsQuery(allTime = true)).items
            .filter { it.amount.minor == amountMinor }.map { it.id }.toSet())
    }

    @Test
    fun `error files are rejected with the codes the flows wait for`() {
        assertEquals(OfxCheck.Csv, OfxParser.check(read("statement.csv")))
        assertEquals(OfxCheck.NotOfx, OfxParser.check(read("notes.txt")))
        assertTrue(OfxParser.check(read("other-bank.ofx")) is OfxCheck.WrongBank)
        assertEquals(OfxCheck.Ok, OfxParser.check(read("october.ofx")))
    }

    @Test
    fun `october adds three operations on top of the fixture, a second time adds none`() {
        // The e2e build pins "now" to 2026-10-08 12:00 MSK.
        val backend = MockBackend(MockConfig(networkDelayMs = 0), FixedClock(OffsetDateTime.parse("2026-10-08T12:00:00+03:00")))
        backend.ready.complete(Unit)
        backend.profile = backend.emptyProfile("+79161234567", "u1")
        val statements = MockStatementsApi(backend)
        runBlocking {
            statements.startUpload("fixture.ofx", OfxParser.parse(fixture), backend.now().minusDays(1), instant = true)
            val october = OfxParser.parse(read("october.ofx"))
            assertEquals(3, october.transactions.size)
            assertEquals(0, october.unreadLines.size)

            val first = statements.startUpload("october.ofx", october, backend.now(), instant = true)
            assertEquals(3, first.result?.newCount)
            val again = statements.startUpload("october.ofx", october, backend.now(), instant = true)
            assertEquals(0, again.result?.newCount)
        }
    }

    @Test
    fun `unreadable has no readable operation but two unread lines, and the result says so`() {
        val backend = MockBackend(MockConfig(networkDelayMs = 0), FixedClock(OffsetDateTime.parse("2026-10-08T12:00:00+03:00")))
        backend.ready.complete(Unit)
        backend.profile = backend.emptyProfile("+79161234567", "u1")
        val statements = MockStatementsApi(backend)
        runBlocking {
            statements.startUpload("fixture.ofx", OfxParser.parse(fixture), backend.now().minusDays(1), instant = true)
            assertEquals(OfxCheck.Ok, OfxParser.check(read("unreadable.ofx")))
            val doc = OfxParser.parse(read("unreadable.ofx"))
            assertEquals(0, doc.transactions.size)
            assertEquals(listOf("no_date", "bad_amount"), doc.unreadLines.map { it.reason })
            // The UI flow upload_unreadable checks these line numbers on «Непрочитанные строки».
            assertEquals(listOf(31, 38), doc.unreadLines.map { it.lineNumber })
            // Kept to close the lines later (FIN-79): the account of the block and the FITID when it was read.
            val account = "OB$1$779fd587938ea35b46f83b06"
            assertEquals(listOf(account, account), doc.unreadLines.map { it.accountId })
            assertEquals(listOf("e2e-bad-0001", "e2e-bad-0002"), doc.unreadLines.map { it.fitId })
            assertEquals(OffsetDateTime.parse("2026-10-01T00:00:00+03:00"), doc.accountPeriods.getValue(account).from)

            val result = assertNotNull(statements.startUpload("unreadable.ofx", doc, backend.now(), instant = true).result)
            assertEquals(0, result.operationCount)
            assertEquals(2, result.unreadCount)
            assertTrue(result.has(ImportNoticeCode.EMPTY_STATEMENT))
        }
    }

    @Test
    fun `unreadable-fixed reads both lines of unreadable and closes them (FIN-79)`() {
        val backend = MockBackend(MockConfig(networkDelayMs = 0), FixedClock(OffsetDateTime.parse("2026-10-08T12:00:00+03:00")))
        backend.ready.complete(Unit)
        backend.profile = backend.emptyProfile("+79161234567", "u1")
        val statements = MockStatementsApi(backend)
        runBlocking {
            statements.startUpload("fixture.ofx", OfxParser.parse(fixture), backend.now().minusDays(1), instant = true)
            val broken = statements.startUpload("unreadable.ofx", OfxParser.parse(read("unreadable.ofx")), backend.now(), instant = true)
            val fixed = OfxParser.parse(read("unreadable-fixed.ofx"))
            assertEquals(2, fixed.transactions.size)
            assertEquals(0, fixed.unreadLines.size)
            statements.startUpload("unreadable-fixed.ofx", fixed, backend.now(), instant = true)

            // The UI flow upload_unreadable_fixed checks the history row of the broken upload.
            val row = statements.listStatements().uploads.single { it.uploadId == broken.id }
            assertEquals(0, row.unreadCount)
            assertEquals(2, row.resolvedUnreadCount)
            assertEquals(backend.now(), row.resolvedByUploadedAt)
            assertTrue(statements.listUnreadLines(broken.id).lines.isEmpty())
        }
    }

    @Test
    fun `stale alone ends on 20 September past its last operation, more than two weeks before now (FIN-125)`() {
        val backend = MockBackend(MockConfig(networkDelayMs = 0), FixedClock(OffsetDateTime.parse("2026-10-08T12:00:00+03:00")))
        backend.ready.complete(Unit)
        backend.profile = backend.emptyProfile("+79161234567", "u1")
        val statements = MockStatementsApi(backend)
        runBlocking {
            // The flow deletes the preloaded statement first, then picks stale.ofx.
            val preloaded = statements.startUpload("fixture.ofx", OfxParser.parse(fixture), backend.now().minusDays(1), instant = true)
            statements.deleteStatement(preloaded.id)
            val doc = OfxParser.parse(read("stale.ofx"))
            assertEquals(3, doc.transactions.size)
            assertEquals(3, statements.startUpload("stale.ofx", doc, backend.now(), instant = true).result?.newCount)

            // The UI flow statement_stale checks «по 20 сентября»: the statement's end, not the last operation (15th).
            val dataTo = OffsetDateTime.parse("2026-09-21T00:00:00+03:00")
            val feed = assertNotNull(MockOperationsApi(backend).listOperations(OperationsQuery()).state)
            assertTrue(feed.stale)
            assertEquals(dataTo, feed.dataTo)
            assertEquals(OffsetDateTime.parse("2026-09-15T13:10:00+03:00"), feed.lastOperationAt)
            val analytics = assertNotNull(MockAnalyticsApi(backend).getAnalytics(PeriodTypeCode.MONTH, null, TransferMode.WITH, null, null).state)
            assertTrue(analytics.stale)
            assertEquals(dataTo, analytics.dataTo)
        }
    }
}
