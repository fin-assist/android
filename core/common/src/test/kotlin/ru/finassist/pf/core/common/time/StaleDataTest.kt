package ru.finassist.pf.core.common.time

import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StaleDataTest {
    private val moscow = ZoneId.of("Europe/Moscow")
    private val vladivostok = ZoneId.of("Asia/Vladivostok")
    private val today = LocalDate.of(2026, 10, 12)

    private fun t(s: String) = OffsetDateTime.parse(s)

    /** Labels use non-breaking spaces; compare them as plain ones. */
    private fun banner(stale: Boolean, dataTo: String?, lastOperationAt: String?, zone: ZoneId = moscow, today: LocalDate = this.today) =
        StaleData.banner(stale, dataTo?.let(::t), lastOperationAt?.let(::t), zone, today)?.replace(' ', ' ')

    @Test fun `the banner names the day before data_to`() = assertEquals(
        "Данные в приложении по 25 сентября — с тех пор прошло больше двух недель",
        banner(stale = true, dataTo = "2026-09-26T00:00:00+03:00", lastOperationAt = "2026-09-25T21:40:00+03:00"),
    )

    @Test fun `the statement's end wins over an earlier last operation`() {
        // The statement runs to 20 September, the last operation is on the 15th: the banner says where the data ends.
        assertEquals(
            "Данные в приложении по 20 сентября — с тех пор прошло больше двух недель",
            banner(stale = true, dataTo = "2026-09-21T00:00:00+03:00", lastOperationAt = "2026-09-15T12:00:00+03:00"),
        )
    }

    @Test fun `without data_to an older server's last operation dates the banner`() = assertEquals(
        "Данные в приложении по 15 сентября — с тех пор прошло больше двух недель",
        banner(stale = true, dataTo = null, lastOperationAt = "2026-09-15T12:00:00+03:00"),
    )

    @Test fun `data_to is read in the profile zone, not in its own offset`() {
        // Start of 26 September in Vladivostok, sent in UTC: the 25th there is the last covered day. In Moscow that
        // moment is 17:00 on the 25th, so the data covers the 25th there too.
        val dataTo = t("2026-09-25T14:00:00Z")
        assertEquals(LocalDate.of(2026, 9, 25), StaleData.lastDay(dataTo, null, vladivostok))
        assertEquals(LocalDate.of(2026, 9, 25), StaleData.lastDay(dataTo, null, moscow))
    }

    @Test fun `an inclusive moment inside the day gives that day`() {
        // A server sending `data_to` as the last moment with data (like `period.data_to`) still reads as the 25th.
        assertEquals(LocalDate.of(2026, 9, 25), StaleData.lastDay(t("2026-09-25T21:40:00+03:00"), null, moscow))
    }

    @Test fun `the last operation of the fallback is dated in the profile zone`() {
        // 23:30 in Moscow on the 25th is already the 26th in Vladivostok.
        val last = t("2026-09-25T20:30:00Z")
        assertEquals(LocalDate.of(2026, 9, 25), StaleData.lastDay(null, last, moscow))
        assertEquals(LocalDate.of(2026, 9, 26), StaleData.lastDay(null, last, vladivostok))
    }

    @Test fun `neither date known gives no day`() = assertNull(StaleData.lastDay(null, null, moscow))

    @Test fun `fresh data has no banner`() =
        assertNull(banner(stale = false, dataTo = "2026-09-26T00:00:00+03:00", lastOperationAt = "2026-09-25T21:40:00+03:00"))

    @Test fun `no operation at all has no banner even with data_to`() {
        // Only empty statements: the screen shows its empty state instead (api.md 4.1).
        assertNull(banner(stale = true, dataTo = "2026-09-26T00:00:00+03:00", lastOperationAt = null))
        assertNull(banner(stale = true, dataTo = null, lastOperationAt = null))
    }

    @Test fun `a day of another year names its year`() = assertEquals(
        "Данные в приложении по 31 декабря 2025 — с тех пор прошло больше двух недель",
        banner(stale = true, dataTo = "2026-01-01T00:00:00+03:00", lastOperationAt = "2025-12-30T10:00:00+03:00", today = LocalDate.of(2026, 2, 1)),
    )
}
