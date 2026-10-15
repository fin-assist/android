package ru.finassist.pf.feature.assistant.impl.domain

import ru.finassist.pf.core.api.model.AnswerSource
import ru.finassist.pf.core.api.model.Block
import ru.finassist.pf.core.api.model.BlockRow
import ru.finassist.pf.core.api.model.BlockType
import ru.finassist.pf.core.api.model.ChangedSince
import ru.finassist.pf.core.api.model.ChartData
import ru.finassist.pf.core.api.model.ChartKind
import ru.finassist.pf.core.api.model.ValueKind
import ru.finassist.pf.core.blocks.BlockFormat
import ru.finassist.pf.core.api.model.TransferMode
import ru.finassist.pf.core.common.time.RussianDates
import java.time.YearMonth
import java.time.ZoneId

/** Parsed `text` block: the narrow Markdown subset of api.md 7.2 — paragraphs, `**bold**`, `- ` lists. */
sealed interface TextPart {
    /** A paragraph made of spans; [Span.bold] for `**…**`. */
    data class Paragraph(val spans: List<Span>) : TextPart

    data class Bullet(val spans: List<Span>) : TextPart

    data class Span(val text: String, val bold: Boolean)
}

object AnswerFormat {

    /** Everything outside the subset is kept as plain text (an unmatched `**` stays literal). */
    fun parse(text: String): List<TextPart> {
        val parts = mutableListOf<TextPart>()
        val paragraph = StringBuilder()
        fun flush() {
            if (paragraph.isNotBlank()) parts += TextPart.Paragraph(spans(paragraph.toString().trim()))
            paragraph.clear()
        }
        text.lines().forEach { raw ->
            val line = raw.trimEnd()
            when {
                line.isBlank() -> flush()
                line.trimStart().startsWith("- ") -> {
                    flush()
                    parts += TextPart.Bullet(spans(line.trimStart().removePrefix("- ").trim()))
                }
                else -> {
                    if (paragraph.isNotEmpty()) paragraph.append(' ')
                    paragraph.append(line.trim())
                }
            }
        }
        flush()
        return parts
    }

    fun spans(text: String): List<TextPart.Span> {
        val out = mutableListOf<TextPart.Span>()
        var rest = text
        while (rest.isNotEmpty()) {
            val open = rest.indexOf("**")
            val close = if (open >= 0) rest.indexOf("**", open + 2) else -1
            if (open < 0 || close < 0) {
                out += TextPart.Span(rest, bold = false)
                break
            }
            if (open > 0) out += TextPart.Span(rest.substring(0, open), bold = false)
            out += TextPart.Span(rest.substring(open + 2, close), bold = true)
            rest = rest.substring(close + 2)
        }
        return out.filter { it.text.isNotEmpty() }
    }

    /** «По вашим операциям, апрель — сентябрь 2026 · посчитано 25 сентября в 19:11 · без переводов». */
    fun source(s: AnswerSource, zone: ZoneId): String {
        val range = s.dataRange?.let { r ->
            val from = YearMonth.from(r.from.atZoneSameInstant(zone))
            val to = YearMonth.from(r.to.minusNanos(1).atZoneSameInstant(zone))
            val text = RussianDates.monthRange(from, to)
            if (from.year == to.year) "$text ${to.year}" else text
        }
        val at = s.calculatedAt.atZoneSameInstant(zone)
        val calculated = "посчитано ${RussianDates.day(at.toLocalDate())} в %02d:%02d".format(at.hour, at.minute)
        return listOfNotNull(
            "По вашим операциям" + (range?.let { ", $it" } ?: ""),
            calculated,
            "без переводов".takeIf { s.transferMode.effective == TransferMode.WITHOUT },
        ).joinToString(" · ")
    }

    /**
     * «с тех пор категории менялись» / «…загружали выписки»; with a code this client does not know — the server's
     * [serverText] (FIN-82), or a generic line without it; null when nothing changed.
     */
    fun changedSince(list: List<ChangedSince>, serverText: String? = null): String? {
        if (list.isEmpty()) return null
        if (list.any { it == ChangedSince.UNKNOWN }) return serverText?.replaceFirstChar { it.uppercase() } ?: "С тех пор данные менялись"
        if (list.size > 1) return "С тех пор данные менялись"
        return when (list.single()) {
            ChangedSince.CATEGORIES -> "С тех пор категории менялись"
            ChangedSince.UPLOADS -> "С тех пор загружали или удаляли выписки"
            ChangedSince.UNKNOWN -> "С тех пор данные менялись"
        }
    }

    /** Row value: the value by its kind, or the text; null — the row is skipped (api.md «Блоки» → `rows`). */
    fun rowValue(r: BlockRow): String? = BlockFormat.value(r.value, r.valueKind) ?: r.text

    /**
     * Blocks as the message draws them: a chart immediately followed by rows is one group (one card — the rows
     * describe the chart), every other block is a group of its own.
     */
    fun groupBlocks(blocks: List<Block>): List<List<Block>> {
        val groups = mutableListOf<List<Block>>()
        var i = 0
        while (i < blocks.size) {
            val b = blocks[i]
            val next = blocks.getOrNull(i + 1)
            if (b.type == BlockType.CHART && next?.type == BlockType.ROWS) {
                groups += listOf(b, next)
                i += 2
            } else {
                groups += listOf(b)
                i += 1
            }
        }
        return groups
    }

    /** The data of a chart block the app can draw — bars of a known value kind with points; null — its alt text is shown. */
    fun drawableChart(block: Block): ChartData? = block.decode(ChartData.serializer())
        ?.takeIf { it.kind == ChartKind.BAR && it.valueKind != ValueKind.UNKNOWN && it.points.isNotEmpty() }
}
