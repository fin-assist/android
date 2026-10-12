package ru.finassist.pf.feature.assistant.impl.ui

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import ru.finassist.pf.core.api.AuthApi
import ru.finassist.pf.core.api.ProfileApi
import ru.finassist.pf.core.api.model.AssistantConsent
import ru.finassist.pf.core.api.model.AssistantConsentGrant
import ru.finassist.pf.core.api.model.ConsentDocument
import ru.finassist.pf.core.api.model.ConsentType
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
        override suspend fun startPhoneVerification(key: IdempotencyKey, phone: String): PhoneVerification = error("unused")
        override fun streamPhoneVerification(verificationToken: String): Flow<PhoneVerificationStatusEvent> = error("unused")
        override suspend fun register(key: IdempotencyKey, request: RegisterRequest): RegisterResponse = error("unused")
        override suspend fun refreshTokens(key: IdempotencyKey, refreshToken: String): TokenPair = error("unused")
        override suspend fun logout(accessToken: String, refreshToken: String) = Unit
        override suspend fun getConsentDocument(type: ConsentType): ConsentDocument { documentRequests++; return doc }
    }

    private class FakeProfile : ProfileApi {
        var failure: AppError? = null
        val grants = mutableListOf<String>()
        override suspend fun getProfile(): Profile = error("unused")
        override suspend fun updateProfile(update: ProfileUpdate): Profile = error("unused")
        override suspend fun deleteAccount() = Unit
        override suspend fun grantAssistantConsent(grant: AssistantConsentGrant): Profile {
            failure?.let { throw it }
            grants += grant.version
            return Profile(
                userId = "u1", phone = "+79161234567", theme = Theme.SYSTEM, timezone = "Europe/Moscow",
                assistantConsent = AssistantConsent(granted = true, version = grant.version, currentVersion = grant.version),
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
        val vm = AiConsentViewModel(auth, profile)
        vm.grant()
        assertEquals("Без согласия помощник не сможет ответить", vm.state.value.error)
        assertTrue(profile.grants.isEmpty())
        vm.setAccepted(true)
        assertNull(vm.state.value.error)
    }

    @Test fun `with the tick the current document version is granted`() {
        val vm = AiConsentViewModel(auth, profile)
        vm.setAccepted(true)
        vm.grant()
        assertEquals(listOf("2"), profile.grants)
        assertTrue(vm.state.value.granted)
    }

    @Test fun `no network is shown under the form`() {
        profile.failure = AppError.Offline()
        val vm = AiConsentViewModel(auth, profile)
        vm.setAccepted(true)
        vm.grant()
        assertEquals("Нет сети — проверьте интернет и повторите", vm.state.value.formError)
        assertFalse(vm.state.value.busy)
        assertFalse(vm.state.value.granted)
    }

    @Test fun `an outdated consent reloads the document`() {
        profile.failure = AppError.Api(ErrorCodes.CONSENT_OUTDATED, 409)
        val vm = AiConsentViewModel(auth, profile)
        val before = auth.documentRequests
        vm.setAccepted(true)
        vm.grant()
        assertEquals("Текст согласия обновился — прочитайте его ещё раз и подтвердите", vm.state.value.formError)
        assertEquals(before + 1, auth.documentRequests)
        assertEquals(document, vm.state.value.document)
    }
}
