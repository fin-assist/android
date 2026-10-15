package ru.finassist.pf.feature.assistant.impl

import kotlin.test.assertTrue
import ru.finassist.pf.core.api.model.ValueKind
import ru.finassist.pf.core.api.model.ChartKind
import ru.finassist.pf.core.api.model.ChartData
import ru.finassist.pf.core.api.model.AnswerSource
import ru.finassist.pf.core.api.model.Block
import ru.finassist.pf.core.api.model.BlockRow
import ru.finassist.pf.core.api.model.BlockType
import ru.finassist.pf.core.api.model.ChangedSince
import ru.finassist.pf.core.api.model.Coverage
import ru.finassist.pf.core.api.model.TransferMode
import ru.finassist.pf.core.common.time.DateRange
import ru.finassist.pf.feature.assistant.impl.domain.AnswerFormat
import ru.finassist.pf.feature.assistant.impl.domain.TextPart
import ru.finassist.pf.feature.assistant.impl.ui.ChatViewModel
import java.time.OffsetDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AnswerFormatTest {
    private fun String.plain() = replace(' ', ' ').replace(' ', ' ')
    private fun t(s: String) = OffsetDateTime.parse(s)

    @Test
    fun `markdown subset`() {
        val parts = AnswerFormat.parse("За апрель ушло **7 200 ₽**, все с июля.\nВ среднем 2 400 ₽.\n\n- раз\n- **два**\nХвост с ** без пары")
        assertEquals(4, parts.size)
        val p0 = parts[0] as TextPart.Paragraph
        assertEquals(listOf("За апрель ушло ", "7 200 ₽", ", все с июля. В среднем 2 400 ₽."), p0.spans.map { it.text })
        assertEquals(listOf(false, true, false), p0.spans.map { it.bold })
        assertEquals("раз", (parts[1] as TextPart.Bullet).spans.single().text)
        assertEquals(true, (parts[2] as TextPart.Bullet).spans.single().bold)
        assertEquals("Хвост с ** без пары", (parts[3] as TextPart.Paragraph).spans.single().text)
    }

    @Test
    fun `source line and changed since`() {
        val s = AnswerSource(
            calculatedAt = t("2026-09-25T19:11:00+03:00"),
            dataRange = DateRange(t("2026-04-01T00:00:00+03:00"), t("2026-10-01T00:00:00+03:00")),
            transferMode = TransferMode.WITHOUT,
            changedSince = emptyList(),
        )
        assertEquals(
            "По вашим операциям, апрель — сентябрь 2026 · посчитано 25 сентября в 19:11 · без переводов",
            AnswerFormat.source(s, ZoneId.of("Europe/Moscow")).plain(),
        )
        assertNull(AnswerFormat.changedSince(emptyList()))
        assertEquals("С тех пор категории менялись", AnswerFormat.changedSince(listOf(ChangedSince.CATEGORIES)))
        assertEquals("С тех пор данные менялись", AnswerFormat.changedSince(listOf(ChangedSince.UNKNOWN)))
        // FIN-83: an unknown code shows the server's text when there is one.
        assertEquals("С тех пор менялись правила", AnswerFormat.changedSince(listOf(ChangedSince.UNKNOWN), "с тех пор менялись правила"))
        assertEquals("С тех пор категории менялись", AnswerFormat.changedSince(listOf(ChangedSince.CATEGORIES), "не нужен"))
    }

    @Test
    fun `row values`() {
        assertEquals("800 ₽", AnswerFormat.rowValue(BlockRow("Средний чек", value = 80000.0, valueKind = ValueKind.AMOUNT))!!.plain())
        assertEquals("9", AnswerFormat.rowValue(BlockRow("Операций", value = 9.0, valueKind = ValueKind.COUNT)))
        assertEquals("46%", AnswerFormat.rowValue(BlockRow("Доля", value = 0.46, valueKind = ValueKind.PERCENT)))
        assertEquals("быстро", AnswerFormat.rowValue(BlockRow("Скорость", value = 3.0, valueKind = ValueKind.UNKNOWN, text = "быстро")))
        assertNull(AnswerFormat.rowValue(BlockRow("Пусто")))
        // The answer's currency (FIN-82): the same minor units in dollars.
        val usd = AnswerFormat.rowValue(BlockRow("Средний чек", value = 80000.0, valueKind = ValueKind.AMOUNT), "USD")!!.plain()
        assertEquals(ru.finassist.pf.core.common.money.Money(80000).format("USD").plain(), usd)
        assertTrue(!usd.contains("₽"), usd)
    }

    @Test
    fun `chunks extend or start text blocks`() {
        var blocks = ChatViewModel.appendChunk(emptyList(), 0, "Привет")
        blocks = ChatViewModel.appendChunk(blocks, 0, ", мир")
        blocks = blocks + Block(type = BlockType.CHART)
        blocks = ChatViewModel.appendChunk(blocks, 2, "Итог")
        assertEquals(listOf("Привет, мир", null, "Итог"), blocks.map { it.text })
    }

    @Test
    fun `a chart followed by rows is one group`() {
        val text = Block.text("a")
        val chart = Block(type = BlockType.CHART)
        val rows = Block(type = BlockType.ROWS)
        assertEquals(listOf(listOf(text), listOf(chart, rows)), AnswerFormat.groupBlocks(listOf(text, chart, rows)))
        assertEquals(listOf(listOf(rows), listOf(chart)), AnswerFormat.groupBlocks(listOf(rows, chart)))
        assertEquals(listOf(listOf(chart), listOf(text), listOf(rows)), AnswerFormat.groupBlocks(listOf(chart, text, rows)))
        assertEquals(emptyList(), AnswerFormat.groupBlocks(emptyList()))
    }

    @Test
    fun `only known bar charts with points are drawn`() {
        val point = ru.finassist.pf.core.api.model.ChartPoint(
            DateRange(OffsetDateTime.parse("2026-09-01T00:00:00+03:00"), OffsetDateTime.parse("2026-10-01T00:00:00+03:00")),
            value = 1.0, coverage = ru.finassist.pf.core.api.model.Coverage.COMPLETE,
        )
        val data = ChartData(kind = ChartKind.BAR, valueKind = ValueKind.AMOUNT, points = listOf(point))
        fun chart(d: ChartData) = Block.of(BlockType.CHART, ChartData.serializer(), d)
        assertEquals(data, AnswerFormat.drawableChart(chart(data)))
        assertNull(AnswerFormat.drawableChart(chart(data.copy(kind = ChartKind.UNKNOWN))))
        assertNull(AnswerFormat.drawableChart(chart(data.copy(valueKind = ValueKind.UNKNOWN))))
        assertNull(AnswerFormat.drawableChart(chart(data.copy(points = emptyList()))))
        assertNull(AnswerFormat.drawableChart(Block(type = BlockType.CHART)), "no data")
    }
}
