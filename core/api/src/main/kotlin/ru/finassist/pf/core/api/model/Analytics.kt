package ru.finassist.pf.core.api.model

import kotlinx.serialization.Serializable
import ru.finassist.pf.core.common.money.Money
import ru.finassist.pf.core.common.time.ApiDateTime
import ru.finassist.pf.core.common.time.DateRange
import ru.finassist.pf.core.common.time.PeriodKey

@Serializable(with = MetricStatus.Serializer::class)
enum class MetricStatus(override val code: String) : ApiCode {
    READY("ready"), TENTATIVE("tentative"), LOCKED("locked"), NONE("none"), UNKNOWN("?");

    object Serializer : OpenEnumSerializer<MetricStatus>("MetricStatus", entries, UNKNOWN)

    /** Unknown status is shown as locked without details (api.md). */
    val effective: MetricStatus get() = if (this == UNKNOWN) LOCKED else this
}

@Serializable(with = LockReason.Serializer::class)
enum class LockReason(override val code: String) : ApiCode {
    NEED_FULL_MONTHS("need_full_months"),
    NEED_MONTHS_WITH_DATA("need_months_with_data"),
    TOO_EARLY_IN_MONTH("too_early_in_month"),
    STALE_DATA("stale_data"),
    UNKNOWN("?");

    object Serializer : OpenEnumSerializer<LockReason>("LockReason", entries, UNKNOWN)
}

@Serializable
data class MetricLock(
    val reason: LockReason,
    val required: Int? = null,
    val available: Int? = null,
    val availableFrom: ApiDateTime? = null,
)

@Serializable
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
@Serializable
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

@Serializable
data class MonthlyChart(
    override val status: MetricStatus,
    override val lock: MetricLock? = null,
    override val basis: MetricBasis? = null,
    val points: List<MonthlyPoint>? = null,
) : MetricLike

@Serializable
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

@Serializable
data class NotableSpendingCard(
    override val status: MetricStatus,
    override val lock: MetricLock? = null,
    override val basis: MetricBasis? = null,
    val items: List<NotableSpendingItem>? = null,
) : MetricLike

@Serializable
data class BankFeesCard(
    override val status: MetricStatus,
    override val lock: MetricLock? = null,
    override val basis: MetricBasis? = null,
    val filters: OperationsFilter? = null,
    val value: Money? = null,
    val kindsName: String? = null,
) : MetricLike

@Serializable
data class SmallFrequentCard(
    override val status: MetricStatus,
    override val lock: MetricLock? = null,
    override val basis: MetricBasis? = null,
    /** Total per month over all groups. */
    val value: Money? = null,
    val items: List<SmallFrequentGroup>? = null,
) : MetricLike

@Serializable
data class AnalyticsTiles(
    val expense: Metric,
    val income: Metric,
    val balance: Metric,
    val dailyExpense: Metric,
    val forecast: Metric? = null,
)

@Serializable(with = BreakdownStatus.Serializer::class)
enum class BreakdownStatus(override val code: String) : ApiCode {
    READY("ready"), NONE("none"), UNKNOWN("?");

    object Serializer : OpenEnumSerializer<BreakdownStatus>("BreakdownStatus", entries, UNKNOWN)
}

@Serializable
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

@Serializable
data class CategoryBreakdown(
    val status: BreakdownStatus,
    val items: List<CategoryAmount>,
)

@Serializable
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

@Serializable
data class RegularPayment(
    val id: String,
    val title: String,
    val amount: Money,
    val monthlyAmount: Money,
    val scheduleName: String,
    val categoryId: String,
    val filters: OperationsFilter,
)

@Serializable
data class NotableSpendingItem(
    val categoryId: String,
    val categoryName: String,
    val categoryIcon: String,
    val amount: Money,
    val typicalAmount: Money,
    val filters: OperationsFilter,
)

@Serializable
data class SmallFrequentGroup(
    val title: String,
    val count: Int,
    val averageAmount: Money,
    val monthlyAmount: Money,
    val yearlyAmount: Money,
    val filters: OperationsFilter,
)

@Serializable
data class Insights(
    val regularPayments: RegularPaymentsCard,
    val notableSpending: NotableSpendingCard,
    val bankFees: BankFeesCard,
    val smallFrequent: SmallFrequentCard,
)

@Serializable
data class AnalyticsPeriodInfo(
    val range: DateRange,
    val isCurrent: Boolean,
    val coverage: Coverage,
    val dataFrom: ApiDateTime? = null,
    val dataTo: ApiDateTime? = null,
    val gaps: List<DateRange>,
)

@Serializable
data class AnalyticsNavigation(
    val previous: PeriodKey? = null,
    val next: PeriodKey? = null,
)

@Serializable
data class AnalyticsState(
    val stale: Boolean,
    val lastOperationAt: ApiDateTime? = null,
    val dataFrom: ApiDateTime,
    val dataTo: ApiDateTime,
    val fullMonths: Int,
    val unreadLinesCount: Int,
    val calculatedAt: ApiDateTime,
    val recalculating: Boolean,
)

/** Analytics screen response. When [hasData] is false nothing else is present. */
@Serializable
data class Analytics(
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

@Serializable
data class AnalyticsPeriodItem(
    val key: PeriodKey,
    val range: DateRange,
    val coverage: Coverage,
)

@Serializable
data class AnalyticsPeriodsList(val periods: List<AnalyticsPeriodItem>)
