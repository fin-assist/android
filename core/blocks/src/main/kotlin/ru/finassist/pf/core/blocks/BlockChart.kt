package ru.finassist.pf.core.blocks

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ru.finassist.pf.core.api.model.Block
import ru.finassist.pf.core.api.model.BlockStatus
import ru.finassist.pf.core.api.model.BlockType
import ru.finassist.pf.core.api.model.ChartData
import ru.finassist.pf.core.api.model.ChartKind
import ru.finassist.pf.core.api.model.Coverage
import ru.finassist.pf.core.api.model.NoteTone
import ru.finassist.pf.core.api.model.ValueKind
import ru.finassist.pf.core.common.time.RussianDates
import ru.finassist.pf.core.designsystem.components.BarPoint
import ru.finassist.pf.core.designsystem.components.InsightState
import ru.finassist.pf.core.designsystem.components.PfAmountText
import ru.finassist.pf.core.designsystem.components.PfBarChart
import ru.finassist.pf.core.designsystem.components.PfCard
import ru.finassist.pf.core.designsystem.components.PfInsightCard
import ru.finassist.pf.core.designsystem.components.PfSectionTitle
import ru.finassist.pf.core.designsystem.icons.PfIcons
import ru.finassist.pf.core.designsystem.theme.PfTheme
import java.time.YearMonth
import kotlin.math.abs

object ChartRenderer : BlockRenderer {
    override val type = BlockType.CHART
    override val version = 1
    override fun prepare(block: Block): (@Composable (BlockContext) -> Unit)? {
        val status = block.status.effective
        if (!status.hasData) return { ctx -> ChartState(block, ctx) }
        val data = block.decode(ChartData.serializer())?.takeIf { it.kind == ChartKind.BAR && it.valueKind != ValueKind.UNKNOWN }
            ?: return AltTextRenderer.prepare(block)
        return { ctx -> ChartCard(block, data, ctx) }
    }
}

@Composable
private fun ChartState(block: Block, ctx: BlockContext) {
    val d = PfTheme.dimens
    block.title?.let {
        PfSectionTitle(it)
        Spacer(Modifier.height(d.space2))
    }
    val locked = block.status.effective == BlockStatus.LOCKED
    PfInsightCard(
        PfIcons.BAR_CHART, block.altText ?: block.title.orEmpty(),
        description = block.emptyText.takeIf { !locked },
        state = if (locked) InsightState.LOCKED else InsightState.EMPTY,
        lockedText = BlockFormat.lockText(block.lock),
    )
}

@Composable
private fun ChartCard(block: Block, data: ChartData, ctx: BlockContext) {
    val d = PfTheme.dimens
    val c = PfTheme.colors
    block.title?.let {
        PfSectionTitle(it)
        Spacer(Modifier.height(d.space2))
    }
    PfCard {
        data.summary?.let { s ->
            BlockFormat.value(s.value, s.valueKind, ctx.currency)?.let { PfAmountText(it, style = PfTheme.type.amountLg, color = c.text) }
        }
        data.caption?.let { Text(it, style = PfTheme.type.caption, color = c.textMuted, modifier = Modifier.padding(top = d.space1)) }
        if (data.points.isNotEmpty()) {
            if (data.summary != null || data.caption != null) Spacer(Modifier.height(d.space4))
            BlockChartBars(data, ctx.tags.chart(block.id ?: "chart"), height = if (data.showValues == true) 64.dp else 96.dp, currency = ctx.currency,
                pointClicks = AnalyticsChartActions.clicks(block, data.points, ctx.onSelectAnalyticsPeriod))
        }
        data.notes.forEachIndexed { i, n ->
            val primary = n.tone == NoteTone.PRIMARY
            Text(
                n.text,
                style = if (primary) PfTheme.type.body else PfTheme.type.hint,
                color = if (primary) c.text else c.textMuted,
                modifier = Modifier.padding(top = if (i == 0) d.space4 else d.space1),
            )
        }
    }
}

/**
 * The bars of a `chart` block (`bar`), shared by the Analytics screen and the assistant's answers: values scaled by
 * the largest magnitude with the sign kept, a partial period filled by its days with data.
 */
@Composable
fun BlockChartBars(data: ChartData, chartId: String, height: Dp, currency: String, pointClicks: Map<String, () -> Unit> = emptyMap()) {
    val max = data.points.mapNotNull { it.value?.let(::abs) }.maxOrNull()?.takeIf { it > 0 } ?: 1.0
    val showYear = data.points.map { it.range.from.year }.distinct().size > 1
    PfBarChart(
        chartId = chartId,
        points = data.points.map { p ->
            val ym = YearMonth.from(p.range.from)
            val partial = p.coverage.effective == Coverage.PARTIAL
            BarPoint(
                key = ym.toString(),
                label = BlockFormat.chartMonthLabel(p, showYear),
                spokenLabel = RussianDates.monthTitle(ym),
                value = p.value?.let { (it / max).toFloat() },
                display = BlockFormat.value(p.value, data.valueKind, currency) ?: "нет данных",
                partial = partial,
                partialFill = BlockFormat.partialFill(p) ?: DEFAULT_PARTIAL_FILL,
                note = p.note.takeIf { partial },
                selectable = ym.toString() in pointClicks,
            )
        },
        highlight = data.highlightIndex ?: -1,
        height = height,
        showValues = data.showValues == true,
        onPointClick = pointClicks.takeIf { it.isNotEmpty() }?.let { clicks -> { key -> clicks[key]?.invoke() } },
    )
}

/** Partial bar fill when a point does not say which days it covers. */
private const val DEFAULT_PARTIAL_FILL = 0.5f
