package ru.finassist.pf.feature.statements.impl.domain

import java.time.LocalDate
import ru.finassist.pf.core.api.model.Upload
import ru.finassist.pf.core.api.model.UploadStatus
import ru.finassist.pf.core.common.time.RussianDates

/** The API supplies inclusive profile days; never interpret them as moments in the device zone. */
internal fun uploadPeriodLabel(upload: Upload): String? {
    if (upload.status == UploadStatus.PROCESSING) return null
    val from = upload.periodFrom ?: return null
    val to = upload.periodTo ?: return null
    return runCatching { RussianDates.dayRange(LocalDate.parse(from), LocalDate.parse(to)) }.getOrNull()
}
