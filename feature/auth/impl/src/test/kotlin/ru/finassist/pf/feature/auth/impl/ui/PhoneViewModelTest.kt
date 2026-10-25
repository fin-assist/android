package ru.finassist.pf.feature.auth.impl.ui

import ru.finassist.pf.core.common.time.ElapsedClock
import ru.finassist.pf.core.common.time.ResponseTiming
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlinx.coroutines.CompletableDeferred
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
import ru.finassist.pf.core.common.time.Clock
import ru.finassist.pf.core.tracking.Event
import ru.finassist.pf.core.tracking.Tracker
import ru.finassist.pf.feature.auth.impl.domain.SignInFlow
import java.time.OffsetDateTime
import java.time.ZoneId
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PhoneViewModelTest {
    private class FakeAuth : AuthApi {
        var failure: AppError? = null
        var pending: CompletableDeferred<PhoneVerification>? = null
        val attemptedPhones = mutableListOf<String>()
        val phones = mutableListOf<String>()
        val keys = mutableListOf<String>()
        override suspend fun startPhoneVerification(key: IdempotencyKey, phone: String): PhoneVerification {
            keys += key.value
            attemptedPhones += phone
            pending?.let { return it.await() }
            failure?.let { throw it }
            phones += phone
            return PhoneVerification("vt_1", "+78000000000", OffsetDateTime.parse("2026-10-13T12:03:00+03:00"), expiresIn = 300)
        }
        override fun streamPhoneVerification(verificationToken: String): Flow<PhoneVerificationStatusEvent> = error("unused")
        override suspend fun register(key: IdempotencyKey, request: RegisterRequest): RegisterResponse = error("unused")
        override suspend fun refreshTokens(key: IdempotencyKey, refreshToken: String): TokenPair = error("unused")
        override suspend fun logout(accessToken: String, refreshToken: String) = Unit
        override suspend fun getConsentDocument(type: ConsentType): ConsentDocument = error("unused")
    }

    /** Device clock, 1 h 40 min ahead of the server (12:03 − 5 min): the deadline must follow it, not `expires_at`. */
    private val deviceNow = OffsetDateTime.parse("2026-10-13T13:38:00+03:00")
    private val clock = object : Clock {
        override fun now(): OffsetDateTime = deviceNow
        override val zone: ZoneId = Clock.MOSCOW
    }
    private val auth = FakeAuth()
    private val flow = SignInFlow()
    private val tracked = mutableListOf<String>()
    private val tracker = object : Tracker {
        override fun track(event: Event, params: Map<String, String>) { tracked += event.name }
        override fun setUserId(userId: String?) = Unit
    }

    @BeforeTest fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun submitted(digits: String = "9161234567"): PhoneViewModel {
        val vm = PhoneViewModel(auth, flow, tracker, clock, ElapsedClock { 0L })
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

    @Test fun `a successful request is kept for the call screen and tracked`() {
        submitted()
        assertEquals("+79161234567", flow.phone)
        assertEquals("vt_1", flow.verification?.verificationToken)
        assertEquals(auth.keys.single(), flow.phoneRequestKey)
        assertEquals(listOf("auth.phone.submitted"), tracked)
    }

    @Test fun `a retry of the same number reuses the key, a new number gets a new one`() {
        auth.failure = AppError.Offline()
        val vm = submitted()
        assertEquals("Нет сети — проверьте интернет и повторите", vm.state.value.error)
        assertFalse(vm.state.value.busy)
        assertTrue(tracked.isEmpty(), "a failed request is not tracked")

        auth.failure = null
        vm.submit()
        assertEquals(auth.keys[0], auth.keys[1])

        vm.onInput(TextFieldValue("9031234567", TextRange(10)))
        vm.submit()
        assertNotEquals(auth.keys[1], auth.keys[2])
        assertEquals("+79031234567", flow.phone)
    }

    @Test fun `unknown origin phone key is preserved without a POST replay`() {
        flow.phone = "+79161234567"
        flow.phoneRequestKey = "restored-key"
        val vm = submitted()
        assertTrue(auth.keys.isEmpty())
        assertEquals("restored-key", flow.phoneRequestKey)
        assertTrue(vm.state.value.replayUnavailable)
        assertEquals("9161234567", vm.state.value.digits)
    }

    @Test fun `a new number drops the previous verification request`() {
        flow.phone = "+79031234567"
        flow.phoneRequestKey = "old"
        flow.verificationReceived(PhoneVerification("vt_old", "+78000000000", OffsetDateTime.parse("2026-10-13T12:03:00+03:00")), deviceNow.toInstant())
        auth.failure = AppError.Offline()
        submitted("9161234567")
        assertNull(flow.verification)
        assertNull(flow.callDeadline)
        assertNotEquals("old", flow.phoneRequestKey)
    }

    @Test fun `the call deadline is the device time of the response plus expires_in`() {
        submitted()
        assertEquals("vt_1", flow.verification?.verificationToken)
        assertEquals(deviceNow.plusSeconds(300).toInstant(), flow.callDeadline)
    }

    @Test fun `too many attempts names the time to retry when the server gives it`() {
        auth.failure = AppError.RateLimited(OffsetDateTime.parse("2026-10-13T14:05:00+03:00"))
        assertEquals("Слишком много попыток. Повторите в 14:05", submitted().state.value.error)
        auth.failure = AppError.RateLimited(null)
        assertEquals("Слишком много попыток", submitted().state.value.error)
    }

    @Test fun `a server error is the generic error`() {
        auth.failure = AppError.Server(503)
        assertEquals("Не получилось отправить номер. Попробуйте ещё раз", submitted().state.value.error)
    }

    @Test fun `the number typed before is restored with the cursor at the end`() {
        flow.phone = "+79161234567"
        val vm = PhoneViewModel(auth, flow, tracker, clock, ElapsedClock { 0L })
        assertEquals("9161234567", vm.state.value.digits)
        assertEquals(TextRange(10), vm.state.value.input.selection)
    }

    @Test fun `moving the cursor keeps the error, editing the number clears it`() {
        val vm = submitted("91612")
        vm.onInput(TextFieldValue("91612", TextRange(2)))
        assertEquals("В номере не хватает цифр", vm.state.value.error)
        assertEquals(TextRange(2), vm.state.value.input.selection)
        vm.onInput(TextFieldValue("916123", TextRange(6)))
        assertNull(vm.state.value.error)
    }

    @Test fun `the notice and the navigation event are one-shot`() {
        val vm = submitted()
        vm.consumeEvent()
        assertNull(vm.events.value)
        vm.setNotice("Вы вышли из аккаунта")
        assertEquals("Вы вышли из аккаунта", vm.state.value.notice)
        vm.setNotice(null)
        assertNull(vm.state.value.notice)
    }

    @Test fun `a late false for number A cannot forbid explicitly submitting changed number B`() {
        val reply = CompletableDeferred<PhoneVerification>()
        auth.pending = reply
        val vm = submitted()
        val originalKey = auth.keys.single()
        vm.onInput(TextFieldValue("9031234567", TextRange(10)))
        vm.submit()
        assertEquals(1, auth.keys.size, "the old request remains busy until it actually finishes")
        reply.completeExceptionally(phoneRefusal(false))
        assertFalse(vm.state.value.busy)
        assertNull(vm.state.value.error)
        assertNull(vm.state.value.requestRetryable)
        assertTrue(vm.state.value.retryReady)
        assertEquals(originalKey, flow.phoneRequestKey, "a stale response never changes the pending UUID")
        auth.pending = null
        vm.submit()
        assertEquals(listOf("+79161234567", "+79031234567"), auth.attemptedPhones)
        assertNotEquals(originalKey, auth.keys.last(), "explicitly submitting another number creates its own action")
        assertEquals(PhoneEvent.GoToCall, vm.events.value)
    }

    @Test fun `mask and cursor changes preserve the original number refusal and key`() {
        val reply = CompletableDeferred<PhoneVerification>()
        auth.pending = reply
        val vm = submitted()
        val originalKey = auth.keys.single()
        vm.onInput(TextFieldValue("+7 (916) 123-45-67", TextRange(5)))
        reply.completeExceptionally(phoneRefusal(false))
        assertEquals("9161234567", vm.state.value.digits)
        assertFalse(vm.state.value.retryReady)
        assertEquals(false, vm.state.value.requestRetryable)
        vm.onInput(TextFieldValue("9161234567", TextRange(3)))
        auth.pending = null
        vm.submit()
        assertEquals(1, auth.keys.size)
        assertEquals(originalKey, flow.phoneRequestKey)
        assertNull(vm.events.value)
    }

    @Test fun `a late Retry-After for A cannot delay the explicitly changed number B`() {
        val reply = CompletableDeferred<PhoneVerification>()
        auth.pending = reply
        val vm = submitted()
        vm.onInput(TextFieldValue("9031234567", TextRange(10)))
        reply.completeExceptionally(phoneRefusal(true))
        assertNull(vm.state.value.error)
        assertTrue(vm.state.value.retryReady)
        auth.pending = null
        vm.submit()
        assertEquals(2, auth.keys.size, "B does not inherit the old 60-second wait")
        assertEquals("+79031234567", auth.attemptedPhones.last())
        assertEquals(PhoneEvent.GoToCall, vm.events.value)
    }

    @Test fun `a late success for A cannot navigate track or install verification after editing B`() {
        val reply = CompletableDeferred<PhoneVerification>()
        auth.pending = reply
        val vm = submitted()
        val originalKey = auth.keys.single()
        vm.onInput(TextFieldValue("9031234567", TextRange(10)))
        vm.submit()
        assertEquals(1, auth.keys.size)
        reply.complete(PhoneVerification("vt_old_A", "+78000000000", deviceNow.plusMinutes(5), expiresIn = 300))
        assertFalse(vm.state.value.busy)
        assertNull(vm.events.value)
        assertNull(flow.verification)
        assertNull(flow.callDeadline)
        assertTrue(tracked.isEmpty())
        assertEquals(originalKey, flow.phoneRequestKey)
        auth.pending = null
        vm.submit()
        assertEquals("vt_1", flow.verification?.verificationToken)
        assertEquals(listOf("auth.phone.submitted"), tracked)
        assertEquals(PhoneEvent.GoToCall, vm.events.value)
        assertEquals("+79031234567", flow.phone)
    }

    private fun phoneRefusal(retryable: Boolean) = AppError.RateLimited(null, 60,
        ru.finassist.pf.core.common.error.ErrorResponseInfo("RATE_LIMITED", 429, requestRetryable = retryable,
            retryAfterSeconds = 60, timing = ResponseTiming(receivedElapsedMillis = 0)))

}
