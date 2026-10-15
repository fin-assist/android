package ru.finassist.pf.core.blocks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ru.finassist.pf.core.api.model.ActionType
import ru.finassist.pf.core.api.model.Block
import ru.finassist.pf.core.api.model.BlockAction
import ru.finassist.pf.core.api.model.BlockStatus
import ru.finassist.pf.core.api.model.BlockType
import ru.finassist.pf.core.api.model.ChartData
import ru.finassist.pf.core.api.model.ChartKind
import ru.finassist.pf.core.api.model.Coverage
import ru.finassist.pf.core.api.model.HeadingData
import ru.finassist.pf.core.api.model.InsightCardData
import ru.finassist.pf.core.api.model.ListStyle
import ru.finassist.pf.core.api.model.NoteTone
import ru.finassist.pf.core.api.model.RankedListData
import ru.finassist.pf.core.api.model.RowsData
import ru.finassist.pf.core.api.model.TextData
import ru.finassist.pf.core.api.model.Tile
import ru.finassist.pf.core.api.model.TileGroupData
import ru.finassist.pf.core.api.model.ValueKind
import ru.finassist.pf.core.common.time.RussianDates
import ru.finassist.pf.core.designsystem.components.BarPoint
import ru.finassist.pf.core.designsystem.components.ButtonVariant
import ru.finassist.pf.core.designsystem.components.InsightState
import ru.finassist.pf.core.designsystem.components.PfAmountText
import ru.finassist.pf.core.designsystem.components.PfBarChart
import ru.finassist.pf.core.designsystem.components.PfButton
import ru.finassist.pf.core.designsystem.components.PfCard
import ru.finassist.pf.core.designsystem.components.PfCategoryBar
import ru.finassist.pf.core.designsystem.components.PfDataRow
import ru.finassist.pf.core.designsystem.components.PfDivider
import ru.finassist.pf.core.designsystem.components.PfInsightCard
import ru.finassist.pf.core.designsystem.components.PfLink
import ru.finassist.pf.core.designsystem.components.PfSectionTitle
import ru.finassist.pf.core.designsystem.components.PfStatTile
import ru.finassist.pf.core.designsystem.components.ValueTone
import ru.finassist.pf.core.designsystem.icons.PfIcons
import ru.finassist.pf.core.designsystem.theme.PfTheme
import java.time.YearMonth
import kotlin.math.abs

/** Catalog version 1 (api.md «Каталог примитивов»): the renderers of the Analytics screen. */
object CatalogV1 {
    val renderers: List<BlockRenderer> = listOf(
        HeadingRenderer, TextRenderer, RowsRenderer, ChartRenderer, TileGroupRenderer, RankedListRenderer, InsightCardRenderer,
    )

    fun registry() = BlockRegistry(renderers, fallback = AltTextRenderer)
}

private fun BlockContext.clickOf(block: Block, action: BlockAction?): (() -> Unit)? =
    action?.takeIf { it.type != ActionType.UNKNOWN }?.let { a -> { onAction(block, a) } }

/** A block this client cannot draw, shown by its text version (api.md «Блоки»); without one — skipped. */
object AltTextRenderer : BlockRenderer {
    override val type = BlockType.UNKNOWN
    override val version = 1
    override fun prepare(block: Block): (@Composable (BlockContext) -> Unit)? {
        val text = block.altText?.takeIf { it.isNotBlank() } ?: return null
        return { Text(text, style = PfTheme.type.body, color = PfTheme.colors.text) }
    }
}

object HeadingRenderer : BlockRenderer {
    override val type = BlockType.HEADING
    override val version = 1
    override fun prepare(block: Block): (@Composable (BlockContext) -> Unit)? {
        val data = block.decode(HeadingData.serializer()) ?: return null
        return { PfSectionTitle(data.text) }
    }
}

object TextRenderer : BlockRenderer {
    override val type = BlockType.TEXT
    override val version = 1
    override fun prepare(block: Block): (@Composable (BlockContext) -> Unit)? {
        val data = block.decode(TextData.serializer()) ?: return null
        return { Text(BlockMarkdown.annotated(data.text), style = PfTheme.type.lead, color = PfTheme.colors.text) }
    }
}

