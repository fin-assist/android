package ru.finassist.pf.mock

import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import ru.finassist.pf.core.common.availability.ClientVersion
import ru.finassist.pf.core.common.availability.ServiceGate
import ru.finassist.pf.core.common.error.AppError
import ru.finassist.pf.core.common.error.ErrorPresentation
import ru.finassist.pf.core.common.error.ErrorSeverity
import ru.finassist.pf.core.common.error.UserMessage
import ru.finassist.pf.core.common.time.SystemClock
import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/** The fake server answers like the real one about the client version and maintenance (FIN-20, FIN-22). */
class MockServiceGateTest {
    private val gate = ServiceGate()
    private val updatePresentation = ErrorPresentation(
        UserMessage.from("Обновите приложение, чтобы продолжить"), ErrorSeverity.ERROR,
    )
    private fun backend(config: MockConfig) =
        MockBackend(config.copy(networkDelayMs = 0), SystemClock(), clientVersion = ClientVersion("0.2.7", 41), gate = gate).also { it.ready.complete(Unit) }

    @Test fun `a version below the minimum is told to update, an equal one passes`() = runBlocking<Unit> {
        backend(MockConfig(minVersion = "0.2.7")).simulateNetwork()
        assertEquals(ServiceGate.State.Open, gate.state.value)
        val error = assertFailsWith<AppError.UpdateRequired> { backend(MockConfig(minVersion = "0.3.0")).simulateNetwork() }
        assertEquals(updatePresentation, error.presentation)
        assertEquals(ServiceGate.State.UpdateRequired(presentation = updatePresentation), gate.state.value)
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

    @Test fun `a stream opening is checked like a request, re-opens included`() = runBlocking<Unit> {
        val b = backend(MockConfig(maintenance = true, minVersion = "9.0.0"))
        val opening = b.droppable(flowOf(1, 2)) { it == 2 }
        assertFailsWith<AppError.Maintenance> { opening.toList() }
        assertIs<ServiceGate.State.Maintenance>(gate.state.value)

        b.config = b.config.copy(maintenance = false)
        // The same flow collected again is the next opening: now the version.
        val error = assertFailsWith<AppError.UpdateRequired> { opening.toList() }
        assertEquals(updatePresentation, error.presentation)
        assertEquals(ServiceGate.State.UpdateRequired(presentation = updatePresentation), gate.state.value)
    }

    @Test fun `an opening that passes ends the maintenance the gate shows`() = runBlocking<Unit> {
        val b = backend(MockConfig(maintenance = true))
        assertFailsWith<AppError.Maintenance> { b.simulateNetwork() }
        b.config = b.config.copy(maintenance = false)
        assertEquals(listOf(1, 2), b.droppable(flowOf(1, 2)) { it == 2 }.toList())
        assertEquals(ServiceGate.State.Open, gate.state.value)
    }

    @Test fun `an open stream is cut at its next event when maintenance starts`() = runBlocking<Unit> {
        val b = backend(MockConfig())
        val events = b.droppable(
            flow {
                emit(1)
                b.config = b.config.copy(maintenance = true)
                emit(2)
                emit(3)
            },
        ) { it == 3 }.toList()
        // A drop: no terminal event; the client re-opens and meets the 503.
        assertEquals(listOf(1), events)
        assertFailsWith<AppError.Maintenance> { b.droppable(flowOf(3)) { true }.toList() }
    }
}
