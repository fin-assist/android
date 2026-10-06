package ru.finassist.pf.mock

import kotlinx.coroutines.runBlocking
import org.junit.Test
import ru.finassist.pf.core.api.model.MetricStatus
import ru.finassist.pf.core.api.model.OperationKindFilter
import ru.finassist.pf.core.api.model.OperationsFilter
import ru.finassist.pf.core.api.model.OperationsQuery
import ru.finassist.pf.core.api.model.PeriodTypeCode
import ru.finassist.pf.core.api.model.TransferMode
import ru.finassist.pf.core.common.time.Clock
import ru.finassist.pf.core.common.time.PeriodKey
import ru.finassist.pf.mock.api.MockOperationsApi
import ru.finassist.pf.mock.api.MockStatementsApi
import ru.finassist.pf.mock.ofx.OfxCheck
import ru.finassist.pf.mock.ofx.OfxParser
import java.io.File
import java.time.OffsetDateTime
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Runs the whole fake backend over the anonymized fixture — the same data the mock flavor ships with. */
class FixtureTest {
    private val fixture = File("src/main/assets/statements/fixture.ofx").readText()

    private class FixedClock(private val at: OffsetDateTime) : Clock {
        override fun now() = at
        override val zone: ZoneId = Clock.MOSCOW
    }

    private fun backend(): Pair<MockBackend, MockStatementsApi> {
        val backend = MockBackend(MockConfig(networkDelayMs = 0), FixedClock(OffsetDateTime.parse("2026-10-06T12:00:00+03:00")))
        backend.ready.complete(Unit)
        backend.profile = backend.emptyProfile("+79161234567", "u1")
        val statements = MockStatementsApi(backend)
        runBlocking { statements.startUpload("fixture.ofx", OfxParser.parse(fixture), backend.now().minusDays(1), instant = true) }
        return backend to statements
    }

    @Test
    fun `fixture parses completely`() {
        assertEquals(OfxCheck.Ok, OfxParser.check(fixture))
        val doc = OfxParser.parse(fixture)
        assertEquals("T-BANK", doc.bankId)
        assertEquals(3, doc.accounts.size)
        assertEquals(1969, doc.transactions.size)
        assertEquals(0, doc.unreadLines.size)
        assertEquals(1, doc.accounts.count { it.isOtherBank })
        assertNotNull(doc.coverageFrom)
        assertEquals(10, doc.coverageFrom!!.monthValue)
    }

    @Test
    fun `format checks`() {
        assertEquals(OfxCheck.Csv, OfxParser.check("Дата операции;Дата платежа;Сумма\n01.09.2026;..."))
        assertEquals(OfxCheck.NotOfx, OfxParser.check("%PDF-1.4 ..."))
        assertTrue(OfxParser.check(fixture.replace("<BANKID>T-BANK</BANKID>", "<BANKID>SBER</BANKID>")) is OfxCheck.WrongBank)
    }

    @Test
    fun `rules pair own transfers and detect refunds, re-import adds nothing`() {
        val (backend, statements) = backend()
        val ledger = backend.ledger
        assertEquals(1969, ledger.all.size)
        assertTrue(ledger.pairCount >= 60, "pairs: ${ledger.pairCount}")
        val refunds = ledger.all.filter { it.isRefund }
        assertTrue(refunds.size in 5..30, "refunds: ${refunds.size}")
        assertTrue(refunds.any { it.refundTarget != null })

        val result = runBlocking {
            statements.startUpload("again.ofx", OfxParser.parse(fixture), backend.now(), instant = true).result!!
        }
        assertEquals(0, result.newCount)
        assertEquals(1969, result.duplicateCount)
        assertEquals(1969, ledger.all.size)
    }

    @Test
    fun `analytics for september is computed with locks and insights`() {
        val (backend, _) = backend()
        val a = backend.analytics.analytics(PeriodTypeCode.MONTH, PeriodKey("2026-09"), TransferMode.WITH)
        assertTrue(a.hasData)
        val tiles = assertNotNull(a.tiles)
        assertTrue(tiles.expense.value!!.minor > 0)
        assertTrue(tiles.income.value!!.minor > 0)
        assertEquals(MetricStatus.READY, tiles.expense.comparison?.status)
        assertEquals(MetricStatus.READY, a.monthlyChart?.status)
        assertEquals(6, a.monthlyChart?.points?.size)
        assertNotNull(a.insights)
        assertEquals(MetricStatus.READY, a.insights!!.regularPayments.status)
        assertNotNull(a.navigation?.previous)
        assertTrue(a.expenseCategories!!.items.first().amount.minor > 0)
        assertTrue(a.expenseCategories!!.items.none { it.categoryId == "cat_own_transfer" })

        val without = backend.analytics.analytics(PeriodTypeCode.MONTH, PeriodKey("2026-09"), TransferMode.WITHOUT)
        assertTrue(without.tiles!!.expense.value!!.minor <= tiles.expense.value!!.minor)
        assertTrue(without.expenseCategories!!.items.none { it.categoryId == "cat_transfers" })

        val year = backend.analytics.analytics(PeriodTypeCode.YEAR, PeriodKey("2026"), TransferMode.WITH)
        assertEquals(12, year.monthlyChart?.points?.size)
        assertEquals(null, year.insights)
    }

    @Test
    fun `feed pages by three months and search finds a merchant`() = runBlocking {
        val (backend, _) = backend()
        val api = MockOperationsApi(backend)
        val first = api.listOperations(OperationsQuery())
        assertTrue(first.items.isNotEmpty())
        assertEquals(3, first.summary?.size)
        assertNotNull(first.nextBefore)
        val second = api.listOperations(OperationsQuery(before = first.nextBefore))
        assertTrue(second.items.all { it.occurredAt < first.range!!.from })

        val search = api.listOperations(OperationsQuery(filter = OperationsFilter(q = "Самокат")))
        assertTrue((search.totalCount ?: 0) >= 50, "found ${search.totalCount}")
        assertTrue(search.items.all { it.title.contains("Самокат") })

        val expensesOnly = api.listOperations(OperationsQuery(filter = OperationsFilter(kind = OperationKindFilter.EXPENSE, transferMode = TransferMode.WITH)))
        assertTrue(expensesOnly.items.none { it.kind.code == "own_transfer" })
    }
}