object RowsRenderer : BlockRenderer {
    override val type = BlockType.ROWS
    override val version = 1
    override fun prepare(block: Block): (@Composable (BlockContext) -> Unit)? {
        val data = block.decode(RowsData.serializer()) ?: return null
        return { ctx -> PfCard { BlockRows(block, data, ctx) } }
    }
}

/** The rows of a `rows` block without a card — the assistant puts them under its chart in one card. */
@Composable
fun BlockRows(block: Block, data: RowsData, ctx: BlockContext) {
    val rows = data.rows.mapNotNull { r ->
        (BlockFormat.value(r.value, r.valueKind, ctx.currency) ?: r.text)?.let { r to it }
    }
    rows.forEachIndexed { i, (r, value) ->
        PfDataRow(r.label, value, sublabel = r.sublabel, onClick = ctx.clickOf(block, r.action), divider = i < rows.lastIndex)
    }
}

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
        lockedText = block.lock?.text,
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
            BlockChartBars(data, ctx.tags.chart(block.id ?: "chart"), height = if (data.showValues == true) 64.dp else 96.dp, currency = ctx.currency)
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
fun BlockChartBars(data: ChartData, chartId: String, height: Dp, currency: String) {
    val max = data.points.mapNotNull { it.value?.let(::abs) }.maxOrNull()?.takeIf { it > 0 } ?: 1.0
    PfBarChart(
        chartId = chartId,
        points = data.points.map { p ->
            val ym = YearMonth.from(p.range.from)
            val partial = p.coverage.effective == Coverage.PARTIAL
            BarPoint(
                key = p.range.from.toString(),
                label = RussianDates.monthShort(ym.month),
                spokenLabel = RussianDates.monthTitle(ym) + (p.note?.let { ", $it" } ?: ""),
                value = p.value?.let { (it / max).toFloat() },
                display = BlockFormat.value(p.value, data.valueKind, currency) ?: "нет данных",
                partial = partial,
                partialFill = BlockFormat.partialFill(p) ?: DEFAULT_PARTIAL_FILL,
                note = p.note.takeIf { partial },
            )
        },
        highlight = data.highlightIndex ?: -1,
        height = height,
        showValues = data.showValues == true,
    )
}

/** Partial bar fill when a point does not say which days it covers. */
private const val DEFAULT_PARTIAL_FILL = 0.5f

object TileGroupRenderer : BlockRenderer {
    override val type = BlockType.TILE_GROUP
    override val version = 1
    override fun prepare(block: Block): (@Composable (BlockContext) -> Unit)? {
        if (block.status.effective == BlockStatus.UNAVAILABLE || block.status.effective == BlockStatus.NONE) {
            val text = block.emptyText ?: return null
            return { Text(text, style = PfTheme.type.body, color = PfTheme.colors.textMuted) }
        }
        val data = block.decode(TileGroupData.serializer())?.takeIf { it.tiles.isNotEmpty() } ?: return null
        return { ctx -> Tiles(block, data, ctx) }
    }
}

