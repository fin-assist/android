package ru.finassist.pf.core.blocks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import ru.finassist.pf.core.api.model.Block
import ru.finassist.pf.core.api.model.BlockStatus
import ru.finassist.pf.core.api.model.BlockType
import ru.finassist.pf.core.api.model.TileGroupDataV2
import ru.finassist.pf.core.api.model.TileGroupSection
import ru.finassist.pf.core.designsystem.components.PfSectionTitle
import ru.finassist.pf.core.designsystem.theme.PfTheme

/** `tile_group@2`: server-ordered sections, with version 1 still accepted from older producers. */
object GroupedTileRenderer : BlockRenderer {
    override val type = BlockType.TILE_GROUP
    override val version = 2

    override fun prepare(block: Block): (@Composable (BlockContext) -> Unit)? {
        if (block.v == 1) return TileGroupRenderer.prepare(block)
        if (block.v != version) return null
        if (block.status.effective == BlockStatus.UNAVAILABLE || block.status.effective == BlockStatus.NONE) {
            return TileGroupRenderer.prepare(block)
        }
        val groups = block.decode(TileGroupDataV2.serializer())?.groups?.filter { it.tiles.isNotEmpty() }
            ?.takeIf { it.isNotEmpty() } ?: return null
        return { ctx -> GroupedTiles(block, groups, ctx) }
    }
}

@Composable
private fun GroupedTiles(block: Block, groups: List<TileGroupSection>, ctx: BlockContext) {
    val d = PfTheme.dimens
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(d.space6)) {
        groups.forEach { group ->
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(d.space4)) {
                group.title?.takeIf { it.isNotBlank() }?.let { PfSectionTitle(it) }
                group.tiles.chunked(2).forEach { pair ->
                    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(d.space4)) {
                        pair.forEach { tile -> BlockTile(block, tile, ctx, Modifier.weight(1f).fillMaxHeight()) }
                    }
                }
            }
        }
    }
}
