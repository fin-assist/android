package ru.finassist.pf.feature.analytics.impl.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import ru.finassist.pf.core.api.RequestOwner
import ru.finassist.pf.core.api.model.BlockType
import ru.finassist.pf.core.api.model.TileGroupData
import ru.finassist.pf.core.api.model.TileGroupDataV2
import ru.finassist.pf.core.blocks.LocalTooltipOnboarding
import ru.finassist.pf.core.blocks.PreparedBlock
import ru.finassist.pf.core.blocks.TooltipOnboarding
import ru.finassist.pf.core.designsystem.components.LocalSuppressPopups

@Composable
internal fun AnalyticsTooltipOnboarding(
    state: AnalyticsUiState,
    blocks: List<PreparedBlock>,
    claim: (suspend (RequestOwner) -> Boolean)?,
    content: @Composable () -> Unit,
) {
    val owner = state.owner
    val controller = remember(owner) { TooltipOnboarding() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val suppressed = LocalSuppressPopups.current || state.hint != null || state.sheet != null
    val responseOrder = remember(blocks) { tooltipResponseOrder(blocks) }
    if (owner != null && claim != null && !suppressed) {
        LaunchedEffect(controller, lifecycle, responseOrder) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                // Let the icon anchors complete their first layout before requesting scroll.
                withFrameNanos { }
                controller.show(responseOrder) { claim(owner) }
            }
        }
    }
    CompositionLocalProvider(LocalTooltipOnboarding provides controller, content = content)
}

internal fun tooltipResponseOrder(blocks: List<PreparedBlock>): List<List<String>> = buildList {
    for (prepared in blocks) {
        val block = prepared.block
        val id = block.id?.takeIf { it.isNotBlank() } ?: continue
        add(listOf(id))
        if (block.type != BlockType.TILE_GROUP) continue
        val tiles = when (block.v) {
            1 -> block.decode(TileGroupData.serializer())?.tiles.orEmpty()
            2 -> block.decode(TileGroupDataV2.serializer())?.groups.orEmpty().flatMap { it.tiles }
            else -> emptyList()
        }
        for (tile in tiles) {
            if (tile.id.isNotBlank()) add(listOf(id, tile.id))
        }
    }
}
