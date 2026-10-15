package ru.finassist.pf.core.blocks

import ru.finassist.pf.core.api.model.ActionType
import ru.finassist.pf.core.api.model.Block
import ru.finassist.pf.core.api.model.BlockAction
import ru.finassist.pf.core.api.model.BlockType
import ru.finassist.pf.core.api.model.ChartPoint
import ru.finassist.pf.core.api.model.Coverage
import ru.finassist.pf.core.api.model.HeadingData
import ru.finassist.pf.core.api.model.TileGroupData
import ru.finassist.pf.core.api.model.ValueKind
import ru.finassist.pf.core.common.time.DateRange
import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BlockFormatTest {
    private fun String.plain() = replace('\u202F', ' ').replace('\u00A0', ' ')

    @Test fun `values by their kind - amount with currency and sign, share, count, approx, unknown`() {
        assertEquals("−84 320 ₽", BlockFormat.value(-8_432_000.0, ValueKind.AMOUNT)!!.plain())
        assertEquals("+156 900 ₽", BlockFormat.value(15_690_000.0, ValueKind.AMOUNT, showPlus = true)!!.plain())
        assertEquals("≈101 200 ₽", BlockFormat.value(10_120_000.0, ValueKind.AMOUNT, approx = true)!!.plain())
        assertEquals("около 101 200 ₽", BlockFormat.spoken(10_120_000.0, ValueKind.AMOUNT, approx = true)!!.plain())
        assertEquals("12,50 €", BlockFormat.value(1250.0, ValueKind.AMOUNT, currency = "EUR")!!.plain())
        assertEquals("46%", BlockFormat.value(0.46, ValueKind.PERCENT))
        assertEquals("−12%", BlockFormat.value(-0.12, ValueKind.PERCENT))
        assertEquals("9", BlockFormat.value(9.0, ValueKind.COUNT))
        assertNull(BlockFormat.value(1.0, ValueKind.UNKNOWN), "an unknown kind is not shown")
        assertNull(BlockFormat.value(null, ValueKind.AMOUNT))
    }

    @Test fun `a partial bar is filled by its days with data`() {
        val september = DateRange(OffsetDateTime.parse("2026-09-01T00:00:00+03:00"), OffsetDateTime.parse("2026-10-01T00:00:00+03:00"))
        fun point(coverage: Coverage, from: String? = null, to: String? = null) = ChartPoint(
            september, value = 1.0, coverage = coverage, dataFrom = from?.let(OffsetDateTime::parse), dataTo = to?.let(OffsetDateTime::parse),
        )
        assertEquals(25f / 30, BlockFormat.partialFill(point(Coverage.PARTIAL, to = "2026-09-25T21:40:00+03:00")))
        assertEquals(6f / 30, BlockFormat.partialFill(point(Coverage.PARTIAL, from = "2026-09-25T09:00:00+03:00", to = "2026-09-30T23:59:59+03:00")))
        assertEquals(1f, BlockFormat.partialFill(point(Coverage.PARTIAL)))
        assertNull(BlockFormat.partialFill(point(Coverage.COMPLETE, to = "2026-09-25T21:40:00+03:00")))
    }

    @Test fun `the registry says what it draws and skips what it cannot (FIN-7)`() {
        val registry = CatalogV1.registry()
        assertEquals("chart@1,heading@1,insight_card@1,ranked_list@1,rows@1,text@1,tile_group@1", registry.supports)
        val heading = Block.of(BlockType.HEADING, HeadingData.serializer(), HeadingData("Раздел"))
        assertNotNull(registry.prepare(heading))
        assertNull(registry.prepare(heading.copy(v = 2, altText = null)), "a newer version without alt_text is skipped")
        assertNotNull(registry.prepare(heading.copy(v = 2, altText = "Раздел")), "a newer version is shown by its alt_text")
        assertNull(registry.prepare(Block(type = BlockType.UNKNOWN)), "an unknown type without alt_text is skipped")
        assertNotNull(registry.prepare(Block(type = BlockType.UNKNOWN, altText = "Карта")))
        val broken = Block(type = BlockType.TILE_GROUP, data = kotlinx.serialization.json.buildJsonObject { put("tiles", kotlinx.serialization.json.JsonPrimitive(1)) })
        assertNull(registry.prepare(broken), "data that does not decode hides that block only")
        val emptyTiles = Block.of(BlockType.TILE_GROUP, TileGroupData.serializer(), TileGroupData(emptyList()))
        assertNull(registry.prepare(emptyTiles))
    }

    @Test fun `a heading stays only above a visible block of its section`() {
        val h1 = Block(type = BlockType.HEADING, title = "1")
        val a = Block(id = "a", type = BlockType.INSIGHT_CARD)
        val h2 = Block(type = BlockType.HEADING, title = "2")
        val b = Block(id = "b", type = BlockType.INSIGHT_CARD)
        val all = listOf(h1, a, h2, b)
        assertEquals(all, BlockLayout.visible(all) { false })
        assertEquals(listOf(h2, b), BlockLayout.visible(all) { it.id == "a" })
        assertTrue(BlockLayout.visible(all) { it.id != null }.isEmpty())
    }

    @Test fun `default tags of a screen prefix`() {
        val tags = BlockTags("screen")
        assertEquals("screen.block.x", tags.block("x"))
        assertEquals("screen.tile.t", tags.tile("t"))
        assertEquals("screen.x.item", tags.item("x"))
        assertEquals("screen.x.more", tags.more("x"))
        assertEquals("screen.chart.x", tags.chart("x"))
    }

    @Test fun `a heading over blocks that did not decode goes with them (FIN-4 review)`() {
        val registry = CatalogV1.registry()
        val heading = Block(type = BlockType.HEADING, title = "Наблюдения")
        val broken = Block(id = "x", type = BlockType.CHART, v = 1, data = kotlinx.serialization.json.buildJsonObject { put("kind", kotlinx.serialization.json.JsonPrimitive("bar")) })
        val unknown = Block(id = "y", type = BlockType.UNKNOWN, v = 1)
        assertTrue(BlockLayout.visible(registry.prepareAll(listOf(heading, broken, unknown)), { it.block }).isEmpty())
    }

    @Test fun `a tap on a block, tile or row only navigates, a data-changing action works only from a button (FIN-4 review)`() {
        val open = BlockAction(ActionType.OPEN_OPERATIONS, filter = ru.finassist.pf.core.api.model.OperationsFilter(categoryId = "c"))
        assertEquals(open, BlockLayout.tapAction(open))
        assertEquals(ActionType.OPEN_ASSISTANT, BlockLayout.tapAction(BlockAction(ActionType.OPEN_ASSISTANT))?.type)
        assertNull(BlockLayout.tapAction(BlockAction(ActionType.DISMISS_REGULAR_PAYMENT, paymentId = "rp", label = "Не подписка")))
        assertNull(BlockLayout.tapAction(BlockAction(ActionType.UNKNOWN)))
        assertNull(BlockLayout.tapAction(null))
    }

    @Test fun `an action without the fields of its type is unknown (contracts review)`() {
        val dismiss = BlockAction(ActionType.DISMISS_REGULAR_PAYMENT, paymentId = "rp", label = "Не подписка")
        assertEquals(dismiss, BlockLayout.usable(dismiss))
        assertNull(BlockLayout.usable(dismiss.copy(paymentId = null)))
        assertNull(BlockLayout.usable(dismiss.copy(label = "")))
        assertNull(BlockLayout.usable(BlockAction(ActionType.OPEN_OPERATIONS)))
        assertNull(BlockLayout.usable(BlockAction(ActionType.OPEN_OPERATIONS, filter = ru.finassist.pf.core.api.model.OperationsFilter())))
        val onlyMode = ru.finassist.pf.core.api.model.OperationsFilter(transferMode = ru.finassist.pf.core.api.model.TransferMode.WITHOUT)
        assertNull(BlockLayout.usable(BlockAction(ActionType.OPEN_OPERATIONS, filter = onlyMode)))
        assertEquals(ActionType.OPEN_OPERATIONS, BlockLayout.usable(BlockAction(ActionType.OPEN_OPERATIONS, filter = onlyMode.copy(selection = "s")))?.type)
        assertNull(BlockLayout.tapAction(BlockAction(ActionType.OPEN_OPERATIONS)))
        assertEquals(ActionType.OPEN_ASSISTANT, BlockLayout.usable(BlockAction(ActionType.OPEN_ASSISTANT))?.type)
    }
}
