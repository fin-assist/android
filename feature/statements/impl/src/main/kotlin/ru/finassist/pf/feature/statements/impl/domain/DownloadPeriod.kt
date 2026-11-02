package ru.finassist.pf.feature.statements.impl.domain

import java.time.LocalDate
import java.time.ZoneId
import ru.finassist.pf.core.api.model.ImportConfig
import ru.finassist.pf.core.common.error.AppError
import ru.finassist.pf.core.common.time.SourceTimeZone

/** Inclusive civil dates; neither the device zone nor the wire offset owns this selection. */
data class DownloadPeriod(val from: LocalDate, val to: LocalDate) {
    fun valid(today: LocalDate): Boolean = from.year in 1..9999 && to.year in 1..9999 && from <= to && to <= today

    fun validateResponse(config: ImportConfig): ZoneId {
        val zone = SourceTimeZone.require(config.timeZone)
        val expectedFrom = from.atStartOfDay(zone).toInstant()
        val expectedTo = to.plusDays(1).atStartOfDay(zone).toInstant()
        if (expectedFrom >= expectedTo || config.suggestedPeriod.from.toInstant() != expectedFrom ||
            config.suggestedPeriod.to.toInstant() != expectedTo) throw AppError.Server(500)
        return zone
    }
}
