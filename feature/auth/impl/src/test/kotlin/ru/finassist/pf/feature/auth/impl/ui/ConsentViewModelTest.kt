package ru.finassist.pf.feature.auth.impl.ui

import ru.finassist.pf.core.common.time.ElapsedClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import ru.finassist.pf.core.api.TokenStore
import ru.finassist.pf.core.api.model.ConsentDocument
import ru.finassist.pf.core.api.model.ConsentType
import ru.finassist.pf.core.api.model.ErrorCodes
import ru.finassist.pf.core.api.model.RegisterRequest
import ru.finassist.pf.core.api.model.RegisterResponse
import ru.finassist.pf.core.api.model.TokenPair
import ru.finassist.pf.core.common.error.AppError
import ru.finassist.pf.core.storage.IdempotencyKeys
import ru.finassist.pf.feature.auth.impl.SessionHarness
import ru.finassist.pf.feature.auth.impl.eventually
import java.util.TimeZone
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ConsentViewModelTest {
    private val harnesses = mutableListOf<SessionHarness>()
    private val h = SessionHarness().also { harnesses += it }
    private val keys = IdempotencyKeys(h.dataStore)

    private var version = "pd-1"
    private var consentFailure: AppError? = null
    private var consentCalls = 0
    private var documentGate: CompletableDeferred<Unit>? = null
    private val requests = mutableListOf<Pair<String, RegisterRequest>>()
    private val registerFailures = ArrayDeque<AppError>()

    @BeforeTest fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        h.flow.phone = "+79161234567"
        h.flow.registrationToken = "reg_1"
        h.auth.consent = { type ->
            consentCalls++
            assertEquals(ConsentType.PERSONAL_DATA, type)
            documentGate?.await()
            consentFailure?.let { throw it }
            ConsentDocument(type, version, "Согласие", "https://example.org/pd/$version")
        }
        h.auth.registerCall = { key, request ->
            requests += key.value to request
            registerFailures.removeFirstOrNull()?.let { throw it }
            RegisterResponse("a_new", "r_new", "u_new")
        }
    }

    @AfterTest fun tearDown() {
        Dispatchers.resetMain()
        harnesses.forEach { it.close() }
    }

    private fun vm(clock: ElapsedClock = ElapsedClock { 0L }) = ConsentViewModel(h.auth, h.flow, h.repo, keys, clock)

    /** Submits and waits for the request to settle: the idempotency key is read from DataStore off the main thread. */
    private fun ConsentViewModel.send() {
        submit()
        eventually { !state.value.busy }
    }

    private fun ConsentViewModel.acceptAndSubmit() {
        setAccepted(true)
        send()
    }

    @Test fun `the consent document is loaded on open`() {
        val vm = vm()
        assertEquals("pd-1", vm.state.value.document?.version)
        assertEquals("+7 916 123-45-67", vm.state.value.phoneDisplay)
    }

    @Test fun `without the tick nothing is sent`() {
        val vm = vm()
        vm.submit()
        assertEquals("Без согласия мы не сможем создать аккаунт и хранить ваши данные", vm.state.value.error)
        assertTrue(requests.isEmpty())
        vm.setAccepted(true)
        assertNull(vm.state.value.error, "ticking the box clears the error")
    }

    @Test fun `without a registration token the user has to call again`() {
        h.flow.registrationToken = null
        val vm = vm()
        vm.acceptAndSubmit()
        assertTrue(vm.state.value.expired)
        assertTrue(requests.isEmpty())
    }

    @Test fun `registration sends the accepted version and starts the session`() = runBlocking {
        val vm = vm()
        vm.acceptAndSubmit()
        eventually { vm.registered.value }

        val request = requests.single().second
        assertEquals(RegisterRequest("reg_1", pdConsentAccepted = true, pdConsentVersion = "pd-1", timezone = TimeZone.getDefault().id), request)
        assertEquals(TokenStore.Session("u_new", TokenPair("a_new", "r_new")), h.tokens.current())
        assertTrue(h.repo.consumeJustRegistered(), "the first upload step follows a registration")
        assertFalse(vm.state.value.busy)
        assertNull(vm.state.value.formError)
    }

    @Test fun `a document that failed to load on open is fetched on submit`() = runBlocking {
        consentFailure = AppError.Offline()
        val vm = vm()
        assertNull(vm.state.value.document)
        consentFailure = null
        version = "pd-2"
        vm.acceptAndSubmit()
        eventually { vm.registered.value }
        assertEquals("pd-2", requests.single().second.pdConsentVersion)
    }

    @Test fun `a retry after a lost response reuses the key, a completed one does not`() = runBlocking {
        registerFailures += AppError.Offline()
        val vm = vm()
        vm.acceptAndSubmit()
        assertEquals("Нет сети — проверьте интернет и повторите", vm.state.value.formError)
        assertFalse(vm.state.value.busy)
        assertFalse(vm.registered.value)

        vm.send()
        assertTrue(vm.registered.value)
        assertEquals(2, requests.size)
        assertEquals(requests[0].first, requests[1].first, "same token and version → same Idempotency-Key")

        // Completed: the same action now gets a fresh key.
        val action = requests[0].first
        assertNotEquals(action, keysFor())
    }

    /** Key the view model would use now for the same token and version. */
    private fun keysFor(): String = runBlocking {
        val sha = java.security.MessageDigest.getInstance("SHA-256").digest("reg_1|pd-1".toByteArray())
            .joinToString("") { "%02x".format(it) }
        keys.keyFor("auth.register:$sha").value
    }

    @Test fun `restored registration key has no fresh replay proof and is never renewed implicitly`() {
        val restored = keysFor() // legacy constructor/storage record has unknown origin
        val vm = vm()
        vm.acceptAndSubmit()
        assertTrue(requests.isEmpty())
        assertTrue(vm.state.value.replayUnavailable)
        assertFalse(vm.state.value.busy)
        assertEquals(restored, keysFor())
        assertEquals("reg_1", h.flow.registrationToken)
    }

    @Test fun `an outdated consent reloads the document and asks for a new tick`() {
        registerFailures += AppError.Api(ErrorCodes.CONSENT_OUTDATED, 409)
        val vm = vm()
        version = "pd-2"
        vm.acceptAndSubmit()

        assertEquals("pd-2", vm.state.value.document?.version)
        assertFalse(vm.state.value.accepted)
        assertEquals("Текст согласия обновился — прочитайте его ещё раз и подтвердите", vm.state.value.formError)
        assertFalse(vm.state.value.busy)

        vm.send()
        assertEquals(1, requests.size, "the old tick does not carry over")
        vm.acceptAndSubmit()
        eventually { vm.registered.value }
        assertEquals("pd-2", requests[1].second.pdConsentVersion)
        assertNotEquals(requests[0].first, requests[1].first, "a new version is a new action")
    }

    @Test fun `an outdated consent whose reload fails still asks for a new tick`() {
        registerFailures += AppError.Api(ErrorCodes.CONSENT_OUTDATED, 409)
        val vm = vm()
        consentFailure = AppError.Offline()
        vm.acceptAndSubmit()
        assertFalse(vm.state.value.accepted)
        assertEquals("pd-1", vm.state.value.document?.version)
        assertEquals("Текст согласия обновился — прочитайте его ещё раз и подтвердите", vm.state.value.formError)
    }

    private fun outdatedFalse() = AppError.Api(ErrorCodes.CONSENT_OUTDATED, 409,
        response = ru.finassist.pf.core.common.error.ErrorResponseInfo(ErrorCodes.CONSENT_OUTDATED, 409, requestRetryable = false))

    @Test fun `same-version GET cannot lift a false-denied registration or replace its key`() {
        registerFailures += outdatedFalse()
        val vm = vm()
        vm.acceptAndSubmit()
        val original = requests.single().first
        assertEquals("pd-1", vm.state.value.document?.version)
        assertFalse(vm.state.value.retryReady)
        assertEquals(false, vm.state.value.requestRetryable)
        vm.acceptAndSubmit()
        assertEquals(1, requests.size, "a new tick on the same document cannot resend the forbidden body")
        assertEquals(original, keysFor(), "the old operation UUID remains intact")
    }

    @Test fun `different fetched version permits corrected registration only after a new tick`() {
        registerFailures += outdatedFalse()
        val vm = vm()
        version = "pd-2"
        vm.acceptAndSubmit()
        val original = requests.single().first
        assertEquals("pd-2", vm.state.value.document?.version)
        assertFalse(vm.state.value.accepted)
        assertTrue(vm.state.value.retryReady)
        assertNull(vm.state.value.requestRetryable)
        vm.send()
        assertEquals(1, requests.size)
        vm.acceptAndSubmit()
        assertTrue(vm.registered.value)
        assertEquals(listOf("pd-1", "pd-2"), requests.map { it.second.pdConsentVersion })
        assertNotEquals(original, requests.last().first, "only the corrected version creates another logical registration")
        assertEquals(original, keysFor(), "the used old UUID was not renewed or replaced")
    }

    @Test fun `a delayed same-version document read never re-enables the denied registration`() {
        registerFailures += outdatedFalse()
        val vm = vm()
        val reply = CompletableDeferred<Unit>()
        documentGate = reply
        vm.setAccepted(true)
        vm.submit()
        eventually { requests.size == 1 && consentCalls == 2 }
        val original = requests.single().first
        assertTrue(vm.state.value.busy)
        vm.submit()
        assertEquals(1, requests.size)
        reply.complete(Unit)
        eventually { !vm.state.value.busy }
        assertFalse(vm.state.value.retryReady)
        vm.acceptAndSubmit()
        assertEquals(1, requests.size)
        assertEquals(original, keysFor())
    }

    @Test fun `a spent registration token sends the user back to the call`() {
        registerFailures += AppError.Unauthorized("REGISTRATION_TOKEN_INVALID")
        val vm = vm()
        vm.acceptAndSubmit()
        assertTrue(vm.state.value.expired)

        registerFailures += AppError.Api("REGISTRATION_TOKEN_EXPIRED", 401)
        val vm2 = vm()
        vm2.acceptAndSubmit()
        assertTrue(vm2.state.value.expired)
        assertNull(h.tokens.flow.value)
    }

    @Test fun `other errors show the generic message`() {
        registerFailures += AppError.Server(500)
        val vm = vm()
        vm.acceptAndSubmit()
        assertEquals("Не получилось создать аккаунт. Попробуйте ещё раз", vm.state.value.formError)
        assertFalse(vm.state.value.expired)
        assertFalse(vm.registered.value)

        registerFailures += AppError.Api("VALIDATION_ERROR", 422)
        vm.send()
        assertEquals("Не получилось создать аккаунт. Попробуйте ещё раз", vm.state.value.formError)
    }

    @Test fun `offline corrective document GET can be recovered without resending the old registration`() {
        registerFailures += outdatedFalse()
        val vm = vm()
        consentFailure = AppError.Offline()
        vm.acceptAndSubmit()
        val original = requests.single().first
        assertTrue(vm.state.value.documentRefreshRequired)
        assertTrue(vm.state.value.documentRefreshReady)
        consentFailure = null
        version = "pd-2"
        vm.refreshDocument()
        eventually { !vm.state.value.busy }
        assertEquals(3, consentCalls)
        assertEquals(1, requests.size, "refreshDocument performs only GET")
        assertFalse(vm.state.value.documentRefreshRequired)
        assertFalse(vm.state.value.accepted)
        assertEquals(original, keysFor())
        assertEquals("reg_1", h.flow.registrationToken)
        vm.send()
        assertEquals(1, requests.size)
        vm.acceptAndSubmit()
        assertEquals("pd-2", requests.last().second.pdConsentVersion)
        assertTrue(vm.registered.value)
    }

    @Test fun `same-version document refresh permits another GET but never the old registration`() {
        registerFailures += outdatedFalse()
        val vm = vm()
        vm.acceptAndSubmit()
        val original = requests.single().first
        repeat(2) {
            assertTrue(vm.state.value.documentRefreshReady)
            vm.refreshDocument()
            eventually { !vm.state.value.busy }
            assertTrue(vm.state.value.documentRefreshRequired)
            assertFalse(vm.state.value.retryReady)
            vm.acceptAndSubmit()
        }
        assertEquals(4, consentCalls)
        assertEquals(1, requests.size)
        assertEquals(original, keysFor())
    }

    @Test fun `a pending manual document refresh admits only one GET and no registration`() {
        registerFailures += outdatedFalse()
        val vm = vm()
        vm.acceptAndSubmit()
        val reply = CompletableDeferred<Unit>()
        documentGate = reply
        vm.refreshDocument()
        assertTrue(vm.state.value.busy)
        assertFalse(vm.state.value.documentRefreshReady)
        repeat(3) { vm.refreshDocument(); vm.setAccepted(true); vm.submit() }
        assertEquals(3, consentCalls)
        assertEquals(1, requests.size)
        reply.complete(Unit)
        eventually { !vm.state.value.busy }
        assertTrue(vm.state.value.documentRefreshReady)
        assertFalse(vm.state.value.retryReady)
    }

    @Test fun `document GET Retry-After retains its original deadline across refresh taps`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        registerFailures += outdatedFalse()
        val vm = vm(ElapsedClock { testScheduler.currentTime })
        consentFailure = documentReadRefusal(true)
        vm.acceptAndSubmit()
        consentFailure = null
        version = "pd-2"
        advanceTimeBy(2_000); runCurrent()
        vm.refreshDocument()
        assertEquals(2, consentCalls)
        assertFalse(vm.state.value.documentRefreshReady)
        advanceTimeBy(2_999); runCurrent()
        vm.refreshDocument()
        assertEquals(2, consentCalls)
        advanceTimeBy(1); runCurrent()
        assertTrue(vm.state.value.documentRefreshReady)
        vm.refreshDocument()
        eventually { !vm.state.value.busy }
        assertEquals(3, consentCalls)
        assertTrue(vm.state.value.retryReady)
        assertFalse(vm.state.value.documentRefreshRequired)
        assertEquals(1, requests.size)
    }

    @Test fun `explicit GET refusal blocks document reads after its wait without changing registration key`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        registerFailures += outdatedFalse()
        val vm = vm(ElapsedClock { testScheduler.currentTime })
        consentFailure = documentReadRefusal(false)
        vm.acceptAndSubmit()
        val original = requests.single().first
        advanceTimeBy(60_000); runCurrent()
        repeat(3) { vm.refreshDocument(); vm.setAccepted(true); vm.submit() }
        assertEquals(2, consentCalls)
        assertEquals(1, requests.size)
        assertTrue(vm.state.value.documentRefreshRequired)
        assertFalse(vm.state.value.documentRefreshReady)
        assertEquals(original, keysFor())
    }

    private fun documentReadRefusal(retryable: Boolean) = AppError.RateLimited(null, 5,
        ru.finassist.pf.core.common.error.ErrorResponseInfo("RATE_LIMITED", 429, requestRetryable = retryable,
            retryAfterSeconds = 5, timing = ru.finassist.pf.core.common.time.ResponseTiming(receivedElapsedMillis = 0)))

}
