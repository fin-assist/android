package ru.finassist.pf.core.blocks

import org.junit.Test
import ru.finassist.pf.core.api.model.ActionType
import ru.finassist.pf.core.api.model.Block
import ru.finassist.pf.core.api.model.BlockAction
import ru.finassist.pf.core.api.model.BlockStatus
import ru.finassist.pf.core.api.model.BlockType
import ru.finassist.pf.core.api.model.ChartPoint
import ru.finassist.pf.core.api.model.Coverage
import ru.finassist.pf.core.api.model.PeriodTypeCode
import ru.finassist.pf.core.common.time.DateRange
import ru.finassist.pf.core.common.time.PeriodKey
import java.time.OffsetDateTime
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AnalyticsChartActionsTest {
    private val block = Block(id = "monthly_chart", type = BlockType.CHART)
    private fun point(month: String = "2026-09", coverage: Coverage = Coverage.COMPLETE, value: Double = 0.0): ChartPoint {
        val start = OffsetDateTime.parse("${month}-01T00:00:00+03:00")
        return ChartPoint(DateRange(start, start.plusMonths(1)), value = value, coverage = coverage,
            action = BlockAction(ActionType.SELECT_ANALYTICS_PERIOD, period = PeriodTypeCode.MONTH, date = PeriodKey(month)))
    }

    @Test fun `zero tiny partial and unknown-coverage months preserve their server action`() {
        val points = listOf(point(), point(value = 0.01), point(coverage = Coverage.PARTIAL), point(coverage = Coverage.UNKNOWN))
        points.forEach { assertEquals(it.action, AnalyticsChartActions.selection(block, it)) }
        assertNull(AnalyticsChartActions.selection(block, point(coverage = Coverage.NO_DATA)))
    }

    @Test fun `a different chart version type id or status cannot select a period`() {
        listOf(block.copy(id = "balance"), block.copy(id = null), block.copy(type = BlockType.ROWS),
            block.copy(v = 2), block.copy(status = BlockStatus.LOCKED), block.copy(status = BlockStatus.UNKNOWN)).forEach {
            assertNull(AnalyticsChartActions.selection(it, point()), it.toString())
        }
    }

    @Test fun `only a valid month target matching this point is enabled`() {
        val p = point()
        val original = requireNotNull(p.action)
        listOf<BlockAction?>(null, BlockAction(ActionType.OPEN_ASSISTANT), BlockAction(ActionType.UNKNOWN),
            original.copy(period = PeriodTypeCode.UNKNOWN), original.copy(period = null),
            original.copy(date = null), original.copy(date = PeriodKey("2026-13")),
            original.copy(date = PeriodKey("2025-09")),
            original.copy(period = PeriodTypeCode.QUARTER, date = PeriodKey("2026-Q3"))).forEach { action ->
            assertNull(AnalyticsChartActions.selection(block, p.copy(action = action)))
        }
        assertNotNull(BlockLayout.usable(p.action))
        assertNull(BlockLayout.tapAction(p.action), "a generic tile or row must remain inert")
    }

    @Test fun `assistant context is inert while Analytics dispatches the full year month key`() {
        val points = listOf(point("2025-09"), point("2026-09"), point("2026-10", Coverage.NO_DATA))
        assertTrue(AnalyticsChartActions.clicks(block, points, onSelect = null).isEmpty())
        val selected = mutableListOf<String>()
        val clicks = AnalyticsChartActions.clicks(block, points) { source, action ->
            assertEquals(block, source)
            selected += action.date!!.value
        }
        assertEquals(setOf("2025-09", "2026-09"), clicks.keys)
        clicks.getValue("2025-09").invoke()
        clicks.getValue("2026-09").invoke()
        assertEquals(listOf("2025-09", "2026-09"), selected)
        assertTrue(AnalyticsChartActions.clicks(block.copy(id = "balance"), points) { _, _ -> error("must stay inert") }.isEmpty())
    }

    @Test fun `axis labels distinguish the same month in different years`() {
        assertEquals("сен", BlockFormat.chartMonthLabel(point(), showYear = false))
        assertEquals("сен\n2025", BlockFormat.chartMonthLabel(point("2025-09"), showYear = true))
        assertEquals("сен\n2026", BlockFormat.chartMonthLabel(point("2026-09"), showYear = true))
    }
}
