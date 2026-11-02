package ru.finassist.pf.mock

import kotlinx.coroutines.runBlocking
import org.junit.Test
import ru.finassist.pf.core.api.model.*
import ru.finassist.pf.core.common.error.AppError
import ru.finassist.pf.core.common.time.PeriodKey
import ru.finassist.pf.mock.api.MockAnalyticsApi
import kotlin.test.*

/** Observable REST transitions: opaque revision ownership, immediate replacement and pending/final versions. */
class AnalyticsRevisionContractTest {
    private fun history() = testBackend().apply {
        load(statement("2000-01-01", "2026-09-30", *(6..9).map { m ->
            trn("subscription-$m", "2026-${m.toString().padStart(2, '0')}-03T09:00", 499, "Subscription", "Онлайн-кинотеатры")
        }.toTypedArray()))
    }
    private fun chart(a: Analytics) = a.blocks.single { it.id == "monthly_chart" }.decode(ChartData.serializer())!!

    @Test fun `the full block settles one dismissal while an older calculation is still pending`() = runBlocking<Unit> {
        val backend = history().apply { deferAnalyticsCalculation = true }
        val api = MockAnalyticsApi(backend)
        val initial = api.getAnalytics(PeriodTypeCode.MONTH, PeriodKey("2026-09"), TransferMode.WITH)
        val card = initial.blocks.single { it.id == "regular_payments" }.decode(InsightCardData.serializer())!!
        val id = card.items.single().button!!.paymentId!!
        val update = api.dismissRegularPayment(id, PeriodKey("2026-09"), TransferMode.WITH)
        AnalyticsIntegrity.requireValid(update)
        assertEquals(listOf("regular_payments"), update.blocks.map { it.id })
        assertEquals(BlockStatus.NONE, update.blocks.single().status)
        assertTrue(update.blocks.single().decode(InsightCardData.serializer())!!.items.isEmpty())
        assertTrue(update.requiredCalcVersion > initial.calculation!!.calcVersion)
        val pending = api.getAnalytics(PeriodTypeCode.MONTH, PeriodKey("2026-09"), TransferMode.WITH)
        assertTrue(pending.calculation!!.recalculating)
        assertTrue(pending.calculation!!.calcVersion < update.requiredCalcVersion)
        val repeated = api.dismissRegularPayment(id, PeriodKey("2026-09"), TransferMode.WITH)
        assertEquals(update, repeated)
        backend.calculation.calculateNow()
        val ready = api.getAnalytics(PeriodTypeCode.MONTH, PeriodKey("2026-09"), TransferMode.WITH)
        assertFalse(ready.calculation!!.recalculating)
        assertTrue(ready.calculation!!.calcVersion >= update.requiredCalcVersion)
        assertEquals(update.blocks.single(), ready.blocks.single { it.id == "regular_payments" })
    }

    @Test fun `embedded first page and cursor chain cover all history in both directions`() = runBlocking<Unit> {
        val api = MockAnalyticsApi(history())
        val first = chart(api.getAnalytics(PeriodTypeCode.YEAR, PeriodKey("2026"), TransferMode.WITH))
        assertEquals(60, first.points.size)
        assertEquals("2026-09", java.time.YearMonth.from(first.points.last().range.from).toString())
        val months = first.points.map { it.range.from }.toMutableList()
        var page = first
        while (page.page!!.previous != null) {
            val response = api.getMonthlyChartPage(page.page!!.previous!!)
            assertEquals("Europe/Moscow", response.timeZone)
            val data = response.block.decode(ChartData.serializer())!!
            assertEquals(first.page!!.revision, data.page!!.revision)
            assertTrue(data.points.size in 1..60)
            assertNull(data.highlightIndex)
            assertTrue(data.notes.isEmpty())
            months.addAll(0, data.points.map { it.range.from })
            page = data
        }
        assertEquals(321, months.size)
        assertEquals(months.distinct().size, months.size)
        assertEquals("2000-01", java.time.YearMonth.from(months.first()).toString())
        val next = api.getMonthlyChartPage(page.page!!.next!!).block.decode(ChartData.serializer())!!
        assertEquals("2001-10", java.time.YearMonth.from(next.points.first().range.from).toString())
    }

    @Test fun `cursor validates owner before stale revision and never crosses changed history`() = runBlocking<Unit> {
        val backend = history()
        val api = MockAnalyticsApi(backend)
        val cursor = chart(api.getAnalytics(null, null, null)).page!!.previous!!
        val mode = chart(api.getAnalytics(null, null, TransferMode.WITHOUT)).page!!.revision
        assertNotEquals(mode, chart(api.getAnalytics(null, null, TransferMode.WITH)).page!!.revision)
        backend.dataChanges.categoriesChanged()
        val stale = assertFailsWith<AppError.Api> { api.getMonthlyChartPage(cursor) }
        assertEquals("OUT_OF_RANGE", stale.details.single().code)
        backend.profile = backend.profile.copy(userId = "another-user")
        val foreign = assertFailsWith<AppError.Api> { api.getMonthlyChartPage(cursor) }
        assertEquals("INVALID_FORMAT", foreign.details.single().code)
        assertEquals("REQUIRED", assertFailsWith<AppError.Api> { api.getMonthlyChartPage("") }.details.single().code)
    }
    @Test fun `an unavailable date distinguishes unsaved validation from an already persisted assignment`() = runBlocking<Unit> {
        val backend = history()
        val api = MockAnalyticsApi(backend)
        val ready = api.getAnalytics(PeriodTypeCode.MONTH, PeriodKey("2026-09"), TransferMode.WITH)
        val id = ready.blocks.single { it.id == "regular_payments" }.decode(InsightCardData.serializer())!!.items.single().button!!.paymentId!!
        val rejected = assertFailsWith<AppError.Api> { api.dismissRegularPayment(id, PeriodKey("1999-01"), TransferMode.WITH) }
        assertEquals(400, rejected.httpStatus)
        assertEquals("OUT_OF_RANGE", rejected.details.single().code)
        assertTrue(backend.dismissedPayments.isEmpty())
        api.dismissRegularPayment(id, PeriodKey("2026-09"), TransferMode.WITH)
        val saved = assertFailsWith<AppError.Api> { api.dismissRegularPayment(id, PeriodKey("1999-01"), TransferMode.WITH) }
        assertEquals(422, saved.httpStatus)
        assertEquals("ANALYTICS_CONTEXT_UNAVAILABLE", saved.code)
        assertEquals(false, saved.requestRetryable)
        assertEquals(setOf(id), backend.dismissedPayments)
    }

}
