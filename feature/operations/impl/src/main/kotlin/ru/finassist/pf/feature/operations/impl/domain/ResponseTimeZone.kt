package ru.finassist.pf.feature.operations.impl.domain

import java.time.ZoneId
import ru.finassist.pf.core.api.model.OperationsList
import ru.finassist.pf.core.common.time.SourceTimeZone

internal fun OperationsList.sourceZone(): ZoneId? {
    val dated = items.isNotEmpty() || !summary.isNullOrEmpty() || range != null || nextBefore != null ||
        state?.let { it.lastOperationAt != null || it.lastUploadedAt != null || it.dataTo != null } == true
    return if (dated) SourceTimeZone.require(timeZone) else null
}
