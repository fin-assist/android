package ru.finassist.pf.feature.analytics.impl

import ru.finassist.pf.core.api.model.AnalyticsPeriodInfo
import ru.finassist.pf.core.api.model.AnalyticsState
import ru.finassist.pf.core.api.model.Coverage
import ru.finassist.pf.core.api.model.LockReason
import ru.finassist.pf.core.api.model.Metric
import ru.finassist.pf.core.api.model.MetricBasis
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
        assertEquals(
            "Сентябрь к 25-му — 84 320 ₽, на 17% больше, чем август к этому дню",
            AnalyticsTexts.comparison(Money(8_432_000), prev, PeriodTypeCode.MONTH, PeriodKey("2026-09"))!!.plain(),
        )
    }

    @Test
    fun `tile notes as on the mockup`() {
        assertEquals("на сентябрь", AnalyticsTexts.forecastNote(PeriodKey("2026-09")))
        assertEquals("на III квартал", AnalyticsTexts.forecastNote(PeriodKey("2026-Q3")))
        assertEquals("на 2026 год", AnalyticsTexts.forecastNote(PeriodKey("2026")))
        val aug = DateRange(t("2026-08-01T00:00:00+03:00"), t("2026-09-01T00:00:00+03:00"))
        assertEquals("в августе — 2 890 ₽", AnalyticsTexts.dailyComparison(Metric(MetricStatus.READY, value = Money(289_000), range = aug))!!.plain())
        val basis = MetricBasis(DateRange(t("2026-04-01T00:00:00+03:00"), t("2026-09-01T00:00:00+03:00")), 5)
        assertEquals("Среднее за апрель — август — 88 900 ₽", AnalyticsTexts.typical(Metric(MetricStatus.READY, basis = basis, value = Money(8_890_000)))!!.plain())
    }

    @Test
    fun `insight descriptions`() {
        val basis = DateRange(t("2026-04-01T00:00:00+03:00"), t("2026-09-01T00:00:00+03:00"))
        assertEquals(
            "В 2,3 раза больше обычного — обычно около 3 100 ₽ (апрель — август)",
            AnalyticsTexts.notable(Money(720_000), Money(310_000), basis).plain(),
        )
        assertEquals(
            "в месяц · кофе и перекусы: 23 раза по 280 ₽ — около 77 000 ₽ в год",
            AnalyticsTexts.smallFrequent("Кофе и перекусы", 23, Money(28_000), Money(7_700_000)).plain(),
        )
    }

    @Test
    fun `incomplete year caption`() {
        val year = AnalyticsPeriodInfo(
            range = DateRange(t("2026-01-01T00:00:00+03:00"), t("2027-01-01T00:00:00+03:00")), isCurrent = true,
            coverage = Coverage.PARTIAL, dataFrom = t("2026-04-01T00:00:00+03:00"), dataTo = t("2026-09-25T21:40:00+03:00"), gaps = emptyList(),
        )
        assertEquals("Апрель — сентябрь 2026 · год неполный", AnalyticsTexts.partialPeriod(year, PeriodTypeCode.YEAR)!!.plain())
        assertNull(AnalyticsTexts.partialPeriod(year, PeriodTypeCode.MONTH))
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

    private fun month(m: Int, y: Int = 2026) = DateRange(
        t("%04d-%02d-01T00:00:00+03:00".format(y, m)),
        t(if (m == 12) "%04d-01-01T00:00:00+03:00".format(y + 1) else "%04d-%02d-01T00:00:00+03:00".format(y, m + 1)),
    )

    private fun point(m: Int, expense: Long?, coverage: Coverage = if (expense == null) Coverage.NO_DATA else Coverage.COMPLETE, dataFrom: String? = null, dataTo: String? = null, gaps: List<DateRange> = emptyList()) =
        MonthlyPoint(
            month = "2026-%02d".format(m), range = month(m), expense = expense?.let { Money(it * 100) }, coverage = coverage,
            dataFrom = dataFrom?.let(::t), dataTo = dataTo?.let(::t), gaps = gaps,
        )

    private fun period(type: Coverage = Coverage.PARTIAL, from: String? = "2026-09-01T08:15:00+03:00", to: String? = "2026-09-25T21:40:00+03:00", range: DateRange = month(9)) =
        AnalyticsPeriodInfo(range = range, isCurrent = true, coverage = type, dataFrom = from?.let(::t), dataTo = to?.let(::t), gaps = emptyList())

    @Test
    fun `partial fill counts the days with data`() {
        assertEquals(25f / 30, AnalyticsTexts.partialFill(point(9, 84_320, Coverage.PARTIAL, dataTo = "2026-09-25T21:40:00+03:00")))
        assertEquals(2f / 30, AnalyticsTexts.partialFill(point(9, 1, Coverage.PARTIAL, dataFrom = "2026-09-29T10:00:00+03:00")))
        val gap = DateRange(t("2026-09-11T00:00:00+03:00"), t("2026-09-21T00:00:00+03:00"))
        assertEquals(20f / 30, AnalyticsTexts.partialFill(point(9, 1, Coverage.PARTIAL, gaps = listOf(gap))))
        assertNull(AnalyticsTexts.partialFill(point(8, 89_700)))
        assertNull(AnalyticsTexts.partialFill(point(10, null)))
    }

    @Test
    fun `year chart note names empty months and partial ones`() {
        val points = (1..12).map { m ->
            when (m) {
                in 4..8 -> point(m, 90_000)
                9 -> point(9, 84_320, Coverage.PARTIAL, dataTo = "2026-09-25T21:40:00+03:00")
                else -> point(m, null)
            }
        }
        assertEquals("Январь — март: в выписке нет операций. Сентябрь — по 25-е", AnalyticsTexts.yearChartNote(points, current = true)!!.plain())
        assertEquals(
            "Январь — март: в выписке нет операций. Октябрь — декабрь: в выписке нет операций. Сентябрь — по 25-е",
            AnalyticsTexts.yearChartNote(points, current = false)!!.plain(),
        )
    }

    @Test
    fun `year chart note for a single empty month and for a full year`() {
        val one = listOf(point(1, null), point(2, 1_000), point(3, 1_000))
        assertEquals("Январь: в выписке нет операций", AnalyticsTexts.yearChartNote(one, current = false))
        assertNull(AnalyticsTexts.yearChartNote(listOf(point(1, 1_000), point(2, 1_000)), current = false))
    }

    @Test
    fun `early period is named by its days`() {
        assertEquals(true, AnalyticsTexts.isEarly(PeriodTypeCode.MONTH, period(), hasPrevious = false))
        assertEquals(false, AnalyticsTexts.isEarly(PeriodTypeCode.MONTH, period(), hasPrevious = true))
        assertEquals(false, AnalyticsTexts.isEarly(PeriodTypeCode.MONTH, period(Coverage.COMPLETE), hasPrevious = false))
        assertEquals(false, AnalyticsTexts.isEarly(PeriodTypeCode.YEAR, period(), hasPrevious = false))
        assertEquals("1–25 сентября 2026", AnalyticsTexts.periodLabel(PeriodKey("2026-09"), period(), PeriodTypeCode.MONTH, early = true).plain())
        assertEquals("Сентябрь 2026", AnalyticsTexts.periodLabel(PeriodKey("2026-09"), period(), PeriodTypeCode.MONTH, early = false))
        assertEquals("Сентябрь 2026", AnalyticsTexts.periodLabel(PeriodKey("2026-09"), period(from = null), PeriodTypeCode.MONTH, early = true))
    }

    @Test
    fun `unread lines name the month`() {
        assertEquals("5 строк за сентябрь не прочитали — суммы могут быть неполными", AnalyticsTexts.unreadLines(5, PeriodTypeCode.MONTH, period()).plain())
        assertEquals("1 строку за период не прочитали — суммы могут быть неполными", AnalyticsTexts.unreadLines(1, PeriodTypeCode.YEAR, period()).plain())
    }

    @Test
    fun `forecast label and total caption`() {
        assertEquals("Прогноз на год", AnalyticsTexts.forecastLabel(PeriodTypeCode.YEAR))
        assertEquals("Прогноз расходов", AnalyticsTexts.forecastLabel(PeriodTypeCode.MONTH))
        assertEquals("1–25 сентября", AnalyticsTexts.totalCaption(PeriodTypeCode.MONTH, period()).plain())
        val year = period(from = "2026-04-01T00:00:00+03:00", to = "2026-09-25T21:40:00+03:00", range = DateRange(t("2026-01-01T00:00:00+03:00"), t("2027-01-01T00:00:00+03:00")))
        assertEquals("апрель — сентябрь", AnalyticsTexts.totalCaption(PeriodTypeCode.YEAR, year).plain())
        // The last operation at midnight on the 1st still belongs to September.
        val midnight = period(from = "2026-04-01T00:00:00+03:00", to = "2026-09-01T00:00:00+03:00", range = DateRange(t("2026-01-01T00:00:00+03:00"), t("2027-01-01T00:00:00+03:00")))
        assertEquals("апрель — сентябрь", AnalyticsTexts.totalCaption(PeriodTypeCode.YEAR, midnight).plain())
        assertEquals("1–30 сентября", AnalyticsTexts.totalCaption(PeriodTypeCode.MONTH, period(from = null, to = null)).plain())
    }
}
