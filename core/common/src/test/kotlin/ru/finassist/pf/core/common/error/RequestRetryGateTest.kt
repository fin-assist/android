package ru.finassist.pf.core.common.error

import java.time.Instant
import java.time.OffsetDateTime
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import ru.finassist.pf.core.common.time.ElapsedClock
import ru.finassist.pf.core.common.time.ResponseTiming
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RequestRetryGateTest {
    @Test fun `relative wait survives taps and recollection without restarting`() = runTest {
        val gate = RequestRetryGate(ElapsedClock { testScheduler.currentTime })
        gate.record(AppError.Api("FUTURE", 429, response = ErrorResponseInfo("FUTURE", 429,
            requestRetryable = true, retryAfterSeconds = 3, timing = ResponseTiming(receivedElapsedMillis = 0))))
        assertFalse(gate.isReady())
        assertFalse(gate.isExplicitlyForbidden(), "a permitted wait is distinct from an explicit refusal")
        advanceTimeBy(1000)
        assertEquals(2000L, gate.remainingMillis())
        val updates = async { gate.readiness.take(2).toList() }
        runCurrent()
        advanceTimeBy(2000)
        runCurrent()
        assertEquals(listOf(false, true), updates.await())
        assertTrue(gate.isReady())
    }

    @Test fun `false dominates an expired wait and only explicit context change clears it`() {
        var elapsed = 0L
        val gate = RequestRetryGate(ElapsedClock { elapsed })
        gate.record(AppError.InProgress(1, ErrorResponseInfo("REQUEST_IN_PROGRESS", 409,
            requestRetryable = false, timing = ResponseTiming(receivedElapsedMillis = 0))))
        elapsed = 10000
        assertFalse(gate.isReady())
        assertTrue(gate.isExplicitlyForbidden(), "the explicit refusal survives the wait")
        gate.clear()
        assertTrue(gate.isReady())
        assertFalse(gate.isExplicitlyForbidden())
    }

    @Test fun `Date gives approximate wait and lost anchor never grants a retry`() {
        var elapsed = 100L
        val gate = RequestRetryGate(ElapsedClock { elapsed })
        val target = OffsetDateTime.parse("2026-10-25T10:00:05Z")
        gate.record(AppError.RateLimited(target, response = ErrorResponseInfo("RATE_LIMITED", 429,
            timing = ResponseTiming(Instant.parse("2026-10-25T10:00:00Z"), 100))))
        assertEquals(5000L, gate.remainingMillis())
        elapsed = 0 // reboot/lost monotonic anchor
        assertFalse(gate.isReady())
        gate.record(AppError.RateLimited(target)) // target without fresh Date cannot use phone wall time
        assertFalse(gate.isReady())
    }

    @Test fun `absent flag still waits but nested result advice never changes original request gate`() {
        var elapsed = 0L
        val gate = RequestRetryGate(ElapsedClock { elapsed })
        gate.record(AppError.RateLimited(null, 2))
        assertFalse(gate.isReady())
        assertFalse(gate.isExplicitlyForbidden(), "absence does not manufacture a prohibition")
        elapsed = 2000
        assertTrue(gate.isReady())
        gate.record(AppError.Api("MAINTENANCE", 503, response = ErrorResponseInfo("MAINTENANCE", 503,
            requestRetryable = false, context = ErrorContext.RESULT_EVENT)))
        assertTrue(gate.isReady())
    }
    @Test fun `wait readiness ticks for a forbidden original without granting original replay`() = runTest {
        val gate = RequestRetryGate(ElapsedClock { testScheduler.currentTime })
        gate.record(AppError.RateLimited(null, 1, ErrorResponseInfo("RATE_LIMITED", 429,
            requestRetryable = false, retryAfterSeconds = 1, timing = ResponseTiming(receivedElapsedMillis = 0))))
        val updates = async { gate.waitReadiness.take(2).toList() }
        runCurrent(); advanceTimeBy(999); runCurrent()
        assertEquals(1L, gate.remainingMillis()); assertFalse(gate.isReady())
        advanceTimeBy(1); runCurrent()
        assertEquals(listOf(false, true), updates.await())
        assertFalse(gate.isReady()); assertTrue(gate.isExplicitlyForbidden())
    }

    @Test fun `changed input drops refusal without restarting its captured wait`() = runTest {
        val gate = RequestRetryGate(ElapsedClock { testScheduler.currentTime })
        gate.record(AppError.RateLimited(null, 3, ErrorResponseInfo("RATE_LIMITED", 429,
            requestRetryable = false, retryAfterSeconds = 3, timing = ResponseTiming(receivedElapsedMillis = 0))))
        advanceTimeBy(1000)
        gate.clearOriginalRefusal()
        assertFalse(gate.isExplicitlyForbidden())
        assertEquals(2000L, gate.remainingMillis())
        val updates = async { gate.readiness.take(2).toList() }
        runCurrent()
        advanceTimeBy(1999)
        gate.clearOriginalRefusal()
        assertFalse(gate.isReady())
        advanceTimeBy(1); runCurrent()
        assertEquals(listOf(false, true), updates.await())
    }

    @Test fun `changed input preserves Date anchored and unanchored absolute waits`() {
        var elapsed = 100L
        val gate = RequestRetryGate(ElapsedClock { elapsed })
        val target = OffsetDateTime.parse("2026-10-25T10:00:05Z")
        gate.record(AppError.RateLimited(target, response = ErrorResponseInfo("RATE_LIMITED", 429,
            requestRetryable = false, timing = ResponseTiming(Instant.parse("2026-10-25T10:00:00Z"), 100))))
        elapsed = 1100
        gate.clearOriginalRefusal()
        assertEquals(4000L, gate.remainingMillis())
        assertFalse(gate.isReady())
        gate.record(AppError.RateLimited(target, response = ErrorResponseInfo("RATE_LIMITED", 429,
            requestRetryable = false)))
        gate.clearOriginalRefusal()
        elapsed = 60000
        assertFalse(gate.isExplicitlyForbidden())
        assertFalse(gate.isReady(), "without Date the absolute wait remains unknown")
    }

    @Test fun `stale input adds only endpoint wait and cannot shorten a captured restriction`() {
        var elapsed = 0L
        val gate = RequestRetryGate(ElapsedClock { elapsed })
        gate.record(AppError.RateLimited(null, 30, ErrorResponseInfo("RATE_LIMITED", 429,
            requestRetryable = false, retryAfterSeconds = 30, timing = ResponseTiming(receivedElapsedMillis = 0))))
        elapsed = 1000
        gate.recordEndpointWait(AppError.RateLimited(null, 5))
        assertTrue(gate.isExplicitlyForbidden(), "stale reply cannot clear the current input refusal")
        assertEquals(29000L, gate.remainingMillis())
        gate.clearOriginalRefusal()
        gate.recordEndpointWait(AppError.Offline())
        gate.recordEndpointWait(AppError.RateLimited(null, 0))
        assertEquals(29000L, gate.remainingMillis())
        gate.recordEndpointWait(AppError.RateLimited(null, 60, ErrorResponseInfo("RATE_LIMITED", 429,
            requestRetryable = false, retryAfterSeconds = 60, timing = ResponseTiming(receivedElapsedMillis = 1000))))
        assertFalse(gate.isExplicitlyForbidden(), "a different input's false does not transfer")
        assertEquals(60000L, gate.remainingMillis())
        elapsed = 60999
        assertFalse(gate.isReady())
        elapsed = 61000
        assertTrue(gate.isReady())
    }

    @Test fun `unknown endpoint wait cannot be relaxed by a later anchored or nested reply`() {
        var elapsed = 0L
        val gate = RequestRetryGate(ElapsedClock { elapsed })
        gate.recordEndpointWait(AppError.RateLimited(null, 3))
        gate.recordEndpointWait(AppError.RateLimited(OffsetDateTime.parse("2026-10-25T10:00:05Z")))
        gate.recordEndpointWait(AppError.RateLimited(null, 1))
        gate.clearOriginalRefusal()
        elapsed = 60000
        assertFalse(gate.isReady())
        gate.clear()
        gate.recordEndpointWait(AppError.Api("MAINTENANCE", 503, response = ErrorResponseInfo("MAINTENANCE", 503,
            requestRetryable = false, retryAfterSeconds = 30, context = ErrorContext.RESULT_EVENT)))
        assertTrue(gate.isReady(), "nested result advice is not an endpoint request restriction")
    }

}
