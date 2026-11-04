package ru.finassist.pf.feature.statements.impl.domain

import org.junit.Test
import java.time.OffsetDateTime
import ru.finassist.pf.core.api.model.IncompleteMonth
import ru.finassist.pf.core.common.error.AppError
import ru.finassist.pf.core.common.time.DateRange
import ru.finassist.pf.feature.statements.impl.importResult
import kotlin.test.*

class ImportResultZoneTest {
    @Test fun `coverage and operation moments use snapshot zone and keep calendar keys unchanged`() {
        fun t(s: String) = OffsetDateTime.parse(s)
        val start = t("2026-08-31T14:00:00Z")
        val end = t("2026-09-30T14:00:00Z")
        val original = importResult().copy(timeZone = "Asia/Vladivostok", firstOperationAt = start, lastOperationAt = end.minusSeconds(1))
        val result = original.copy(coverage = original.coverage.copy(incompleteMonths = listOf(
            IncompleteMonth("2026-09", DateRange(start, end), start, end.minusSeconds(1), listOf(DateRange(start, end)), false),
        ))).inSourceZone()
        assertEquals("2026-09-01", result.firstOperationAt!!.toLocalDate().toString())
        assertEquals(start.toInstant(), result.firstOperationAt!!.toInstant())
        val month = result.coverage.incompleteMonths.single()
        assertEquals("2026-09", month.month)
        assertEquals("2026-09-01", month.range.from.toLocalDate().toString())
        assertEquals("2026-10-01", month.range.to.toLocalDate().toString())
        assertEquals("2026-09-30", month.dataTo.toLocalDate().toString())
        assertEquals(month.range, month.gaps.single())
        assertEquals("2026-08-31", original.firstOperationAt!!.toLocalDate().toString())
    }
    @Test fun `unknown zone is never interpreted from offset or profile`() {
        assertFailsWith<AppError.Api> { importResult().copy(timeZone = "Area/Future_City").inSourceZone() }
        assertFailsWith<AppError.Server> { importResult().copy(timeZone = null).inSourceZone() }
    }
}
