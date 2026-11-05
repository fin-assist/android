package ru.finassist.pf.mock

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Test
import ru.finassist.pf.core.api.model.AskRequest
import ru.finassist.pf.core.api.model.AssistantConsentGrant
import ru.finassist.pf.core.api.model.ConsentType
import ru.finassist.pf.core.api.model.ErrorCodes
import ru.finassist.pf.core.api.model.IdempotencyKey
import ru.finassist.pf.core.api.model.RegisterRequest
import ru.finassist.pf.core.api.model.StartPhoneVerificationRequest
import ru.finassist.pf.core.common.error.AppError
import ru.finassist.pf.core.common.time.ElapsedClock
import ru.finassist.pf.mock.MockConsentScenarios.DocumentMode
import ru.finassist.pf.mock.api.MockAssistantApi
import ru.finassist.pf.mock.api.MockAuthApi
import ru.finassist.pf.mock.api.MockProfileApi
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MockConsentScenariosTest {
    private val pd = ConsentType.PERSONAL_DATA
    private val assistant = ConsentType.ASSISTANT

    @Test fun `a stale GET does not publish its version or authorize an old Start and evidence preserves key identity`() = runTest {
        val backend = testBackend(userId = "")
        val api = MockAuthApi(backend)
        backend.consents.publish(pd, "pd-2")
        backend.consents.setDocument(pd, "pd-1")
        assertEquals("pd-1", api.getConsentDocument(pd).version)
        val old = StartPhoneVerificationRequest("+79990001122", true, "pd-1", true)
        val refusedKey = IdempotencyKey.random()
        assertEquals(ErrorCodes.CONSENT_OUTDATED, assertFailsWith<AppError.Api> { api.startPhoneVerification(refusedKey, old) }.code)
        assertEquals(ErrorCodes.CONSENT_OUTDATED, assertFailsWith<AppError.Api> { api.startPhoneVerification(refusedKey, old) }.code)
        assertTrue(api.consentReceipts.isEmpty())
        backend.consents.setDocument(pd)
        assertEquals("pd-2", api.getConsentDocument(pd).version)
        api.startPhoneVerification(IdempotencyKey.random(), old.copy(pdConsentVersion = "pd-2"))
        val evidence = backend.consents.snapshot()
        assertEquals(listOf("pd-1", "pd-1", "pd-2"), evidence.startVersions)
        assertEquals(2, evidence.startKeyCount)
        assertEquals(2, evidence.personalData.reads)
        assertEquals("pd-2", api.consentReceipts.single().request.pdConsentVersion)
        assertFalse(evidence.overflow)
    }

    @Test fun `a held document retains its captured version after newer publication and response`() = runTest {
        val backend = testBackend()
        val api = MockAuthApi(backend)
        backend.consents.publish(assistant, "ai-1")
        backend.consents.setDocument(assistant, mode = DocumentMode.HELD)
        val old = async(start = CoroutineStart.UNDISPATCHED) { api.getConsentDocument(assistant) }
        assertFalse(old.isCompleted)
        assertEquals(1, backend.consents.snapshot().assistant.activeReads)
        backend.consents.publish(assistant, "ai-2")
        backend.consents.setDocument(assistant)
        assertEquals("ai-2", api.getConsentDocument(assistant).version)
        backend.consents.releaseDocuments(assistant)
        assertEquals("ai-1", old.await().version)
        val evidence = backend.consents.snapshot().assistant
        assertEquals(2, evidence.reads)
        assertEquals(2, evidence.maxConcurrentReads)
        assertEquals(0, evidence.activeReads)
    }

    @Test fun `cancelling a held read retires it without releasing a later held request`() = runTest {
        val backend = testBackend()
        val api = MockAuthApi(backend)
        backend.consents.setDocument(pd, "pd-1", DocumentMode.HELD)
        val cancelled = async(start = CoroutineStart.UNDISPATCHED) { api.getConsentDocument(pd) }
        cancelled.cancelAndJoin()
        assertEquals(0, backend.consents.snapshot().personalData.activeReads)
        val pending = async(start = CoroutineStart.UNDISPATCHED) { api.getConsentDocument(pd) }
        assertFalse(pending.isCompleted)
        backend.consents.releaseDocuments(pd)
        assertEquals("pd-1", pending.await().version)
        assertEquals(DocumentMode.READY, backend.consents.documentSettings(pd).mode)
        assertEquals(0, backend.consents.snapshot().personalData.activeReads)
    }

    @Test fun `document refusal is targeted and has canonical retry false timing with an independently observed elapsed wait`() = runTest {
        var elapsed = 10_000L
        val backend = MockBackend(FAST, TestClock(NOW), elapsedClock = ElapsedClock { elapsed })
        backend.ready.complete(Unit)
        val api = MockAuthApi(backend)
        assertNull(backend.consents.snapshot().personalData.waitRemainingMillis)
        backend.consents.setDocument(pd, mode = DocumentMode.OFFLINE)
        assertFailsWith<AppError.Offline> { api.getConsentDocument(pd) }
        assertEquals(MockBackend.CONSENT_VERSION_ASSISTANT, api.getConsentDocument(assistant).version)
        backend.consents.setDocument(pd, mode = DocumentMode.RATE_LIMITED, retrySeconds = 3)
        val error = assertFailsWith<AppError.RateLimited> { api.getConsentDocument(pd) }
        assertEquals(false, error.response?.requestRetryable)
        assertEquals(3L, error.retryAfterSeconds)
        assertEquals(backend.now().plusSeconds(3), error.retryAt)
        assertEquals(10_000L, error.response?.timing?.receivedElapsedMillis)
        assertEquals(3_000L, backend.consents.snapshot().personalData.waitRemainingMillis)
        elapsed += 3_001
        assertEquals(0L, backend.consents.snapshot().personalData.waitRemainingMillis)
        backend.consents.setDocument(pd)
        assertEquals(MockBackend.CONSENT_VERSION_PD, api.getConsentDocument(pd).version)
        assertEquals(3, backend.consents.snapshot().personalData.reads)
        assertEquals(1, backend.consents.snapshot().assistant.reads)
        assertEquals(0L, backend.consents.snapshot().personalData.waitRemainingMillis)
    }

    @Test fun `rate limited reads can explicitly permit a later user retry and wall clock is the timing fallback`() = runTest {
        val clock = TestClock(NOW)
        val backend = testBackend(clock)
        val api = MockAuthApi(backend)
        backend.consents.setDocument(assistant, mode = DocumentMode.RATE_LIMITED, retrySeconds = 5, retryable = true)
        val error = assertFailsWith<AppError.RateLimited> { api.getConsentDocument(assistant) }
        assertEquals(true, error.response?.requestRetryable)
        assertEquals(5_000L, backend.consents.snapshot().assistant.waitRemainingMillis)
        clock.at = clock.at.plusSeconds(5)
        assertEquals(0L, backend.consents.snapshot().assistant.waitRemainingMillis)
    }

    @Test fun `published assistant versions update the profile and old grants cannot authorize Ask or spend quota`() = runTest {
        val backend = testBackend(assistantConsent = true)
        val profile = MockProfileApi(backend)
        val api = MockAssistantApi(backend)
        backend.consents.publish(assistant, "ai-2")
        val before = profile.getProfile().assistantConsent!!
        assertEquals(MockBackend.CONSENT_VERSION_ASSISTANT, before.version)
        assertEquals("ai-2", before.currentVersion)
        assertTrue(before.needsRenewal)
        assertEquals(ErrorCodes.CONSENT_REQUIRED, assertFailsWith<AppError.Api> {
            api.ask(IdempotencyKey.random(), AskRequest("Сколько я потратил?"))
        }.code)
        assertTrue(backend.assistant.questions.isEmpty())
        assertTrue(backend.assistant.usage.isEmpty())
        assertEquals(ErrorCodes.CONSENT_OUTDATED, assertFailsWith<AppError.Api> {
            profile.grantAssistantConsent(AssistantConsentGrant(MockBackend.CONSENT_VERSION_ASSISTANT))
        }.code)
        assertNull(backend.consents.snapshot().grantedVersion)
        val accepted = profile.grantAssistantConsent(AssistantConsentGrant("ai-2"))
        assertFalse(accepted.assistantConsent!!.needsRenewal)
        assertEquals(listOf(MockBackend.CONSENT_VERSION_ASSISTANT, "ai-2"), backend.consents.snapshot().grantVersions)
        assertEquals("ai-2", backend.consents.snapshot().grantedVersion)
        profile.revokeAssistantConsent()
        assertEquals("ai-2", profile.getProfile().assistantConsent?.currentVersion)
        assertTrue(profile.getProfile().assistantConsent!!.needsRenewal)
    }

    @Test fun `Register rejection is controllable without changing pinned evidence or cached successful responses`() = runTest {
        val backend = testBackend(userId = "")
        val api = MockAuthApi(backend)
        backend.consents.publish(pd, "pd-1")
        val start = api.startPhoneVerification(IdempotencyKey.random(), StartPhoneVerificationRequest("+79990001122", true, "pd-1", true))
        val token = api.streamPhoneVerification(start.verificationToken).toList().last().registrationToken!!
        val request = RegisterRequest(token, true, "pd-1")
        backend.consents.publish(pd, "pd-2")
        backend.consents.setRegisterRejection(MockConsentScenarios.RegisterRejection.OUTDATED)
        assertEquals(ErrorCodes.CONSENT_OUTDATED, assertFailsWith<AppError.Api> { api.register(IdempotencyKey.random(), request) }.code)
        backend.consents.setRegisterRejection(MockConsentScenarios.RegisterRejection.REQUIRED)
        assertEquals(ErrorCodes.CONSENT_REQUIRED, assertFailsWith<AppError.Api> { api.register(IdempotencyKey.random(), request) }.code)
        assertEquals("", backend.profile.userId)
        backend.consents.setRegisterRejection(null)
        val acceptedKey = IdempotencyKey.random()
        val registered = api.register(acceptedKey, request)
        backend.consents.setRegisterRejection(MockConsentScenarios.RegisterRejection.OUTDATED)
        assertEquals(registered, api.register(acceptedKey, request))
        assertEquals(listOf("pd-1", "pd-1", "pd-1", "pd-1"), backend.consents.snapshot().registerVersions)
        assertEquals("pd-1", api.consentReceipts.single().request.pdConsentVersion)
    }

    @Test fun `fixture input and evidence are bounded and snapshots do not expose keys or mutable history`() = runTest {
        val backend = testBackend(userId = "")
        val api = MockAuthApi(backend)
        for (version in listOf("", "x".repeat(65), "bad/version", "кириллица")) {
            assertFailsWith<IllegalArgumentException> { backend.consents.publish(pd, version) }
            assertFailsWith<IllegalArgumentException> { backend.consents.setDocument(pd, version) }
        }
        assertFailsWith<IllegalArgumentException> { backend.consents.setDocument(pd, retrySeconds = 0) }
        assertFailsWith<IllegalArgumentException> { backend.consents.setDocument(pd, retrySeconds = 61) }
        assertFailsWith<IllegalStateException> { backend.consents.publish(ConsentType.UNKNOWN, "pd-1") }
        assertEquals(ErrorCodes.NOT_FOUND, assertFailsWith<AppError.Api> { api.getConsentDocument(ConsentType.UNKNOWN) }.code)
        val old = StartPhoneVerificationRequest("+79990001122", true, "old", true)
        val key = IdempotencyKey.random()
        val empty = backend.consents.snapshot()
        repeat(129) { assertFailsWith<AppError.Api> { api.startPhoneVerification(key, old) } }
        val evidence = backend.consents.snapshot()
        assertTrue(empty.startVersions.isEmpty())
        assertEquals(128, evidence.startVersions.size)
        assertEquals(1, evidence.startKeyCount)
        assertTrue(evidence.overflow)
    }
}
