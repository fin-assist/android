package ru.finassist.pf.core.common.availability

import ru.finassist.pf.core.common.error.AppError
import ru.finassist.pf.core.common.error.ErrorContext
import ru.finassist.pf.core.common.error.ErrorResponseInfo
import ru.finassist.pf.core.common.time.ElapsedClock
import ru.finassist.pf.core.common.time.ResponseTiming
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ServiceGateRecoveryTest {
    @Test fun `known correction uses the original receipt and the gate minimum`() {
        val error = AppError.Maintenance(null, 10, ErrorResponseInfo("MAINTENANCE", 503,
            requestRetryable = false, timing = ResponseTiming(receivedElapsedMillis = 1000)))
        val clock = ElapsedClock { 1500 }
        assertEquals(14_500L, ServiceGate.recoveryDeadline(error, clock)!!.remainingMillis(clock))
    }
    @Test fun `fresh legacy maintenance uses the existing sixty second default`() {
        val clock = ElapsedClock { 1000 }
        assertEquals(60_000L, ServiceGate.recoveryDeadline(AppError.Maintenance(null, null), clock)!!.remainingMillis(clock))
    }
    @Test fun `update and nonrequest contexts never grant an automatic correction`() {
        val clock = ElapsedClock { 1000 }
        assertNull(ServiceGate.recoveryDeadline(AppError.UpdateRequired(), clock))
        assertNull(ServiceGate.recoveryDeadline(AppError.RateLimited(null, 10), clock))
        for (context in listOf(ErrorContext.GENERIC_EVENT, ErrorContext.RESULT_EVENT)) {
            assertNull(ServiceGate.recoveryDeadline(AppError.Maintenance(null, 15,
                ErrorResponseInfo("MAINTENANCE", 503, context = context)), clock))
        }
    }
    @Test fun `a lost receipt anchor is not restarted from the callback`() {
        assertNull(ServiceGate.recoveryDeadline(AppError.Maintenance(null, 15,
            ErrorResponseInfo("MAINTENANCE", 503, timing = ResponseTiming(receivedElapsedMillis = 2000))), ElapsedClock { 1000 }))
    }
}
