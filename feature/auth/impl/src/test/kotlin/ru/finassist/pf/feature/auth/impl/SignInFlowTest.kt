package ru.finassist.pf.feature.auth.impl

import ru.finassist.pf.core.api.model.PhoneVerification
import ru.finassist.pf.feature.auth.impl.domain.SignInFlow
import java.time.Instant
import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SignInFlowTest {
    @Test fun `reset drops the one-time secrets and keeps the number`() {
        val flow = SignInFlow().apply {
            phone = "+79161234567"
            verificationReceived(
                PhoneVerification("vt_1", "+78000000000", OffsetDateTime.parse("2026-10-13T12:03:00+03:00"), expiresIn = 300),
                Instant.parse("2026-10-13T09:00:00Z"),
            )
            registrationToken = "reg_1"
            phoneRequestKey = "key_1"
        }
        assertEquals(Instant.parse("2026-10-13T09:05:00Z"), flow.callDeadline)
        flow.reset()
        assertNull(flow.verification)
        assertNull(flow.callDeadline)
        assertNull(flow.registrationToken)
        assertNull(flow.phoneRequestKey)
        assertEquals("+79161234567", flow.phone)
    }
}
