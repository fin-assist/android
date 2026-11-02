package ru.finassist.pf.feature.statements.impl.domain

import java.time.LocalDate
import java.time.OffsetDateTime
import org.junit.Test
import ru.finassist.pf.core.api.model.ImportConfig
import ru.finassist.pf.core.common.error.AppError
import ru.finassist.pf.core.common.time.DateRange
import ru.finassist.pf.core.common.time.SourceTimeZone
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DownloadPeriodTest {
    private fun config(zone: String?, from: String, to: String) = ImportConfig(
        downloadUrl = "https://bank.example/server-link?keep=this", loginUrl = "https://bank.example/login", maxFileSizeBytes = 1,
        acceptedFormats = listOf("OFX"), suggestedPeriod = DateRange(OffsetDateTime.parse(from), OffsetDateTime.parse(to)), timeZone = zone,
    )

    @Test fun `range matches exact source-zone starts across historical gaps and skipped dates`() {
        for ((zone, first, last, start, end) in listOf(
            listOf("America/Toronto", "1919-03-31", "1919-03-31", "1919-03-31T04:30:00Z", "1919-04-01T04:00:00Z"),
            listOf("Pacific/Apia", "2011-12-30", "2011-12-31", "2011-12-30T10:00:00Z", "2011-12-31T10:00:00Z"),
            listOf("Europe/Moscow", "1900-01-01", "1900-01-01", "1899-12-31T21:29:43Z", "1900-01-01T21:29:43Z"),
            listOf("America/New_York", "2024-03-10", "2024-03-10", "2024-03-10T05:00:00Z", "2024-03-11T04:00:00Z"),
        )) {
            val period = DownloadPeriod(LocalDate.parse(first), LocalDate.parse(last))
            val response = config(zone, start, end)
            assertEquals(zone, period.validateResponse(response).id)
            assertEquals("https://bank.example/server-link?keep=this", response.downloadUrl)
            assertFailsWith<AppError.Server> { period.validateResponse(response.copy(suggestedPeriod = response.suggestedPeriod.copy(to = response.suggestedPeriod.to.minusSeconds(1)))) }
        }
    }

    @Test fun `inclusive single day is valid but future reversed and year zero are rejected`() {
        val today = LocalDate.parse("2026-11-01")
        assertTrue(DownloadPeriod(today, today).valid(today))
        assertFalse(DownloadPeriod(today, today.plusDays(1)).valid(today))
        assertFalse(DownloadPeriod(today, today.minusDays(1)).valid(today))
        assertFalse(DownloadPeriod(LocalDate.of(0,1,1), today).valid(today))
    }

    @Test fun `missing zone is a server defect while unknown local zone is explicit incompatibility`() {
        val period = DownloadPeriod(LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-01"))
        assertFailsWith<AppError.Server> { period.validateResponse(config(null, "2026-10-01T00:00:00Z", "2026-10-02T00:00:00Z")) }
        val unknown = assertFailsWith<AppError.Api> { period.validateResponse(config("Future/Valid_Zone", "2026-10-01T00:00:00Z", "2026-10-02T00:00:00Z")) }
        assertEquals(SourceTimeZone.UNSUPPORTED, unknown.code)
        assertFailsWith<AppError.Server> {
            DownloadPeriod(LocalDate.parse("2011-12-30"), LocalDate.parse("2011-12-30")).validateResponse(config("Pacific/Apia", "2011-12-30T10:00:00Z", "2011-12-30T10:00:00Z"))
        }
    }
}
