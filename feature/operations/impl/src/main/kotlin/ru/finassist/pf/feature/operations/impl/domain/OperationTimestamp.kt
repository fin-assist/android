package ru.finassist.pf.feature.operations.impl.domain

import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/** Keep the source offset and subsecond precision when returning a server timestamp (api.md 4.2–4.3). */
internal object OperationTimestamp {
    fun OffsetDateTime.toWire(): String = format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
}
