package ru.finassist.pf.core.common

import org.junit.Test
import ru.finassist.pf.core.common.time.Clock
import ru.finassist.pf.core.common.time.ShiftedClock
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import kotlin.test.assertEquals

class ShiftedClockTest {
    @Test
    fun `starts at the pinned moment and advances with real time`() {
        var real = Instant.parse("2031-01-01T00:00:00Z")
        val start = OffsetDateTime.parse("2026-10-08T12:00:00+03:00")
        val clock = ShiftedClock(start, Clock.MOSCOW) { real }

        assertEquals(start.toInstant(), clock.now().toInstant())
        assertEquals(Clock.MOSCOW, clock.now().atZoneSameInstant(Clock.MOSCOW).zone)

        real = real.plus(Duration.ofMinutes(90))
        assertEquals(OffsetDateTime.parse("2026-10-08T13:30:00+03:00").toInstant(), clock.now().toInstant())
    }
}
