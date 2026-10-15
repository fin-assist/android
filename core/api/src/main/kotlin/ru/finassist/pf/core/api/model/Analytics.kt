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

/** Analytics screen response (api.md 6.1). When [hasData] is false nothing else is present. */
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
)

@Serializable
data class AnalyticsPeriodItem(
    val key: PeriodKey,
    val range: DateRange,
    val coverage: Coverage,
)

@Serializable
data class AnalyticsPeriodsList(val periods: List<AnalyticsPeriodItem>)
