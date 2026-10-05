package ru.finassist.pf.feature.assistant.impl.domain

import ru.finassist.pf.core.common.time.RussianDates
import ru.finassist.pf.core.network.codes.AnswerErrorCode
import ru.finassist.pf.core.network.codes.AnswerStatus
import ru.finassist.pf.core.network.codes.ChangedSince
import ru.finassist.pf.core.network.codes.MessageRole
import ru.finassist.pf.core.network.dto.AnswerSourceDto
import ru.finassist.pf.core.network.dto.BlockDto
import ru.finassist.pf.core.network.dto.ChipDto
import ru.finassist.pf.core.network.dto.MessageDto
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter


/** One entry of the thread. Assistant answers mutate while streaming, so they are replaced by id in the list. */
sealed interface ChatMessage {
    val id: String
    val createdAt: OffsetDateTime

    data class User(override val id: String, val text: String, override val createdAt: OffsetDateTime) : ChatMessage

    data class Assistant(
        override val id: String,
        val status: AnswerStatus,
        val blocks: List<BlockDto>,
        val chips: List<ChipDto>,
        val source: AnswerSourceDto?,
        val charged: Boolean?,
        val errorCode: AnswerErrorCode?,
        override val createdAt: OffsetDateTime,
    ) : ChatMessage

    companion object {
        /** Null for unknown roles — the contract says not to show them. */
        fun from(m: MessageDto): ChatMessage? {
            val at = runCatching { OffsetDateTime.parse(m.createdAt) }.getOrElse { OffsetDateTime.now() }
            return when (MessageRole.fromWire(m.role)) {
                MessageRole.User -> User(m.id, m.text.orEmpty(), at)
                MessageRole.Assistant -> {
                    val status = AnswerStatus.fromWire(m.status)
                    Assistant(
                        m.id, status,
                        blocks = if (status == AnswerStatus.Failed) emptyList() else m.blocks.orEmpty(),
                        chips = m.chips.orEmpty(), source = m.source, charged = m.charged,
                        errorCode = if (status == AnswerStatus.Failed) AnswerErrorCode.fromWire(m.errorCode) else null,
                        createdAt = at,
                    )
                }
                MessageRole.Unknown -> null
            }
        }
    }
}

/** «По вашим операциям, апрель — сентябрь 2026 · посчитано 25 сентября в 19:11». */
fun sourceText(s: AnswerSourceDto, zone: ZoneId = ZoneId.systemDefault()): String {
    val range = s.dataRange?.let { r ->
        runCatching {
            val from = YearMonth.from(OffsetDateTime.parse(r.from).toLocalDate())
            val to = YearMonth.from(OffsetDateTime.parse(r.to).toLocalDate().minusDays(1))
            RussianDates.monthRange(from, to)
        }.getOrNull()
    }
    val at = runCatching { OffsetDateTime.parse(s.calculatedAt).atZoneSameInstant(zone) }.getOrNull()
    val calc = at?.let { "посчитано ${RussianDates.dayMonth(it.toLocalDate())} в ${it.format(DateTimeFormatter.ofPattern("H:mm"))}" }
    return listOfNotNull("По вашим операциям" + (range?.let { ", $it" } ?: ""), calc).joinToString(" · ")
}

/** «с тех пор категории менялись» / «с тех пор загружали выписки» / «с тех пор данные менялись»; null if nothing changed. */
fun changedSinceText(codes: List<String>): String? {
    if (codes.isEmpty()) return null
    val known = codes.map(ChangedSince::fromWire)
    return when {
        ChangedSince.Unknown in known -> "С тех пор данные менялись"
        ChangedSince.Categories in known && ChangedSince.Uploads in known -> "С тех пор менялись категории и выписки"
        ChangedSince.Categories in known -> "С тех пор категории менялись"
        else -> "С тех пор загружали или удаляли выписки"
    }
}

/** Day separator label: «Сегодня», «Вчера», «25 сентября», «25 сентября 2025». */
fun dayLabel(date: LocalDate, today: LocalDate): String = when {
    date == today -> "Сегодня"
    date == today.minusDays(1) -> "Вчера"
    date.year == today.year -> RussianDates.dayMonth(date)
    else -> RussianDates.dayMonth(date, withYear = true)
}

/** «Новые — в 00:00 по Москве (01:00 по вашему времени)» when the phone is not on Moscow time. */
fun resetText(resetsAt: String, zone: ZoneId = ZoneId.systemDefault()): String {
    val t = runCatching { OffsetDateTime.parse(resetsAt) }.getOrNull() ?: return "Новые — в 00:00 по Москве"
    val local = t.atZoneSameInstant(zone)
    val moscow = t.atZoneSameInstant(ZoneId.of("Europe/Moscow"))
    val fmt = DateTimeFormatter.ofPattern("HH:mm")
    return if (local.toLocalTime() == moscow.toLocalTime()) "Новые — в ${moscow.format(fmt)} по Москве"
    else "Новые — в ${moscow.format(fmt)} по Москве (${local.format(fmt)} по вашему времени)"
}

/** A line of the narrow Markdown subset (api.md 7.2 `text`): paragraph text or a bullet item, with bold spans. */
data class TextLine(val text: String, val bullet: Boolean)
data class Span(val text: String, val bold: Boolean)

fun parseMarkdown(text: String): List<List<TextLine>> =
    text.split(Regex("\n\\s*\n")).filter { it.isNotBlank() }.map { para ->
        para.lines().filter { it.isNotBlank() }.map { line ->
            val trimmed = line.trim()
            if (trimmed.startsWith("- ")) TextLine(trimmed.removePrefix("- "), bullet = true) else TextLine(trimmed, bullet = false)
        }
    }

fun spans(text: String): List<Span> {
    val out = mutableListOf<Span>()
    var rest = text
    while (true) {
        val start = rest.indexOf("**")
        if (start < 0) { if (rest.isNotEmpty()) out += Span(rest, false); break }
        val end = rest.indexOf("**", start + 2)
        if (end < 0) { out += Span(rest, false); break }
        if (start > 0) out += Span(rest.substring(0, start), false)
        out += Span(rest.substring(start + 2, end), true)
        rest = rest.substring(end + 2)
    }
    return out
}

fun axisLabel(fromIso: String): String = runCatching { RussianDates.monthShort[OffsetDateTime.parse(fromIso).monthValue - 1] }.getOrDefault("")
fun axisSpoken(fromIso: String): String = runCatching { RussianDates.monthNominative[OffsetDateTime.parse(fromIso).monthValue - 1] }.getOrDefault("")
fun dayOfMonth(iso: String?): Int? = iso?.let { runCatching { OffsetDateTime.parse(it).dayOfMonth }.getOrNull() }
fun monthLength(fromIso: String): Int = runCatching { YearMonth.from(OffsetDateTime.parse(fromIso).toLocalDate()).lengthOfMonth() }.getOrDefault(30)

