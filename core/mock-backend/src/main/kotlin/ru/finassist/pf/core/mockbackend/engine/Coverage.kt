package ru.finassist.pf.core.mockbackend.engine

import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.YearMonth
import java.time.ZoneId

/**
 * Which days are covered by at least one uploaded file. A file covers `DTSTART..DTEND` (the export period),
 * not just first..last operation: a quiet week inside the period is still "known to have no operations".
 * All day arithmetic is in the profile zone.
 */
class Coverage(private val uploads: List<Upload>, private val zone: ZoneId, private val today: LocalDate) {
    /** Covered day ranges, merged, as inclusive [start, endInclusive]. */
    val ranges: List<ClosedRange<LocalDate>> = uploads
        .mapNotNull { u ->
            val from = (u.coveredFrom ?: u.firstOperationAt)?.atZoneSameInstant(zone)?.toLocalDate() ?: return@mapNotNull null
            val toExclusiveInstant = u.coveredTo ?: u.lastOperationAt ?: return@mapNotNull null
            val toLocal = toExclusiveInstant.atZoneSameInstant(zone).toLocalDate()
            // DTEND is a moment; the covered last day is the day before it when it falls exactly on midnight.
            val last = if (toExclusiveInstant.atZoneSameInstant(zone).toLocalTime().toSecondOfDay() == 0) toLocal.minusDays(1) else toLocal
            from..last
        }
        .sortedBy { it.start }
        .fold(mutableListOf<ClosedRange<LocalDate>>()) { acc, r ->
            val prev = acc.lastOrNull()
            if (prev != null && !r.start.isAfter(prev.endInclusive.plusDays(1))) {
                acc[acc.lastIndex] = prev.start..maxOf(prev.endInclusive, r.endInclusive)
            } else acc += r
            acc
        }

    val isEmpty: Boolean get() = ranges.isEmpty()
    val dataFrom: LocalDate? get() = ranges.firstOrNull()?.start
    val dataTo: LocalDate? get() = ranges.lastOrNull()?.endInclusive

    fun covers(day: LocalDate): Boolean = ranges.any { day in it }

    /** A month is full when every day is covered and the month has ended. */
    fun isFullMonth(ym: YearMonth): Boolean {
        if (!ym.atEndOfMonth().isBefore(today)) return false
        var d = ym.atDay(1)
        while (!d.isAfter(ym.atEndOfMonth())) { if (!covers(d)) return false; d = d.plusDays(1) }
        return true
    }

    fun hasData(ym: YearMonth): Boolean = ranges.any { it.start <= ym.atEndOfMonth() && it.endInclusive >= ym.atDay(1) }

    /** Uncovered day ranges strictly inside [from, to] (inclusive days). */
    fun gaps(from: LocalDate, to: LocalDate): List<ClosedRange<LocalDate>> {
        val out = mutableListOf<ClosedRange<LocalDate>>()
        var cursor = from
        for (r in ranges) {
            if (r.endInclusive < cursor) continue
            if (r.start > to) break
            if (r.start > cursor) out += cursor..minOf(r.start.minusDays(1), to)
            cursor = maxOf(cursor, r.endInclusive.plusDays(1))
            if (cursor > to) break
        }
        if (cursor <= to) out += cursor..to
        return out
    }

    /** Full months, oldest first, across all data. */
    fun fullMonths(): List<YearMonth> {
        val from = dataFrom ?: return emptyList()
        val to = dataTo ?: return emptyList()
        return generateSequence(YearMonth.from(from)) { it.plusMonths(1) }
            .takeWhile { it <= YearMonth.from(to) }
            .filter(::isFullMonth)
            .toList()
    }

    fun monthsWithData(): List<YearMonth> {
        val from = dataFrom ?: return emptyList()
        val to = dataTo ?: return emptyList()
        return generateSequence(YearMonth.from(from)) { it.plusMonths(1) }.takeWhile { it <= YearMonth.from(to) }.filter(::hasData).toList()
    }

    companion object {
        fun OffsetDateTime.localDate(zone: ZoneId): LocalDate = atZoneSameInstant(zone).toLocalDate()
    }
}
