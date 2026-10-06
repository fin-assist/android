package ru.finassist.pf.feature.assistant.impl

import ru.finassist.pf.core.api.model.AnswerSource
import ru.finassist.pf.core.api.model.Block
import ru.finassist.pf.core.api.model.BlockRow
import ru.finassist.pf.core.api.model.BlockType
import ru.finassist.pf.core.api.model.ChangedSince
import ru.finassist.pf.core.api.model.TransferMode
import ru.finassist.pf.core.common.money.Money
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
    }

    @Test
    fun `row values`() {
        assertEquals("800 ₽", AnswerFormat.rowValue(BlockRow("Средний чек", amount = Money(80000)))!!.plain())
        assertEquals("9", AnswerFormat.rowValue(BlockRow("Операций", count = 9)))
        assertEquals("46%", AnswerFormat.rowValue(BlockRow("Доля", percent = 0.46)))
        assertNull(AnswerFormat.rowValue(BlockRow("Пусто")))
    }

    @Test
    fun `chunks extend or start text blocks`() {
        var blocks = ChatViewModel.appendChunk(emptyList(), 0, "Привет")
        blocks = ChatViewModel.appendChunk(blocks, 0, ", мир")
        blocks = blocks + Block(type = BlockType.CHART)
        blocks = ChatViewModel.appendChunk(blocks, 2, "Итог")
        assertEquals(listOf("Привет, мир", null, "Итог"), blocks.map { it.text })
    }
}
