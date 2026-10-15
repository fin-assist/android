package ru.finassist.pf.mock

import kotlinx.coroutines.runBlocking
import ru.finassist.pf.core.common.availability.ClientVersion
import ru.finassist.pf.core.common.availability.ServiceGate
import ru.finassist.pf.core.common.error.AppError
import ru.finassist.pf.core.common.time.SystemClock
import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/** The fake server answers like the real one about the client version and maintenance (FIN-20, FIN-22). */
class MockServiceGateTest {
    private val gate = ServiceGate()
    private fun backend(config: MockConfig) =
        MockBackend(config.copy(networkDelayMs = 0), SystemClock(), clientVersion = ClientVersion("0.2.7", 41), gate = gate).also { it.ready.complete(Unit) }

    @Test fun `a version below the minimum is told to update, an equal one passes`() = runBlocking<Unit> {
        backend(MockConfig(minVersion = "0.2.7")).simulateNetwork()
        assertEquals(ServiceGate.State.Open, gate.state.value)
        assertFailsWith<AppError.UpdateRequired> { backend(MockConfig(minVersion = "0.3.0")).simulateNetwork() }
        assertEquals(ServiceGate.State.UpdateRequired, gate.state.value)
    }

    @Test fun `maintenance comes first and ends with the first request after it`() = runBlocking<Unit> {
        val until = OffsetDateTime.parse("2026-10-15T14:30:00+03:00")
        val b = backend(MockConfig(maintenance = true, maintenanceUntil = until, minVersion = "9.0.0"))
        val e = assertFailsWith<AppError.Maintenance> { b.simulateNetwork() }
        assertEquals(until, e.until)
        assertEquals(until, assertIs<ServiceGate.State.Maintenance>(gate.state.value).until)

        b.config = b.config.copy(maintenance = false, minVersion = null)
        b.simulateNetwork()
        assertEquals(ServiceGate.State.Open, gate.state.value)
    }
}
