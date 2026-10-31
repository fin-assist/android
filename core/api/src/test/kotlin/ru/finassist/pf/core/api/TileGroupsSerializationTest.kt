package ru.finassist.pf.core.api

import org.junit.Test
import ru.finassist.pf.core.api.model.Block
import ru.finassist.pf.core.api.model.BlockStatus
import ru.finassist.pf.core.api.model.Tile
import ru.finassist.pf.core.api.model.TileGroupData
import ru.finassist.pf.core.api.model.TileGroupDataV2
import ru.finassist.pf.core.api.model.TileGroupSection
import ru.finassist.pf.core.api.model.ValueKind
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class TileGroupsSerializationTest {
    @Test fun `groups preserve server order and tolerate unknown fields and open codes`() {
        val data = ApiJson.decodeFromString(TileGroupDataV2.serializer(), """
            {"future":true,"groups":[
              {"future":42,"tiles":[
                {"id":"expense","title":"Расходы за период","value":0,"value_kind":"amount","future":{}},
                {"id":"income","title":"Доходы за период","value":100,"value_kind":"future_kind"},
                {"id":"balance","title":"Доходы минус расходы","status":"future_status"}]},
              {"title":"Дополнительные показатели","tiles":[{"id":"daily","title":"Средние расходы в день"}]}]}
        """)
        assertEquals(listOf("expense", "income", "balance"), data.groups[0].tiles.map { it.id })
        assertNull(data.groups[0].title)
        assertEquals("Дополнительные показатели", data.groups[1].title)
        assertEquals(0.0, data.groups[0].tiles[0].value)
        assertEquals(ValueKind.UNKNOWN, data.groups[0].tiles[1].valueKind)
        assertEquals(BlockStatus.UNKNOWN, data.groups[0].tiles[2].status)
    }

    @Test fun `a broken section or tile does not discard its valid neighbours`() {
        val data = ApiJson.decodeFromString(TileGroupDataV2.serializer(), """
            {"groups":[null,{"title":"Missing tiles"},{"tiles":{}},
              {"tiles":[null,{"title":"Missing id"},{"id":"ok","title":"Факт","value":-123,"value_kind":"amount"}]},
              {"title":"Пустая группа","tiles":[]}]}
        """)
        assertEquals(3, data.groups.size)
        // The shared tolerant-list serializer treats a non-array as empty; the renderer hides empty sections.
        assertEquals(emptyList(), data.groups[0].tiles)
        assertEquals(listOf("ok"), data.groups[1].tiles.map { it.id })
        assertEquals(-123.0, data.groups[1].tiles.single().value)
        assertEquals(emptyList(), data.groups[2].tiles)
    }

    @Test fun `version two and legacy payloads keep their distinct shapes`() {
        val block = ApiJson.decodeFromString(Block.serializer(), """
            {"id":"tiles","type":"tile_group","v":2,"data":{"groups":[{"tiles":[{"id":"x","title":"X"}]}]}}
        """)
        assertNotNull(block.decode(TileGroupDataV2.serializer()))
        assertNull(block.decode(TileGroupData.serializer()))
        val data = TileGroupDataV2(listOf(TileGroupSection(listOf(Tile("x", "X")))))
        assertEquals("""{"groups":[{"tiles":[{"id":"x","title":"X"}]}]}""",
            ApiJson.encodeToString(TileGroupDataV2.serializer(), data))
        assertEquals(emptyList(), ApiJson.decodeFromString(TileGroupDataV2.serializer(), """{"groups":[]}""").groups)
    }
}
