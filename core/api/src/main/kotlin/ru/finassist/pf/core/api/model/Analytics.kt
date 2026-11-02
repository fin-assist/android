package ru.finassist.pf.core.api.model

import kotlinx.serialization.Serializable
import ru.finassist.pf.core.common.time.ApiDateTime
import ru.finassist.pf.core.common.time.DateRange
import ru.finassist.pf.core.common.time.PeriodKey

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

/** Analytics screen response (api.md 6.1); calculation is required even for an empty ledger. */
@Serializable
data class Analytics(
    val hasData: Boolean,
    val params: AnalyticsParams? = null,
    val period: AnalyticsPeriodInfo? = null,
    val navigation: AnalyticsNavigation? = null,
    /** Currency of every amount in [blocks]. */
    val currency: String = "RUB",
    /** Blocks of the screen in the server's order (FIN-4); a broken one is dropped, not the screen. */
    @Serializable(with = BlockList::class)
    val blocks: List<Block> = emptyList(),
    val state: AnalyticsState? = null,
    val timeZone: String? = null,
    val calculation: AnalyticsCalculation? = null,
)

@Serializable
data class AnalyticsPeriodItem(
    val key: PeriodKey,
    val range: DateRange,
    val coverage: Coverage,
)

@Serializable
data class AnalyticsPeriodsList(val periods: List<AnalyticsPeriodItem>, val timeZone: String? = null)

/** Applied user data version, distinct from the target accepted by a mutation. */
@Serializable
data class AnalyticsCalculation(val calcVersion: Int, val recalculating: Boolean)

/** Complete replacement blocks, never decoded with the tolerant screen block-list serializer. */
@Serializable
data class AnalyticsBlockUpdate(
    val params: AnalyticsParams,
    val currency: String,
    val timeZone: String,
    val requiredCalcVersion: Int,
    @Serializable(with = CompleteBlockList::class) val blocks: List<Block>,
)

/** Adjacent monthly-chart page; selection and notes continue to belong to the full response. */
@Serializable
data class AnalyticsChartPage(val block: Block, val timeZone: String)

