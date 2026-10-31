package ru.finassist.pf.core.blocks

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ru.finassist.pf.core.api.model.ActionType
import ru.finassist.pf.core.api.model.Block
import ru.finassist.pf.core.api.model.BlockAction
import ru.finassist.pf.core.api.model.BlockType

/** Layout rules of a column of blocks that do not depend on Compose — unit-tested. */
object BlockLayout {
    /**
     * Blocks left after [hidden] (a kill switch): a `heading` stays only while some other block remains under it,
     * up to the next heading — a section whose blocks are all switched off loses its title too.
     */
    fun visible(blocks: List<Block>, hidden: (Block) -> Boolean): List<Block> = visible(blocks, { it }, hidden)

    /**
     * The same over prepared blocks: run it after [BlockRegistry.prepareAll], so a section whose blocks did not decode
     * or are unknown without `alt_text` loses its heading too.
     */
    fun <T> visible(items: List<T>, blockOf: (T) -> Block, hidden: (T) -> Boolean = { false }): List<T> {
        val kept = items.filterNot(hidden)
        return kept.filterIndexed { i, item ->
            if (blockOf(item).type != BlockType.HEADING) return@filterIndexed true
            kept.drop(i + 1).takeWhile { blockOf(it).type != BlockType.HEADING }.isNotEmpty()
        }
    }

    /**
     * The action of a tap on a block, a tile or a row: only navigation (`open_*`). Such a tap happens by accident
     * easily, so an action that changes data (`dismiss_regular_payment`) works only from a button, even if a server
     * puts it on the element; an unknown one does nothing.
     */
    fun tapAction(action: BlockAction?): BlockAction? =
        usable(action)?.takeIf { it.type == ActionType.OPEN_OPERATIONS || it.type == ActionType.OPEN_ASSISTANT }

    /**
     * [action] with the fields its type needs (api.md «Действия»): `filter` for `open_operations`, `payment_id` and
     * `label` for `dismiss_regular_payment`; a blank string or a filter without conditions counts as missing. Without
     * them, or of an unknown type, the action is unknown — null.
     */
    fun usable(action: BlockAction?): BlockAction? = action?.takeIf {
        when (it.type) {
            // `transfer_mode` sets the mode, not the selection: a filter with only it has no condition.
            ActionType.OPEN_OPERATIONS -> it.filter?.copy(transferMode = null)?.isEmpty == false
            ActionType.OPEN_ASSISTANT -> true
            ActionType.DISMISS_REGULAR_PAYMENT -> !it.paymentId.isNullOrBlank() && !it.label.isNullOrBlank()
            ActionType.SELECT_ANALYTICS_PERIOD -> AnalyticsPeriodTargets.of(it.period, it.date) != null
            ActionType.UNKNOWN -> false
        }
    }
}

/**
 * Prepared blocks one under another: [spacing] before each (by the previous block), a test tag per block id, and
 * [onShown] once a block with an id is actually drawn for the response [exposureKey] names — the exposure event of an
 * experiment (FIN-9): a new response with the same ids is shown anew.
 */
@Composable
fun BlocksColumn(
    blocks: List<PreparedBlock>,
    ctx: BlockContext,
    spacing: (previous: Block?, next: Block) -> Dp,
    exposureKey: Any? = null,
    onShown: (Block) -> Unit = {},
) {
    blocks.forEachIndexed { i, p ->
        val gap = spacing(blocks.getOrNull(i - 1)?.block, p.block)
        if (gap > 0.dp) Spacer(Modifier.height(gap))
        val id = p.block.id
        // By id: a block moved by a new order keeps its state (an expanded list) instead of taking its slot's.
        key(id ?: i) {
            Column(Modifier.fillMaxWidth().then(if (id != null) Modifier.testTag(ctx.tags.block(id)) else Modifier)) { p.draw(ctx) }
            if (id != null) LaunchedEffect(id, exposureKey) { onShown(p.block) }
        }
    }
}
