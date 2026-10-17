package ru.finassist.pf.mock

import ru.finassist.pf.core.api.model.AnalyticsNavigation
import ru.finassist.pf.core.api.model.AnalyticsParams
import ru.finassist.pf.core.api.model.AnalyticsPeriodInfo
import ru.finassist.pf.core.api.model.AnalyticsState
import ru.finassist.pf.core.api.model.Coverage
import ru.finassist.pf.core.api.model.LockReason
import ru.finassist.pf.core.api.model.OperationKindFilter
import ru.finassist.pf.core.api.model.OperationsFilter
import ru.finassist.pf.core.api.model.PeriodTypeCode
import ru.finassist.pf.core.api.model.TransferMode
import ru.finassist.pf.core.common.money.Money
import ru.finassist.pf.core.common.time.DateRange
import ru.finassist.pf.core.common.time.PeriodKey
import ru.finassist.pf.mock.domain.AnalyticsEngine
import ru.finassist.pf.mock.domain.AnalyticsSnapshot
import ru.finassist.pf.mock.domain.AnalyticsTiles
import ru.finassist.pf.mock.domain.BankFeesCard
import ru.finassist.pf.mock.domain.BreakdownStatus
import ru.finassist.pf.mock.domain.CategoryAmount
import ru.finassist.pf.mock.domain.CategoryBreakdown
import ru.finassist.pf.mock.domain.Insights
import ru.finassist.pf.mock.domain.Metric
import ru.finassist.pf.mock.domain.MetricBasis
import ru.finassist.pf.mock.domain.MetricLock
import ru.finassist.pf.mock.domain.MetricStatus
import ru.finassist.pf.mock.domain.MonthlyChart
import ru.finassist.pf.mock.domain.MonthlyPoint
import ru.finassist.pf.mock.domain.NotableSpendingCard
import ru.finassist.pf.mock.domain.NotableSpendingItem
import ru.finassist.pf.mock.domain.RegularPayment
import ru.finassist.pf.mock.domain.RegularPaymentsCard
import ru.finassist.pf.mock.domain.SmallFrequentCard
import ru.finassist.pf.mock.domain.SmallFrequentGroup
import java.time.OffsetDateTime
import java.time.YearMonth
import java.time.ZoneOffset

/**
 * What the server computes for the design-check artboards of «Аналитика» (`Analytics.dc.html` renderVals(): September
 * 2026 by the 25th): the composer turns it into the golden responses the client's design check renders (FIN-4).
 */
object AnalyticsDesignFixtures {
    fun day(m: Int, d: Int) = OffsetDateTime.of(2026, m, d, 0, 0, 0, 0, ZoneOffset.ofHours(3))
    fun month(m: Int) = DateRange(day(m, 1), if (m == 12) OffsetDateTime.of(2027, 1, 1, 0, 0, 0, 0, ZoneOffset.ofHours(3)) else day(m + 1, 1))
    fun rub(v: Long) = Money(v * 100)
    val sep = month(9)

    val year = DateRange(day(1, 1), OffsetDateTime.of(2027, 1, 1, 0, 0, 0, 0, ZoneOffset.ofHours(3)))

    /**
     * Filters of the operations behind the figures of one response, shaped as `AnalyticsEngine` shapes them: the
     * period and the transfer mode, plus what the figure selects. Never empty — a client treats `open_operations`
     * with an empty filter as no action (api.md «Действия»), and the rows would lose their chevrons in the design
     * check (FIN-100).
     */
    data class Scope(val range: DateRange, val mode: TransferMode) {
        val period get() = OperationsFilter(from = range.from, to = range.to, transferMode = mode)
        val expense get() = period.copy(kind = OperationKindFilter.EXPENSE)
        val income get() = period.copy(kind = OperationKindFilter.INCOME)
    }

    val basis = MetricBasis(DateRange(day(4, 1), day(9, 1)), fullMonths = 5)

    fun ready(value: Long, comparison: Metric? = null, typical: Metric? = null, share: Double? = null, filters: OperationsFilter? = null) =
        Metric(MetricStatus.READY, basis = basis, filters = filters, value = rub(value), comparison = comparison, typical = typical, shareOfIncome = share)

    fun locked(reason: LockReason, required: Int? = null, available: Int? = null) =
        Metric(MetricStatus.LOCKED, lock = MetricLock(reason, required, available))

