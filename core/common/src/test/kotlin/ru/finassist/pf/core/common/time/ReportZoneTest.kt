package ru.finassist.pf.core.common.time

import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReportZoneTest {
    private val vladivostok = ZoneId.of("Asia/Vladivostok")
    private val zone = ReportZone { Clock.MOSCOW }

    @Test fun `the device zone until the profile zone is known`() {
        assertEquals(Clock.MOSCOW, zone.current)
        assertNull(zone.profile.value)
    }

    @Test fun `the profile zone once set, the device zone again after clear`() {
        zone.set("Asia/Vladivostok")
        assertEquals(vladivostok, zone.current)
        assertEquals(vladivostok, zone.profile.value)

        zone.clear()
        assertEquals(Clock.MOSCOW, zone.current)
    }

    @Test fun `an unknown zone id keeps the last known zone`() {
        zone.set("Asia/Vladivostok")
        zone.set("Mars/Olympus_Mons")
        assertEquals(vladivostok, zone.current)
    }

    @Test fun `a clock over the report zone moves to the profile zone when it is set`() {
        // 23:30 in Moscow is already the next day in Vladivostok.
        val clock = ShiftedClock(OffsetDateTime.parse("2026-10-08T23:30:00+03:00"), zone::current) { Instant.parse("2031-01-01T00:00:00Z") }
        assertEquals(LocalDate.of(2026, 10, 8), clock.today())

        zone.set("Asia/Vladivostok")
        assertEquals(vladivostok, clock.zone)
        assertEquals(LocalDate.of(2026, 10, 9), clock.today())
    }

    @Test fun `withZone keeps the instant and moves the date`() {
        val clock = ShiftedClock(OffsetDateTime.parse("2026-10-08T23:30:00+03:00"), Clock.MOSCOW) { Instant.parse("2031-01-01T00:00:00Z") }
        val shown = clock.withZone(vladivostok)
        assertEquals(clock.now().toInstant(), shown.now().toInstant())
        assertEquals(vladivostok, shown.zone)
        assertEquals(LocalDate.of(2026, 10, 9), shown.today())
    }
}
