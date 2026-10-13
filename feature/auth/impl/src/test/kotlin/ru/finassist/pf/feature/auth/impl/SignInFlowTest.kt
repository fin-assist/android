package ru.finassist.pf.feature.auth.impl

import ru.finassist.pf.core.api.model.PhoneVerification
import ru.finassist.pf.feature.auth.impl.domain.SignInFlow
import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SignInFlowTest {
    @Test fun `reset drops the one-time secrets and keeps the number`() {
        val flow = SignInFlow().apply {
            phone = "+79161234567"
            verification = PhoneVerification("vt_1", "+78000000000", OffsetDateTime.parse("2026-10-13T12:03:00+03:00"))
            registrationToken = "reg_1"
            phoneRequestKey = "key_1"
        }
        flow.reset()
        assertNull(flow.verification)
        assertNull(flow.registrationToken)
        assertNull(flow.phoneRequestKey)
        assertEquals("+79161234567", flow.phone)
    }
}