    fun cat(name: String, icon: String, amount: Long, share: Double, count: Int, filter: OperationsFilter, note: String? = null) =
        CategoryAmount(
            categoryId = "c_$name", categoryName = name, categoryIcon = icon, note = note, amount = rub(amount), share = share,
            operationCount = count, filters = filter.copy(categoryId = "c_$name"),
        )

    fun point(m: Int, expense: Long?, kept: Double?) = MonthlyPoint(
        month = "2026-%02d".format(m), range = month(m),
        expense = expense?.let(::rub), income = expense?.let { e -> rub((e / (1 - kept!!)).toLong()) },
        coverage = when {
            expense == null -> Coverage.NO_DATA
            m == 9 -> Coverage.PARTIAL
            else -> Coverage.COMPLETE
        },
        dataTo = if (m == 9) day(9, 25) else null,
    )

    /** Insights of September; regular payments are recognised over the base months, the latest of them August. */
    fun insights(s: Scope) = Insights(
        regularPayments = RegularPaymentsCard(
            MetricStatus.READY, basis = basis, value = rub(4_200), count = 7,
            filters = s.period.copy(selection = "${AnalyticsEngine.SELECTION_REGULAR_ALL}_2026-08_${s.mode.code}", selectionName = "Регулярные платежи"),
            items = listOf(
                RegularPayment("r1", "Спортзал", rub(2_900), rub(2_900), "1-го числа", "c_sport", s.period.copy(selection = "s_regular_r1", selectionName = "Спортзал")),
                RegularPayment("r2", "Яндекс Плюс", rub(399), rub(399), "12-го числа", "c_subs", s.period.copy(selection = "s_regular_r2", selectionName = "Яндекс Плюс")),
                RegularPayment("r3", "Облачное хранилище", rub(299), rub(299), "3-го числа", "c_subs", s.period.copy(selection = "s_regular_r3", selectionName = "Облачное хранилище")),
                RegularPayment("r4", "Мобильная связь", rub(350), rub(350), "20-го числа", "c_phone", s.period.copy(selection = "s_regular_r4", selectionName = "Мобильная связь")),
                RegularPayment("r5", "Кинотеатр онлайн", rub(149), rub(149), "5-го числа", "c_subs", s.period.copy(selection = "s_regular_r5", selectionName = "Кинотеатр онлайн")),
                RegularPayment("r6", "Музыка", rub(69), rub(69), "8-го числа", "c_subs", s.period.copy(selection = "s_regular_r6", selectionName = "Музыка")),
                RegularPayment("r7", "Антивирус", rub(34), rub(34), "15-го числа", "c_subs", s.period.copy(selection = "s_regular_r7", selectionName = "Антивирус")),
            ),
        ),
        notableSpending = NotableSpendingCard(
            MetricStatus.READY, basis = basis,
            items = listOf(NotableSpendingItem("c_fuel", "Заправки", "car", rub(7_200), rub(3_100), s.expense.copy(categoryId = "c_fuel"))),
        ),
        bankFees = BankFeesCard(MetricStatus.READY, basis = basis, value = rub(1_240),
            filters = s.period.copy(selection = AnalyticsEngine.SELECTION_BANK_FEES, selectionName = "Комиссии и проценты банку"),
            kindsName = "обслуживание, переводы, снятие наличных"),
        // Counted over August, the last full month: September runs to the 25th (docs/calc.md, section 5).
        smallFrequent = SmallFrequentCard(
            MetricStatus.READY, basis = basis, value = rub(6_400), month = YearMonth.of(2026, 8),
            items = listOf(SmallFrequentGroup("Кофе и перекусы", 23, rub(280), rub(6_400), rub(77_000), s.period.copy(selection = "s_small_merchant_2026-08_with_coffee", selectionName = "Кофе и перекусы"))),
        ),
    )

    val analyticsState = AnalyticsState(
        stale = false, lastOperationAt = day(9, 25), dataFrom = day(4, 1), dataTo = day(9, 25), fullMonths = 5,
        unreadLinesCount = 0, calculatedAt = day(9, 26), recalculating = false,
    )

