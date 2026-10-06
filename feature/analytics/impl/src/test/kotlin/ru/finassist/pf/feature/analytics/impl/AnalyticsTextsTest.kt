package ru.finassist.pf.feature.analytics.impl

import ru.finassist.pf.core.api.model.AnalyticsState
import ru.finassist.pf.core.api.model.Coverage
import ru.finassist.pf.core.api.model.LockReason
import ru.finassist.pf.core.api.model.Metric
import ru.finassist.pf.core.api.model.MetricLock
import ru.finassist.pf.core.api.model.MetricStatus
import ru.finassist.pf.core.api.model.MonthlyPoint
import ru.finassist.pf.core.api.model.PeriodTypeCode
import ru.finassist.pf.core.common.money.Money
import ru.finassist.pf.core.common.time.DateRange
import ru.finassist.pf.core.common.time.PeriodKey
import ru.finassist.pf.feature.analytics.impl.domain.AnalyticsTexts
import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AnalyticsTextsTest {
    private fun String.plain() = replace(' ', ' ').replace(' ', ' ')
    private fun t(s: String) = OffsetDateTime.parse(s)

    private val state = AnalyticsState(
        stale = false, dataFrom = t("2026-09-01T08:15:00+03:00"), dataTo = t("2026-09-25T21:40:00+03:00"),
        fullMonths = 0, unreadLinesCount = 0, calculatedAt = t("2026-09-25T21:52:00+03:00"), recalculating = false,
    )

    @Test
    fun `period titles`() {
        assertEquals("Сентябрь 2026", AnalyticsTexts.periodTitle(PeriodKey("2026-09")))
        assertEquals("III квартал 2026", AnalyticsTexts.periodTitle(PeriodKey("2026-Q3")))
        assertEquals("2026", AnalyticsTexts.periodTitle(PeriodKey("2026")))
    }

    @Test
    fun `lock texts`() {
        assertEquals(
            "Нужно 3 полных месяца — пока есть только 1–25 сентября",
            AnalyticsTexts.lock(MetricLock(LockReason.NEED_FULL_MONTHS, required = 3, available = 0), state).plain(),
        )
        assertEquals("Нужно 3 полных месяца, есть 1", AnalyticsTexts.lock(MetricLock(LockReason.NEED_FULL_MONTHS, 3, 1), state).plain())
        assertEquals("Нужно 2 месяца с данными, есть 1", AnalyticsTexts.lock(MetricLock(LockReason.NEED_MONTHS_WITH_DATA, 2, 1), state).plain())
        assertEquals("Пока недоступно", AnalyticsTexts.lock(MetricLock(LockReason.UNKNOWN), state))
    }

    @Test
    fun comparison() {
        val prev = Metric(
            status = MetricStatus.READY, value = Money(7_200_000),
            range = DateRange(t("2026-08-01T00:00:00+03:00"), t("2026-08-26T00:00:00+03:00")),
        )
        assertEquals("На 17% больше, чем август к этому дню", AnalyticsTexts.comparison(Money(8_432_000), prev, PeriodTypeCode.MONTH))
        assertEquals("На 50% меньше, чем август к этому дню", AnalyticsTexts.comparison(Money(3_600_000), prev, PeriodTypeCode.MONTH))
        assertNull(AnalyticsTexts.comparison(Money(1), Metric(status = MetricStatus.LOCKED), PeriodTypeCode.MONTH))
    }

    @Test
    fun `share of income`() {
        assertEquals("46% дохода", AnalyticsTexts.share(Metric(status = MetricStatus.READY, shareOfIncome = 0.46)))
        assertEquals("−12% · потратили больше, чем получили", AnalyticsTexts.share(Metric(status = MetricStatus.READY, shareOfIncome = -0.12)))
        assertEquals("Доходов за период нет", AnalyticsTexts.share(Metric(status = MetricStatus.READY)))
    }

    @Test
    fun `bar notes`() {
        val sep = DateRange(t("2026-09-01T00:00:00+03:00"), t("2026-10-01T00:00:00+03:00"))
        fun point(from: String?, to: String?, coverage: Coverage, gaps: List<DateRange> = emptyList()) =
            MonthlyPoint("2026-09", sep, expense = Money(1), coverage = coverage, dataFrom = from?.let(::t), dataTo = to?.let(::t), gaps = gaps)
        assertEquals("по 25-е", AnalyticsTexts.barNote(point("2026-09-01T08:00:00+03:00", "2026-09-25T21:00:00+03:00", Coverage.PARTIAL)))
        assertEquals("с 29-го", AnalyticsTexts.barNote(point("2026-09-29T08:00:00+03:00", "2026-09-30T21:00:00+03:00", Coverage.PARTIAL)))
        assertEquals(
            "без 11–19-го",
            AnalyticsTexts.barNote(
                point("2026-09-01T08:00:00+03:00", "2026-09-30T21:00:00+03:00", Coverage.PARTIAL, listOf(DateRange(t("2026-09-11T00:00:00+03:00"), t("2026-09-20T00:00:00+03:00")))),
            ),
        )
        assertNull(AnalyticsTexts.barNote(point(null, null, Coverage.COMPLETE)))
    }
}
