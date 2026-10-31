package ru.finassist.pf.core.blocks

import ru.finassist.pf.core.api.model.ActionType
import ru.finassist.pf.core.api.model.Block
import ru.finassist.pf.core.api.model.BlockAction
import ru.finassist.pf.core.api.model.BlockType
import ru.finassist.pf.core.api.model.ChartPoint
import ru.finassist.pf.core.api.model.Coverage
import ru.finassist.pf.core.api.model.PeriodTypeCode
import java.time.YearMonth

/** Context-specific actions: other charts and generic block taps never acquire period selection. */
object AnalyticsChartActions {
    fun selection(block: Block, point: ChartPoint): BlockAction? {
        if (block.id != "monthly_chart" || block.type != BlockType.CHART || block.v != 1 || !block.status.hasData) return null
        if (point.coverage == Coverage.NO_DATA) return null
        val action = point.action?.takeIf { it.type == ActionType.SELECT_ANALYTICS_PERIOD } ?: return null
        val target = AnalyticsPeriodTargets.of(action.period, action.date) ?: return null
        if (target.period != PeriodTypeCode.MONTH || target.date.value != YearMonth.from(point.range.from).toString()) return null
        return action
    }

    fun clicks(block: Block, points: List<ChartPoint>, onSelect: ((Block, BlockAction) -> Unit)?): Map<String, () -> Unit> {
        val select = onSelect ?: return emptyMap()
        return points.mapNotNull { point ->
            val action = selection(block, point) ?: return@mapNotNull null
            action.date!!.value to { select(block, action) }
        }.toMap()
    }
}
