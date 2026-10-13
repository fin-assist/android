package ru.finassist.pf.core.common.time

import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals

class ClockTest {
    @Test fun `today is the date of now in the clock's zone`() {
        // 22:30 UTC is already the next day in Moscow.
        val clock = ShiftedClock(OffsetDateTime.parse("2026-10-07T22:30:00Z"), ZoneId.of("Europe/Moscow")) { Instant.parse("2026-10-13T10:00:00Z") }
        assertEquals(LocalDate.of(2026, 10, 8), clock.today())
    }

    @Test fun `a shifted clock keeps its date, not the device's`() {
        val clock = ShiftedClock(OffsetDateTime.parse("2026-10-08T12:00:00+03:00"), Clock.MOSCOW) { Instant.parse("2026-10-13T10:00:00Z") }
        assertEquals(LocalDate.of(2026, 10, 8), clock.today())
    }
}
