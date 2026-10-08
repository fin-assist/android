package ru.finassist.pf.feature.analytics.impl.ui

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ru.finassist.pf.core.api.model.Analytics
import ru.finassist.pf.core.api.model.AnalyticsNavigation
import ru.finassist.pf.core.api.model.AnalyticsParams
import ru.finassist.pf.core.api.model.AnalyticsPeriodInfo
import ru.finassist.pf.core.api.model.AnalyticsState
import ru.finassist.pf.core.api.model.AnalyticsTiles
import ru.finassist.pf.core.api.model.AssistantLimit
import ru.finassist.pf.core.api.model.BankFeesCard
import ru.finassist.pf.core.api.model.BreakdownStatus
import ru.finassist.pf.core.api.model.CategoryAmount
import ru.finassist.pf.core.api.model.CategoryBreakdown
import ru.finassist.pf.core.api.model.Coverage
import ru.finassist.pf.core.api.model.Insights
import ru.finassist.pf.core.api.model.LockReason
import ru.finassist.pf.core.api.model.Metric
import ru.finassist.pf.core.api.model.MetricLock
import ru.finassist.pf.core.api.model.MetricBasis
import ru.finassist.pf.core.api.model.MetricStatus
import ru.finassist.pf.core.api.model.MonthlyChart
import ru.finassist.pf.core.api.model.MonthlyPoint
import ru.finassist.pf.core.api.model.NotableSpendingCard
import ru.finassist.pf.core.api.model.NotableSpendingItem
import ru.finassist.pf.core.api.model.OperationsFilter
import ru.finassist.pf.core.api.model.PeriodTypeCode
import ru.finassist.pf.core.api.model.RegularPayment
import ru.finassist.pf.core.api.model.RegularPaymentsCard
import ru.finassist.pf.core.api.model.SmallFrequentCard
import ru.finassist.pf.core.api.model.SmallFrequentGroup
import ru.finassist.pf.core.api.model.TransferMode
import ru.finassist.pf.core.common.money.Money
import ru.finassist.pf.core.common.time.DateRange
import ru.finassist.pf.core.common.time.PeriodKey
import ru.finassist.pf.core.screenshot.DesignCheck
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * Artboards `Analytics` / `AnalyticsDark` and their states (`AnalyticsNoTransfers`, `AnalyticsTransfers`,
 * `AnalyticsHintCategories`, `AnalyticsHintAsk`, `AnalyticsLimit`, `AnalyticsYear`, `AnalyticsEmpty`, `AnalyticsEarly`,
 * `AnalyticsStale`, `AnalyticsPartial`): September 2026 by the 25th, numbers as in `Analytics.dc.html` renderVals().
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AnalyticsDesignCheckTest {
    private fun day(m: Int, d: Int) = OffsetDateTime.of(2026, m, d, 0, 0, 0, 0, ZoneOffset.ofHours(3))
    private fun month(m: Int) = DateRange(day(m, 1), if (m == 12) OffsetDateTime.of(2027, 1, 1, 0, 0, 0, 0, ZoneOffset.ofHours(3)) else day(m + 1, 1))
    private fun rub(v: Long) = Money(v * 100)
    private val f = OperationsFilter()
    private val sep = month(9)
    private val basis = MetricBasis(DateRange(day(4, 1), day(9, 1)), fullMonths = 5)

    private fun ready(value: Long, comparison: Metric? = null, typical: Metric? = null, share: Double? = null) =
        Metric(MetricStatus.READY, basis = basis, filters = f, value = rub(value), comparison = comparison, typical = typical, shareOfIncome = share)

    private fun locked(reason: LockReason, required: Int? = null, available: Int? = null) =
        Metric(MetricStatus.LOCKED, lock = MetricLock(reason, required, available))

    private fun cat(name: String, icon: String, amount: Long, share: Double, count: Int, note: String? = null) =
        CategoryAmount(categoryId = "c_$name", categoryName = name, categoryIcon = icon, note = note, amount = rub(amount), share = share, operationCount = count, filters = f)

    private fun point(m: Int, expense: Long?, kept: Double?) = MonthlyPoint(
        month = "2026-%02d".format(m), range = month(m),
        expense = expense?.let(::rub), income = expense?.let { e -> rub((e / (1 - kept!!)).toLong()) },
        coverage = when {
            expense == null -> Coverage.NO_DATA
            m == 9 -> Coverage.PARTIAL
            else -> Coverage.COMPLETE
        },
        dataTo = if (m == 9) day(9, 25) else null,
    )

    private val insights = Insights(
        regularPayments = RegularPaymentsCard(
            MetricStatus.READY, basis = basis, filters = f, value = rub(4_200), count = 7,
            items = listOf(
                RegularPayment("r1", "Спортзал", rub(2_900), rub(2_900), "1-го числа", "c_sport", f),
                RegularPayment("r2", "Яндекс Плюс", rub(399), rub(399), "12-го числа", "c_subs", f),
                RegularPayment("r3", "Облачное хранилище", rub(299), rub(299), "3-го числа", "c_subs", f),
                RegularPayment("r4", "Мобильная связь", rub(350), rub(350), "20-го числа", "c_phone", f),
                RegularPayment("r5", "Кинотеатр онлайн", rub(149), rub(149), "5-го числа", "c_subs", f),
                RegularPayment("r6", "Музыка", rub(69), rub(69), "8-го числа", "c_subs", f),
                RegularPayment("r7", "Антивирус", rub(34), rub(34), "15-го числа", "c_subs", f),
            ),
        ),
        notableSpending = NotableSpendingCard(
            MetricStatus.READY, basis = basis,
            items = listOf(NotableSpendingItem("c_fuel", "Заправки", "car", rub(7_200), rub(3_100), f)),
        ),
        bankFees = BankFeesCard(MetricStatus.READY, basis = basis, filters = f, value = rub(1_240), kindsName = "обслуживание, переводы, снятие наличных"),
        smallFrequent = SmallFrequentCard(
            MetricStatus.READY, basis = basis, value = rub(6_400),
            items = listOf(SmallFrequentGroup("Кофе и перекусы", 23, rub(280), rub(6_400), rub(77_000), f)),
        ),
    )

    private val analyticsState = AnalyticsState(
        stale = false, lastOperationAt = day(9, 25), dataFrom = day(4, 1), dataTo = day(9, 25), fullMonths = 5,
        unreadLinesCount = 0, calculatedAt = day(9, 26), recalculating = false,
    )

    /** The month of September; [noTransfers] is the «Без переводов» variant with transfers to people removed. */
    private fun september(noTransfers: Boolean = false): Analytics {
        // Expense by month April — September and the share of income kept.
        val spend = if (noTransfers) listOf(85_100L, 73_000L, 82_600L, 89_800L, 83_400L, 78_720L) else listOf(91_200L, 78_400L, 88_900L, 96_300L, 89_700L, 84_320L)
        val kept = if (noTransfers) listOf(0.36, 0.46, 0.40, 0.39, 0.40, 0.45) else listOf(0.38, 0.48, 0.41, 0.41, 0.41, 0.46)
        val t = noTransfers
        return Analytics(
            hasData = true,
            params = AnalyticsParams(PeriodTypeCode.MONTH, PeriodKey("2026-09"), if (t) TransferMode.WITHOUT else TransferMode.WITH),
            period = AnalyticsPeriodInfo(range = sep, isCurrent = true, coverage = Coverage.PARTIAL, dataFrom = day(9, 1), dataTo = day(9, 25), gaps = emptyList()),
            navigation = AnalyticsNavigation(previous = PeriodKey("2026-08")),
            tiles = AnalyticsTiles(
                expense = ready(
                    if (t) 78_720 else 84_320,
                    comparison = Metric(MetricStatus.READY, value = rub(if (t) 67_860 else 72_060), range = DateRange(day(8, 1), day(8, 26))),
                    typical = ready(if (t) 82_800 else 88_900),
                ),
                income = ready(if (t) 143_390 else 156_900),
                balance = ready(if (t) 64_670 else 72_580, share = if (t) 0.45 else 0.46),
                dailyExpense = ready(if (t) 3_150 else 3_370, comparison = Metric(MetricStatus.READY, value = rub(if (t) 2_690 else 2_890), range = month(8))),
                forecast = ready(if (t) 94_500 else 101_200),
            ),
            expenseCategories = CategoryBreakdown(
                BreakdownStatus.READY,
                if (t) {
                    listOf(
                        cat("Супермаркеты", "cart", 32_400, 0.41, 21),
                        cat("Различные товары", "package", 18_100, 0.23, 9),
                        cat("Заправки", "car", 7_200, 0.09, 4),
                        cat("Фастфуд", "bike", 4_900, 0.06, 8),
                        cat("Кафе и рестораны", "coffee", 4_000, 0.05, 6),
                        cat("Транспорт", "car", 3_400, 0.04, 11),
                        cat("Связь", "repeat", 3_420, 0.04, 2),
                        cat("Аптеки", "package", 2_400, 0.03, 3),
                    )
                } else {
                    listOf(
                        cat("Супермаркеты", "cart", 32_400, 0.38, 21),
                        cat("Различные товары", "package", 18_100, 0.21, 9),
                        cat("Заправки", "car", 7_200, 0.09, 4),
                        cat("Переводы", "users", 5_600, 0.07, 3),
                        cat("Кафе и рестораны", "coffee", 9_800, 0.12, 14),
                        cat("Транспорт", "car", 5_400, 0.06, 11),
                        cat("Связь", "repeat", 3_420, 0.04, 2),
                        cat("Аптеки", "package", 2_400, 0.03, 3),
                    )
                },
            ),
            incomeCategories = CategoryBreakdown(
                BreakdownStatus.READY,
                listOfNotNull(
                    cat("Финансы", "percent", 142_140, if (t) 0.99 else 0.91, 3, note = "зарплата и проценты"),
                    if (t) null else cat("Переводы", "users", 13_510, 0.08, 5, note = "5 переводов"),
                    cat("Дивиденды и купоны", "bar-chart", 1_250, 0.01, 1, note = "по акциям и облигациям"),
                ),
            ),
            monthlyChart = MonthlyChart(MetricStatus.READY, basis = basis, points = spend.mapIndexed { i, e -> point(4 + i, e, kept[i]) }),
            insights = insights,
            state = analyticsState,
        )
    }

    /** Year 2026 with data from April 1 to September 25: no forecast (12 full months needed), no insights. */
    private val year2026: Analytics = run {
        val spend = mapOf(4 to 91_200L, 5 to 78_400L, 6 to 88_900L, 7 to 96_300L, 8 to 89_700L, 9 to 84_320L)
        val kept = mapOf(4 to 0.38, 5 to 0.48, 6 to 0.41, 7 to 0.41, 8 to 0.41, 9 to 0.46)
        Analytics(
            hasData = true,
            params = AnalyticsParams(PeriodTypeCode.YEAR, PeriodKey("2026"), TransferMode.WITH),
            period = AnalyticsPeriodInfo(
                range = DateRange(day(1, 1), OffsetDateTime.of(2027, 1, 1, 0, 0, 0, 0, ZoneOffset.ofHours(3))),
                isCurrent = true, coverage = Coverage.PARTIAL, dataFrom = day(4, 1), dataTo = day(9, 25), gaps = emptyList(),
            ),
            navigation = AnalyticsNavigation(),
            tiles = AnalyticsTiles(
                expense = ready(528_820, typical = locked(LockReason.NEED_FULL_MONTHS, 24, 5)),
                income = ready(921_400),
                balance = ready(392_580, share = 0.43),
                dailyExpense = ready(2_970),
                forecast = locked(LockReason.NEED_FULL_MONTHS, 12, 5),
            ),
            expenseCategories = CategoryBreakdown(
                BreakdownStatus.READY,
                listOf(
                    cat("Супермаркеты", "cart", 196_400, 0.37, 128),
                    cat("Различные товары", "package", 104_800, 0.20, 51),
                    cat("Заправки", "car", 38_600, 0.07, 24),
                    cat("Переводы", "users", 33_900, 0.06, 17),
                    cat("Кафе и рестораны", "coffee", 31_200, 0.06, 80),
                ),
            ),
            incomeCategories = CategoryBreakdown(
                BreakdownStatus.READY,
                listOf(
                    cat("Финансы", "percent", 851_900, 0.92, 18),
                    cat("Переводы", "users", 62_300, 0.07, 27),
                    cat("Дивиденды и купоны", "bar-chart", 7_200, 0.01, 4),
                ),
            ),
            monthlyChart = MonthlyChart(MetricStatus.READY, basis = basis, points = (1..12).map { m -> point(m, spend[m], kept[m]) }),
            insights = null,
            state = analyticsState,
        )
    }

    /** Only September 1–25 in the statement: no full month yet. */
    private val early: Analytics = september().let { a ->
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
                bankFees = insights.bankFees.copy(basis = null),
                smallFrequent = SmallFrequentCard(MetricStatus.LOCKED, lock = MetricLock(LockReason.NEED_FULL_MONTHS, 1, 0)),
            ),
            state = analyticsState.copy(dataFrom = day(9, 1), fullMonths = 0),
        )
    }

    /** Last operation on September 25, more than two weeks ago: the forecast is locked (`stale_data`), the tile shows the fact. */
    private val stale: Analytics = september().let { a ->
        a.copy(tiles = a.tiles!!.copy(forecast = locked(LockReason.STALE_DATA)), state = analyticsState.copy(stale = true))
    }

    private val blocks = AnalyticsBlocks(
        tiles = true, expenseCategories = true, incomeCategories = true, monthlyChart = true, regularPayments = true,
        notableSpending = true, bankFees = true, smallFrequent = true, transfersFilter = true, assistant = true, upload = true,
    )

    private fun ui(data: Analytics) = AnalyticsUiState(
        loading = false,
        data = data,
        blocks = blocks,
        limit = AssistantLimit(remaining = 5, dailyMax = 5, resetsAt = day(9, 27)),
    )

    private val actions = AnalyticsActions(openSearch = {}, openChat = {}, openUpload = {}, openUnreadLines = { _, _ -> }, back = null)

    private class Handlers(private val mode: TransferMode) : AnalyticsHandlers {
        override fun load(quiet: Boolean) = Unit
        override fun consumeSnackbar() = Unit
        override fun setPeriodType(type: PeriodTypeCode) = Unit
        override fun goTo(key: PeriodKey) = Unit
        override fun openSheet(sheet: AnalyticsSheet?) = Unit
        override fun nextHint(stop: Boolean) = Unit
        override fun onTileOpened(tile: String) = Unit
        override fun dismissRegular(id: String) = Unit
        override fun setTransferMode(mode: TransferMode) = Unit
        override fun currentTransferMode() = mode
        override fun toggleAllCategories() = Unit
    }

    private fun capture(name: String, state: AnalyticsUiState, heightDp: Int, dark: Boolean = false, popups: Boolean = false) =
        DesignCheck.capture(name, dark = dark, heightDp = heightDp, tab = 1, popups = popups) {
            AnalyticsContent(state, actions, Handlers(state.data?.params?.transferMode ?: TransferMode.WITH))
        }

    @Test fun analytics() = capture("Analytics", ui(september()), heightDp = 2848)

    @Test fun analyticsDark() = capture("AnalyticsDark", ui(september()), heightDp = 2848, dark = true)

    @Test fun analyticsNoTransfers() = capture("AnalyticsNoTransfers", ui(september(noTransfers = true)), heightDp = 2792)

    @Test fun analyticsTransfers() =
        capture("AnalyticsTransfers", ui(september()).copy(sheet = AnalyticsSheet.TRANSFERS), heightDp = 844, popups = true)

    @Test fun analyticsHintCategories() =
        capture("AnalyticsHintCategories", ui(september()).copy(hint = AnalyticsHint.CATEGORIES), heightDp = 2848)

    @Test fun analyticsHintAsk() = capture("AnalyticsHintAsk", ui(september()).copy(hint = AnalyticsHint.ASK), heightDp = 2848)

    @Test fun analyticsLimit() =
        capture("AnalyticsLimit", ui(september()).copy(limit = AssistantLimit(remaining = 0, dailyMax = 5, resetsAt = day(9, 27))), heightDp = 2872)

    @Test fun analyticsYear() = capture("AnalyticsYear", ui(year2026), heightDp = 1700)

    @Test fun analyticsEmpty() = capture("AnalyticsEmpty", ui(Analytics(hasData = false)).copy(limit = null), heightDp = 844)

    @Test fun analyticsEarly() = capture("AnalyticsEarly", ui(early), heightDp = 2096)

    @Test fun analyticsStale() = capture("AnalyticsStale", ui(stale), heightDp = 2952)

    @Test fun analyticsPartial() =
        capture("AnalyticsPartial", ui(september().copy(state = analyticsState.copy(unreadLinesCount = 5))), heightDp = 2900)
}
