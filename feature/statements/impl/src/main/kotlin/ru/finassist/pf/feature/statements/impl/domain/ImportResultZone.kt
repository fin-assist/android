package ru.finassist.pf.feature.statements.impl.domain

import ru.finassist.pf.core.api.model.ImportResult
import ru.finassist.pf.core.common.time.DateRange
import ru.finassist.pf.core.common.time.SourceTimeZone

/** Preserve instants while giving all result text helpers the snapshot's civil dates. */
internal fun ImportResult.inSourceZone(): ImportResult {
    val zone = SourceTimeZone.require(timeZone)
    fun DateRange.local() = DateRange(from.atZoneSameInstant(zone).toOffsetDateTime(), to.atZoneSameInstant(zone).toOffsetDateTime())
    return copy(
        firstOperationAt = firstOperationAt?.atZoneSameInstant(zone)?.toOffsetDateTime(),
        lastOperationAt = lastOperationAt?.atZoneSameInstant(zone)?.toOffsetDateTime(),
        coverage = coverage.copy(incompleteMonths = coverage.incompleteMonths.map { month -> month.copy(
            range = month.range.local(), dataFrom = month.dataFrom.atZoneSameInstant(zone).toOffsetDateTime(),
            dataTo = month.dataTo.atZoneSameInstant(zone).toOffsetDateTime(), gaps = month.gaps.map { it.local() },
        ) }),
    )
}
