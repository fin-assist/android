package ru.finassist.pf.mock.domain

import ru.finassist.pf.core.api.model.Analytics
import ru.finassist.pf.core.api.model.Block
import ru.finassist.pf.core.api.model.Tile
import ru.finassist.pf.core.api.model.TileGroupData
import ru.finassist.pf.core.api.model.TileGroupDataV2
import ru.finassist.pf.mock.MockAnalyticsHelp

internal fun Analytics.withAnalyticsHelp(mode: MockAnalyticsHelp, balanceFirst: Boolean = false): Analytics {
    if (!hasData || mode == MockAnalyticsHelp.MANUAL) return this
    val reversed = mode == MockAnalyticsHelp.REVERSED
    val balanceOrder = if (reversed || balanceFirst) 1 else 2
    val dailyOrder = if (balanceFirst) 2 else 1
    val blocks = blocks.map { block ->
        when (block.id) {
            "tiles" -> block.mapHelpTiles { tile ->
                if (tile.id != "daily_expense" || tile.helpText.isNullOrBlank()) tile else tile.copy(
                    helpTags = if (reversed) emptyList() else listOf("onboarding"),
                    helpOrder = dailyOrder.takeUnless { reversed },
                )
            }
            "balance" -> if (block.helpText.isNullOrBlank()) block else block.copy(
                helpTags = listOf("onboarding", "future_tag"),
                helpOrder = balanceOrder,
                helpText = if (reversed) "Разница показывает, сколько осталось после расходов." else block.helpText,
            )
            "monthly_chart" -> if (reversed && !block.helpText.isNullOrBlank()) block.copy(
                helpTags = listOf("onboarding"),
                helpOrder = 2,
            ) else block
            else -> block
        }
    }
    return copy(blocks = blocks)
}

private fun Block.mapHelpTiles(transform: (Tile) -> Tile): Block = when (v) {
    1 -> decode(TileGroupData.serializer())?.let { group ->
        copy(data = Block.of(type, TileGroupData.serializer(), group.copy(tiles = group.tiles.map(transform))).data)
    }
    2 -> decode(TileGroupDataV2.serializer())?.let { groups ->
        val mapped = groups.copy(groups = groups.groups.map { group -> group.copy(tiles = group.tiles.map(transform)) })
        copy(data = Block.of(type, TileGroupDataV2.serializer(), mapped, v = 2).data)
    }
    else -> null
} ?: this
