package ru.finassist.pf.core.network.dto

import kotlinx.serialization.Serializable

@Serializable
data class AnalyticsDto(
    val hasData: Boolean,
    val params: AnalyticsParamsDto? = null,
    val period: AnalyticsPeriodInfoDto? = null,
    val navigation: AnalyticsNavigationDto? = null,
    val tiles: AnalyticsTilesDto? = null,
    val expenseCategories: CategoryBreakdownDto? = null,
    val incomeCategories: CategoryBreakdownDto? = null,
    val monthlyChart: MonthlyChartDto? = null,
    val insights: InsightsDto? = null,
    val state: AnalyticsStateDto? = null,
)

@Serializable
data class AnalyticsPeriodInfoDto(
    val range: RangeDto,
    val isCurrent: Boolean,
    val coverage: String,
    val dataFrom: String? = null,
    val dataTo: String? = null,
    val gaps: List<RangeDto>,
)

@Serializable
data class AnalyticsNavigationDto(val previous: String? = null, val next: String? = null)

@Serializable
data class MetricLockDto(
    val reason: String,
    val required: Int? = null,
    val available: Int? = null,
    val availableFrom: String? = null,
)

@Serializable
data class MetricBasisDto(val range: RangeDto, val fullMonths: Int)

/**
 * One DTO for every metric shape (Metric / ValueMetric / ComparisonMetric / tiles / cards): the contract is a
 * family of flat objects with optional fields, so a single class keeps mappers simple.
 */
@Serializable
data class MetricDto(
    val status: String,
    val lock: MetricLockDto? = null,
    val basis: MetricBasisDto? = null,
    val filters: OperationsFilterDto? = null,
    val value: Long? = null,
    val range: RangeDto? = null,
    val comparison: MetricDto? = null,
    val typical: MetricDto? = null,
    val shareOfIncome: Double? = null,
    val points: List<MonthlyPointDto>? = null,
    val count: Int? = null,
    val items: List<MetricItemDto>? = null,
    val kindsName: String? = null,
)

@Serializable
data class AnalyticsTilesDto(
    val expense: MetricDto,
    val income: MetricDto,
    val balance: MetricDto,
    val dailyExpense: MetricDto,
    val forecast: MetricDto? = null,
)

@Serializable
data class CategoryBreakdownDto(val status: String, val items: List<CategoryAmountDto>)

@Serializable
data class CategoryAmountDto(
    val categoryId: String? = null,
    val categoryName: String,
    val categoryIcon: String,
    val note: String? = null,
    val amount: Long,
    val share: Double? = null,
    val operationCount: Int,
    val filters: OperationsFilterDto,
)

@Serializable
data class MonthlyChartDto(val status: String, val lock: MetricLockDto? = null, val points: List<MonthlyPointDto>? = null)

@Serializable
data class MonthlyPointDto(
    val month: String,
    val range: RangeDto,
    val expense: Long? = null,
    val income: Long? = null,
    val coverage: String,
    val dataFrom: String? = null,
    val dataTo: String? = null,
    val gaps: List<RangeDto>,
)

@Serializable
data class InsightsDto(
    val regularPayments: MetricDto,
    val notableSpending: MetricDto,
    val bankFees: MetricDto,
    val smallFrequent: MetricDto,
)

/** Items of regular_payments / notable_spending / small_frequent cards — union of their fields. */
@Serializable
data class MetricItemDto(
    // regular payment
    val id: String? = null,
    val title: String? = null,
    val amount: Long? = null,
    val monthlyAmount: Long? = null,
    val scheduleName: String? = null,
    val categoryId: String? = null,
    // notable spending
    val categoryName: String? = null,
    val categoryIcon: String? = null,
    val typicalAmount: Long? = null,
    // small frequent
    val count: Int? = null,
    val averageAmount: Long? = null,
    val yearlyAmount: Long? = null,
    val filters: OperationsFilterDto,
)

@Serializable
data class AnalyticsStateDto(
    val stale: Boolean,
    val lastOperationAt: String? = null,
    val dataFrom: String,
    val dataTo: String,
    val fullMonths: Int,
    val unreadLinesCount: Int,
    val calculatedAt: String,
    val recalculating: Boolean,
)

@Serializable
data class AnalyticsPeriodsListDto(val periods: List<AnalyticsPeriodItemDto>)

@Serializable
data class AnalyticsPeriodItemDto(val key: String, val range: RangeDto, val coverage: String)
