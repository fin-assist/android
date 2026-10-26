package ru.finassist.pf.core.common.time

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServerDeadlineTest {
    @Test fun `relative duration needs no Date and elapsed includes time away`() {
        val deadline = ResponseTiming(receivedElapsedMillis = 1000).deadline(durationSeconds = 60)!!
        assertEquals(10_000L, deadline.remainingMillis(ElapsedClock { 51_000 }))
        assertEquals(0L, deadline.remainingMillis(ElapsedClock { 90_000 }))
        assertNull(deadline.remainingMillis(ElapsedClock { 0 }))
    }
    @Test fun `datetime uses same response Date never device wall time`() {
        val date = Instant.parse("2026-10-25T12:00:00Z")
        val timing = ResponseTiming(date, 1000)
        assertEquals(9_000L, timing.deadline(target = date.plusSeconds(10))!!.remainingMillis(ElapsedClock { 2000 }))
        assertEquals(59_000L, timing.deadline(durationSeconds = 60, target = date.plusSeconds(10))!!.remainingMillis(ElapsedClock { 2000 }))
        assertNull(ResponseTiming(receivedElapsedMillis = 1000).deadline(target = date))
        assertNull(ResponseTiming(date).deadline(durationSeconds = 10))
    }
    @Test fun `replay window rejects exact expiry and a lost anchor`() {
        val window = RequestReplayWindow(1000, 60_000)
        assertTrue(window.isOpen(ElapsedClock { 1001 }))
        assertFalse(window.isOpen(ElapsedClock { 61_000 }))
        assertFalse(window.isOpen(ElapsedClock { 0 }))
    }
}