    /** The month of September; [noTransfers] is the «Без переводов» variant with transfers to people removed. */
    fun september(noTransfers: Boolean = false): AnalyticsSnapshot {
        // Expense by month April — September and the share of income kept.
        val spend = if (noTransfers) listOf(85_100L, 73_000L, 82_600L, 89_800L, 83_400L, 78_720L) else listOf(91_200L, 78_400L, 88_900L, 96_300L, 89_700L, 84_320L)
        val kept = if (noTransfers) listOf(0.36, 0.46, 0.40, 0.39, 0.40, 0.45) else listOf(0.38, 0.48, 0.41, 0.41, 0.41, 0.46)
        val t = noTransfers
        val s = Scope(sep, if (t) TransferMode.WITHOUT else TransferMode.WITH)
        return AnalyticsSnapshot(
            hasData = true,
            params = AnalyticsParams(PeriodTypeCode.MONTH, PeriodKey("2026-09"), if (t) TransferMode.WITHOUT else TransferMode.WITH),
            period = AnalyticsPeriodInfo(range = sep, isCurrent = true, coverage = Coverage.PARTIAL, dataFrom = day(9, 1), dataTo = day(9, 25), gaps = emptyList()),
            navigation = AnalyticsNavigation(previous = PeriodKey("2026-08")),
            tiles = AnalyticsTiles(
                expense = ready(
                    if (t) 78_720 else 84_320,
                    comparison = Metric(MetricStatus.READY, value = rub(if (t) 67_860 else 72_060), range = DateRange(day(8, 1), day(8, 26))),
                    typical = ready(if (t) 82_800 else 88_900),
                    filters = s.expense,
                ),
                income = ready(if (t) 143_390 else 156_900, filters = s.income),
                balance = ready(if (t) 64_670 else 72_580, share = if (t) 0.45 else 0.46),
                dailyExpense = ready(if (t) 3_150 else 3_370, comparison = Metric(MetricStatus.READY, value = rub(if (t) 2_690 else 2_890), range = month(8))),
                forecast = ready(if (t) 94_500 else 101_200),
            ),
            expenseCategories = CategoryBreakdown(
                BreakdownStatus.READY,
                if (t) {
                    listOf(
                        cat("Супермаркеты", "cart", 32_400, 0.41, 21, filter = s.expense),
                        cat("Различные товары", "package", 18_100, 0.23, 9, filter = s.expense),
                        cat("Заправки", "car", 7_200, 0.09, 4, filter = s.expense),
                        cat("Фастфуд", "bike", 4_900, 0.06, 8, filter = s.expense),
                        cat("Кафе и рестораны", "coffee", 4_000, 0.05, 6, filter = s.expense),
                        cat("Транспорт", "car", 3_400, 0.04, 11, filter = s.expense),
                        cat("Связь", "repeat", 3_420, 0.04, 2, filter = s.expense),
                        cat("Аптеки", "package", 2_400, 0.03, 3, filter = s.expense),
                    )
                } else {
                    listOf(
                        cat("Супермаркеты", "cart", 32_400, 0.38, 21, filter = s.expense),
                        cat("Различные товары", "package", 18_100, 0.21, 9, filter = s.expense),
                        cat("Заправки", "car", 7_200, 0.09, 4, filter = s.expense),
                        cat("Переводы", "users", 5_600, 0.07, 3, filter = s.expense),
                        cat("Кафе и рестораны", "coffee", 9_800, 0.12, 14, filter = s.expense),
                        cat("Транспорт", "car", 5_400, 0.06, 11, filter = s.expense),
                        cat("Связь", "repeat", 3_420, 0.04, 2, filter = s.expense),
                        cat("Аптеки", "package", 2_400, 0.03, 3, filter = s.expense),
                    )
                },
            ),
            incomeCategories = CategoryBreakdown(
                BreakdownStatus.READY,
                listOfNotNull(
                    cat("Финансы", "percent", 142_140, if (t) 0.99 else 0.91, 3, filter = s.income, note = "зарплата и проценты"),
                    if (t) null else cat("Переводы", "users", 13_510, 0.08, 5, filter = s.income, note = "5 переводов"),
                    cat("Дивиденды и купоны", "bar-chart", 1_250, 0.01, 1, filter = s.income, note = "по акциям и облигациям"),
                ),
            ),
            monthlyChart = MonthlyChart(MetricStatus.READY, basis = basis, points = spend.mapIndexed { i, e -> point(4 + i, e, kept[i]) }),
            insights = insights(s),
            state = analyticsState,
        )
    }

