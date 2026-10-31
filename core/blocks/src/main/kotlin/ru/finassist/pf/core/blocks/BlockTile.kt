package ru.finassist.pf.core.blocks

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import ru.finassist.pf.core.api.model.Block
import ru.finassist.pf.core.api.model.Tile
import ru.finassist.pf.core.designsystem.components.PfStatTile

/** A tile keeps the same values, accessibility and allowed actions in both catalog versions. */
@Composable
internal fun BlockTile(block: Block, tile: Tile, ctx: BlockContext, modifier: Modifier, minAmountFontSize: TextUnit = 14.sp) {
    val tag = ctx.tags.tile(tile.id)
    if (!tile.status.hasData) {
        PfStatTile(tile.title, modifier.testTag(tag), locked = true, lockedText = BlockFormat.lockText(tile.lock), chartId = tag)
        return
    }
    val action = BlockLayout.tapAction(tile.action)
    PfStatTile(
        tile.title, modifier.testTag(tag),
        value = BlockFormat.value(tile.value, tile.valueKind, ctx.currency, tile.approx, tile.showPlus),
        valueLabel = BlockFormat.spoken(tile.value, tile.valueKind, ctx.currency, tile.approx, tile.showPlus),
        note = tile.caption, chartId = tag,
        onClick = action?.let { { ctx.onAction(block, it) } },
        minAmountFontSize = minAmountFontSize,
    )
}
