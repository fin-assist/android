package ru.finassist.pf.feature.analytics.impl.domain

import ru.finassist.pf.core.api.ApiJson
import ru.finassist.pf.core.api.model.Analytics
import ru.finassist.pf.core.api.model.AnalyticsChartPage
import ru.finassist.pf.core.api.model.AnalyticsIntegrity
import ru.finassist.pf.core.api.model.Block
import ru.finassist.pf.core.api.model.ChartData
import ru.finassist.pf.core.api.model.TransferMode
import ru.finassist.pf.core.common.error.AppError
import ru.finassist.pf.core.common.time.SourceTimeZone
import kotlinx.serialization.json.jsonObject
import java.time.YearMonth

/** At most three adjacent pages. Selection metadata belongs solely to the latest full ready response. */
internal class MonthlyChartPages {
    data class Context(val mode: TransferMode, val zone: String, val revision: String)
    var context: Context? = null
        private set
    private var original: Block? = null
    private var selected: YearMonth? = null
    private val pages = mutableListOf<ChartData>()

    fun clear() { context = null; original = null; selected = null; pages.clear() }

    fun accept(response: Analytics) {
        val block = response.blocks.firstOrNull { it.id == "monthly_chart" && it.type == ru.finassist.pf.core.api.model.BlockType.CHART && it.v == 1 && it.status.hasData }
        if (block == null) { clear(); return }
        val data = validated(block, response.timeZone)
        val next = Context(response.params!!.transferMode, response.timeZone!!, data.page!!.revision)
        if (context != next) pages.clear()
        context = next
        original = block
        selected = data.highlightIndex?.let(data.points::getOrNull)?.let { month(it.range.from) }
        val index = pages.indexOfFirst { it.points.first().range.from == data.points.first().range.from }
        if (index >= 0) pages[index] = data else {
            val adjacent = pages.any {
                month(it.points.last().range.from).plusMonths(1) == month(data.points.first().range.from) ||
                    month(data.points.last().range.from).plusMonths(1) == month(it.points.first().range.from)
            }
            if (!adjacent) pages.clear()
            pages += data
        }
        sort()
        val center = pages.indexOfFirst { it.points.first().range.from == data.points.first().range.from }
        while (pages.size > 3) pages.removeAt(if (center > pages.size / 2) 0 else pages.lastIndex)
    }

    fun cursor(older: Boolean): String? = if (older) pages.firstOrNull()?.page?.previous else pages.lastOrNull()?.page?.next

    fun append(response: AnalyticsChartPage, cursor: String, older: Boolean) {
        val current = context ?: throw AppError.Server(500)
        SourceTimeZone.require(response.timeZone)
        val data = validated(response.block, response.timeZone)
        if (response.timeZone != current.zone || data.page!!.revision != current.revision || cursor != cursor(older)) throw AppError.Server(500)
        val edge = if (older) pages.first() else pages.last()
        val adjacent = if (older) month(data.points.last().range.from).plusMonths(1) == month(edge.points.first().range.from)
            else month(edge.points.last().range.from).plusMonths(1) == month(data.points.first().range.from)
        if (!adjacent) throw AppError.Server(500)
        if (older) pages.add(0, data) else pages.add(data)
        if (pages.size > 3) pages.removeAt(if (older) pages.lastIndex else 0)
    }

    fun block(): Block? {
        val base = original ?: return null
        val data = base.decode(ChartData.serializer()) ?: return null
        val points = pages.flatMap { it.points }
        val combined = data.copy(points = points, highlightIndex = points.indexOfFirst { month(it.range.from) == selected }.takeIf { it >= 0 })
        return base.copy(data = ApiJson.encodeToJsonElement(ChartData.serializer(), combined).jsonObject)
    }

    private fun validated(block: Block, sourceZone: String?): ChartData {
        val zone = SourceTimeZone.require(sourceZone)
        val data = AnalyticsIntegrity.chart(block)
        val months = data.points.map { point ->
            val first = point.range.from.atZoneSameInstant(zone)
            val month = YearMonth.from(first)
            if (first.toInstant() != month.atDay(1).atStartOfDay(zone).toInstant() ||
                point.range.to.toInstant() != month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant()) throw AppError.Server(500)
            month
        }
        if (months.zipWithNext().any { (a, b) -> a.plusMonths(1) != b }) throw AppError.Server(500)
        return data
    }

    private fun sort() { pages.sortBy { it.points.first().range.from.toInstant() } }
    private fun month(value: java.time.OffsetDateTime) = YearMonth.from(value.atZoneSameInstant(SourceTimeZone.require(context?.zone)))
}
