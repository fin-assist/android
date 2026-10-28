package ru.finassist.pf.mock

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Test
import ru.finassist.pf.core.api.model.*
import ru.finassist.pf.core.common.error.AppError
import ru.finassist.pf.mock.api.MockAuthApi
import kotlin.test.*

class MockPhoneConsentTest {
    private val clock = TestClock(NOW)
    private val backend = testBackend(clock, config = FAST, userId = "")
    private val api = MockAuthApi(backend)
    private val body = StartPhoneVerificationRequest("+79990001122", true, MockBackend.CONSENT_VERSION_PD, true)
    @Test fun `every missing confirmation and invalid version stops before a receipt or verification exists`() = runTest {
        for ((request, code) in listOf(body.copy(ageConfirmed = false) to ErrorCodes.VALIDATION_ERROR,
            body.copy(pdConsentAccepted = false) to ErrorCodes.CONSENT_REQUIRED,
            body.copy(pdConsentVersion = "old") to ErrorCodes.CONSENT_OUTDATED,
            body.copy(pdConsentVersion = "") to ErrorCodes.VALIDATION_ERROR,
            body.copy(pdConsentVersion = "x".repeat(65)) to ErrorCodes.VALIDATION_ERROR)) {
            assertEquals(code, assertFailsWith<AppError.Api> { api.startPhoneVerification(IdempotencyKey.random(), request) }.code)
        }
        assertTrue(api.consentReceipts.isEmpty()); assertEquals("", backend.profile.userId)
    }
    @Test fun `Start receipt is server timed and remains pinned across replay version rotation and Register`() = runTest {
        val acceptedAt = clock.at
        val key = IdempotencyKey.random()
        val verification = api.startPhoneVerification(key, body)
        val receipt = api.consentReceipts.single()
        assertEquals(acceptedAt, receipt.consentAcceptedAt); assertEquals(acceptedAt, receipt.ageConfirmedAt)
        assertNull(receipt.accountId)
        clock.at = clock.at.plusSeconds(5)
        api.personalDataConsentVersion = "new-version"
        assertEquals(verification.verificationToken, api.startPhoneVerification(key, body).verificationToken)
        assertEquals(1, api.consentReceipts.size)
        assertEquals(ErrorCodes.IDEMPOTENCY_CONFLICT, assertFailsWith<AppError.Api> {
            api.startPhoneVerification(key, body.copy(pdConsentVersion = "new-version"))
        }.code)
        val token = api.streamPhoneVerification(verification.verificationToken).toList().last().registrationToken!!
        val registered = api.register(IdempotencyKey.random(), RegisterRequest(token, true, body.pdConsentVersion))
        assertEquals(registered.userId, receipt.accountId)
        assertEquals(acceptedAt, receipt.consentAcceptedAt)
    }
    @Test fun `existing account requires both confirmations and only verified phone links its own receipt`() = runTest {
        val request = body.copy(phone = backend.config.existingPhone)
        assertFailsWith<AppError.Api> { api.startPhoneVerification(IdempotencyKey.random(), request.copy(ageConfirmed = false)) }
        val verification = api.startPhoneVerification(IdempotencyKey.random(), request)
        assertNull(api.consentReceipts.single().accountId)
        api.streamPhoneVerification(verification.verificationToken).toList()
        assertEquals(backend.profile.userId, api.consentReceipts.single().accountId)
        api.streamPhoneVerification(verification.verificationToken).toList()
        assertEquals(1, api.consentReceipts.size)
    }
    @Test fun `new Start after version rotation needs the new document instead of old profile consent`() = runTest {
        api.personalDataConsentVersion = "new"
        assertEquals(ErrorCodes.CONSENT_OUTDATED, assertFailsWith<AppError.Api> { api.startPhoneVerification(IdempotencyKey.random(), body) }.code)
        api.startPhoneVerification(IdempotencyKey.random(), body.copy(pdConsentVersion = "new"))
        assertEquals("new", api.consentReceipts.single().request.pdConsentVersion)
    }
}