    /** Year 2026 with data from April 1 to September 25: no forecast (12 full months needed), no insights. */
    val year2026: AnalyticsSnapshot = run {
        val s = Scope(year, TransferMode.WITH)
        val spend = mapOf(4 to 91_200L, 5 to 78_400L, 6 to 88_900L, 7 to 96_300L, 8 to 89_700L, 9 to 84_320L)
        val kept = mapOf(4 to 0.38, 5 to 0.48, 6 to 0.41, 7 to 0.41, 8 to 0.41, 9 to 0.46)
        AnalyticsSnapshot(
            hasData = true,
            params = AnalyticsParams(PeriodTypeCode.YEAR, PeriodKey("2026"), TransferMode.WITH),
            period = AnalyticsPeriodInfo(
                range = year,
                isCurrent = true, coverage = Coverage.PARTIAL, dataFrom = day(4, 1), dataTo = day(9, 25), gaps = emptyList(),
            ),
            navigation = AnalyticsNavigation(),
            tiles = AnalyticsTiles(
                expense = ready(528_820, typical = locked(LockReason.NEED_FULL_MONTHS, 24, 5), filters = s.expense),
                income = ready(921_400, filters = s.income),
                balance = ready(392_580, share = 0.43),
                dailyExpense = ready(2_970),
                forecast = locked(LockReason.NEED_FULL_MONTHS, 12, 5),
            ),
            expenseCategories = CategoryBreakdown(
                BreakdownStatus.READY,
                listOf(
                    cat("Супермаркеты", "cart", 196_400, 0.37, 128, filter = s.expense),
                    cat("Различные товары", "package", 104_800, 0.20, 51, filter = s.expense),
                    cat("Заправки", "car", 38_600, 0.07, 24, filter = s.expense),
                    cat("Переводы", "users", 33_900, 0.06, 17, filter = s.expense),
                    cat("Кафе и рестораны", "coffee", 31_200, 0.06, 80, filter = s.expense),
                ),
            ),
            incomeCategories = CategoryBreakdown(
                BreakdownStatus.READY,
                listOf(
                    cat("Финансы", "percent", 851_900, 0.92, 18, filter = s.income),
                    cat("Переводы", "users", 62_300, 0.07, 27, filter = s.income),
                    cat("Дивиденды и купоны", "bar-chart", 7_200, 0.01, 4, filter = s.income),
                ),
            ),
            monthlyChart = MonthlyChart(MetricStatus.READY, basis = basis, points = (1..12).map { m -> point(m, spend[m], kept[m]) }),
            insights = null,
            state = analyticsState,
        )
    }

    /** Only September 1–25 in the statement: no full month yet. */
    val early: AnalyticsSnapshot = september().let { a ->
        val noFull = locked(LockReason.NEED_FULL_MONTHS, 1, 0)
        a.copy(
            navigation = AnalyticsNavigation(),
            tiles = a.tiles!!.copy(
                expense = a.tiles!!.expense.copy(comparison = noFull, typical = locked(LockReason.NEED_FULL_MONTHS, 2, 0)),
                dailyExpense = a.tiles!!.dailyExpense.copy(comparison = noFull),
            ),
            monthlyChart = MonthlyChart(MetricStatus.LOCKED, lock = MetricLock(LockReason.NEED_MONTHS_WITH_DATA, 2, 1)),
            insights = Insights(
                regularPayments = RegularPaymentsCard(MetricStatus.LOCKED, lock = MetricLock(LockReason.NEED_FULL_MONTHS, 2, 0)),
                notableSpending = NotableSpendingCard(MetricStatus.LOCKED, lock = MetricLock(LockReason.NEED_FULL_MONTHS, 3, 0)),
                bankFees = a.insights!!.bankFees.copy(basis = null),
                smallFrequent = SmallFrequentCard(MetricStatus.LOCKED, lock = MetricLock(LockReason.NEED_FULL_MONTHS, 1, 0)),
            ),
            state = analyticsState.copy(dataFrom = day(9, 1), fullMonths = 0),
        )
    }

    /** Last operation on September 25, more than two weeks ago: the forecast is locked (`stale_data`), the tile shows the fact. */
    val stale: AnalyticsSnapshot = september().let { a ->
        a.copy(tiles = a.tiles!!.copy(forecast = locked(LockReason.STALE_DATA)), state = analyticsState.copy(stale = true))
    }
}
