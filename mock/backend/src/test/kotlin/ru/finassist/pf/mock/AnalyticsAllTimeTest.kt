package ru.finassist.pf.mock

import kotlinx.coroutines.runBlocking
import org.junit.Test
import ru.finassist.pf.core.api.model.Coverage
import ru.finassist.pf.core.api.model.ErrorCodes
import ru.finassist.pf.core.api.model.FieldErrorCodes
import ru.finassist.pf.core.api.model.PeriodTypeCode
import ru.finassist.pf.core.api.model.TransferMode
import ru.finassist.pf.core.common.error.AppError
import ru.finassist.pf.core.common.time.PeriodKey
import ru.finassist.pf.mock.api.MockAnalyticsApi
import ru.finassist.pf.mock.api.MockStatementsApi
import ru.finassist.pf.mock.domain.AnalyticsComposer
import ru.finassist.pf.mock.ofx.OfxCheck
import ru.finassist.pf.mock.ofx.OfxParser
import java.io.File
import java.time.OffsetDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class AnalyticsAllTimeTest {
    private fun snapshot(b: MockBackend, mode: TransferMode = TransferMode.WITH) =
        b.analytics.analytics(PeriodTypeCode.ALL_TIME, null, mode)

    @Test fun `all-time unites years and uses covered days while preserving modes and own exclusions`() {
        val b = testBackend(TestClock("2026-10-31T12:00:00+03:00"))
        b.load(statement("2025-12-30", "2025-12-31", trn("old", "2025-12-30T12:00", 100, "Shop", "Супермаркеты")))
        b.load(statement("2026-10-01", "2026-10-30",
            trn("new", "2026-10-03T12:00", 200, "Shop", "Супермаркеты"),
            trn("salary", "2026-10-04T12:00", 500, "Salary", "Зарплата", debit = false),
            trn("person", "2026-10-05T12:00", 50, "Person", "Переводы"),
            trn("own", "2026-10-06T12:00", 40, "Между своими счетами", "Переводы")))
        for ((mode, expense) in listOf(TransferMode.WITH to 35000L, TransferMode.WITHOUT to 30000L)) {
            val s = snapshot(b, mode)
            assertEquals(PeriodKey.ALL_TIME, s.params?.date)
            assertEquals(mode, s.params?.transferMode)
            val p = assertNotNull(s.period)
            assertEquals("2025-12-30T00:00+03:00", p.range.from.toString())
            assertEquals("2026-10-31T00:00+03:00", p.range.to.toString())
            assertEquals(s.state?.dataFrom, p.range.from)
            assertEquals(s.state?.dataTo, p.range.to)
            assertFalse(p.isCurrent)
            assertEquals(Coverage.PARTIAL, p.coverage)
            val t = assertNotNull(s.tiles)
            assertEquals(expense, t.expense.value?.minor)
            assertEquals(50000L, t.income.value?.minor)
            assertEquals(50000L - expense, t.balance.value?.minor)
            assertEquals(expense / 32, t.dailyExpense.value?.minor)
            assertEquals(mode, t.expense.filters?.transferMode)
            assertEquals(p.range.from, t.expense.filters?.from)
            assertEquals(p.range.to, t.expense.filters?.to)
            assertNull(t.expense.comparison); assertNull(t.expense.typical); assertNull(t.forecast)
            assertNull(s.insights)
            assertNull(s.navigation?.previous); assertNull(s.navigation?.next)
            val points = assertNotNull(s.monthlyChart?.points)
            assertEquals(11, points.size)
            assertEquals("2025-12", points.first().month)
            assertNull(points[1].expense)
            assertEquals(Coverage.NO_DATA, points[1].coverage)
            assertFalse(AnalyticsComposer.compose(s).blocks.any { it.id == "balance" })
        }
    }

    @Test fun `short all-time coverage shows one partial month without thresholds and covered zero stays zero`() {
        val b = testBackend(TestClock("2026-10-10T12:00:00+03:00"))
        b.load(statement("2026-10-01", "2026-10-10", trn("salary", "2026-10-03T12:00", 10, "Salary", "Зарплата", debit = false)))
        val s = snapshot(b)
        assertEquals(Coverage.COMPLETE, s.period?.coverage)
        assertEquals(Coverage.COMPLETE, b.analytics.periods(PeriodTypeCode.ALL_TIME).periods.single().coverage)
        val point = assertNotNull(s.monthlyChart?.points).single()
        assertEquals(Coverage.PARTIAL, point.coverage)
        assertEquals(0L, point.expense?.minor)
        assertTrue(AnalyticsComposer.compose(s).blocks.any { it.id == "monthly_chart" })
        assertEquals(0L, s.tiles?.expense?.value?.minor)
        assertNull(s.monthlyChart?.lock)
    }

    @Test fun `all-time has no last twelve months truncation and deduplicates overlapping imports`() {
        val b = testBackend(TestClock("2026-10-31T12:00:00+03:00"))
        val line = trn("old", "2024-10-03T12:00", 10, "Shop", "Супермаркеты")
        b.load(statement("2024-10-01", "2024-10-31", line), "a.ofx")
        b.load(statement("2024-10-01", "2024-11-30", line), "overlap.ofx")
        b.load(statement("2026-10-01", "2026-10-30", trn("new", "2026-10-03T12:00", 20, "Shop", "Супермаркеты")), "new.ofx")
        val s = snapshot(b)
        assertEquals(25, s.monthlyChart?.points?.size)
        assertEquals(3000L, s.tiles?.expense?.value?.minor)
        assertEquals(0L, s.monthlyChart?.points?.get(1)?.expense?.minor)
    }

    @Test fun `all-time refund reduces expenses and categories without increasing income`() {
        val b = testBackend(TestClock("2026-10-10T12:00:00+03:00"))
        b.load(statement("2025-12-01", "2026-10-10",
            trn("d", "2026-09-30T12:00", 100, "Ozon", "Маркетплейсы"),
            trn("r", "2026-10-03T12:00", 40, "Ozon", "Маркетплейсы", debit = false)))
        val s = snapshot(b)
        assertEquals(6000L, s.tiles?.expense?.value?.minor)
        assertEquals(0L, s.tiles?.income?.value?.minor)
        assertEquals(6000L, s.expenseCategories?.items?.single()?.amount?.minor)
    }

    @Test fun `deletion shrinks all-time range and last deletion leaves no fictional fact`() = runBlocking {
        val b = testBackend(TestClock("2026-10-31T12:00:00+03:00"))
        val old = b.load(statement("2025-12-30", "2025-12-31", trn("old", "2025-12-30T12:00", 10, "Shop", "Супермаркеты")), "old.ofx")
        val new = b.load(statement("2026-10-01", "2026-10-30", trn("new", "2026-10-03T12:00", 20, "Shop", "Супермаркеты")), "new.ofx")
        MockStatementsApi(b).deleteStatement(old.id)
        val s = snapshot(b)
        assertEquals("2026-10-01T00:00+03:00", s.period?.range?.from.toString())
        assertEquals(2000L, s.tiles?.expense?.value?.minor)
        MockStatementsApi(b).deleteStatement(new.id)
        assertFalse(snapshot(b).hasData)
        assertTrue(b.analytics.periods(PeriodTypeCode.ALL_TIME).periods.isEmpty())
    }

    @Test fun `clock-only changes daily denominator and month fullness in both modes`() {
        val clock = TestClock("2026-10-30T12:00:00+03:00")
        val b = testBackend(clock)
        b.load(statement("2026-10-01", "2026-10-31",
            trn("shop", "2026-10-03T12:00", 31, "Shop", "Супермаркеты"),
            trn("person", "2026-10-04T12:00", 31, "Person", "Переводы")))
        for ((at, coverage, days) in listOf(Triple("2026-10-30T12:00:00+03:00", Coverage.PARTIAL, 30),
            Triple("2026-10-31T12:00:00+03:00", Coverage.PARTIAL, 31), Triple("2026-11-01T00:05:00+03:00", Coverage.COMPLETE, 31))) {
            clock.at = OffsetDateTime.parse(at)
            for ((mode, expense) in listOf(TransferMode.WITH to 6200L, TransferMode.WITHOUT to 3100L)) {
                val s = snapshot(b, mode)
                assertEquals(expense / days, s.tiles?.dailyExpense?.value?.minor)
                assertEquals(coverage, s.monthlyChart?.points?.single()?.coverage)
                assertEquals(Coverage.COMPLETE, s.period?.coverage)
                assertFalse(s.period!!.isCurrent)
            }
        }
    }

    @Test fun `all-time accepts only its marker and rejects unsupported list type canonically`() = runBlocking {
        val api = MockAnalyticsApi(testBackend().apply { load(fixtureDoc()) })
        for ((period, key) in listOf(PeriodTypeCode.ALL_TIME to "2026", PeriodTypeCode.MONTH to "all_time", PeriodTypeCode.YEAR to "all_time")) {
            val e = assertFailsWith<AppError.Api> { api.getAnalytics(period, PeriodKey(key)) }
            assertEquals(ErrorCodes.VALIDATION_ERROR, e.code)
            assertEquals("date", e.details.single().field)
            assertEquals(FieldErrorCodes.INVALID_FORMAT, e.details.single().code)
        }
        assertEquals(PeriodKey.ALL_TIME, api.getAnalytics(PeriodTypeCode.ALL_TIME, PeriodKey.ALL_TIME).params?.date)
        assertFailsWith<AppError.Api> { api.listPeriods(PeriodTypeCode.UNKNOWN) }
        Unit
    }

    @Test fun `authored UI source parses and expands the original envelope through its actual covered end`() {
        val text = File("../../app/src/mockE2e/assets/e2e/statements/all-time.ofx").readText()
        assertEquals(OfxCheck.Ok, OfxParser.check(text))
        val doc = OfxParser.parse(text)
        assertEquals(2, doc.transactions.size)
        assertTrue(doc.unreadLines.isEmpty())
        val b = testBackend(TestClock("2026-10-08T12:00:00+03:00"))
        b.load(fixtureDoc())
        val before = snapshot(b, TransferMode.WITHOUT)
        b.load(doc, "all-time.ofx")
        val after = snapshot(b, TransferMode.WITHOUT)
        assertEquals("2024-01-01T00:00+03:00", after.period?.range?.from.toString())
        assertEquals("2026-10-08T00:00+03:00", after.period?.range?.to.toString())
        assertEquals(34, after.monthlyChart?.points?.size)
        assertEquals(before.tiles!!.expense.value!!.minor + 20000, after.tiles!!.expense.value!!.minor)
        assertEquals(before.tiles!!.income.value!!.minor + 120000, after.tiles!!.income.value!!.minor)
    }

    @Test fun `empty uploaded statement remains the shared empty analytics state`() {
        val b = testBackend().apply { load(statement("2026-10-01", "2026-10-05")) }
        assertFalse(snapshot(b).hasData)
        assertFalse(AnalyticsComposer.compose(snapshot(b)).hasData)
    }
}
