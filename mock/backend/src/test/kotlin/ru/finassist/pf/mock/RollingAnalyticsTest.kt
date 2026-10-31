package ru.finassist.pf.mock

import kotlinx.coroutines.runBlocking
import org.junit.Test
import ru.finassist.pf.core.api.model.*
import ru.finassist.pf.core.common.error.AppError
import ru.finassist.pf.core.common.time.PeriodKey
import ru.finassist.pf.mock.api.MockAnalyticsApi
import ru.finassist.pf.mock.domain.AnalyticsComposer
import java.time.OffsetDateTime
import kotlin.test.*

/** Executable edge cases of contracts d827664 (FIN-413), docs/calc.md section 12. */
class RollingAnalyticsTest {
    private data class SkippedCase(val zone: String, val rawFrom: String, val rawTo: String, val from: String, val to: String, val days: Long)

    private fun backend() = testBackend(TestClock("2026-10-31T12:00:00+03:00"))
    private fun MockBackend.screen(type: PeriodTypeCode, mode: TransferMode = TransferMode.WITH) = analytics.analytics(type, null, mode)

    @Test fun `coverage exclusive end shifts by calendar months with day clamp`() {
        val cases = listOf(
            Triple("2026-09-30", PeriodTypeCode.ROLLING_YEAR, "2025-10-01"),
            Triple("2026-09-30", PeriodTypeCode.ROLLING_QUARTER, "2026-07-01"),
            Triple("2026-10-30", PeriodTypeCode.ROLLING_YEAR, "2025-10-31"),
            Triple("2026-09-15", PeriodTypeCode.ROLLING_QUARTER, "2026-06-16"),
            Triple("2026-10-05", PeriodTypeCode.ROLLING_QUARTER, "2026-07-06"),
            Triple("2024-05-30", PeriodTypeCode.ROLLING_QUARTER, "2024-02-29"),
            Triple("2023-05-30", PeriodTypeCode.ROLLING_QUARTER, "2023-02-28"),
            Triple("2024-02-28", PeriodTypeCode.ROLLING_YEAR, "2023-02-28"),
        )
        for ((last, type, first) in cases) {
            val b = backend().apply { load(statement("2022-01-01", last, trn("old", "2022-01-02T12:00", 10, "Old", "Супермаркеты"))) }
            val s = b.screen(type)
            assertEquals(first, s.period!!.range.from.toLocalDate().toString())
            assertEquals(PeriodKey(type.code), s.params!!.date)
            assertFalse(s.period!!.isCurrent)
            assertEquals(Coverage.COMPLETE, s.period!!.coverage)
            assertEquals(AnalyticsNavigation(), s.navigation)
            assertEquals(listOf(AnalyticsPeriodItem(PeriodKey(type.code), s.period!!.range, Coverage.COMPLETE)), b.analytics.periods(type).periods)
        }
    }

    @Test fun `facts use exact edge operations matching refunds and transfer modes before slicing`() {
        val b = backend().apply { load(statement("2024-10-01", "2026-10-30",
            trn("old", "2024-10-03T12:00", 1000, "Old", "Супермаркеты"),
            trn("outside", "2025-10-30T12:00", 999, "Outside", "Супермаркеты"),
            trn("start", "2025-10-31T00:00", 10, "Shop", "Супермаркеты"),
            trn("income", "2026-01-01T12:00", 100, "Salary", "Зарплата", debit = false),
            trn("person", "2026-10-30T12:00", 5, "Person", "Переводы"),
            trn("own", "2026-10-30T13:00", 50, "Между своими счетами", "Переводы"),
            trn("refund", "2026-10-30T14:00", 2, "Shop", "Супермаркеты", debit = false),
            trn("last", "2026-10-30T23:59", 3, "Last", "Супермаркеты"),
            trn("after", "2026-10-31T00:00", 2000, "Future", "Супермаркеты"),
        )) }
        for ((mode, expected) in listOf(TransferMode.WITH to 1600L, TransferMode.WITHOUT to 1100L)) {
            val s = b.screen(PeriodTypeCode.ROLLING_YEAR, mode)
            assertEquals(expected, s.tiles!!.expense.value!!.minor)
            assertEquals(10000L, s.tiles!!.income.value!!.minor)
            assertEquals(10000L - expected, s.tiles!!.balance.value!!.minor)
            assertEquals(expected / 365, s.tiles!!.dailyExpense.value!!.minor)
            assertEquals(expected, s.expenseCategories!!.items.sumOf { it.amount.minor })
            assertNull(s.tiles!!.forecast)
            assertNull(s.tiles!!.expense.typical)
            assertNull(s.insights)
            val chart = AnalyticsComposer.compose(s).blocks.single { it.id == "monthly_chart" }.decode(ChartData.serializer())!!
            assertEquals(13, chart.points.size)
            assertEquals(expected.toDouble(), chart.points.sumOf { it.value ?: 0.0 })
            assertEquals("2025-10-31", chart.points.first().range.from.toLocalDate().toString())
            assertEquals(PeriodKey("2025-10"), chart.points.first().action!!.date)
            assertEquals(PeriodKey("2026-10"), chart.points.last().action!!.date)
            assertNull(chart.highlightIndex)
        }
    }

