package ru.finassist.pf.feature.operations.impl.domain

import ru.finassist.pf.core.common.time.RussianDates
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/** Grouping is a client concern (api.md 4.1): by day in the feed, by month in search, in the profile zone. */
object Grouping {
    data class Group(val label: String, val items: List<Operation>)

    fun byDay(items: List<Operation>, zone: ZoneId, today: LocalDate = LocalDate.now(zone)): List<Group> =
        items.groupBy { it.occurredAt.atZoneSameInstant(zone).toLocalDate() }.entries
            .sortedByDescending { it.key }
            .map { (day, ops) -> Group(RussianDates.dayMonth(day, withYear = day.year != today.year), ops) }

    fun byMonth(items: List<Operation>, zone: ZoneId): List<Group> =
        items.groupBy { YearMonth.from(it.occurredAt.atZoneSameInstant(zone)) }.entries
            .sortedByDescending { it.key }
            .map { (ym, ops) -> Group(RussianDates.monthTitle(ym, withYear = true), ops) }
}
