package ru.finassist.pf.feature.analytics.impl.domain

import ru.finassist.pf.core.api.model.*
import ru.finassist.pf.core.common.time.DateRange
import ru.finassist.pf.core.common.time.PeriodKey
import ru.finassist.pf.core.common.error.AppError
import java.time.YearMonth
import java.time.ZoneId
import kotlin.test.*

class MonthlyChartPagesTest {
    private val cache = MonthlyChartPages()
    private val zone = "Europe/Moscow"
    private fun block(start: String, revision: String = "r1", previous: String? = "older", next: String? = "newer", selected: Int? = null): Block {
        val first = YearMonth.parse(start)
        val points = (0 until 60).map { offset ->
            val month = first.plusMonths(offset.toLong())
            ChartPoint(DateRange(month.atDay(1).atStartOfDay(ZoneId.of(zone)).toOffsetDateTime(), month.plusMonths(1).atDay(1).atStartOfDay(ZoneId.of(zone)).toOffsetDateTime()),
                value = if (offset == 1) 0.0 else offset.toDouble(), coverage = if (offset == 2) Coverage.NO_DATA else Coverage.COMPLETE)
        }
        return Block.of(BlockType.CHART, ChartData.serializer(), ChartData(ChartKind.BAR, ValueKind.AMOUNT, points,
            highlightIndex = selected, notes = listOf(ChartNote("Selected-period facts")), page = ChartPageInfo(revision, previous, next)))
            .copy(id = "monthly_chart", title = "History", helpText = "How to navigate")
    }
    private fun ready(block: Block, mode: TransferMode = TransferMode.WITH) = Analytics(true,
        params = AnalyticsParams(PeriodTypeCode.MONTH, PeriodKey("2021-03"), mode), timeZone = zone, blocks = listOf(block))
    private fun data() = cache.block()!!.decode(ChartData.serializer())!!

    @Test fun `prepend keeps the selected month and selected-period notes rather than the old index`() {
        cache.accept(ready(block("2021-01", selected = 2)))
        cache.append(AnalyticsChartPage(block("2016-01", previous = "oldest", next = "return"), zone), "older", true)
        assertEquals(120, data().points.size)
        assertEquals(62, data().highlightIndex)
        assertEquals("Selected-period facts", data().notes.single().text)
        assertEquals("History", cache.block()!!.title)
        assertEquals("How to navigate", cache.block()!!.helpText)
        assertEquals("oldest", cache.cursor(true))
    }

    @Test fun `only three neighbours remain and evicted months can be loaded again in the other direction`() {
        cache.accept(ready(block("2021-01", selected = 2)))
        cache.append(AnalyticsChartPage(block("2016-01", previous = "p2"), zone), "older", true)
        cache.append(AnalyticsChartPage(block("2011-01", previous = "p3"), zone), "p2", true)
        cache.append(AnalyticsChartPage(block("2006-01", previous = null), zone), "p3", true)
        assertEquals(180, data().points.size)
        assertNull(data().highlightIndex)
        assertEquals("2006-01", YearMonth.from(data().points.first().range.from).toString())
        assertNull(cache.cursor(true))
        cache.append(AnalyticsChartPage(block("2021-01", next = null), zone), "newer", false)
        assertEquals(180, data().points.size)
        assertEquals(122, data().highlightIndex)
        assertNull(cache.cursor(false))
    }

    @Test fun `same revision reuses neighbouring pages while new revision or transfer mode clears them`() {
        val first = block("2021-01", selected = 2)
        cache.accept(ready(first))
        cache.append(AnalyticsChartPage(block("2016-01"), zone), "older", true)
        cache.accept(ready(first.copy(title = "New selected period")))
        assertEquals(120, data().points.size)
        assertEquals("New selected period", cache.block()!!.title)
        cache.accept(ready(block("2021-01", revision = "r2")))
        assertEquals(60, data().points.size)
        cache.append(AnalyticsChartPage(block("2016-01", revision = "r2"), zone), "older", true)
        cache.accept(ready(block("2021-01", revision = "r2"), TransferMode.WITHOUT))
        assertEquals(60, data().points.size)
    }

    @Test fun `wrong revision zone direction and nonadjacent pages never mix`() {
        cache.accept(ready(block("2021-01")))
        val before = cache.block()
        for ((response, cursor, older) in listOf(
            Triple(AnalyticsChartPage(block("2016-01", revision = "other"), zone), "older", true),
            Triple(AnalyticsChartPage(block("2016-01"), "Asia/Tokyo"), "older", true),
            Triple(AnalyticsChartPage(block("2001-01"), zone), "older", true),
            Triple(AnalyticsChartPage(block("2016-01"), zone), "wrong", true),
        )) {
            assertFailsWith<AppError.Server> { cache.append(response, cursor, older) }
            assertEquals(before, cache.block())
        }
    }
    @Test fun `duplicate out of order or noncalendar points cannot poison the existing cache`() {
        cache.accept(ready(block("2021-01")))
        val previous = cache.block()
        val b = block("2016-01")
        val d = b.decode(ChartData.serializer())!!
        for (points in listOf(d.points.reversed(), d.points + d.points.last(),
            d.points.mapIndexed { index, point -> if (index == 0) point.copy(range = point.range.copy(from = point.range.from.plusHours(1))) else point })) {
            val malformed = b.copy(data = ru.finassist.pf.core.api.ApiJson.encodeToJsonElement(ChartData.serializer(), d.copy(points = points)) as kotlinx.serialization.json.JsonObject)
            assertFailsWith<AppError.Server> { cache.append(AnalyticsChartPage(malformed, zone), "older", true) }
            assertEquals(previous, cache.block())
        }
    }

}
