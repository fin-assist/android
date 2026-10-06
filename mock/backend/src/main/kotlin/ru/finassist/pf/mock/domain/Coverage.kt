package ru.finassist.pf.mock.domain

import ru.finassist.pf.core.common.time.DateRange
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.YearMonth
import java.time.ZoneId

/**
 * Which calendar days the uploaded statements cover (the union of every upload's `DTSTART..DTEND`), and the
 * derived notions the API exposes: full months, gaps, partial periods. All in the profile time zone.
 */
class Coverage(ranges: List<Pair<OffsetDateTime, OffsetDateTime>>, private val zone: ZoneId, private val today: LocalDate) {

    /** Covered day intervals `[from, toExclusive)`, merged and sorted. */
    val days: List<ClosedRange<LocalDate>> = ranges
        .map { (from, to) -> from.atZoneSameInstant(zone).toLocalDate()..to.atZoneSameInstant(zone).toLocalDate() }
        .sortedBy { it.start }
        .fold(mutableListOf<ClosedRange<LocalDate>>()) { acc, r ->
            val last = acc.lastOrNull()
            if (last != null && !r.start.isAfter(last.endInclusive.plusDays(1))) {
                acc[acc.lastIndex] = last.start..maxOf(last.endInclusive, r.endInclusive)
            } else {
                acc += r
            }
            acc
        }

    val isEmpty: Boolean get() = days.isEmpty()
    val firstDay: LocalDate? get() = days.firstOrNull()?.start
    val lastDay: LocalDate? get() = days.lastOrNull()?.endInclusive

    fun isCovered(day: LocalDate): Boolean = days.any { day in it }

    /** Uncovered day intervals strictly inside the data (between the first and the last covered day). */
    val gaps: List<DateRange>
        get() = days.zipWithNext { a, b -> a.endInclusive.plusDays(1) to b.start }
            .filter { (from, to) -> from.isBefore(to) }
            .map { (from, to) -> DateRange(from.atStartOfDay(zone).toOffsetDateTime(), to.atStartOfDay(zone).toOffsetDateTime()) }

    fun gapsWithin(from: LocalDate, toExclusive: LocalDate): List<DateRange> = gaps.filter { g ->
        g.from.atZoneSameInstant(zone).toLocalDate().isBefore(toExclusive) &&
            g.to.atZoneSameInstant(zone).toLocalDate().isAfter(from)
    }

    /** A month is full when every day of it is covered and the month has ended. */
    fun isFullMonth(month: YearMonth): Boolean {
        if (!month.atEndOfMonth().isBefore(today)) return false
        return (1..month.lengthOfMonth()).all { isCovered(month.atDay(it)) }
    }

    fun hasData(month: YearMonth): Boolean = (1..month.lengthOfMonth()).any { isCovered(month.atDay(it)) }

    /** Months with at least one covered day, oldest first. */
    val monthsWithData: List<YearMonth>
        get() {
            val first = firstDay ?: return emptyList()
            val last = lastDay ?: return emptyList()
            return generateSequence(YearMonth.from(first)) { it.plusMonths(1) }
                .takeWhile { !it.isAfter(YearMonth.from(last)) }
                .filter { hasData(it) }
                .toList()
        }

    val fullMonths: List<YearMonth> get() = monthsWithData.filter { isFullMonth(it) }

    /** Data boundaries inside `[from, toExclusive)`: first and last covered day, or null when none. */
    fun dataBounds(from: LocalDate, toExclusive: LocalDate): Pair<LocalDate, LocalDate>? {
        var first: LocalDate? = null
        var last: LocalDate? = null
        for (r in days) {
            val s = maxOf(r.start, from)
            val e = minOf(r.endInclusive, toExclusive.minusDays(1))
            if (s.isAfter(e)) continue
            if (first == null) first = s
            last = e
        }
        return first?.let { it to last!! }
    }

    /** `complete` / `partial` / `no_data` for a calendar period: complete only when it has ended and every day is covered. */
    fun coverageOf(from: LocalDate, toExclusive: LocalDate): String {
        dataBounds(from, toExclusive) ?: return "no_data"
        val ended = toExclusive.minusDays(1).isBefore(today)
        val allCovered = generateSequence(from) { it.plusDays(1) }.takeWhile { it.isBefore(toExclusive) }.all { isCovered(it) }
        return if (ended && allCovered) "complete" else "partial"
    }
}
