package ru.finassist.pf.mock.domain

import ru.finassist.pf.core.api.model.AnalyticsNavigation
import ru.finassist.pf.core.api.model.AnalyticsParams
import ru.finassist.pf.core.api.model.AnalyticsPeriodInfo
import ru.finassist.pf.core.api.model.AnalyticsState
import ru.finassist.pf.core.api.model.LockReason
import ru.finassist.pf.core.api.model.Coverage
import ru.finassist.pf.core.api.model.OperationsFilter
import ru.finassist.pf.core.common.money.Money
import ru.finassist.pf.core.common.time.ApiDateTime
import ru.finassist.pf.core.common.time.DateRange

// What the fake server computes for the Analytics screen before it lays it out as blocks (AnalyticsComposer, FIN-5).
// Was the API model of 6.1 before FIN-4; now internal to the fake server, as the real `analytics` keeps its metrics.

enum class MetricStatus(val code: String) {
    READY("ready"), TENTATIVE("tentative"), LOCKED("locked"), NONE("none"), UNKNOWN("?");


    /** Unknown status is shown as locked without details (api.md). */
    val effective: MetricStatus get() = if (this == UNKNOWN) LOCKED else this
}


data class MetricLock(
    val reason: LockReason,
    val required: Int? = null,
    val available: Int? = null,
    val availableFrom: ApiDateTime? = null,
)

data class MetricBasis(
    val range: DateRange,
    val fullMonths: Int,
)

/** Fields shared by every metric and card: status plus why it is locked and on what history it was computed. */
interface MetricLike {
    val status: MetricStatus
    val lock: MetricLock?
    val basis: MetricBasis?

    val isReady: Boolean
        get() = status.effective == MetricStatus.READY || status.effective == MetricStatus.TENTATIVE
}

/** `Metric` / `ValueMetric` / `ComparisonMetric` / tiles: one class, optional fields per api.md. */
data class Metric(
    override val status: MetricStatus,
    override val lock: MetricLock? = null,
    override val basis: MetricBasis? = null,
    val filters: OperationsFilter? = null,
    /** Signed kopecks; present at `ready` / `tentative`. */
    val value: Money? = null,
    /** `ComparisonMetric.range` — what the compared sum covers («август к 25-му»). */
    val range: DateRange? = null,
    /** `ExpenseTile.comparison`, `DailyExpenseTile.comparison`. */
    val comparison: Metric? = null,
    /** `ExpenseTile.typical`. */
    val typical: Metric? = null,
    /** `BalanceTile.share_of_income`; null at zero income. */
    val shareOfIncome: Double? = null,
) : MetricLike

data class MonthlyChart(
    override val status: MetricStatus,
    override val lock: MetricLock? = null,
    override val basis: MetricBasis? = null,
    val points: List<MonthlyPoint>? = null,
) : MetricLike

data class RegularPaymentsCard(
    override val status: MetricStatus,
    override val lock: MetricLock? = null,
    override val basis: MetricBasis? = null,
    val filters: OperationsFilter? = null,
    /** Total per month, weekly and yearly payments normalised to a month. */
    val value: Money? = null,
    val count: Int? = null,
    val items: List<RegularPayment>? = null,
) : MetricLike

data class NotableSpendingCard(
    override val status: MetricStatus,
    override val lock: MetricLock? = null,
    override val basis: MetricBasis? = null,
    val items: List<NotableSpendingItem>? = null,
) : MetricLike

data class BankFeesCard(
    override val status: MetricStatus,
    override val lock: MetricLock? = null,
    override val basis: MetricBasis? = null,
    val filters: OperationsFilter? = null,
    val value: Money? = null,
    val kindsName: String? = null,
) : MetricLike

data class SmallFrequentCard(
    override val status: MetricStatus,
    override val lock: MetricLock? = null,
    override val basis: MetricBasis? = null,
    /** Total per month over all groups. */
    val value: Money? = null,
    val items: List<SmallFrequentGroup>? = null,
) : MetricLike

data class AnalyticsTiles(
    val expense: Metric,
    val income: Metric,
    val balance: Metric,
    val dailyExpense: Metric,
    val forecast: Metric? = null,
)

enum class BreakdownStatus(val code: String) {
    READY("ready"), NONE("none"), UNKNOWN("?");

}

data class CategoryAmount(
    val categoryId: String? = null,
    val categoryName: String,
    val categoryIcon: String,
    val note: String? = null,
    val amount: Money,
    val share: Double? = null,
    val operationCount: Int,
    val filters: OperationsFilter,
)

data class CategoryBreakdown(
    val status: BreakdownStatus,
    val items: List<CategoryAmount>,
)

data class MonthlyPoint(
    val month: String,
    val range: DateRange,
    val expense: Money? = null,
    val income: Money? = null,
    val coverage: Coverage,
    val dataFrom: ApiDateTime? = null,
    val dataTo: ApiDateTime? = null,
    val gaps: List<DateRange> = emptyList(),
)

data class RegularPayment(
    val id: String,
    val title: String,
    val amount: Money,
    val monthlyAmount: Money,
    val scheduleName: String,
    val categoryId: String,
    val filters: OperationsFilter,
)

data class NotableSpendingItem(
    val categoryId: String,
    val categoryName: String,
    val categoryIcon: String,
    val amount: Money,
    val typicalAmount: Money,
    val filters: OperationsFilter,
)

data class SmallFrequentGroup(
    val title: String,
    val count: Int,
    val averageAmount: Money,
    val monthlyAmount: Money,
    val yearlyAmount: Money,
    val filters: OperationsFilter,
)

data class Insights(
    val regularPayments: RegularPaymentsCard,
    val notableSpending: NotableSpendingCard,
    val bankFees: BankFeesCard,
    val smallFrequent: SmallFrequentCard,
)


/** The computed screen before layout: what 6.1 returned before FIN-4. */
data class AnalyticsSnapshot(
    val hasData: Boolean,
    val params: AnalyticsParams? = null,
    val period: AnalyticsPeriodInfo? = null,
    val navigation: AnalyticsNavigation? = null,
    val tiles: AnalyticsTiles? = null,
    val expenseCategories: CategoryBreakdown? = null,
    val incomeCategories: CategoryBreakdown? = null,
    val monthlyChart: MonthlyChart? = null,
    val insights: Insights? = null,
    val state: AnalyticsState? = null,
)
