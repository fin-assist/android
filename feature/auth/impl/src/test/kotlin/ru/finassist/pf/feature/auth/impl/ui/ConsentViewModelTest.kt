package ru.finassist.pf.feature.auth.impl.ui

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import ru.finassist.pf.core.api.TokenStore
import ru.finassist.pf.core.api.model.*
import ru.finassist.pf.core.common.error.AppError
import ru.finassist.pf.core.common.error.ErrorResponseInfo
import ru.finassist.pf.core.common.time.ElapsedClock
import ru.finassist.pf.core.storage.IdempotencyKeys
import ru.finassist.pf.feature.auth.impl.SessionHarness
import ru.finassist.pf.feature.auth.impl.eventually
import java.util.TimeZone
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ConsentViewModelTest {
    private val h = SessionHarness()
    private val keys = IdempotencyKeys(h.dataStore)
    private val requests = mutableListOf<Pair<String, RegisterRequest>>()
    private val failures = ArrayDeque<AppError>()
    private var pending: CompletableDeferred<Unit>? = null
    private var documentCalls = 0

    @BeforeTest fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        h.flow.phone = "+79161234567"
        h.flow.registrationToken = "reg_1"
        h.flow.phoneRequest = StartPhoneVerificationRequest(h.flow.phone, true, "pd-1", true)
        h.flow.consentDocument = ConsentDocument(ConsentType.PERSONAL_DATA, "pd-1", "Согласие", "https://example.test/pd-1")
        h.auth.consent = { documentCalls++; error("Register must not refetch or replace the accepted document") }
        h.auth.registerCall = { key, request ->
            requests += key.value to request
            pending?.await()
            failures.removeFirstOrNull()?.let { throw it }
            RegisterResponse("a_new", "r_new", "u_new")
        }
    }
    @AfterTest fun tearDown() { Dispatchers.resetMain(); h.close() }
    private fun vm(clock: ElapsedClock = ElapsedClock { 0L }) = ConsentViewModel(h.auth, h.flow, h.repo, keys, clock)
    private fun ConsentViewModel.send() { submit(); eventually { !state.value.busy } }

    @Test fun `shows the original document without receiving consent again`() {
        val vm = vm()
        assertEquals("pd-1", vm.state.value.document?.version)
        assertEquals("+7 916 123-45-67", vm.state.value.phoneDisplay)
        assertEquals(0, documentCalls)
    }
    @Test fun `Register sends the pinned Start evidence and starts the session`() = runBlocking {
        val vm = vm(); vm.send()
        eventually { vm.registered.value }
        assertEquals(RegisterRequest("reg_1", true, "pd-1", TimeZone.getDefault().id), requests.single().second)
        assertEquals(TokenStore.Session("u_new", TokenPair("a_new", "r_new")), h.tokens.current())
        assertTrue(h.repo.consumeJustRegistered())
        assertEquals(0, documentCalls)
    }
    @Test fun `lost response reuses identical body and key even after device zone changes`() {
        failures += AppError.Offline()
        val zone = TimeZone.getDefault()
        try {
            val vm = vm(); vm.send()
            assertEquals("Не получилось создать аккаунт", vm.state.value.formError)
            assertTrue(vm.state.value.errorPresentation?.connectionFailure == true)
            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Honolulu"))
            vm.send()
            assertEquals(requests[0], requests[1])
            assertTrue(vm.registered.value)
        } finally { TimeZone.setDefault(zone) }
    }
    @Test fun `a registration server error is not presented as a connection failure`() {
        failures += AppError.Server(503)
        val vm = vm(); vm.send()
        assertEquals(false, vm.state.value.errorPresentation?.connectionFailure)
        assertFalse(vm.registered.value)
    }

    @Test fun `missing token or original Start evidence requires a new phone verification`() {
        h.flow.registrationToken = null
        val missingToken = vm(); missingToken.submit()
        assertTrue(missingToken.state.value.expired)
        h.flow.registrationToken = "reg_1"; h.flow.phoneRequest = null
        val missingEvidence = vm(); missingEvidence.submit()
        assertTrue(missingEvidence.state.value.expired)
        assertTrue(requests.isEmpty())
    }
    @Test fun `invalid local confirmations never register`() {
        for (request in listOf(h.flow.phoneRequest!!.copy(ageConfirmed = false), h.flow.phoneRequest!!.copy(pdConsentAccepted = false))) {
            h.flow.phoneRequest = request
            val vm = vm(); vm.submit()
            assertTrue(vm.state.value.expired)
        }
        assertTrue(requests.isEmpty())
    }
    @Test fun `server evidence mismatch starts over instead of accepting a new version`() {
        failures += AppError.Api(ErrorCodes.CONSENT_OUTDATED, 422)
        val vm = vm(); vm.send()
        assertTrue(vm.state.value.expired)
        assertEquals("pd-1", requests.single().second.pdConsentVersion)
        assertEquals(0, documentCalls)
    }
    @Test fun `unknown and expired registration tokens start over`() {
        for (error in listOf(AppError.Unauthorized(ErrorCodes.TOKEN_EXPIRED), AppError.Api(ErrorCodes.INVALID_TOKEN, 401), AppError.Api(ErrorCodes.CONSENT_REQUIRED, 403))) {
            failures += error
            h.flow.registrationToken = "reg_1"
            h.flow.phoneRequest = StartPhoneVerificationRequest(h.flow.phone, true, "pd-1", true)
            val vm = vm(); vm.send()
            assertTrue(vm.state.value.expired)
        }
    }
    @Test fun `server error stays on the same confirmation screen`() {
        failures += AppError.Server(500)
        val vm = vm(); vm.send()
        assertFalse(vm.state.value.expired)
        assertEquals("Не получилось создать аккаунт. Попробуйте ещё раз", vm.state.value.formError)
    }
    @Test fun `explicit original request refusal prevents another POST`() {
        failures += AppError.Server(500, response = ErrorResponseInfo("INTERNAL_ERROR", 500, requestRetryable = false))
        val vm = vm(); vm.send(); vm.submit()
        assertEquals(1, requests.size)
        assertFalse(vm.state.value.retryReady)
    }
    @Test fun `duplicate taps while registering create a single request`() {
        pending = CompletableDeferred()
        val vm = vm(); vm.submit()
        eventually { requests.size == 1 }
        vm.submit()
        assertEquals(1, requests.size)
        pending!!.complete(Unit)
        eventually { !vm.state.value.busy }
    }
}