    @Test fun `short history and gaps preserve unknown months while covered empty month is zero`() {
        val b = backend().apply {
            load(statement("2026-08-20", "2026-08-31", trn("d", "2026-08-21T12:00", 31, "Shop", "Супермаркеты")))
            load(statement("2026-10-01", "2026-10-30"), "empty.ofx")
        }
        val s = b.screen(PeriodTypeCode.ROLLING_QUARTER)
        assertEquals(Coverage.PARTIAL, s.period!!.coverage)
        assertEquals("2026-07-31", s.period!!.range.from.toLocalDate().toString())
        assertEquals("2026-08-20", s.period!!.dataFrom!!.toLocalDate().toString())
        assertEquals(3100L / 42, s.tiles!!.dailyExpense.value!!.minor)
        val points = s.monthlyChart!!.points!!
        assertEquals(listOf(null, 3100L, null, 0L), points.map { it.expense?.minor })
        assertEquals(listOf(Coverage.NO_DATA, Coverage.PARTIAL, Coverage.NO_DATA, Coverage.PARTIAL), points.map { it.coverage })
        val s2 = AnalyticsComposer.compose(s)
        assertFalse(s2.blocks.any { it.id == "balance" || it.id == "regular_payments" })
        val chart = s2.blocks.single { it.id == "monthly_chart" }.decode(ChartData.serializer())!!
        assertNull(chart.points[0].action)
        assertEquals(PeriodKey("2026-10"), chart.points.last().action!!.date)
    }

    @Test fun `timezone reprojects original coverage instants and applies each boundary offset`() {
        val b = backend().apply { load(statement("2026-07-01", "2026-10-30", trn("d", "2026-10-30T23:00", 10, "Shop", "Супермаркеты"))) }
        b.profile = b.profile.copy(timezone = "Europe/Berlin")
        assertEquals(OffsetDateTime.parse("2026-07-31T00:00:00+02:00"), b.screen(PeriodTypeCode.ROLLING_QUARTER).period!!.range.from)
        assertEquals(OffsetDateTime.parse("2026-10-31T00:00:00+01:00"), b.screen(PeriodTypeCode.ROLLING_QUARTER).period!!.range.to)
        b.profile = b.profile.copy(timezone = "Asia/Tokyo")
        assertEquals("2026-08-01", b.screen(PeriodTypeCode.ROLLING_QUARTER).period!!.range.from.toLocalDate().toString())
        assertEquals("2026-11-01", b.screen(PeriodTypeCode.ROLLING_QUARTER).period!!.range.to.toLocalDate().toString())
        assertEquals(OffsetDateTime.parse("2026-10-31T05:00:00+09:00"), b.screen(PeriodTypeCode.ROLLING_QUARTER).period!!.dataTo)
    }

    @Test fun `a missing local midnight uses the first real start of day`() {
        val b = testBackend(TestClock("2019-02-04T12:00:00-02:00")).apply {
            load(statement("2018-01-01", "2019-02-03", trn("dst", "2019-02-01T12:00", 10, "Shop", "Супермаркеты")))
        }
        b.profile = b.profile.copy(timezone = "America/Sao_Paulo")
        val range = b.screen(PeriodTypeCode.ROLLING_QUARTER).period!!.range
        assertEquals(OffsetDateTime.parse("2018-11-04T01:00:00-02:00"), range.from)
        assertEquals(OffsetDateTime.parse("2019-02-04T00:00:00-02:00"), range.to)
    }

