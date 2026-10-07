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
import ru.finassist.pf.core.api.model.Metric
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

/** Artboards `Analytics` / `AnalyticsDark` (390×2848): September 2026 by the 25th, numbers as on the canvas. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AnalyticsDesignCheckTest {
    private fun day(m: Int, d: Int) = OffsetDateTime.of(2026, m, d, 0, 0, 0, 0, ZoneOffset.ofHours(3))
    private fun month(m: Int) = DateRange(day(m, 1), if (m == 12) day(12, 31) else day(m + 1, 1))
    private fun rub(v: Long) = Money(v * 100)
    private val f = OperationsFilter()
    private val sep = month(9)
    private val basis = MetricBasis(DateRange(day(4, 1), day(9, 1)), fullMonths = 5)

    private fun ready(value: Long, comparison: Metric? = null, typical: Metric? = null, share: Double? = null) =
        Metric(MetricStatus.READY, basis = basis, filters = f, value = rub(value), comparison = comparison, typical = typical, shareOfIncome = share)

    private fun cat(name: String, icon: String, amount: Long, share: Double, count: Int, note: String? = null) =
        CategoryAmount(categoryId = "c_$name", categoryName = name, categoryIcon = icon, note = note, amount = rub(amount), share = share, operationCount = count, filters = f)

    // Expense by month April — September and the share of income kept (46% in September).
    private val spend = listOf(91_200L, 78_400L, 88_900L, 96_300L, 89_700L, 84_320L)
    private val kept = listOf(0.38, 0.48, 0.41, 0.41, 0.41, 0.46)

    private val analytics = Analytics(
        hasData = true,
        params = AnalyticsParams(PeriodTypeCode.MONTH, PeriodKey("2026-09"), TransferMode.WITH),
        period = AnalyticsPeriodInfo(range = sep, isCurrent = true, coverage = Coverage.PARTIAL, dataFrom = day(9, 1), dataTo = day(9, 25), gaps = emptyList()),
        navigation = AnalyticsNavigation(previous = PeriodKey("2026-08")),
        tiles = AnalyticsTiles(
            expense = ready(84_320, comparison = Metric(MetricStatus.READY, value = rub(72_060), range = DateRange(day(8, 1), day(8, 26))), typical = ready(88_900)),
            income = ready(156_900),
            balance = ready(72_580, share = 0.46),
            dailyExpense = ready(3_370, comparison = Metric(MetricStatus.READY, value = rub(2_890), range = month(8))),
            forecast = ready(101_200),
        ),
        expenseCategories = CategoryBreakdown(
            BreakdownStatus.READY,
            listOf(
                cat("Супермаркеты", "cart", 32_400, 0.38, 21),
                cat("Различные товары", "package", 18_100, 0.21, 9),
                cat("Заправки", "car", 7_200, 0.09, 4),
                cat("Переводы", "users", 5_600, 0.07, 3),
                cat("Кафе и рестораны", "coffee", 9_800, 0.12, 14),
                cat("Транспорт", "car", 5_400, 0.06, 11),
                cat("Связь", "repeat", 3_420, 0.04, 2),
                cat("Аптеки", "package", 2_400, 0.03, 3),
            ),
        ),
        incomeCategories = CategoryBreakdown(
            BreakdownStatus.READY,
            listOf(
                cat("Финансы", "percent", 142_140, 0.91, 3, note = "зарплата и проценты"),
                cat("Переводы людям", "users", 13_510, 0.08, 5, note = "5 переводов"),
                cat("Дивиденды и купоны", "bar-chart", 1_250, 0.01, 1, note = "по акциям и облигациям"),
            ),
        ),
        monthlyChart = MonthlyChart(
            MetricStatus.READY, basis = basis,
            points = spend.mapIndexed { i, e ->
                val m = 4 + i
                MonthlyPoint(
                    month = "2026-%02d".format(m), range = month(m), expense = rub(e), income = rub((e / (1 - kept[i])).toLong()),
                    coverage = if (m == 9) Coverage.PARTIAL else Coverage.COMPLETE, dataTo = if (m == 9) day(9, 25) else null,
                )
            },
        ),
        insights = Insights(
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
        ),
        state = AnalyticsState(
            stale = false, lastOperationAt = day(9, 25), dataFrom = day(4, 1), dataTo = day(9, 25), fullMonths = 5,
            unreadLinesCount = 0, calculatedAt = day(9, 26), recalculating = false,
        ),
    )

    private val state = AnalyticsUiState(
        loading = false,
        data = analytics,
        blocks = AnalyticsBlocks(
            tiles = true, expenseCategories = true, incomeCategories = true, monthlyChart = true, regularPayments = true,
            notableSpending = true, bankFees = true, smallFrequent = true, transfersFilter = true, assistant = true, upload = true,
        ),
        limit = AssistantLimit(remaining = 5, dailyMax = 5, resetsAt = day(9, 27)),
    )

    private val actions = AnalyticsActions(openSearch = {}, openChat = {}, openUpload = {}, openUnreadLines = { _, _ -> }, back = null)

    private val handlers = object : AnalyticsHandlers {
        override fun load(quiet: Boolean) = Unit
        override fun consumeSnackbar() = Unit
        override fun setPeriodType(type: PeriodTypeCode) = Unit
        override fun goTo(key: PeriodKey) = Unit
        override fun openSheet(sheet: AnalyticsSheet?) = Unit
        override fun nextHint(stop: Boolean) = Unit
        override fun onTileOpened(tile: String) = Unit
        override fun dismissRegular(id: String) = Unit
        override fun setTransferMode(mode: TransferMode) = Unit
        override fun currentTransferMode() = TransferMode.WITH
        override fun toggleAllCategories() = Unit
    }

    private fun capture(name: String, dark: Boolean) = DesignCheck.capture(name, dark = dark, heightDp = 2848, tab = 1) {
        AnalyticsContent(state, actions, handlers)
    }

    @Test fun analytics() = capture("Analytics", dark = false)

    @Test fun analyticsDark() = capture("AnalyticsDark", dark = true)
}
