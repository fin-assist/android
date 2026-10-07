package ru.finassist.pf.mock

import kotlinx.coroutines.runBlocking
import org.junit.Test
import ru.finassist.pf.core.api.model.LockReason
import ru.finassist.pf.core.api.model.MetricLock
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
import ru.finassist.pf.mock.ofx.OfxDocument
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

    private fun backend(
        now: String = "2026-10-06T12:00:00+03:00",
        doc: OfxDocument = OfxParser.parse(fixture),
    ): Pair<MockBackend, MockStatementsApi> {
        val backend = MockBackend(MockConfig(networkDelayMs = 0), FixedClock(OffsetDateTime.parse(now)))
        backend.ready.complete(Unit)
        backend.profile = backend.emptyProfile("+79161234567", "u1")
        val statements = MockStatementsApi(backend)
        runBlocking { statements.startUpload("fixture.ofx", doc, backend.now().minusDays(1), instant = true) }
        return backend to statements
    }

    /** The fixture cut to start on [from]: fewer full months of history. */
    private fun fixtureFrom(from: String): OfxDocument {
        val start = OffsetDateTime.parse(from)
        val doc = OfxParser.parse(fixture)
        return doc.copy(coverageFrom = start, transactions = doc.transactions.filter { !it.postedAt.isBefore(start) })
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
    fun `quarter and year forecasts open with 3 and 12 full months`() {
        // Full fixture: October 2025 — September 2026, 12 full months.
        val (full, _) = backend()
        val year = full.analytics.analytics(PeriodTypeCode.YEAR, PeriodKey("2026"), TransferMode.WITH)
        assertEquals(12, year.state!!.fullMonths)
        val yearForecast = assertNotNull(year.tiles?.forecast)
        assertEquals(MetricStatus.READY, yearForecast.status)
        assertEquals(12, yearForecast.basis?.fullMonths)
        assertTrue(yearForecast.value!!.minor >= year.tiles!!.expense.value!!.minor, "forecast below the fact")
        // FIN-28: a past year has no forecast at all.
        assertEquals(null, full.analytics.analytics(PeriodTypeCode.YEAR, PeriodKey("2025"), TransferMode.WITH).tiles?.forecast)
    }

    @Test
    fun `year forecast is locked with 9 full months, quarter is open`() {
        // FIN-28 scenario: data from 1 January, 9 full months.
        val (cut, _) = backend(doc = fixtureFrom("2026-01-01T00:00:00+03:00"))
        val year = cut.analytics.analytics(PeriodTypeCode.YEAR, PeriodKey("2026"), TransferMode.WITH)
        assertEquals(9, year.state!!.fullMonths)
        val locked = assertNotNull(year.tiles?.forecast)
        assertEquals(MetricStatus.LOCKED, locked.status)
        assertEquals(MetricLock(LockReason.NEED_FULL_MONTHS, required = 12, available = 9), locked.lock)

        val quarter = cut.analytics.analytics(PeriodTypeCode.QUARTER, PeriodKey("2026-Q4"), TransferMode.WITH)
        assertEquals(MetricStatus.READY, quarter.tiles?.forecast?.status)
        assertEquals(3, quarter.tiles?.forecast?.basis?.fullMonths)
    }

    @Test
    fun `stale statement locks every forecast`() {
        // Last operation on 30 September, «today» more than two weeks later.
        val (stale, _) = backend(now = "2026-10-20T12:00:00+03:00")
        for ((type, key) in listOf(PeriodTypeCode.MONTH to "2026-10", PeriodTypeCode.QUARTER to "2026-Q4", PeriodTypeCode.YEAR to "2026")) {
            val f = stale.analytics.analytics(type, PeriodKey(key), TransferMode.WITH).tiles?.forecast
            assertEquals(LockReason.STALE_DATA, f?.lock?.reason, key)
        }
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

    @Test
    fun `regular payments selection does not depend on the last computed card`() = runBlocking {
        val (backend, _) = backend()
        val api = MockOperationsApi(backend)
        val card = backend.analytics.analytics(PeriodTypeCode.MONTH, PeriodKey("2026-09"), TransferMode.WITH).insights!!.regularPayments
        val filter = assertNotNull(card.filters)
        val titles = card.items.orEmpty().map { it.title }.toSet()
        // Another month recomputes the card; the September selection must still mean September's merchants.
        backend.analytics.analytics(PeriodTypeCode.MONTH, PeriodKey("2026-05"), TransferMode.WITHOUT)
        val found = api.listOperations(OperationsQuery(filter = filter))
        assertTrue(found.items.isNotEmpty())
        assertTrue(found.items.all { it.title in titles }, "unexpected ${found.items.map { it.title }.toSet() - titles}")
    }
}
