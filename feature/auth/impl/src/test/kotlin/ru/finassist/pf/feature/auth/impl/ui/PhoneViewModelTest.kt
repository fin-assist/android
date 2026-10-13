package ru.finassist.pf.feature.auth.impl.ui

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import ru.finassist.pf.core.api.AuthApi
import ru.finassist.pf.core.api.model.ConsentDocument
import ru.finassist.pf.core.api.model.ConsentType
import ru.finassist.pf.core.api.model.ErrorCodes
import ru.finassist.pf.core.api.model.FieldErrorCodes
import ru.finassist.pf.core.api.model.IdempotencyKey
import ru.finassist.pf.core.api.model.PhoneVerification
import ru.finassist.pf.core.api.model.PhoneVerificationStatusEvent
import ru.finassist.pf.core.api.model.RegisterRequest
import ru.finassist.pf.core.api.model.RegisterResponse
import ru.finassist.pf.core.api.model.TokenPair
import ru.finassist.pf.core.common.error.AppError
import ru.finassist.pf.core.tracking.NoopTracker
import ru.finassist.pf.feature.auth.impl.domain.SignInFlow
import java.time.OffsetDateTime
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class PhoneViewModelTest {
    private class FakeAuth : AuthApi {
        var failure: AppError? = null
        val phones = mutableListOf<String>()
        override suspend fun startPhoneVerification(key: IdempotencyKey, phone: String): PhoneVerification {
            failure?.let { throw it }
            phones += phone
            return PhoneVerification("vt_1", "+78000000000", OffsetDateTime.parse("2026-10-13T12:03:00+03:00"))
        }
        override fun streamPhoneVerification(verificationToken: String): Flow<PhoneVerificationStatusEvent> = error("unused")
        override suspend fun register(key: IdempotencyKey, request: RegisterRequest): RegisterResponse = error("unused")
        override suspend fun refreshTokens(key: IdempotencyKey, refreshToken: String): TokenPair = error("unused")
        override suspend fun logout(accessToken: String, refreshToken: String) = Unit
        override suspend fun getConsentDocument(type: ConsentType): ConsentDocument = error("unused")
    }

    private val auth = FakeAuth()

    @BeforeTest fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun submitted(digits: String = "9161234567"): PhoneViewModel {
        val vm = PhoneViewModel(auth, SignInFlow(), NoopTracker)
        vm.onInput(TextFieldValue(digits, TextRange(digits.length)))
        vm.submit()
        return vm
    }

    private fun validation(field: String?, code: String = FieldErrorCodes.INVALID_FORMAT) =
        AppError.Api(ErrorCodes.VALIDATION_ERROR, 400, details = listOf(AppError.FieldError(field, code, "bad")))

    @Test fun `a complete number goes to the call screen`() {
        val vm = submitted()
        assertEquals(listOf("+79161234567"), auth.phones)
        assertEquals(PhoneEvent.GoToCall, vm.events.value)
        assertNull(vm.state.value.error)
    }

    @Test fun `the server's format violation at phone is the field error`() {
        auth.failure = validation("phone")
        val vm = submitted()
        assertEquals("Нужен российский номер: +7 и 10 цифр", vm.state.value.error)
        assertNull(vm.events.value)
        assertFalse(vm.state.value.busy)
    }

    @Test fun `any violation code at phone is the same field error`() {
        auth.failure = validation("phone", "SOMETHING_NEW")
        assertEquals("Нужен российский номер: +7 и 10 цифр", submitted().state.value.error)
    }

    @Test fun `a validation error not at phone, or another code, is the generic error`() {
        auth.failure = validation("other")
        assertEquals("Не получилось отправить номер. Попробуйте ещё раз", submitted().state.value.error)
        auth.failure = AppError.Api("INVALID_PHONE", 400)
        assertEquals("Не получилось отправить номер. Попробуйте ещё раз", submitted().state.value.error)
    }

    @Test fun `a short number is caught before the request`() {
        val vm = submitted("91612")
        assertEquals("В номере не хватает цифр", vm.state.value.error)
        assertEquals(emptyList(), auth.phones)
    }
}
