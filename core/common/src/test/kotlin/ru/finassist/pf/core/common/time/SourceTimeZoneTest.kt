package ru.finassist.pf.core.common.time

import ru.finassist.pf.core.common.error.AppError
import java.time.OffsetDateTime
import kotlin.test.*

class SourceTimeZoneTest {
    @Test fun `historical civil day follows the source IANA rules rather than the timestamp offset`() {
        val instant = OffsetDateTime.parse("1899-12-31T21:29:43Z")
        assertEquals("1900-01-01", instant.atZoneSameInstant(SourceTimeZone.require("Europe/Moscow")).toLocalDate().toString())
    }
    @Test fun `missing or malformed zones fail and an unknown local zone has a safe explicit notice`() {
        for (value in listOf(null, "", " ", " Europe/Moscow", "+03:00")) assertFailsWith<AppError.Server> { SourceTimeZone.require(value) }
        val error = assertFailsWith<AppError.Api> { SourceTimeZone.require("Future/Uninstalled_Zone") }
        assertEquals(SourceTimeZone.UNSUPPORTED, error.rawCode)
        assertEquals(SourceTimeZone.MESSAGE, error.presentation.textOrFallback("fallback"))
        assertEquals("Europe/Moscow", SourceTimeZone.require("Europe/Moscow").id)
    }
}
