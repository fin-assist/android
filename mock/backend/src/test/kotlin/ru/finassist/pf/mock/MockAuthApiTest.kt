package ru.finassist.pf.mock

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Test
import ru.finassist.pf.core.api.model.ConsentType
import ru.finassist.pf.core.api.model.ErrorCodes
import ru.finassist.pf.core.api.model.FieldErrorCodes
import ru.finassist.pf.core.api.model.IdempotencyKey
import ru.finassist.pf.core.api.model.RegisterRequest
import ru.finassist.pf.core.api.model.VerificationStatus
import ru.finassist.pf.core.common.error.AppError
import ru.finassist.pf.core.common.time.SystemClock
import ru.finassist.pf.mock.api.MockAuthApi
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MockAuthApiTest {
    private val auth = MockAuthApi(MockBackend(MockConfig(networkDelayMs = 0), SystemClock()).also { it.ready.complete(Unit) })

    @Test
    fun `a number outside +7 and 10 digits is a VALIDATION_ERROR at phone (FIN-68)`() = runBlocking {
        for (phone in listOf("+7916123456", "+380501234567", "89161234567")) {
            val e = assertFailsWith<AppError.Api> { auth.startPhoneVerification(IdempotencyKey("k-$phone"), phone) }
            assertEquals(ErrorCodes.VALIDATION_ERROR, e.code)
            assertEquals(400, e.httpStatus)
            assertEquals(listOf("phone" to FieldErrorCodes.INVALID_FORMAT), e.details.map { it.field to it.code })
        }
    }

    @Test
    fun `a valid number starts the verification`() = runBlocking {
        val v = auth.startPhoneVerification(IdempotencyKey("k1"), "+79161234567")
        assertTrue(v.verificationToken.startsWith("vt_"))
    }

    // ---- callback verification, registration, tokens (api.md 1) ----

    private val existing = MockConfig().existingPhone
    private val newPhone = "+79990001122"

    private val clock = TestClock(NOW)
    private val backend = testBackend(clock, config = FAST.copy(callVerifyDelayMs = 5_000), userId = "")
    private val api = MockAuthApi(backend)

    private fun key(k: String) = IdempotencyKey(k)
    private fun register(token: String, accepted: Boolean = true, version: String = MockBackend.CONSENT_VERSION_PD, timezone: String? = "Asia/Yekaterinburg") =
        RegisterRequest(token, accepted, version, timezone)

    /** Verifies [phone] and returns the final status event. */
    private suspend fun verify(phone: String, k: String = phone) =
        api.streamPhoneVerification(api.startPhoneVerification(key(k), phone).verificationToken).toList().last()

    @Test
    fun `the verification is pending until the call and then signs the existing user in`() = runTest {
        val v = api.startPhoneVerification(key("k"), existing)
        assertEquals("+78001234567", v.callbackNumber)
        assertEquals(clock.at.plusMinutes(5), v.expiresAt)
        assertEquals(300, v.expiresIn)
        assertNull(v.serverTime, "no Date header in-process")
        // Same key, same verification; `expires_in` is recomputed on the replay (api.md 1.1).
        clock.at = clock.at.plusSeconds(1)
        assertEquals(v.copy(expiresIn = 299), api.startPhoneVerification(key("k"), existing))
        clock.at = clock.at.minusSeconds(1)

        val events = api.streamPhoneVerification(v.verificationToken).toList()
        assertEquals(listOf(VerificationStatus.PENDING, VerificationStatus.VERIFIED), events.map { it.status })
        val verified = events.last()
        assertEquals(false, verified.isNewUser)
        assertTrue(verified.accessToken!!.startsWith("mock."))
        assertTrue(verified.refreshToken!!.startsWith("rt_"))
        assertNull(verified.registrationToken)
        // The session now has a user with that phone.
        assertEquals(existing, backend.profile.phone)
        assertTrue(backend.profile.userId.isNotEmpty())
    }

    @Test
    fun `once the call has happened the stream says verified at once`() = runTest {
        val v = api.startPhoneVerification(key("k"), existing)
        clock.at = clock.at.plusSeconds(10)
        assertEquals(listOf(VerificationStatus.VERIFIED), api.streamPhoneVerification(v.verificationToken).toList().map { it.status })
    }

    @Test
    fun `a verification older than 5 minutes is expired`() = runTest {
        val v = api.startPhoneVerification(key("k"), existing)
        clock.at = clock.at.plusMinutes(5).plusSeconds(1)
        val events = api.streamPhoneVerification(v.verificationToken).toList()
        assertEquals(listOf(VerificationStatus.EXPIRED), events.map { it.status })
        assertNull(events.single().accessToken)
        assertEquals("", backend.profile.userId)
    }

    @Test
    fun `expires_in counts down on replays, floors partial seconds and stops at zero`() = runTest {
        val v = api.startPhoneVerification(key("k"), existing)
        clock.at = clock.at.plusSeconds(100).plusNanos(500_000_000)
        assertEquals(199, api.startPhoneVerification(key("k"), existing).expiresIn)
        clock.at = clock.at.plusMinutes(10)
        val late = api.startPhoneVerification(key("k"), existing)
        assertEquals(0, late.expiresIn)
        assertEquals(v.verificationToken, late.verificationToken)
    }

    @Test
    fun `a call due after the end of the request ends the open stream with expired`() = runTest {
        backend.config = backend.config.copy(verificationTtlMs = 3_000)
        val v = api.startPhoneVerification(key("late"), existing)
        val events = api.streamPhoneVerification(v.verificationToken).toList()
        assertEquals(listOf(VerificationStatus.PENDING, VerificationStatus.EXPIRED), events.map { it.status })
        assertEquals("", backend.profile.userId)
    }

    @Test
    fun `the request lifetime follows the config`() = runTest {
        backend.config = backend.config.copy(verificationTtlMs = 5_000)
        val v = api.startPhoneVerification(key("short"), existing)
        assertEquals(clock.at.plusSeconds(5), v.expiresAt)
        assertEquals(5, v.expiresIn)
    }

    @Test
    fun `an unknown verification token is INVALID_TOKEN`() = runTest {
        val e = assertFailsWith<AppError.Unauthorized> { api.streamPhoneVerification("vt_missing").toList() }
        assertEquals(ErrorCodes.INVALID_TOKEN, e.code)
    }

    @Test
    fun `a new phone gets a registration token, the same one on every check`() = runTest {
        val v = api.startPhoneVerification(key("k"), newPhone)
        val first = api.streamPhoneVerification(v.verificationToken).toList().last()
        assertEquals(VerificationStatus.VERIFIED, first.status)
        assertEquals(true, first.isNewUser)
        val token = assertNotNull(first.registrationToken)
        assertTrue(token.startsWith("reg_"))
        assertNull(first.accessToken)
        assertEquals(token, api.streamPhoneVerification(v.verificationToken).toList().last().registrationToken)
        // No account yet.
        assertEquals("", backend.profile.userId)
    }

    @Test
    fun `registration needs the current personal data consent`() = runTest {
        val token = verify(newPhone).registrationToken!!
        val notAccepted = assertFailsWith<AppError.Api> { api.register(key("r1"), register(token, accepted = false)) }
        assertEquals(ErrorCodes.CONSENT_REQUIRED to 403, notAccepted.code to notAccepted.httpStatus)
        val outdated = assertFailsWith<AppError.Api> { api.register(key("r2"), register(token, version = "2020-01-01")) }
        assertEquals(ErrorCodes.CONSENT_OUTDATED to 422, outdated.code to outdated.httpStatus)
        val unknown = assertFailsWith<AppError.Unauthorized> { api.register(key("r3"), register("reg_missing")) }
        assertEquals(ErrorCodes.INVALID_TOKEN, unknown.code)
        // The failed attempts did not use the token up.
        api.register(key("r4"), register(token))
    }

    @Test
    fun `registration creates the account, uses the token up and is idempotent by key`() = runTest {
        val v = api.startPhoneVerification(key("v"), newPhone)
        val token = api.streamPhoneVerification(v.verificationToken).toList().last().registrationToken!!
        val response = api.register(key("r"), register(token))
        assertTrue(response.accessToken.startsWith("mock."))
        assertEquals(response.userId, backend.profile.userId)
        assertEquals(newPhone, backend.profile.phone)
        assertEquals("Asia/Yekaterinburg", backend.profile.timezone)
        assertEquals(response, api.register(key("r"), register(token)))

        // Token and verification are spent.
        assertEquals(ErrorCodes.INVALID_TOKEN, assertFailsWith<AppError.Unauthorized> { api.register(key("r2"), register(token)) }.code)
        assertEquals(ErrorCodes.INVALID_TOKEN, assertFailsWith<AppError.Unauthorized> { api.streamPhoneVerification(v.verificationToken).toList() }.code)

        // The phone now has an account: signing in again is not a new user.
        val again = verify(newPhone, k = "again")
        assertEquals(false, again.isNewUser)
        assertEquals(response.userId, backend.profile.userId)
    }

    @Test
    fun `registration without a time zone defaults to Moscow`() = runTest {
        val token = verify(newPhone).registrationToken!!
        api.register(key("r"), register(token, timezone = null))
        assertEquals("Europe/Moscow", backend.profile.timezone)
    }

    @Test
    fun `a refresh token works once and is replaced by a new pair`() = runTest {
        val rt = verify(existing).refreshToken!!
        val pair = api.refreshTokens(key("t1"), rt)
        assertNotEquals(rt, pair.refreshToken)
        // A retry with the same key gets the same pair, not TOKEN_REVOKED.
        assertEquals(pair, api.refreshTokens(key("t1"), rt))
        val reused = assertFailsWith<AppError.Unauthorized> { api.refreshTokens(key("t2"), rt) }
        assertEquals(ErrorCodes.TOKEN_REVOKED, reused.code)
        api.refreshTokens(key("t3"), pair.refreshToken)
    }

    @Test
    fun `logout revokes the refresh token`() = runTest {
        val event = verify(existing)
        api.logout(event.accessToken!!, event.refreshToken!!)
        assertEquals(ErrorCodes.TOKEN_REVOKED, assertFailsWith<AppError.Unauthorized> { api.refreshTokens(key("t"), event.refreshToken!!) }.code)
    }

    @Test
    fun `consent documents carry the current versions`() = runTest {
        val pd = api.getConsentDocument(ConsentType.PERSONAL_DATA)
        assertEquals(MockBackend.CONSENT_VERSION_PD, pd.version)
        assertTrue(pd.url.endsWith(MockBackend.CONSENT_VERSION_PD))
        val assistant = api.getConsentDocument(ConsentType.ASSISTANT)
        assertEquals(MockBackend.CONSENT_VERSION_ASSISTANT, assistant.version)
        assertEquals(ConsentType.ASSISTANT, assistant.type)
        val e = assertFailsWith<AppError.Api> { api.getConsentDocument(ConsentType.UNKNOWN) }
        assertEquals(ErrorCodes.NOT_FOUND to 404, e.code to e.httpStatus)
    }

    @Test
    fun `auth requests fail offline`() = runTest {
        backend.config = backend.config.copy(offline = true)
        assertFailsWith<AppError.Offline> { api.startPhoneVerification(key("k"), existing) }
        assertFailsWith<AppError.Offline> { api.refreshTokens(key("t"), "rt_x") }
    }
}
