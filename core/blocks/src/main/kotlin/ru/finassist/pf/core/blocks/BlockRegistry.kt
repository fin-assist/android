package ru.finassist.pf.core.blocks

import androidx.compose.runtime.Composable
import ru.finassist.pf.core.api.model.Block
import ru.finassist.pf.core.api.model.BlockAction
import ru.finassist.pf.core.api.model.BlockType

/**
 * Draws one primitive of the catalog (api.md «Блоки») up to [version] of its data schema. [prepare] runs outside
 * composition: it decodes `data` and returns what to draw, or null when the block cannot be drawn (its data does not
 * decode) — then that block alone is skipped, not the screen (FIN-7).
 */
interface BlockRenderer {
    val type: BlockType
    val version: Int

    fun prepare(block: Block): (@Composable (BlockContext) -> Unit)?
}

/** What renderers need from the screen they are drawn on. */
class BlockContext(
    /** Currency of every amount (`currency` of the response). */
    val currency: String,
    val tags: BlockTags,
    /** A tap on an element with [action] of [block]; an unknown action type never gets here. */
    val onAction: (block: Block, action: BlockAction) -> Unit,
    /** Ids of lists shown in full («Все категории»). */
    val expanded: Set<String> = emptySet(),
    val onToggleExpanded: (id: String) -> Unit = {},
    /** Wraps the main card of a block — the Analytics screen lays its coachmarks over it. */
    val decorate: @Composable (block: Block, content: @Composable () -> Unit) -> Unit = { _, content -> content() },
)

/** Test tags and chart ids of the drawn blocks (UI tests, docs/e2e.md). */
open class BlockTags(private val prefix: String) {
    open fun block(id: String) = "$prefix.block.$id"
    open fun tile(id: String) = "$prefix.tile.$id"
    open fun item(blockId: String) = "$prefix.$blockId.item"
    open fun more(blockId: String) = "$prefix.$blockId.more"
    open fun chart(blockId: String) = "$prefix.chart.$blockId"
}

/** A block ready to draw. */
class PreparedBlock(val block: Block, val draw: @Composable (BlockContext) -> Unit)

/**
 * Renderers by type (FIN-7). [supports] goes to the server (api.md 6.1 `supports`), which then sends only blocks this
 * client draws; a block it still does not know — or of a newer version — is shown by its `alt_text`, or skipped.
 */
class BlockRegistry(renderers: List<BlockRenderer>, private val fallback: BlockRenderer? = null) {
    private val byType = renderers.associateBy { it.type }

    /** `type@v,…` of every renderer, as api.md 6.1 `supports` wants it. */
    val supports: String = renderers.sortedBy { it.type.code }.joinToString(",") { "${it.type.code}@${it.version}" }

    fun prepare(block: Block): PreparedBlock? {
        val renderer = byType[block.type]?.takeIf { block.v <= it.version }
        val draw = renderer?.prepare(block) ?: fallback?.prepare(block) ?: return null
        return PreparedBlock(block, draw)
    }

    fun prepareAll(blocks: List<Block>): List<PreparedBlock> = blocks.mapNotNull(::prepare)
}
