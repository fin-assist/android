package ru.finassist.pf.core.common.time

import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.YearMonth
import java.time.ZoneId

/**
 * Russian date wording used across screens. All functions take already-zoned values:
 * the caller converts instants into the profile time zone first (see [inZone]).
 */
object RussianDates {
    private const val NBSP = ' '
    private const val EN_DASH = '–'   // ranges within one month: «1–25 сентября»
    private const val EM_DASH = '—'   // ranges across months: «апрель — сентябрь»

    /** Genitive month names: «25 сентября». */
    val monthGenitive = listOf(
        "января", "февраля", "марта", "апреля", "мая", "июня",
        "июля", "августа", "сентября", "октября", "ноября", "декабря",
    )

    /** Nominative month names: «Сентябрь». */
    val monthNominative = listOf(
        "январь", "февраль", "март", "апрель", "май", "июнь",
        "июль", "август", "сентябрь", "октябрь", "ноябрь", "декабрь",
    )

    /** Short axis labels: «сен». */
    val monthShort = listOf("янв", "фев", "мар", "апр", "май", "июн", "июл", "авг", "сен", "окт", "ноя", "дек")

    fun OffsetDateTime.inZone(zone: ZoneId): OffsetDateTime = atZoneSameInstant(zone).toOffsetDateTime()

    /** «25 сентября» or «25 сентября 2025» when [withYear]. */
    fun dayMonth(date: LocalDate, withYear: Boolean = false): String =
        "${date.dayOfMonth}$NBSP${monthGenitive[date.monthValue - 1]}" + if (withYear) "$NBSP${date.year}" else ""

    /** «Сентябрь» / «Сентябрь 2026» with a capital letter — period titles. */
    fun monthTitle(ym: YearMonth, withYear: Boolean = true): String =
        monthNominative[ym.monthValue - 1].replaceFirstChar { it.uppercase() } + if (withYear) "$NBSP${ym.year}" else ""

    /**
     * Range of days: «1–25 сентября» (same month), «29 июня — 25 сентября» (same year),
     * «1 октября 2025 — 30 сентября 2026» (different years). [toExclusive] is the API's exclusive end.
     */
    fun dayRange(from: LocalDate, toExclusive: LocalDate): String {
        val last = toExclusive.minusDays(1)
        return when {
            from.year == last.year && from.month == last.month ->
                "${from.dayOfMonth}$EN_DASH${last.dayOfMonth}$NBSP${monthGenitive[last.monthValue - 1]}"
            from.year == last.year -> "${dayMonth(from)} $EM_DASH ${dayMonth(last)}"
            else -> "${dayMonth(from, true)} $EM_DASH ${dayMonth(last, true)}"
        }
    }

    /** «апрель — сентябрь 2026», «октябрь 2025 — сентябрь 2026», or «сентябрь 2026» for one month. */
    fun monthRange(from: YearMonth, toInclusive: YearMonth): String {
        val f = monthNominative[from.monthValue - 1]
        val t = monthNominative[toInclusive.monthValue - 1]
        return when {
            from == toInclusive -> "$t$NBSP${toInclusive.year}"
            from.year == toInclusive.year -> "$f $EM_DASH $t$NBSP${toInclusive.year}"
            else -> "$f$NBSP${from.year} $EM_DASH $t$NBSP${toInclusive.year}"
        }
    }

    /** «3 файла», «1 086 операций», «5 строк» — Russian plural by count. */
    fun plural(n: Long, one: String, few: String, many: String): String {
        val mod10 = n % 10
        val mod100 = n % 100
        val form = when {
            mod100 in 11..14 -> many
            mod10 == 1L -> one
            mod10 in 2..4 -> few
            else -> many
        }
        return "${groupThousands(n)}$NBSP$form"
    }

    private fun groupThousands(value: Long): String {
        val s = value.toString()
        val sb = StringBuilder()
        s.forEachIndexed { i, c ->
            if (i > 0 && (s.length - i) % 3 == 0) sb.append(NBSP)
            sb.append(c)
        }
        return sb.toString()
    }

    /** Quarter title: «III квартал 2026». */
    fun quarterTitle(year: Int, quarter: Int): String {
        val roman = listOf("I", "II", "III", "IV")[quarter - 1]
        return "$roman$NBSP" + "квартал$NBSP$year"
    }
}
