package ru.finassist.pf.mock

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.withTimeout
import org.junit.Test
import ru.finassist.pf.core.api.model.ConsentType
import ru.finassist.pf.core.common.availability.ServiceGate
import ru.finassist.pf.core.common.error.AppError
import ru.finassist.pf.core.common.time.ElapsedClock
import ru.finassist.pf.mock.api.MockAuthApi
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The opt-in server-side fixture orders actual reads; it never performs a read or changes the client's clock. */
@OptIn(ExperimentalCoroutinesApi::class)
class MockStreamRecoveryBarrierTest {
    private val pd = ConsentType.PERSONAL_DATA

    private suspend fun withBackend(gate: ServiceGate = ServiceGate(ElapsedClock { 0L }), test: suspend (MockBackend) -> Unit) {
        val backend = MockBackend(FAST, TestClock(NOW), gate = gate, elapsedClock = ElapsedClock { 0L })
        backend.ready.complete(Unit)
        try { test(backend) } finally { backend.scope.cancel() }
    }

    private suspend fun maintenance(backend: MockBackend) {
        backend.config = backend.config.copy(maintenance = true)
        assertFailsWith<AppError.Maintenance> { opening(backend) }
        backend.config = backend.config.copy(maintenance = false)
    }

    private suspend fun opening(backend: MockBackend) = backend.droppable(flowOf("done")) { true }.toList()

    @Test fun `normal streams and active maintenance do not wait for a future document read`() = runTest {
        withBackend { backend ->
            backend.consents.setStreamRecoveryReadTarget(pd, 128)
            assertEquals(listOf("done"), withTimeout(1_000) { opening(backend) })
            backend.config = backend.config.copy(maintenance = true)
            withTimeout(1_000) { assertFailsWith<AppError.Maintenance> { opening(backend) } }
            assertEquals(0, backend.consents.snapshot().personalData.reads)
        }
    }

    @Test fun `a fresh backend does not inherit the previous process barrier`() = runTest {
        withBackend { first -> first.consents.setStreamRecoveryReadTarget(pd, 128) }
        withBackend { fresh ->
            maintenance(fresh)
            assertEquals(listOf("done"), withTimeout(1_000) { opening(fresh) })
            assertEquals(0, fresh.consents.snapshot().personalData.reads)
        }
    }

    @Test fun `reset releases a waiting recovery without creating a document request`() = runTest {
        withBackend { backend ->
            backend.consents.setStreamRecoveryReadTarget(pd, 2)
            maintenance(backend)
            val pending = async(start = CoroutineStart.UNDISPATCHED) { opening(backend) }
            runCurrent()
            assertFalse(pending.isCompleted)
            backend.consents.setStreamRecoveryReadTarget(pd, 0)
            assertEquals(listOf("done"), pending.await())
            assertEquals(0, backend.consents.snapshot().personalData.reads)
        }
    }

    @Test fun `cancelling a recovery wait leaves the read counter and server state untouched`() = runTest {
        withBackend { backend ->
            val api = MockAuthApi(backend)
            api.getConsentDocument(pd)
            backend.consents.setStreamRecoveryReadTarget(pd, 2)
            maintenance(backend)
            val pending = async(start = CoroutineStart.UNDISPATCHED) { opening(backend) }
            runCurrent()
            assertFalse(pending.isCompleted)
            pending.cancelAndJoin()
            assertEquals(1, backend.consents.snapshot().personalData.reads)
            val next = async(start = CoroutineStart.UNDISPATCHED) { opening(backend) }
            runCurrent()
            assertFalse(next.isCompleted, "cancelling one collection must not disable the fixture for another")
            api.getConsentDocument(pd)
            assertEquals(listOf("done"), next.await())
            assertEquals(2, backend.consents.snapshot().personalData.reads)
        }
    }

    @Test fun `only selected real read entry releases a still blocked recovery and overshoot remains visible`() = runTest {
        withBackend { backend ->
            val api = MockAuthApi(backend)
            backend.consents.setStreamRecoveryReadTarget(pd, 2)
            maintenance(backend)
            val pending = async(start = CoroutineStart.UNDISPATCHED) { opening(backend) }
            runCurrent()
            assertFalse(pending.isCompleted)
            // Failed GET attempts still enter the counters, but cannot legitimately open ServiceGate.
            // Successful unrelated GETs are covered by the independent gate-open release regression instead.
            backend.config = backend.config.copy(offline = true)
            assertFailsWith<AppError.Offline> { api.getConsentDocument(ConsentType.ASSISTANT) }
            runCurrent()
            assertFalse(pending.isCompleted)
            assertFailsWith<AppError.Offline> { api.getConsentDocument(pd) }
            runCurrent()
            assertFalse(pending.isCompleted)
            assertFailsWith<AppError.Offline> { api.getConsentDocument(pd) }
            assertFailsWith<AppError.Offline> { api.getConsentDocument(pd) }
            backend.config = backend.config.copy(offline = false)
            assertEquals(listOf("done"), pending.await())
            assertEquals(3, backend.consents.snapshot().personalData.reads, "the fixture must not rewrite or cap evidence to its target")
        }
    }

    @Test fun `invalid bounds or scope do not release an existing read barrier`() = runTest {
        withBackend { backend ->
            backend.consents.setStreamRecoveryReadTarget(pd, 2)
            maintenance(backend)
            val pending = async(start = CoroutineStart.UNDISPATCHED) { opening(backend) }
            runCurrent()
            for (count in listOf(-1, 129)) assertFailsWith<IllegalArgumentException> { backend.consents.setStreamRecoveryReadTarget(pd, count) }
            assertFailsWith<IllegalArgumentException> { backend.consents.setStreamRecoveryReadTarget(ConsentType.ASSISTANT, 2) }
            runCurrent()
            assertFalse(pending.isCompleted)
            backend.consents.setStreamRecoveryReadTarget(pd, 0)
            assertEquals(listOf("done"), pending.await())
        }
    }

    @Test fun `an independent gate reopening releases a pending recovery without inventing a read`() = runTest {
        val gate = ServiceGate(ElapsedClock { 0L })
        withBackend(gate) { backend ->
            MockAuthApi(backend).getConsentDocument(pd)
            backend.consents.setStreamRecoveryReadTarget(pd, 2)
            maintenance(backend)
            val pending = async(start = CoroutineStart.UNDISPATCHED) { opening(backend) }
            runCurrent()
            assertFalse(pending.isCompleted)
            gate.reopen()
            runCurrent()
            assertTrue(pending.isCompleted, "an optional ordering fixture must not mask a missing probe after maintenance ended")
            assertEquals(listOf("done"), pending.await())
            assertEquals(1, backend.consents.snapshot().personalData.reads)
            assertEquals(ServiceGate.State.Open, gate.state.value)
        }
    }
}
