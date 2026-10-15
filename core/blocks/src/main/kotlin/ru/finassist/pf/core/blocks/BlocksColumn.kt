package ru.finassist.pf.core.blocks

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ru.finassist.pf.core.api.model.Block
import ru.finassist.pf.core.api.model.BlockType

/** Layout rules of a column of blocks that do not depend on Compose — unit-tested. */
object BlockLayout {
    /**
     * Blocks left after [hidden] (a kill switch): a `heading` stays only while some other block remains under it,
     * up to the next heading — a section whose blocks are all switched off loses its title too.
     */
    fun visible(blocks: List<Block>, hidden: (Block) -> Boolean): List<Block> {
        val kept = blocks.filterNot(hidden)
        return kept.filterIndexed { i, b ->
            if (b.type != BlockType.HEADING) return@filterIndexed true
            val rest = kept.drop(i + 1)
            val section = rest.takeWhile { it.type != BlockType.HEADING }
            section.isNotEmpty()
        }
    }
}

/**
 * Prepared blocks one under another: [spacing] before each (by the previous block), a test tag per block id, and
 * [onShown] once a block with an id is actually drawn — the exposure event of an experiment (FIN-9).
 */
@Composable
fun BlocksColumn(
    blocks: List<PreparedBlock>,
    ctx: BlockContext,
    spacing: (previous: Block?, next: Block) -> Dp,
    onShown: (Block) -> Unit = {},
) {
    blocks.forEachIndexed { i, p ->
        val gap = spacing(blocks.getOrNull(i - 1)?.block, p.block)
        if (gap > 0.dp) Spacer(Modifier.height(gap))
        val id = p.block.id
        Column(Modifier.fillMaxWidth().then(if (id != null) Modifier.testTag(ctx.tags.block(id)) else Modifier)) { p.draw(ctx) }
        if (id != null) LaunchedEffect(id) { onShown(p.block) }
    }
}