/** Tiles two to a row (mockup `Analytics`: 2×2), each row as tall as its tallest tile. */
@Composable
private fun Tiles(block: Block, data: TileGroupData, ctx: BlockContext) {
    val d = PfTheme.dimens
    Column(verticalArrangement = Arrangement.spacedBy(d.space4)) {
        data.tiles.chunked(2).forEach { pair ->
            Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(d.space4)) {
                pair.forEach { tile -> TileView(block, tile, ctx, Modifier.weight(1f).fillMaxHeight()) }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun TileView(block: Block, tile: Tile, ctx: BlockContext, modifier: Modifier) {
    val tag = ctx.tags.tile(tile.id)
    if (!tile.status.hasData) {
        PfStatTile(tile.title, modifier.testTag(tag), locked = true, lockedText = tile.lock?.text, chartId = tag)
        return
    }
    PfStatTile(
        tile.title, modifier.testTag(tag),
        value = BlockFormat.value(tile.value, tile.valueKind, ctx.currency, tile.approx, tile.showPlus),
        valueLabel = BlockFormat.spoken(tile.value, tile.valueKind, ctx.currency, tile.approx, tile.showPlus),
        note = tile.caption, chartId = tag, onClick = ctx.clickOf(block, tile.action),
    )
}

object RankedListRenderer : BlockRenderer {
    override val type = BlockType.RANKED_LIST
    override val version = 1
    override fun prepare(block: Block): (@Composable (BlockContext) -> Unit)? {
        val status = block.status.effective
        val data = if (status.hasData) block.decode(RankedListData.serializer()) ?: return null else null
        return { ctx -> RankedList(block, data, ctx) }
    }
}

@Composable
private fun RankedList(block: Block, data: RankedListData?, ctx: BlockContext) {
    val d = PfTheme.dimens
    val c = PfTheme.colors
    block.title?.let {
        PfSectionTitle(it)
        Spacer(Modifier.height(d.space2))
    }
    if (data == null || data.items.isEmpty()) {
        (data?.emptyText ?: block.emptyText ?: block.lock?.text)?.let { Text(it, style = PfTheme.type.body, color = c.textMuted) }
        return
    }
    if (data.style == ListStyle.PLAIN) {
        PfCard {
            data.items.forEachIndexed { i, item ->
                PfDataRow(
                    item.title,
                    BlockFormat.value(item.value, item.valueKind, ctx.currency, showPlus = item.showPlus).orEmpty(),
                    sublabel = item.note,
                    tone = if (item.showPlus && item.value > 0) ValueTone.POSITIVE else ValueTone.DEFAULT,
                    onClick = ctx.clickOf(block, item.action),
                    divider = i < data.items.lastIndex,
                    modifier = Modifier.testTag(ctx.tags.item(block.id.orEmpty())),
                )
            }
        }
        return
    }
    data.total?.let { t ->
        // Mockup: `align-items: baseline` — the caption and the amount share a baseline.
        Row(horizontalArrangement = Arrangement.spacedBy(d.space3)) {
            Text(t.label.orEmpty(), style = PfTheme.type.body, color = c.textMuted, modifier = Modifier.weight(1f).alignByBaseline())
            BlockFormat.value(t.value, t.valueKind, ctx.currency)?.let {
                PfAmountText(it, style = PfTheme.type.amountMd, color = c.text, modifier = Modifier.alignByBaseline())
            }
        }
    }
    val id = block.id.orEmpty()
    val expanded = id in ctx.expanded
    val visibleCount = data.visibleCount
    val visible = if (expanded || visibleCount == null) data.items else data.items.take(visibleCount)
    val maxPositive = data.items.maxOf { it.value }.coerceAtLeast(1.0)
    ctx.decorate(block) {
        Column {
            Spacer(Modifier.height(d.space2))
            PfCard {
                Column(verticalArrangement = Arrangement.spacedBy(d.space2)) {
                    visible.forEachIndexed { i, item ->
                        val amount = BlockFormat.value(item.value, item.valueKind, ctx.currency).orEmpty()
                        PfCategoryBar(
                            chartId = ctx.tags.chart(id),
                            index = i,
                            icon = item.icon,
                            label = item.title + (item.note?.let { " · $it" } ?: ""),
                            amount = amount,
                            percent = if (item.value > 0) (item.value / maxPositive).toFloat() else 0f,
                            share = item.share?.let { "${(it * 100).toInt()}%" },
                            spoken = listOfNotNull(item.title, amount, item.note).joinToString(", "),
                            onClick = ctx.clickOf(block, item.action),
                            modifier = Modifier.testTag(ctx.tags.item(id)),
                        )
                    }
                }
            }
        }
    }
    if (visibleCount != null && data.items.size > visibleCount) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            PfLink(if (expanded) "Свернуть" else data.moreLabel ?: "Все", onClick = { ctx.onToggleExpanded(id) }, modifier = Modifier.testTag(ctx.tags.more(id)))
        }
    }
}

object InsightCardRenderer : BlockRenderer {
    override val type = BlockType.INSIGHT_CARD
    override val version = 1
    override fun prepare(block: Block): (@Composable (BlockContext) -> Unit)? {
        val data = block.decode(InsightCardData.serializer()) ?: return AltTextRenderer.prepare(block)
        return { ctx -> InsightCard(block, data, ctx) }
    }
}

@Composable
private fun InsightCard(block: Block, data: InsightCardData, ctx: BlockContext) {
    val d = PfTheme.dimens
    val status = block.status.effective
    val state = when (status) {
        BlockStatus.READY -> InsightState.READY
        BlockStatus.TENTATIVE -> InsightState.TENTATIVE
        BlockStatus.NONE, BlockStatus.UNAVAILABLE -> InsightState.EMPTY
        else -> InsightState.LOCKED
    }
    val listed = data.itemsMore != null || data.items.any { it.action?.type == ActionType.DISMISS_REGULAR_PAYMENT }
    PfInsightCard(
        icon = data.icon,
        title = data.title,
        amount = BlockFormat.value(data.value, data.valueKind, ctx.currency, data.approx).takeIf { status.hasData },
        description = if (state == InsightState.EMPTY) block.emptyText else data.description,
        state = state,
        lockedText = block.lock?.text,
        chartId = ctx.tags.chart(block.id ?: data.title),
        onClick = ctx.clickOf(block, block.action),
        content = if (!status.hasData || data.items.isEmpty() && data.itemsMore == null) null else ({
            if (listed) {
                // Mockup `Analytics`: a line above the list and between the rows; a button under a row.
                Column(Modifier.padding(top = d.space2)) {
                    PfDivider()
                    data.items.forEach { item ->
                        PfDataRow(item.title, itemValue(item, ctx).orEmpty(), sublabel = item.sublabel, divider = false)
                        item.action?.takeIf { it.type == ActionType.DISMISS_REGULAR_PAYMENT }?.let { a ->
                            Box(Modifier.offset(x = -d.space4)) {
                                val label = a.label ?: "Скрыть"
                                PfButton(
                                    label, onClick = { ctx.onAction(block, a) }, variant = ButtonVariant.GHOST,
                                    modifier = Modifier.semantics { contentDescription = "$label — ${item.title}" },
                                )
                            }
                        }
                        PfDivider()
                    }
                    data.itemsMore?.let { more ->
                        PfDataRow(more.label.orEmpty(), BlockFormat.value(more.value, more.valueKind, ctx.currency).orEmpty(), tone = ValueTone.MUTED, divider = false)
                    }
                }
            } else {
                data.items.forEach { item ->
                    PfDataRow(item.title, itemValue(item, ctx).orEmpty(), sublabel = item.sublabel, onClick = ctx.clickOf(block, item.action), divider = false)
                }
            }
        }),
    )
}

private fun itemValue(item: ru.finassist.pf.core.api.model.InsightItem, ctx: BlockContext): String? =
    BlockFormat.value(item.value, item.valueKind, ctx.currency)?.let { v -> item.valueSuffix?.let { "$v $it" } ?: v }

/** The narrow Markdown subset of `text` blocks: paragraphs, `**bold**`, `- ` bullets; anything else is plain text. */
object BlockMarkdown {
    fun annotated(text: String): AnnotatedString = buildAnnotatedString {
        text.trim().split(Regex("\\n\\s*\\n")).forEachIndexed { pi, paragraph ->
            if (pi > 0) append("\n\n")
            paragraph.lines().forEachIndexed { li, raw ->
                if (li > 0) append("\n")
                val line = if (raw.trimStart().startsWith("- ")) "•  " + raw.trimStart().removePrefix("- ") else raw
                line.split("**").forEachIndexed { i, part ->
                    if (i % 2 == 1) withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(part) } else append(part)
                }
            }
        }
    }
}
