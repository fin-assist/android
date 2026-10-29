package ru.finassist.pf.feature.auth.impl

import ru.finassist.pf.core.common.error.AppError
import ru.finassist.pf.core.common.error.ErrorResponseInfo
import ru.finassist.pf.core.common.time.ElapsedClock
import ru.finassist.pf.core.common.time.ResponseTiming
import ru.finassist.pf.feature.auth.impl.domain.PhoneStartGate
import kotlin.test.*

class PhoneStartGateTest {
    private var elapsed = 100L
    private val gate = PhoneStartGate(ElapsedClock { elapsed })
    @Test fun `received 429 waits then enables only a new intent regardless of old replay permission`() {
        gate.record(AppError.RateLimited(null, 60, ErrorResponseInfo("RATE_LIMITED", 429,
            requestRetryable = false, retryAfterSeconds = 60, timing = ResponseTiming(receivedElapsedMillis = elapsed))))
        assertTrue(gate.newIntentRequired); assertFalse(gate.isReady())
        elapsed += 59_999; assertFalse(gate.isReady())
        elapsed++; assertTrue(gate.isReady()); assertTrue(gate.newIntentRequired)
        gate.clear(); assertFalse(gate.newIntentRequired)
    }
    @Test fun `missing rate limit timing cannot be replaced by a client timeout`() {
        gate.record(AppError.RateLimited(java.time.OffsetDateTime.parse("2026-10-28T12:00:00Z")))
        elapsed += 1_000_000
        assertFalse(gate.isReady())
    }
    @Test fun `lost response leaves the original intent and explicit non429 refusal remains forbidden`() {
        gate.record(AppError.Offline())
        assertTrue(gate.isReady()); assertFalse(gate.newIntentRequired)
        gate.record(AppError.Server(500, response = ErrorResponseInfo("INTERNAL_ERROR", 500, requestRetryable = false)))
        assertFalse(gate.isReady()); assertFalse(gate.newIntentRequired)
    }
}
