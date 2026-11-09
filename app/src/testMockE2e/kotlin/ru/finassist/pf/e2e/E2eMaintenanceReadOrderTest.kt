package ru.finassist.pf.e2e

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import ru.finassist.pf.core.api.SseReopenState
import ru.finassist.pf.core.api.reopeningStream
import ru.finassist.pf.core.api.model.ConsentType
import ru.finassist.pf.core.common.availability.ServiceGate
import ru.finassist.pf.core.common.error.AppError
import ru.finassist.pf.core.common.time.Clock
import ru.finassist.pf.core.common.time.ElapsedClock
import ru.finassist.pf.feature.availability.impl.ui.AvailabilityViewModel
import ru.finassist.pf.mock.MockBackend
import ru.finassist.pf.mock.MockConfig
import ru.finassist.pf.mock.MockUiEvidence
import ru.finassist.pf.mock.api.MockAuthApi
import java.time.OffsetDateTime
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Qase46's exact read/probe witnesses against the real mock API, SSE policy and availability VM. */
@OptIn(ExperimentalCoroutinesApi::class)
class E2eMaintenanceReadOrderTest {
    @BeforeTest fun setUp() = Dispatchers.setMain(StandardTestDispatcher())
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    @Test fun `probe scheduled first preserves the exact Qase46 witnesses`() = runTest {
        verifyInterleaving(streamFirst = false)
    }

    @Test fun `stream recovery scheduled first preserves the exact Qase46 witnesses`() = runTest {
        verifyInterleaving(streamFirst = true)
    }

    @Test fun `without fixture a successful stream legitimately cancels an unstarted probe`() = runTest {
        verifyInterleaving(streamFirst = true, fence = false)
    }

    private suspend fun TestScope.verifyInterleaving(streamFirst: Boolean, fence: Boolean = true) {
        val elapsed = ElapsedClock { testScheduler.currentTime }
        val start = OffsetDateTime.parse("2026-10-15T12:00:00+03:00")
        val clock = object : Clock {
            override val zone = Clock.MOSCOW
            override fun now() = start.plusNanos(testScheduler.currentTime * 1_000_000)
        }
        val gate = ServiceGate(elapsed)
        val backend = MockBackend(MockConfig(networkDelayMs = 0, streamDrops = 1, streamRateLimits = 1,
            streamRetrySeconds = 15), clock, StandardTestDispatcher(testScheduler), gate = gate, elapsedClock = elapsed)
        backend.ready.complete(Unit)
        val auth = MockAuthApi(backend)
        var vm: AvailabilityViewModel? = null
        try {
            // The control-first fast login reads one document, without creating a Phone screen.
            auth.getConsentDocument(ConsentType.PERSONAL_DATA)
            fun opening() = backend.droppable(flowOf("progress", "done")) { it == "done" }
            assertEquals(listOf("progress"), opening().toList())
            assertFailsWith<AppError.RateLimited> { opening().toList() }
            advanceTimeBy(15_000); runCurrent()
            backend.config = backend.config.copy(maintenance = true, maintenanceRetryAfterSeconds = 15)
            val state = SseReopenState()
            fun stream() = reopeningStream(isTerminal = { it == "done" }, state = state, elapsedClock = elapsed,
                recoverOnceAfter = { ServiceGate.recoveryDeadline(it, elapsed) }, open = ::opening)
            assertFailsWith<AppError.Maintenance> { stream().toList() }
            if (fence) assertNull(E2eConsentControl(backend.consents).apply(mapOf(
                "consent_type" to listOf("personal_data"), "consent_stream_recovery_reads" to listOf("2"))))
            backend.config = backend.config.copy(maintenance = false, streamRateLimits = 0)

            val events = mutableListOf<String>()
            fun recover() = launch { events += stream().toList() }
            if (streamFirst) { recover(); runCurrent() }
            vm = AvailabilityViewModel(gate, auth, clock, elapsed)
            runCurrent()
            advanceTimeBy(14_999); runCurrent()
            assertEquals(1, backend.consents.snapshot().personalData.reads, "neither correction may start early")
            assertTrue(events.isEmpty())
            advanceTimeBy(1); runCurrent()
            // Both dispatch orders occur at the same server deadline; allow the probe's nested launch to run first.
            if (!streamFirst) { recover(); runCurrent() }

            val consent = backend.consents.snapshot().personalData
            val evidence = backend.uiEvidence.snapshot(0, 0, 0)
            println("FIN481 streamFirst=$streamFirst fence=$fence reads=${consent.reads} " +
                "probes=${evidence.counts.getValue(MockUiEvidence.Metric.MAINTENANCE_PROBES)}")
            assertEquals(ServiceGate.State.Open, gate.state.value)
            assertEquals(listOf("progress", "done"), events)
            assertEquals(1, backend.streamRateLimited.value)
            assertTrue(evidence.predicates.getValue(MockUiEvidence.Predicate.STREAM_NOT_EARLY))
            val unmet = E2eConsentControl(backend.consents).apply(mapOf(
                "consent_type" to listOf("personal_data"), "expect_consent_reads" to listOf("2")))
            if (fence || !streamFirst) {
                assertNull(unmet)
                assertEquals(1, evidence.counts.getValue(MockUiEvidence.Metric.MAINTENANCE_PROBES))
                assertTrue(evidence.predicates.getValue(MockUiEvidence.Predicate.MAINTENANCE_NOT_EARLY))
            } else {
                assertEquals("Consent count mismatch: expect_consent_reads type=personal_data expected=2 actual=1", unmet)
                assertEquals(0, evidence.counts.getValue(MockUiEvidence.Metric.MAINTENANCE_PROBES))
                assertFalse(evidence.predicates.getValue(MockUiEvidence.Predicate.MAINTENANCE_NOT_EARLY))
            }
        } finally {
            vm?.viewModelScope?.cancel()
            backend.scope.cancel()
        }
    }
}
