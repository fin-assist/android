package ru.finassist.pf.feature.auth.impl

import ru.finassist.pf.core.api.model.PhoneVerification
import ru.finassist.pf.feature.auth.impl.domain.CallDeadline
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** api.md 1.1, «Срок на устройстве»: the example there — server 22:12:00 → 22:17:00, device receives at 22:13:40. */
class CallDeadlineTest {
    private val expiresAt = OffsetDateTime.parse("2026-10-04T22:17:00+03:00")
    private val received = OffsetDateTime.parse("2026-10-04T22:13:40+03:00").toInstant()

    private fun verification(expiresIn: Int? = null, serverTime: Instant? = null) =
        PhoneVerification("vt", "+74951234567", expiresAt, expiresIn = expiresIn, serverTime = serverTime)

    @Test fun `expires_in is added to the device time of arrival, not to expires_at`() {
        val deadline = CallDeadline.of(verification(expiresIn = 300), received)
        assertEquals(OffsetDateTime.parse("2026-10-04T22:18:40+03:00").toInstant(), deadline)
        assertEquals("22:18", CallDeadline.clockText(deadline!!, ZoneId.of("Europe/Moscow")))
    }

    @Test fun `expires_in wins over the Date header`() {
        val serverTime = OffsetDateTime.parse("2026-10-04T22:16:00+03:00").toInstant()
        assertEquals(received.plusSeconds(120), CallDeadline.of(verification(expiresIn = 120, serverTime = serverTime), received))
    }

    @Test fun `zero or a negative expires_in means the wait is over on arrival`() {
        assertEquals(received, CallDeadline.of(verification(expiresIn = 0), received))
        assertEquals(received, CallDeadline.of(verification(expiresIn = -5), received))
    }

    @Test fun `without expires_in the time left is expires_at minus the server time of the Date header`() {
        val serverTime = OffsetDateTime.parse("2026-10-04T22:12:00+03:00").toInstant()
        assertEquals(received.plusSeconds(300), CallDeadline.of(verification(serverTime = serverTime), received))
    }

    @Test fun `a Date header after expires_at gives no time left, not a deadline in the past`() {
        val serverTime = OffsetDateTime.parse("2026-10-04T22:20:00+03:00").toInstant()
        assertEquals(received, CallDeadline.of(verification(serverTime = serverTime), received))
    }

    @Test fun `without expires_in and Date there is no deadline`() {
        assertNull(CallDeadline.of(verification(), received))
    }

    @Test fun `the time is shown in the device zone with the seconds dropped`() {
        val deadline = Instant.parse("2026-10-04T19:18:59Z")
        assertEquals("22:18", CallDeadline.clockText(deadline, ZoneId.of("Europe/Moscow")))
        assertEquals("00:18", CallDeadline.clockText(deadline, ZoneId.of("Asia/Yekaterinburg")))
        assertEquals("19:18", CallDeadline.clockText(deadline, ZoneId.of("UTC")))
    }
}
