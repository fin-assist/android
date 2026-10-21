package ru.finassist.pf.feature.statements.impl.ui

import org.junit.Test
import ru.finassist.pf.core.api.model.UploadStatus
import ru.finassist.pf.feature.statements.impl.upload
import java.util.TimeZone
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UploadPeriodTest {
    @Test fun `profile calendar days are unchanged by the device timezone`() {
        val original = TimeZone.getDefault()
        try {
            for (zone in listOf("Pacific/Kiritimati", "America/Los_Angeles", "Europe/Moscow")) {
                TimeZone.setDefault(TimeZone.getTimeZone(zone))
                val row = upload("u").copy(periodFrom = "2026-09-01", periodTo = "2026-09-30")
                assertEquals("1–30\u00a0сентября", uploadPeriodLabel(row), zone)
            }
        } finally { TimeZone.setDefault(original) }
    }

    @Test fun `absent incomplete malformed and processing periods have no label`() {
        val row = upload("u")
        assertNull(uploadPeriodLabel(row))
        assertNull(uploadPeriodLabel(row.copy(periodFrom = "2026-09-01")))
        assertNull(uploadPeriodLabel(row.copy(periodTo = "2026-09-30")))
        assertNull(uploadPeriodLabel(row.copy(periodFrom = "invalid", periodTo = "2026-09-30")))
        assertNull(uploadPeriodLabel(row.copy(status = UploadStatus.PROCESSING, periodFrom = "2026-09-01", periodTo = "2026-09-30")))
    }

    @Test fun `one day and year boundary stay inclusive`() {
        assertEquals("25\u00a0сентября", uploadPeriodLabel(upload("u").copy(periodFrom = "2026-09-25", periodTo = "2026-09-25")))
        assertEquals("31\u00a0декабря 2025 — 1\u00a0января 2026", uploadPeriodLabel(upload("u").copy(periodFrom = "2025-12-31", periodTo = "2026-01-01")))
    }
}
