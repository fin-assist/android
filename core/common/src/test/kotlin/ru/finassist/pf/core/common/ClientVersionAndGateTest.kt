package ru.finassist.pf.core.common

import ru.finassist.pf.core.common.availability.ClientVersion
import ru.finassist.pf.core.common.availability.ServiceGate
import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClientVersionAndGateTest {
    private val now = OffsetDateTime.parse("2026-10-15T12:00:00+03:00")

    @Test fun `the header and the comparison follow api md (FIN-20)`() {
        val v = ClientVersion("0.2.5", 40)
        assertEquals("android/0.2.5+40", v.header)
        assertFalse(v.isBelow("0.2.5"), "equal passes")
        assertTrue(v.isBelow("0.2.6"))
        assertTrue(v.isBelow("0.10.0"), "numbers, not strings")
        assertFalse(ClientVersion("0.10.0", 1).isBelow("0.9.9"))
        assertFalse(v.isBelow(null))
        assertFalse(v.isBelow(""), "a blank minimum does not block")
        assertFalse(v.isBelow("0.02.0"), "a malformed minimum does not block")
        assertNull(ClientVersion.parse("1.2"))
    }

    @Test fun `maintenance retries not sooner than allowed, update is final`() {
        val gate = ServiceGate()
        gate.maintenance(null, 3, now)
        val m = assertIs<ServiceGate.State.Maintenance>(gate.state.value)
        assertEquals(now.plusSeconds(ServiceGate.MIN_RETRY_SECONDS.toLong()), m.retryAt)
        gate.maintenance(null, null, now)
        assertEquals(now.plusSeconds(ServiceGate.DEFAULT_RETRY_SECONDS.toLong()), (gate.state.value as ServiceGate.State.Maintenance).retryAt)

        gate.reopen()
        assertEquals(ServiceGate.State.Open, gate.state.value)
        assertEquals(1, gate.reopened.value)
        gate.reopen()
        assertEquals(1, gate.reopened.value, "an open gate does not reopen again")

        // Only the expected maintenance ends: a newer one set meanwhile stays.
        gate.maintenance(null, 60, now)
        val first = gate.state.value as ServiceGate.State.Maintenance
        gate.maintenance(null, 120, now)
        gate.reopen(first)
        assertIs<ServiceGate.State.Maintenance>(gate.state.value)
        gate.reopen()

        // A Remote Config requirement over maintenance: lifting it shows the maintenance again (review of #81).
        gate.maintenance(null, 60, now)
        gate.updateRequiredByConfig()
        assertEquals(ServiceGate.State.UpdateRequired(fromServer = false), gate.state.value)
        gate.configAllowsVersion()
        assertIs<ServiceGate.State.Maintenance>(gate.state.value)
        gate.reopen()

        gate.updateRequired()
        gate.maintenance(null, 60, now)
        gate.reopen()
        assertEquals(ServiceGate.State.UpdateRequired(), gate.state.value)
    }
}
