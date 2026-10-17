package ru.finassist.pf.mock

import ru.finassist.pf.core.api.model.AnalyticsPeriodInfo
import ru.finassist.pf.core.api.model.AnalyticsState
import ru.finassist.pf.core.api.model.Coverage
import ru.finassist.pf.core.api.model.LockReason
import ru.finassist.pf.mock.domain.Metric
import ru.finassist.pf.mock.domain.MetricBasis
import ru.finassist.pf.mock.domain.MetricLock
import ru.finassist.pf.mock.domain.MetricStatus
import ru.finassist.pf.mock.domain.MonthlyPoint
import ru.finassist.pf.core.api.model.PeriodTypeCode
import ru.finassist.pf.core.common.money.Money
import ru.finassist.pf.core.common.time.DateRange
import ru.finassist.pf.core.common.time.PeriodKey
import ru.finassist.pf.mock.domain.AnalyticsServerTexts as AnalyticsTexts
import java.time.OffsetDateTime
import java.time.YearMonth
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Texts of the Analytics blocks, built by the server since FIN-4 (moved from the client with [AnalyticsTexts]). */
class AnalyticsServerTextsTest {
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
    fun `small frequent spending names the month of the calculation only when it is not the selected one`() {
        val text = "в месяц · фастфуд: 10 раз по 442,70 ₽ — около 53 124 ₽ в год"
        fun sf(month: YearMonth?, selected: YearMonth?) = AnalyticsTexts.smallFrequent("Фастфуд", 10, Money(44_270), Money(5_312_400), month, selected).plain()
        // The selected month is full: it is the month of the calculation, the text stays as it was.
        assertEquals(text, sf(YearMonth.of(2026, 8), YearMonth.of(2026, 8)))
        // September is incomplete: the card counts the last full month before it and names it.
        assertEquals("по августу · $text", sf(YearMonth.of(2026, 8), YearMonth.of(2026, 9)))
        // Across a year the month carries its year.
        assertEquals("по декабрю 2025 · $text", sf(YearMonth.of(2025, 12), YearMonth.of(2026, 1)))
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
        // A gap that runs into the next months counts only its September days: data to the 20th, gap from the 21st.
        val longGap = DateRange(t("2026-09-21T00:00:00+03:00"), t("2026-11-10T00:00:00+03:00"))
        assertEquals(20f / 30, AnalyticsTexts.partialFill(point(9, 1, Coverage.PARTIAL, dataTo = "2026-09-20T18:00:00+03:00", gaps = listOf(longGap))))
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

    @Test
    fun `unit names of the period types`() {
        assertEquals("месяц", AnalyticsTexts.unitName(PeriodTypeCode.MONTH))
        assertEquals("квартал", AnalyticsTexts.unitName(PeriodTypeCode.QUARTER))
        assertEquals("год", AnalyticsTexts.unitName(PeriodTypeCode.YEAR))
        assertEquals("месяц", AnalyticsTexts.unitName(PeriodTypeCode.UNKNOWN))
    }

    @Test
    fun `coverage caption under the period`() {
        assertNull(AnalyticsTexts.coverage(period(Coverage.COMPLETE)))
        assertEquals("За этот период данных нет", AnalyticsTexts.coverage(period(Coverage.NO_DATA)))
        assertEquals("Данные за 1–25 сентября", AnalyticsTexts.coverage(period())!!.plain())
        // Unknown coverage is shown as partial.
        assertEquals("Данные за 1–25 сентября", AnalyticsTexts.coverage(period(Coverage.UNKNOWN))!!.plain())
        assertEquals("Данные за часть периода", AnalyticsTexts.coverage(period(to = null)))
        val gap = DateRange(t("2026-07-11T00:00:00+03:00"), t("2026-07-20T00:00:00+03:00"))
        val july = AnalyticsPeriodInfo(
            range = month(7), isCurrent = false, coverage = Coverage.PARTIAL,
            dataFrom = t("2026-07-01T09:00:00+03:00"), dataTo = t("2026-07-31T20:00:00+03:00"), gaps = listOf(gap),
        )
        assertEquals("Данные за 1–31 июля, без 11–19 июля · период неполный", AnalyticsTexts.coverage(july)!!.plain())
    }

    @Test
    fun `lock texts without details`() {
        assertEquals("Пока недоступно", AnalyticsTexts.lock(null, state))
        assertEquals("Нужно больше полных месяцев", AnalyticsTexts.lock(MetricLock(LockReason.NEED_FULL_MONTHS), state))
        assertEquals("Нужно больше месяцев с данными", AnalyticsTexts.lock(MetricLock(LockReason.NEED_MONTHS_WITH_DATA), state))
        // Without the state the day range is unknown: the count is named instead.
        assertEquals("Нужно 3 полных месяца, есть 0", AnalyticsTexts.lock(MetricLock(LockReason.NEED_FULL_MONTHS, 3), null).plain())
        assertEquals("Выписка устарела — загрузите новую", AnalyticsTexts.lock(MetricLock(LockReason.STALE_DATA), state))
    }

    @Test
    fun `too early in the month names the first day it is counted from`() {
        assertEquals(
            "Посчитаем с 8 сентября",
            AnalyticsTexts.lock(MetricLock(LockReason.TOO_EARLY_IN_MONTH, availableFrom = t("2026-09-08T00:00:00+03:00")), state).plain(),
        )
        assertEquals("Рано считать — мало дней", AnalyticsTexts.lock(MetricLock(LockReason.TOO_EARLY_IN_MONTH), state))
    }

    @Test
    fun `quarter and year comparisons name the previous period`() {
        val prev = Metric(status = MetricStatus.READY, value = Money(10_000_00), range = month(4))
        assertEquals("На 20% больше, чем прошлый квартал к этому дню", AnalyticsTexts.comparison(Money(12_000_00), prev, PeriodTypeCode.QUARTER, PeriodKey("2026-Q3")))
        assertEquals("Столько же, сколько прошлый год к этому дню", AnalyticsTexts.comparison(Money(10_000_00), prev, PeriodTypeCode.YEAR, PeriodKey("2026")))
        assertEquals("На 20% больше, чем прошлый период к этому дню", AnalyticsTexts.comparison(Money(12_000_00), prev.copy(range = null), PeriodTypeCode.MONTH))
    }

    @Test
    fun `daily average over the months with data`() {
        val year = period(from = "2026-04-01T00:00:00+03:00", to = "2026-10-01T00:00:00+03:00", range = DateRange(t("2026-01-01T00:00:00+03:00"), t("2027-01-01T00:00:00+03:00")))
        assertEquals("в среднем за апрель — сентябрь", AnalyticsTexts.dailyAverageOver(year)!!.plain())
        assertNull(AnalyticsTexts.dailyAverageOver(period(from = null)))
        assertNull(AnalyticsTexts.dailyAverageOver(period(to = null)))
    }

    @Test
    fun `balance explained at the end of the screen`() {
        assertEquals("Доходов за период нет", AnalyticsTexts.balanceExplained(Metric(status = MetricStatus.READY), withChart = true))
        assertEquals(
            "46% дохода: на столько доходы по выписке больше расходов. На графике — та же доля по месяцам",
            AnalyticsTexts.balanceExplained(Metric(status = MetricStatus.READY, shareOfIncome = 0.456), withChart = true).plain(),
        )
        assertEquals(
            "12% дохода: на столько расходы по выписке больше доходов. График по месяцам появится, когда в выписке будет хотя бы один полный месяц",
            AnalyticsTexts.balanceExplained(Metric(status = MetricStatus.READY, shareOfIncome = -0.12), withChart = false).plain(),
        )
    }

    @Test
    fun `peak month of the chart`() {
        assertEquals("Больше всего — в июле: 96 300 ₽", AnalyticsTexts.peakMonth(listOf(point(6, 90_000), point(7, 96_300), point(8, null)))!!.plain())
        assertNull(AnalyticsTexts.peakMonth(listOf(point(8, null))))
        assertNull(AnalyticsTexts.peakMonth(emptyList()))
    }

    @Test
    fun `a partial bar covering the whole month has no note`() {
        assertNull(AnalyticsTexts.barNote(point(9, 1_000, Coverage.PARTIAL, dataFrom = "2026-09-01T10:00:00+03:00", dataTo = "2026-09-30T20:00:00+03:00")))
    }

    @Test
    fun `balance share of a month needs income and expenses`() {
        val p = point(9, 600).copy(income = Money(1_000_00))
        assertEquals(0.4, AnalyticsTexts.balanceShare(p)!!, 1e-9)
        assertEquals(-0.5, AnalyticsTexts.balanceShare(point(9, 1_500).copy(income = Money(1_000_00)))!!, 1e-9)
        assertNull(AnalyticsTexts.balanceShare(point(9, 600)))
        assertNull(AnalyticsTexts.balanceShare(point(9, null).copy(income = Money(1_000_00))))
        assertNull(AnalyticsTexts.balanceShare(point(9, 600).copy(income = Money(0))))
    }
}