    @Test fun `end of covered day uses its own historical offset before a midnight jump`() {
        val doc = statement("2018-08-01", "2018-11-03", trn("end", "2018-11-01T12:00", 10, "Shop", "Супермаркеты"))
            .copy(coverageFrom = OffsetDateTime.parse("2018-08-01T00:00:00-03:00"), coverageTo = OffsetDateTime.parse("2018-11-03T23:59:59-03:00"))
        val b = backend().apply { load(doc); profile = profile.copy(timezone = "America/Sao_Paulo") }
        val period = b.screen(PeriodTypeCode.ROLLING_QUARTER).period!!
        assertEquals(OffsetDateTime.parse("2018-11-04T01:00:00-02:00"), period.range.to)
        assertEquals(OffsetDateTime.parse("2018-11-03T23:59:59-03:00"), period.dataTo)
    }

    @Test fun `whole skipped civil dates never add a phantom day or chart month`() {
        val cases = listOf(
            SkippedCase("Pacific/Apia", "2011-01-01T00:00:00-10:00", "2012-03-29T23:59:59+14:00", "2011-12-31T00:00:00+14:00", "2012-03-30T00:00:00+14:00", 90L),
            SkippedCase("Pacific/Kiritimati", "1994-01-01T00:00:00-10:00", "1995-03-30T23:59:59+14:00", "1995-01-01T00:00:00+14:00", "1995-03-31T00:00:00+14:00", 89L),
        )
        for ((zone, rawFrom, rawTo, from, to, days) in cases) {
            val at = OffsetDateTime.parse(rawTo).minusDays(1)
            val doc = statement("1990-01-01", "2026-01-01", trn("skip", "2012-01-02T12:00", 1, "Shop", "Супермаркеты").copy(postedAt = at, amountMinor = days.toLong() * 100))
                .copy(coverageFrom = OffsetDateTime.parse(rawFrom), coverageTo = OffsetDateTime.parse(rawTo))
            val b = backend().apply { load(doc); profile = profile.copy(timezone = zone) }
            val s = b.screen(PeriodTypeCode.ROLLING_QUARTER)
            assertEquals(OffsetDateTime.parse(from), s.period!!.range.from)
            assertEquals(OffsetDateTime.parse(to), s.period!!.range.to)
            assertEquals(Coverage.COMPLETE, s.period!!.coverage)
            assertEquals(100L, s.tiles!!.dailyExpense.value!!.minor)
            assertTrue(s.period!!.gaps.isEmpty())
            assertTrue(s.monthlyChart!!.points!!.all { it.range.from.isBefore(it.range.to) })
            if (zone == "Pacific/Kiritimati") assertEquals(listOf("1995-01", "1995-02", "1995-03"), s.monthlyChart!!.points!!.map { it.month })
        }
    }

    @Test fun `the resolved exclusive boundary is shifted when the last covered day precedes a skipped date`() {
        val doc = statement("2010-01-01", "2011-12-29", trn("skip", "2011-12-28T12:00", 364, "Shop", "Супермаркеты"))
            .copy(coverageFrom = OffsetDateTime.parse("2010-01-01T00:00:00-10:00"), coverageTo = OffsetDateTime.parse("2011-12-29T23:59:59-10:00"))
        val b = backend().apply { load(doc); profile = profile.copy(timezone = "Pacific/Apia") }
        val s = b.screen(PeriodTypeCode.ROLLING_YEAR)
        assertEquals(OffsetDateTime.parse("2010-12-31T00:00:00-10:00"), s.period!!.range.from)
        assertEquals(OffsetDateTime.parse("2011-12-31T00:00:00+14:00"), s.period!!.range.to)
        assertEquals(100L, s.tiles!!.dailyExpense.value!!.minor)
        assertEquals(Coverage.COMPLETE, s.period!!.coverage)
    }

