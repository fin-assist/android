package ru.finassist.pf.feature.assistant.impl.ui

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import ru.finassist.pf.core.api.AuthApi
import ru.finassist.pf.core.api.ProfileApi
import ru.finassist.pf.core.api.model.Consent
import ru.finassist.pf.core.api.model.ConsentType
import ru.finassist.pf.core.api.model.AssistantConsentGrant
import ru.finassist.pf.core.api.model.ConsentDocument
import ru.finassist.pf.core.api.model.ErrorCodes
import ru.finassist.pf.core.api.model.IdempotencyKey
import ru.finassist.pf.core.api.model.PhoneVerification
import ru.finassist.pf.core.api.model.PhoneVerificationStatusEvent
import ru.finassist.pf.core.api.model.Profile
import ru.finassist.pf.core.api.model.ProfileUpdate
import ru.finassist.pf.core.api.model.RegisterRequest
import ru.finassist.pf.core.api.model.RegisterResponse
import ru.finassist.pf.core.api.model.Theme
import ru.finassist.pf.core.api.model.TokenPair
import ru.finassist.pf.core.common.error.AppError
import java.time.OffsetDateTime
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AiConsentViewModelTest {
    private val document = ConsentDocument(ConsentType.ASSISTANT, "2", "Согласие на передачу данных помощнику", "https://plainfin.ru/ai")

    private class FakeAuth(private val doc: ConsentDocument) : AuthApi {
        var documentRequests = 0
        var nextDocument = doc
        var documentGate: CompletableDeferred<Unit>? = null
        var documentFailure: AppError? = null
        var documentCall: (suspend (Int) -> ConsentDocument)? = null
        override suspend fun startPhoneVerification(key: IdempotencyKey, phone: String): PhoneVerification = error("unused")
        override fun streamPhoneVerification(verificationToken: String): Flow<PhoneVerificationStatusEvent> = error("unused")
        override suspend fun register(key: IdempotencyKey, request: RegisterRequest): RegisterResponse = error("unused")
        override suspend fun refreshTokens(key: IdempotencyKey, refreshToken: String): TokenPair = error("unused")
        override suspend fun logout(accessToken: String, refreshToken: String) = Unit
        override suspend fun getConsentDocument(type: ConsentType): ConsentDocument {
            documentRequests++
            documentCall?.let { return it(documentRequests) }
            documentGate?.await()
            documentFailure?.let { throw it }
            return nextDocument
        }
    }

    private class FakeProfile : ProfileApi {
        var failure: AppError? = null
        val grants = mutableListOf<String>()
        val attempts = mutableListOf<String>()
        override suspend fun getProfile(): Profile = error("unused")
        override suspend fun updateProfile(update: ProfileUpdate): Profile = error("unused")
        override suspend fun deleteAccount() = Unit
        override suspend fun grantAssistantConsent(grant: AssistantConsentGrant): Profile {
            attempts += grant.version
            failure?.let { throw it }
            grants += grant.version
            return Profile(
                userId = "u1", phone = "+79161234567", theme = Theme.SYSTEM, timezone = "Europe/Moscow",
                consents = listOf(Consent(ConsentType.ASSISTANT, granted = true, version = grant.version, currentVersion = grant.version)),
                createdAt = OffsetDateTime.parse("2026-09-01T00:00:00+03:00"),
            )
        }
        override suspend fun revokeAssistantConsent() = Unit
    }

    private val auth = FakeAuth(document)
    private val profile = FakeProfile()

    @BeforeTest fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    @Test fun `without the tick the checkbox says why`() {
        val vm = AiConsentViewModel(auth, profile, ru.finassist.pf.core.common.time.ElapsedClock { 0L })
        vm.grant()
        assertEquals("Без согласия помощник не сможет ответить", vm.state.value.error)
        assertTrue(profile.grants.isEmpty())
        vm.setAccepted(true)
        assertNull(vm.state.value.error)
    }

    @Test fun `with the tick the current document version is granted`() {
        val vm = AiConsentViewModel(auth, profile, ru.finassist.pf.core.common.time.ElapsedClock { 0L })
        vm.setAccepted(true)
        vm.grant()
        assertEquals(listOf("2"), profile.grants)
        assertTrue(vm.state.value.granted)
    }

    @Test fun `no network is shown under the form`() {
        profile.failure = AppError.Offline()
        val vm = AiConsentViewModel(auth, profile, ru.finassist.pf.core.common.time.ElapsedClock { 0L })
        vm.setAccepted(true)
        vm.grant()
        assertEquals("Нет сети — проверьте интернет и повторите", vm.state.value.formError)
        assertFalse(vm.state.value.busy)
        assertFalse(vm.state.value.granted)
    }

    @Test fun `an outdated consent reloads the document`() {
        profile.failure = AppError.Api(ErrorCodes.CONSENT_OUTDATED, 409)
        val vm = AiConsentViewModel(auth, profile, ru.finassist.pf.core.common.time.ElapsedClock { 0L })
        val before = auth.documentRequests
        vm.setAccepted(true)
        vm.grant()
        assertEquals("Текст согласия обновился — прочитайте его ещё раз и подтвердите", vm.state.value.formError)
        assertEquals(before + 1, auth.documentRequests)
        assertEquals(document, vm.state.value.document)
    }
    private fun vm() = AiConsentViewModel(auth, profile, ru.finassist.pf.core.common.time.ElapsedClock { 0L })

    private fun outdatedFalse() = AppError.Api(ErrorCodes.CONSENT_OUTDATED, 409,
        response = ru.finassist.pf.core.common.error.ErrorResponseInfo(ErrorCodes.CONSENT_OUTDATED, 409, requestRetryable = false))

    @Test fun `delayed document reload never permits the old false-denied consent PUT`() {
        val vm = vm()
        val reply = CompletableDeferred<Unit>()
        auth.documentGate = reply
        auth.nextDocument = document.copy(version = "3")
        profile.failure = outdatedFalse()
        vm.setAccepted(true)
        vm.grant()
        assertFalse(vm.state.value.busy)
        assertFalse(vm.state.value.retryReady)
        assertEquals("2", vm.state.value.document?.version)
        profile.failure = null
        vm.setAccepted(false)
        vm.setAccepted(true)
        vm.grant()
        assertEquals(listOf("2"), profile.attempts, "a new tick is not a new document version")
        reply.complete(Unit)
        assertEquals("3", vm.state.value.document?.version)
        assertFalse(vm.state.value.accepted, "the tick of the old document does not carry over")
        assertTrue(vm.state.value.retryReady)
    }

    @Test fun `offline document reload retains the old consent prohibition`() {
        val vm = vm()
        auth.documentFailure = AppError.Offline()
        profile.failure = outdatedFalse()
        vm.setAccepted(true)
        vm.grant()
        assertEquals("2", vm.state.value.document?.version)
        assertFalse(vm.state.value.retryReady)
        assertEquals(false, vm.state.value.requestRetryable)
        assertEquals("Не получилось загрузить документ согласия", vm.state.value.formError)
        profile.failure = null
        auth.documentFailure = null
        vm.grant()
        assertEquals(listOf("2"), profile.attempts)
        assertFalse(vm.state.value.granted)
    }

    @Test fun `a successful GET of the same version cannot clear an explicit consent refusal`() {
        val vm = vm()
        profile.failure = outdatedFalse()
        vm.setAccepted(true)
        vm.grant()
        assertEquals(2, auth.documentRequests)
        assertEquals(document, vm.state.value.document)
        assertTrue(vm.state.value.accepted, "a same-version GET does not pretend the displayed document changed")
        assertFalse(vm.state.value.retryReady)
        assertEquals(false, vm.state.value.requestRetryable)
        profile.failure = null
        vm.grant()
        assertEquals(listOf("2"), profile.attempts)
    }

    @Test fun `a different fetched version clears only the old context and requires a fresh tick`() {
        val vm = vm()
        auth.nextDocument = document.copy(version = "3")
        profile.failure = outdatedFalse()
        vm.setAccepted(true)
        vm.grant()
        assertEquals("3", vm.state.value.document?.version)
        assertTrue(vm.state.value.retryReady)
        assertNull(vm.state.value.requestRetryable)
        assertFalse(vm.state.value.accepted)
        profile.failure = null
        vm.grant()
        assertEquals(listOf("2"), profile.attempts)
        vm.setAccepted(true)
        vm.grant()
        assertEquals(listOf("2", "3"), profile.attempts)
        assertEquals(listOf("3"), profile.grants)
        assertTrue(vm.state.value.granted)
    }

    @Test fun `a stale initial document read cannot replace the corrected version or its reset tick`() {
        val initial = CompletableDeferred<ConsentDocument>()
        auth.documentCall = { call -> when (call) { 1 -> initial.await(); 2 -> document; else -> document.copy(version = "3") } }
        profile.failure = outdatedFalse()
        val vm = vm()
        vm.setAccepted(true)
        vm.grant()
        assertEquals("3", vm.state.value.document?.version)
        initial.complete(document)
        assertEquals("3", vm.state.value.document?.version)
        assertFalse(vm.state.value.accepted)
        assertTrue(vm.state.value.retryReady)
        profile.failure = null
        vm.setAccepted(true)
        vm.grant()
        assertEquals(listOf("2", "3"), profile.attempts)
    }
}
