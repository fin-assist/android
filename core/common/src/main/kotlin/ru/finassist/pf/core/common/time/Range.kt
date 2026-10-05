package ru.finassist.pf.core.common.time

import java.time.OffsetDateTime

/** Half-open interval `[from, to)`, as in the API. */
data class Range(val from: OffsetDateTime, val to: OffsetDateTime) {
    operator fun contains(t: OffsetDateTime): Boolean = !t.isBefore(from) && t.isBefore(to)
}
