package ru.finassist.pf.feature.analytics.impl.ui

import ru.finassist.pf.core.api.model.ActionType
import ru.finassist.pf.core.api.model.Block
import ru.finassist.pf.core.api.model.BlockAction
import ru.finassist.pf.core.api.model.BlockLock
import ru.finassist.pf.core.api.model.BlockStatus
import ru.finassist.pf.core.api.model.BlockType
import ru.finassist.pf.core.api.model.LockReason
import ru.finassist.pf.core.api.model.OperationsFilter
import ru.finassist.pf.core.api.model.Tile
import ru.finassist.pf.core.api.model.TileGroupData
import ru.finassist.pf.core.api.model.TileGroupDataV2
import ru.finassist.pf.core.api.model.TileGroupSection
import ru.finassist.pf.core.api.model.ValueKind

/** Synthetic catalog responses: ids intentionally do not encode client-side financial layout rules. */
internal object GroupedTilesFixtures {
    private val open = BlockAction(ActionType.OPEN_OPERATIONS, filter = OperationsFilter(q = "synthetic"))
    fun groups(large: Boolean = false, locked: Boolean = false): List<TileGroupSection> = listOf(
        TileGroupSection(listOf(
            Tile("cost", "Расходы за период", value = if (large) 987_654_321_000.0 else 8_432_000.0,
                valueKind = ValueKind.AMOUNT, caption = "30 декабря 2025 — 30 октября 2026", action = open),
            Tile("earned", "Доходы за период", value = if (large) 123_456_789_000.0 else 15_690_000.0,
                valueKind = ValueKind.AMOUNT, showPlus = true, action = open),
            Tile("difference", "Доходы минус расходы", value = if (large) -864_197_532_000.0 else 7_258_000.0,
                valueKind = ValueKind.AMOUNT, caption = "46% дохода · по выписке"),
        )),
        TileGroupSection(listOf(
            Tile("average", "Средние расходы в день", value = 0.0, valueKind = ValueKind.AMOUNT,
                caption = "По дням с данными не позже сегодня; пропуски не учитываются"),
            if (locked) Tile("estimate", "Прогноз расходов", status = BlockStatus.LOCKED,
                lock = BlockLock(LockReason.NEED_FULL_MONTHS, "Нужно 12 полных месяцев, есть 11. Учитываем всю загруженную историю."), action = open)
            else Tile("estimate", "Прогноз расходов", value = 10_120_000.0, valueKind = ValueKind.AMOUNT, approx = true),
        ), title = "Дополнительные показатели"),
    )

    fun block(large: Boolean = false, locked: Boolean = false) =
        Block.of(BlockType.TILE_GROUP, TileGroupDataV2.serializer(), TileGroupDataV2(groups(large, locked)), v = 2).copy(id = "tiles")

    fun legacy() = Block.of(BlockType.TILE_GROUP, TileGroupData.serializer(),
        TileGroupData(groups().flatMap { it.tiles })).copy(id = "legacy")
}
