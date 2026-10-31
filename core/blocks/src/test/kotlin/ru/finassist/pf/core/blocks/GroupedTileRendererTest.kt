package ru.finassist.pf.core.blocks

import org.junit.Test
import ru.finassist.pf.core.api.model.Block
import ru.finassist.pf.core.api.model.BlockStatus
import ru.finassist.pf.core.api.model.BlockType
import ru.finassist.pf.core.api.model.Tile
import ru.finassist.pf.core.api.model.TileGroupData
import ru.finassist.pf.core.api.model.TileGroupDataV2
import ru.finassist.pf.core.api.model.TileGroupSection
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class GroupedTileRendererTest {
    private val tile = Tile("server_metric", "Показатель сервера")
    private fun grouped(groups: List<TileGroupSection>) =
        Block.of(BlockType.TILE_GROUP, TileGroupDataV2.serializer(), TileGroupDataV2(groups), v = 2)

    @Test fun `registered version two renderer still accepts legacy producer payloads`() {
        val old = Block.of(BlockType.TILE_GROUP, TileGroupData.serializer(), TileGroupData(listOf(tile)))
        assertNotNull(GroupedTileRenderer.prepare(old))
        assertNotNull(CatalogV1.registry().prepare(old))
        assertNull(GroupedTileRenderer.prepare(old.copy(v = 2)), "a version mismatch must not flatten the old shape")
    }

    @Test fun `empty sections cannot leave orphan titles and valid neighbours survive`() {
        val empty = TileGroupSection(emptyList(), title = "Не показывать")
        assertNull(GroupedTileRenderer.prepare(grouped(listOf(empty))))
        assertNotNull(GroupedTileRenderer.prepare(grouped(listOf(empty, TileGroupSection(listOf(tile))))))
        assertNull(GroupedTileRenderer.prepare(grouped(emptyList())))
    }

    @Test fun `none and unavailable use the server explanation instead of numeric tiles`() {
        val block = grouped(listOf(TileGroupSection(listOf(tile))))
        for (status in listOf(BlockStatus.NONE, BlockStatus.UNAVAILABLE)) {
            assertNotNull(GroupedTileRenderer.prepare(block.copy(status = status, emptyText = "Нет данных")))
            assertNull(GroupedTileRenderer.prepare(block.copy(status = status)))
        }
    }

    @Test fun `unknown versions are left to the registry text fallback`() {
        val block = grouped(listOf(TileGroupSection(listOf(tile)))).copy(v = 3)
        assertNull(GroupedTileRenderer.prepare(block))
        assertNull(CatalogV1.registry().prepare(block))
        assertNotNull(CatalogV1.registry().prepare(block.copy(altText = "Показатели сервера")))
    }
}
