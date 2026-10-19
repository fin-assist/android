package ru.finassist.pf.e2e

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import ru.finassist.pf.core.api.AuthApi
import ru.finassist.pf.core.api.model.ConsentDocument
import ru.finassist.pf.core.api.model.ConsentType
import ru.finassist.pf.core.api.model.IdempotencyKey
import ru.finassist.pf.core.api.model.PhoneVerification
import ru.finassist.pf.core.api.model.PhoneVerificationStatusEvent
import ru.finassist.pf.core.api.model.RegisterRequest
import ru.finassist.pf.core.api.model.RegisterResponse
import ru.finassist.pf.core.api.model.TokenPair
import ru.finassist.pf.core.api.model.VerificationStatus
import ru.finassist.pf.core.common.error.AppError
import ru.finassist.pf.feature.applock.api.AppLock
import ru.finassist.pf.feature.applock.api.BiometricAvailability
import ru.finassist.pf.feature.applock.api.LockState
import ru.finassist.pf.feature.applock.api.VerifyResult
import ru.finassist.pf.feature.auth.api.SessionRepository
import ru.finassist.pf.feature.auth.api.SessionState
import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class E2eSignInTest {
    private val auth = FakeAuthApi()
    private val session = FakeSession()
    private val lock = FakeAppLock()
    private val started = mutableListOf<TokenPair>()

    private fun signIn(timeoutMs: Long = 30_000) = E2eSignIn(
        authApi = auth,
        session = session,
        startSession = { tokens ->
            started += tokens
            session.state.value = SessionState.SignedIn("u1")
        },
        appLock = lock,
        timeoutMs = timeoutMs,
    )

    @Test
    fun `existing account is signed in with its tokens and the passcode is set`() = runTest {
        auth.events = listOf(
            PhoneVerificationStatusEvent(VerificationStatus.PENDING),
            PhoneVerificationStatusEvent(VerificationStatus.VERIFIED, isNewUser = false, accessToken = "a", refreshToken = "r"),
        )

        assertNull(signIn().signIn("+79161234567", "1234"))

        assertEquals(listOf("+79161234567"), auth.phones)
        assertEquals(listOf("vt_1"), auth.streamed)
        assertEquals(listOf(TokenPair("a", "r")), started)
        assertEquals(listOf("1234"), lock.passcodes)
        assertEquals(LockState.Unlocked, lock.state.value)
    }

    @Test
    fun `a new number is never registered and gets no passcode`() = runTest {
        auth.events = listOf(PhoneVerificationStatusEvent(VerificationStatus.VERIFIED, isNewUser = true, registrationToken = "reg"))

        val unmet = signIn().signIn("+79990000000", "1234")

        assertTrue(unmet!!.contains("not an existing account"), unmet)
        assertTrue(started.isEmpty())
        assertTrue(lock.passcodes.isEmpty())
    }

    @Test
    fun `an unknown new-user flag is not taken for an existing account`() = runTest {
        auth.events = listOf(PhoneVerificationStatusEvent(VerificationStatus.VERIFIED, accessToken = "a", refreshToken = "r"))

        assertTrue(signIn().signIn("+79161234567", "1234")!!.contains("not an existing account"))
        assertTrue(started.isEmpty())
    }

    @Test
    fun `expired verification ends without a session`() = runTest {
        auth.events = listOf(PhoneVerificationStatusEvent(VerificationStatus.EXPIRED))

        assertEquals("phone verification ended as expired", signIn().signIn("+79161234567", "1234"))
        assertTrue(started.isEmpty())
        assertTrue(lock.passcodes.isEmpty())
    }

    @Test
    fun `verified without tokens ends without a session`() = runTest {
        auth.events = listOf(PhoneVerificationStatusEvent(VerificationStatus.VERIFIED, isNewUser = false, accessToken = "a"))

        assertEquals("verified without tokens", signIn().signIn("+79161234567", "1234"))
        assertTrue(started.isEmpty())
    }

    @Test
    fun `a session already there is reported, not replaced`() = runTest {
        session.state.value = SessionState.SignedIn("u0")

        assertTrue(signIn().signIn("+79161234567", "1234")!!.startsWith("already signed in"))
        assertTrue(auth.phones.isEmpty())
        assertTrue(lock.passcodes.isEmpty())
    }

    @Test
    fun `offline fake server is reported by the error type, without its message`() = runTest {
        auth.startError = AppError.Offline()

        assertEquals("sign-in failed: Offline", signIn().signIn("+79161234567", "1234"))
        assertTrue(started.isEmpty())
    }

    @Test
    fun `a stream that ends while pending is reported`() = runTest {
        auth.events = listOf(PhoneVerificationStatusEvent(VerificationStatus.PENDING))

        assertEquals("the phone verification stream ended without a result", signIn().signIn("+79161234567", "1234"))
        assertTrue(started.isEmpty())
    }

    @Test
    fun `a call that never comes times out without a session`() = runTest {
        auth.hang = true

        assertEquals("sign-in did not finish in 5000 ms", signIn(timeoutMs = 5_000).signIn("+79161234567", "1234"))
        assertTrue(started.isEmpty())
        assertTrue(lock.passcodes.isEmpty())
    }

    @Test
    fun `a passcode that does not unlock the app is not success`() = runTest {
        auth.events = listOf(PhoneVerificationStatusEvent(VerificationStatus.VERIFIED, isNewUser = false, accessToken = "a", refreshToken = "r"))
        lock.unlockOnSet = false

        assertEquals("sign-in did not finish in 5000 ms", signIn(timeoutMs = 5_000).signIn("+79161234567", "1234"))
    }

    private class FakeAuthApi : AuthApi {
        var events: List<PhoneVerificationStatusEvent> = emptyList()
        var startError: AppError? = null
        var hang = false
        val phones = mutableListOf<String>()
        val streamed = mutableListOf<String>()

        override suspend fun startPhoneVerification(key: IdempotencyKey, phone: String): PhoneVerification {
            startError?.let { throw it }
            phones += phone
            return PhoneVerification("vt_${phones.size}", "+74950000000", OffsetDateTime.parse("2026-10-08T12:05:00+03:00"), 300)
        }

        override fun streamPhoneVerification(verificationToken: String): Flow<PhoneVerificationStatusEvent> {
            streamed += verificationToken
            return if (hang) flow { awaitCancellation() } else flowOf(*events.toTypedArray())
        }

        override suspend fun register(key: IdempotencyKey, request: RegisterRequest): RegisterResponse = error("unused")
        override suspend fun refreshTokens(key: IdempotencyKey, refreshToken: String): TokenPair = error("unused")
        override suspend fun logout(accessToken: String, refreshToken: String) = error("unused")
        override suspend fun getConsentDocument(type: ConsentType): ConsentDocument = error("unused")
    }

    private class FakeSession : SessionRepository {
        override val state = MutableStateFlow<SessionState>(SessionState.SignedOut)
        override suspend fun consumeJustRegistered(): Boolean = error("unused")
        override suspend fun signOut(reason: String?) = error("unused")
        override suspend fun clearLocal(reason: String?) = error("unused")
        override fun consumeSignOutReason(): String? = error("unused")
    }

    private class FakeAppLock : AppLock {
        val passcodes = mutableListOf<String>()
        var unlockOnSet = true
        override val state = MutableStateFlow<LockState>(LockState.NotConfigured)
        override val biometricEnabled: StateFlow<Boolean> = MutableStateFlow(false)
        override fun biometricAvailability(): BiometricAvailability = BiometricAvailability.NONE

        override suspend fun setPasscode(code: String) {
            passcodes += code
            state.value = if (unlockOnSet) LockState.Unlocked else LockState.Locked
        }

        override suspend fun verify(code: String): VerifyResult = error("unused")
        override suspend fun matches(code: String): Boolean = error("unused")
        override suspend fun setBiometricEnabled(enabled: Boolean) = error("unused")
        override fun unlockByBiometric() = error("unused")
        override fun lock() = error("unused")
        override suspend fun forgetAndSignOut() = error("unused")
        override suspend fun reset() = error("unused")
    }
}
