package ru.finassist.pf.mock

import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Test
import ru.finassist.pf.core.api.ApiJson
import ru.finassist.pf.core.api.model.IdempotencyKey
import ru.finassist.pf.core.api.model.PhoneVerification
import ru.finassist.pf.core.common.availability.ClientVersion
import ru.finassist.pf.core.common.availability.ServiceGate
import ru.finassist.pf.core.common.error.AppError
import ru.finassist.pf.core.common.error.UserMessage
import ru.finassist.pf.core.common.time.ElapsedClock
import ru.finassist.pf.mock.api.MockAuthApi
import kotlin.test.*

class MockProducerTimingTest {
    @Test fun `phone replay has fresh receipt duration and Date without persisting anchors or wire fields`() = runBlocking<Unit> {
        var elapsed = 1_000L
        val elapsedClock = ElapsedClock { elapsed }
        val wall = TestClock(NOW)
        val backend = MockBackend(FAST, wall, elapsedClock = elapsedClock)
        backend.ready.complete(Unit)
        try {
            val api = MockAuthApi(backend)
            val key = IdempotencyKey("phone-replay")
            val first = api.startPhoneVerification(key, "+79160000000")
            assertEquals(300, first.expiresIn)
            assertEquals(1_000, first.responseTiming.receivedElapsedMillis)
            wall.at = wall.at.plusSeconds(2)
            elapsed = 4_000
            val replay = api.startPhoneVerification(key, "+79160000000")
            assertEquals(first.verificationToken, replay.verificationToken)
            assertEquals(first.expiresAt, replay.expiresAt)
            assertEquals(298, replay.expiresIn)
            assertEquals(4_000, replay.responseTiming.receivedElapsedMillis)
            assertEquals(wall.now().toInstant(), replay.responseTiming.serverDate)
            assertEquals(replay.serverTime, replay.responseTiming.serverDate)
            val deadline = assertNotNull(replay.responseTiming.deadline(replay.expiresIn?.toLong()))
            wall.at = wall.at.plusYears(3)
            elapsed += 1_000
            assertEquals(297_000, deadline.remainingMillis(elapsedClock))
            val stored = assertIs<PhoneVerification>(backend.idempotency.getValue("auth.phone:phone-replay").result)
            assertNull(stored.responseTiming.receivedElapsedMillis)
            assertNull(stored.responseTiming.serverDate)
            val wire = ApiJson.encodeToString(PhoneVerification.serializer(), replay)
            assertFalse(wire.contains("response_timing"))
            assertFalse(wire.contains("server_time"))
        } finally { backend.scope.cancel() }
    }

    @Test fun `availability gate keeps the exact safe presentation and response monotonic anchor`() = runBlocking<Unit> {
        var elapsed = 10_000L
        val elapsedClock = ElapsedClock { elapsed }
        val wall = TestClock(NOW)
        val gate = ServiceGate(elapsedClock)
        val backend = MockBackend(FAST.copy(maintenance = true, maintenanceRetryAfterSeconds = 20), wall,
            clientVersion = ClientVersion("0.0.0", 0), gate = gate, elapsedClock = elapsedClock)
        backend.ready.complete(Unit)
        try {
            val error = assertFailsWith<AppError.Maintenance> { backend.simulateNetwork() }
            val maintenance = assertIs<ServiceGate.State.Maintenance>(gate.state.value)
            assertEquals(error.presentation, maintenance.presentation)
            assertIs<UserMessage.Safe>(maintenance.presentation.userMessage)
            assertEquals(false, error.requestRetryable)
            wall.at = wall.at.minusYears(5)
            elapsed += 5_000
            assertEquals(15_000, assertNotNull(maintenance.deadline).remainingMillis(elapsedClock))
            backend.config = FAST.copy(minVersion = "9.0.0")
            val update = assertFailsWith<AppError.UpdateRequired> { backend.simulateNetwork() }
            assertEquals(update.presentation, assertIs<ServiceGate.State.UpdateRequired>(gate.state.value).presentation)
        } finally { backend.scope.cancel() }
    }
}
