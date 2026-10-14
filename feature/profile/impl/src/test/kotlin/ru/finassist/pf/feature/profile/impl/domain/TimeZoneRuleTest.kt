package ru.finassist.pf.feature.profile.impl.domain

import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class TimeZoneRuleTest {
    private val t0 = Instant.parse("2026-10-14T09:00:00Z")
    private val profile = "Europe/Moscow"
    private val trip = "Asia/Novosibirsk"

    @Test fun `the profile zone on the device clears the record`() {
        assertEquals(TimeZoneStep.Clear, TimeZoneRule.next(profile, profile, null, t0))
        assertEquals(TimeZoneStep.Clear, TimeZoneRule.next(profile, profile, PendingZone(trip, t0), t0.plus(Duration.ofDays(5))))
    }

    @Test fun `a new zone starts the count`() {
        assertEquals(TimeZoneStep.Record(PendingZone(trip, t0)), TimeZoneRule.next(profile, trip, null, t0))
    }

    @Test fun `another zone starts the count over`() {
        val pending = PendingZone("Asia/Yekaterinburg", t0)
        val now = t0.plus(Duration.ofDays(4))
        assertEquals(TimeZoneStep.Record(PendingZone(trip, now)), TimeZoneRule.next(profile, trip, pending, now))
    }

    @Test fun `the zone is sent at 72 hours, not a second earlier`() {
        val pending = PendingZone(trip, t0)
        assertEquals(TimeZoneStep.Wait, TimeZoneRule.next(profile, trip, pending, t0.plus(Duration.ofHours(72)).minusSeconds(1)))
        assertEquals(TimeZoneStep.Send(trip), TimeZoneRule.next(profile, trip, pending, t0.plus(Duration.ofHours(72))))
        assertEquals(TimeZoneStep.Send(trip), TimeZoneRule.next(profile, trip, pending, t0.plus(Duration.ofDays(30))))
    }

    @Test fun `a clock set back before the record starts the count over`() {
        val now = t0.minusSeconds(60)
        assertEquals(TimeZoneStep.Record(PendingZone(trip, now)), TimeZoneRule.next(profile, trip, PendingZone(trip, t0), now))
    }
}
