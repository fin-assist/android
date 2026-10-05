package ru.finassist.pf.core.common.time

import java.time.LocalDate
import java.time.Month
import java.time.OffsetDateTime
import java.time.YearMonth

/**
 * Russian date labels as the mockups show them. The API never sends calendar labels, so every screen builds
 * them here: «25 сентября», «1–25 сентября», «Сентябрь 2026», «апрель — сентябрь».
 */
object RussianDates {
    private val GENITIVE = listOf(
        "января", "февраля", "марта", "апреля", "мая", "июня",
        "июля", "августа", "сентября", "октября", "ноября", "декабря",
    )
    private val NOMINATIVE = listOf(
        "январь", "февраль", "март", "апрель", "май", "июнь",
        "июль", "август", "сентябрь", "октябрь", "ноябрь", "декабрь",
    )
    private val SHORT = listOf("янв", "фев", "мар", "апр", "май", "июн", "июл", "авг", "сен", "окт", "ноя", "дек")

    const val EN_DASH = "–"
    const val EM_DASH = "—"
    private const val NBSP = " "

    fun monthGenitive(month: Month) = GENITIVE[month.value - 1]
    fun monthNominative(month: Month) = NOMINATIVE[month.value - 1]
    fun monthShort(month: Month) = SHORT[month.value - 1]

    /** «25 сентября» or «25 сентября 2025» when the year differs from [currentYear]. */
    fun day(date: LocalDate, currentYear: Int? = null): String {
        val base = "${date.dayOfMonth}$NBSP${monthGenitive(date.month)}"
        return if (currentYear != null && date.year != currentYear) "$base$NBSP${date.year}" else base
    }

    /** «Сентябрь 2026», capitalised for headers. */
    fun monthTitle(month: YearMonth): String =
        monthNominative(month.month).replaceFirstChar { it.uppercase() } + " " + month.year

    /** «1–25 сентября», «29 июня — 25 сентября», «1 апреля 2025 — 25 сентября 2026». */
    fun dayRange(from: LocalDate, toInclusive: LocalDate): String = when {
        from == toInclusive -> day(from)
        from.year != toInclusive.year ->
            "${day(from)} ${from.year} $EM_DASH ${day(toInclusive)} ${toInclusive.year}"
        from.month == toInclusive.month -> "${from.dayOfMonth}$EN_DASH${toInclusive.dayOfMonth}$NBSP${monthGenitive(from.month)}"
        else -> "${day(from)} $EM_DASH ${day(toInclusive)}"
    }

    /** «апрель — сентябрь», «сентябрь», «ноябрь 2025 — сентябрь 2026». */
    fun monthRange(from: YearMonth, toInclusive: YearMonth): String = when {
        from == toInclusive -> monthNominative(from.month)
        from.year != toInclusive.year ->
            "${monthNominative(from.month)} ${from.year} $EM_DASH ${monthNominative(toInclusive.month)} ${toInclusive.year}"
        else -> "${monthNominative(from.month)} $EM_DASH ${monthNominative(toInclusive.month)}"
    }

    /** Last calendar day inside a half-open range: `[1 Sep, 1 Oct)` → 30 Sep. */
    fun lastDayOf(range: DateRange): LocalDate = range.to.toLocalDate().minusDays(1)

    fun OffsetDateTime.toLocalDay(): LocalDate = toLocalDate()
}

/** Russian plural form: pluralize(5, "операция", "операции", "операций") → «операций». */
fun pluralize(count: Int, one: String, few: String, many: String): String {
    val n = kotlin.math.abs(count) % 100
    val n1 = n % 10
    return when {
        n in 11..19 -> many
        n1 == 1 -> one
        n1 in 2..4 -> few
        else -> many
    }
}

fun countWithNoun(count: Int, one: String, few: String, many: String): String =
    "${groupThousands(count)} ${pluralize(count, one, few, many)}"

private fun groupThousands(value: Int): String {
    val s = kotlin.math.abs(value).toString()
    val sb = StringBuilder()
    s.forEachIndexed { i, c ->
        if (i > 0 && (s.length - i) % 3 == 0) sb.append(' ')
        sb.append(c)
    }
    return (if (value < 0) "−" else "") + sb
}