    @Test fun `a full month and daily count ignore its nonexistent final civil date in one or multiple uploads`() {
        for (split in listOf(false, true)) {
            val b = backend()
            val doc = statement("1994-01-01", "1995-02-27", trn("skip", "1994-12-20T12:00", 91, "Shop", "Супермаркеты"))
                .copy(coverageFrom = OffsetDateTime.parse("1994-01-01T00:00:00-10:00"), coverageTo = OffsetDateTime.parse("1995-02-27T23:59:59+14:00"))
            if (split) {
                b.load(doc.copy(coverageTo = OffsetDateTime.parse("1994-12-30T23:59:59-10:00")), "before.ofx")
                b.load(doc.copy(coverageFrom = OffsetDateTime.parse("1995-01-01T00:00:00+14:00"), transactions = emptyList()), "after.ofx")
            } else b.load(doc)
            b.profile = b.profile.copy(timezone = "Pacific/Kiritimati")
            val s = b.screen(PeriodTypeCode.ROLLING_QUARTER)
            assertEquals(Coverage.COMPLETE, s.period!!.coverage)
            assertEquals(100L, s.tiles!!.dailyExpense.value!!.minor)
            assertTrue(s.period!!.gaps.isEmpty(), "the normalized zero-width gap is not missing data")
            val december = s.monthlyChart!!.points!!.single { it.month == "1994-12" }
            assertEquals(Coverage.COMPLETE, december.coverage)
            assertTrue(december.gaps.isEmpty())
        }
    }

    @Test fun `today ceiling does not move the coverage anchor`() {
        val clock = TestClock("2026-10-10T12:00:00+03:00")
        val b = testBackend(clock).apply { load(statement("2026-10-01", "2026-10-30", trn("d", "2026-10-03T12:00", 31, "Shop", "Супермаркеты"))) }
        val early = b.screen(PeriodTypeCode.ROLLING_QUARTER)
        assertEquals(310L, early.tiles!!.dailyExpense.value!!.minor)
        assertEquals("2026-10-31", early.period!!.range.to.toLocalDate().toString())
        clock.at = OffsetDateTime.parse("2026-11-01T12:00:00+03:00")
        val later = b.screen(PeriodTypeCode.ROLLING_QUARTER)
        assertEquals(3100L / 30, later.tiles!!.dailyExpense.value!!.minor)
        assertEquals(early.period!!.range, later.period!!.range)
    }

    @Test fun `deleting the anchor statement recalculates the singleton and deleting all data clears it`() = runBlocking {
        val b = backend()
        val old = b.load(statement("2026-08-01", "2026-08-31", trn("d", "2026-08-03T12:00", 10, "Shop", "Супермаркеты")), "old.ofx")
        val latest = b.load(statement("2026-10-01", "2026-10-30"), "latest.ofx")
        val api = ru.finassist.pf.mock.api.MockStatementsApi(b)
        assertEquals("2026-10-31", b.screen(PeriodTypeCode.ROLLING_YEAR).period!!.range.to.toLocalDate().toString())
        api.deleteStatement(latest.id)
        assertEquals("2026-09-01", b.screen(PeriodTypeCode.ROLLING_YEAR).period!!.range.to.toLocalDate().toString())
        assertEquals(1000L, b.screen(PeriodTypeCode.ROLLING_YEAR).tiles!!.expense.value!!.minor)
        api.deleteStatement(old.id)
        assertFalse(b.screen(PeriodTypeCode.ROLLING_YEAR).hasData)
        assertTrue(b.analytics.periods(PeriodTypeCode.ROLLING_YEAR).periods.isEmpty())
    }

    @Test fun `api rejects mixed keys and accepts absent or exact singleton while preserving mode`() = runBlocking {
        val b = backend().apply { load(statement("2026-10-01", "2026-10-30", trn("d", "2026-10-03T12:00", 1, "Shop", "Супермаркеты"))) }
        val api = MockAnalyticsApi(b)
        for (type in listOf(PeriodTypeCode.ROLLING_YEAR, PeriodTypeCode.ROLLING_QUARTER)) {
            val response = api.getAnalytics(type, PeriodKey(type.code), TransferMode.WITHOUT)
            assertEquals(TransferMode.WITHOUT, response.params!!.transferMode)
            assertEquals(response, api.getAnalytics(type, null, TransferMode.WITHOUT))
            for (date in listOf("2026", "2026-10", "all_time", "rolling_week", if (type == PeriodTypeCode.ROLLING_YEAR) "rolling_quarter" else "rolling_year")) {
                val e = assertFailsWith<AppError.Api> { api.getAnalytics(type, PeriodKey(date), TransferMode.WITH) }
                assertEquals(ErrorCodes.VALIDATION_ERROR, e.code)
                assertEquals("date", e.details.single().field)
            }
            assertFailsWith<AppError.Api> { api.getAnalytics(PeriodTypeCode.MONTH, PeriodKey(type.code), TransferMode.WITH) }
        }
    }
}
