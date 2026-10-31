package ru.finassist.pf.core.blocks

import ru.finassist.pf.core.api.model.*
import ru.finassist.pf.core.common.time.DateRange
import ru.finassist.pf.core.common.time.PeriodKey
import java.time.OffsetDateTime
import kotlin.test.*

class RollingChartActionsTest {
    private val block = Block(id = "monthly_chart", type = BlockType.CHART)
    private val point = ChartPoint(
        DateRange(OffsetDateTime.parse("2025-10-31T00:00:00+03:00"), OffsetDateTime.parse("2025-11-01T00:00:00+03:00")),
        value = 0.0, coverage = Coverage.PARTIAL,
        action = BlockAction(ActionType.SELECT_ANALYTICS_PERIOD, period = PeriodTypeCode.MONTH, date = PeriodKey("2025-10")),
    )

    @Test fun `zero edge slice keeps the full month action only in analytics monthly chart`() {
        assertEquals(point.action, AnalyticsChartActions.selection(block, point))
        assertNull(AnalyticsChartActions.selection(block, point.copy(coverage = Coverage.NO_DATA)))
        assertNull(AnalyticsChartActions.selection(block.copy(id = "balance"), point))
        assertTrue(AnalyticsChartActions.clicks(block, listOf(point), null).isEmpty())
        assertNull(BlockLayout.tapAction(point.action))
        var selected: BlockAction? = null
        AnalyticsChartActions.clicks(block, listOf(point)) { _, action -> selected = action }.getValue("2025-10")()
        assertEquals(point.action, selected)
    }

    @Test fun `mismatched month unknown types and singleton targets cannot expand an edge action`() {
        for (action in listOf(
            point.action!!.copy(date = PeriodKey("2026-10")),
            point.action!!.copy(period = PeriodTypeCode.UNKNOWN),
            point.action!!.copy(date = PeriodKey.ROLLING_YEAR),
            point.action!!.copy(period = PeriodTypeCode.ROLLING_YEAR, date = PeriodKey.ROLLING_YEAR),
        )) assertNull(AnalyticsChartActions.selection(block, point.copy(action = action)))
        assertEquals("окт\n2025", BlockFormat.chartMonthLabel(point, true))
    }
}
