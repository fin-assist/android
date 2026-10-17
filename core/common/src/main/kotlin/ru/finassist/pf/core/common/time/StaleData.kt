package ru.finassist.pf.core.common.time

import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId

/**
 * The stale statement banner of «Операции» and «Аналитика» (api.md 4.1 and 6.1 `state.stale`, FIN-125): «Данные в
 * приложении по 25 сентября — с тех пор прошло больше двух недель». The date is where the statement data ends, not
 * the last operation: a statement whose period runs past its last operation covers those quiet days too.
 */
object StaleData {
    /**
     * Last day the statement data covers, in [zone] (the profile zone): the day before [dataTo] — `state.data_to` is
     * the start of the day after the last covered day, so `2026-09-26T00:00` is «по 25 сентября». A server that does
     * not send `data_to` yet falls back to the day of [lastOperationAt]. `null` when neither is known.
     */
    fun lastDay(dataTo: OffsetDateTime?, lastOperationAt: OffsetDateTime?, zone: ZoneId): LocalDate? =
        dataTo?.atZoneSameInstant(zone)?.toLocalDate()?.minusDays(1)
            ?: lastOperationAt?.atZoneSameInstant(zone)?.toLocalDate()

    /** Banner text for [lastDay]; the year is named when it is not [currentYear]. */
    fun text(lastDay: LocalDate, currentYear: Int): String =
        "Данные в приложении по ${RussianDates.day(lastDay, currentYear)} — с тех пор прошло больше двух недель"

    /**
     * Banner text, or `null` when the banner is not shown: the data is not [stale], or there is no operation at all
     * ([lastOperationAt] absent — the screen shows its empty state instead, api.md 4.1).
     */
    fun banner(stale: Boolean, dataTo: OffsetDateTime?, lastOperationAt: OffsetDateTime?, zone: ZoneId, today: LocalDate): String? {
        if (!stale || lastOperationAt == null) return null
        return lastDay(dataTo, lastOperationAt, zone)?.let { text(it, today.year) }
    }
}
