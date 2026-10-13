package ru.finassist.pf.feature.auth.impl.ui

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import ru.finassist.pf.core.api.TokenStore
import ru.finassist.pf.core.api.model.PhoneVerification
import ru.finassist.pf.core.api.model.PhoneVerificationStatusEvent
import ru.finassist.pf.core.api.model.TokenPair
import ru.finassist.pf.core.api.model.VerificationStatus
import ru.finassist.pf.core.common.error.AppError
import ru.finassist.pf.core.common.time.Clock
import ru.finassist.pf.core.toggles.Flag
import ru.finassist.pf.feature.auth.impl.SessionHarness
import ru.finassist.pf.feature.auth.impl.domain.CallLockText
import ru.finassist.pf.feature.auth.impl.eventually
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
class CallViewModelTest {
    private val now = OffsetDateTime.parse("2026-10-13T14:22:00+03:00")
    private val clock = object : Clock {
        override fun now(): OffsetDateTime = now
        override val zone: ZoneId = Clock.MOSCOW
    }
    private val harnesses = mutableListOf<SessionHarness>()

    /** Streams opened, by verification token. */
    private val opened = mutableListOf<String>()

    @BeforeTest fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest fun tearDown() {
        Dispatchers.resetMain()
        harnesses.forEach { it.close() }
    }

    private fun verification(token: String = "vt_1", callback: String = "+78001000000") =
        PhoneVerification(token, callback, now.plusMinutes(3))

    private fun harness(registration: Boolean = true) =
        SessionHarness(flagValues = mapOf(Flag.AUTH_REGISTRATION to registration)).also { h ->
            harnesses += h
            h.flow.phone = "+79161234567"
            h.flow.phoneRequestKey = "key-1"
            h.flow.verification = verification()
        }

    private fun SessionHarness.vm(vararg events: PhoneVerificationStatusEvent, failure: AppError? = null): CallViewModel {
        auth.stream = { token ->
            opened += token
            flow {
                events.forEach { emit(it) }
                failure?.let { throw it }
            }
        }
        return CallViewModel(auth, flow, repo, flags, tracker, clock)
    }

    private fun event(status: VerificationStatus) = PhoneVerificationStatusEvent(status)

    @Test fun `the screen shows the number being verified and the number to call`() {
        val vm = harness().vm()
        assertEquals("+7 916 123-45-67", vm.state.value.phoneDisplay)
        assertEquals("+78001000000", vm.state.value.callbackNumber)
        assertEquals(CallPhase.WAITING, vm.state.value.phase)
    }

    @Test fun `without a verification request there is nothing to watch`() {
        val h = harness()
        h.flow.verification = null
        val vm = h.vm()
        vm.watch()
        assertTrue(opened.isEmpty())
        assertEquals("", vm.state.value.callbackNumber)
    }

    @Test fun `resume does not open a second stream while one is live`() {
        val h = harness()
        h.auth.stream = { token -> opened += token; MutableSharedFlow() }
        val vm = CallViewModel(h.auth, h.flow, h.repo, h.flags, h.tracker, clock)
        vm.watch()
        vm.watch()
        assertEquals(listOf("vt_1"), opened)
        vm.stopWatching()
        vm.watch()
        assertEquals(listOf("vt_1", "vt_1"), opened, "re-opened after the screen came back")
    }

    @Test fun `pending keeps waiting, expired and unknown statuses end the request`() {
        val pending = harness().vm(event(VerificationStatus.PENDING)).apply { watch() }
        assertEquals(CallPhase.WAITING, pending.state.value.phase)
        val expired = harness().vm(event(VerificationStatus.EXPIRED)).apply { watch() }
        assertEquals(CallPhase.EXPIRED, expired.state.value.phase)
        val unknown = harness().vm(event(VerificationStatus.PENDING), event(VerificationStatus.UNKNOWN)).apply { watch() }
        assertEquals(CallPhase.EXPIRED, unknown.state.value.phase)
    }

    @Test fun `stream errors map to offline, expired and locked`() {
        val offline = harness().vm(failure = AppError.Offline()).apply { watch() }
        assertEquals(CallPhase.OFFLINE, offline.state.value.phase)
        val unauthorized = harness().vm(failure = AppError.Unauthorized("VERIFICATION_TOKEN_INVALID")).apply { watch() }
        assertEquals(CallPhase.EXPIRED, unauthorized.state.value.phase)
        val server = harness().vm(failure = AppError.Server(503)).apply { watch() }
        assertEquals(CallPhase.EXPIRED, server.state.value.phase)

        val retryAt = now.plusMinutes(10)
        val locked = harness().vm(failure = AppError.RateLimited(retryAt)).apply { watch() }
        assertEquals(CallPhase.LOCKED, locked.state.value.phase)
        assertEquals(CallLockText.of(retryAt, now, Clock.MOSCOW), locked.state.value.lockText)
        assertEquals("Слишком много попыток. Позвонить можно будет через 10 минут, в 14:32", locked.state.value.lockText)
    }

    @Test fun `a new number goes to consent with its registration token`() {
        val h = harness(registration = true)
        val vm = h.vm(PhoneVerificationStatusEvent(VerificationStatus.VERIFIED, isNewUser = true, registrationToken = "reg_1"))
        vm.watch()
        assertEquals(CallEvent.NewUser, vm.events.value)
        assertEquals("reg_1", h.flow.registrationToken)
        assertNull(h.tokens.flow.value, "no session for a new number")
        vm.consumeEvent()
        assertNull(vm.events.value)
    }

    @Test fun `a new number with registration switched off is told so`() {
        val h = harness(registration = false)
        val vm = h.vm(PhoneVerificationStatusEvent(VerificationStatus.VERIFIED, isNewUser = true, registrationToken = "reg_1"))
        vm.watch()
        assertEquals(CallEvent.RegistrationClosed, vm.events.value)
    }

    @Test fun `an existing user is signed in with the tokens from the stream`() = runBlocking {
        val h = harness()
        h.profile.userId = "u7"
        val vm = h.vm(PhoneVerificationStatusEvent(VerificationStatus.VERIFIED, isNewUser = false, accessToken = "a", refreshToken = "r"))
        vm.watch()
        eventually { vm.events.value != null }
        assertEquals(CallEvent.SignedIn, vm.events.value)
        assertEquals(TokenStore.Session("u7", TokenPair("a", "r")), h.tokens.current())
        assertTrue("track:auth.verified" in h.log.all())
    }

    @Test fun `verified without tokens is treated as expired`() {
        val h = harness()
        val vm = h.vm(PhoneVerificationStatusEvent(VerificationStatus.VERIFIED, isNewUser = false, accessToken = "a"))
        vm.watch()
        assertEquals(CallPhase.EXPIRED, vm.state.value.phase)
        assertNull(vm.events.value)
        assertNull(h.tokens.flow.value)
    }

    @Test fun `a failed profile request after verification shows offline or expired`() {
        val verified = PhoneVerificationStatusEvent(VerificationStatus.VERIFIED, isNewUser = false, accessToken = "a", refreshToken = "r")
        val offline = harness().apply { profile.failure = AppError.Offline() }
        val vmOffline = offline.vm(verified).apply { watch() }
        eventually { vmOffline.state.value.phase == CallPhase.OFFLINE }
        assertNull(vmOffline.events.value)
        assertNull(offline.tokens.flow.value)

        val server = harness().apply { profile.failure = AppError.Server(500) }
        val vmServer = server.vm(verified).apply { watch() }
        eventually { vmServer.state.value.phase == CallPhase.EXPIRED }
        assertNull(vmServer.events.value)
    }

    @Test fun `calling again starts a new request with a new key and watches it`() {
        val h = harness()
        val keys = mutableListOf<String>()
        h.auth.startPhone = { key, phone ->
            keys += key.value
            assertEquals("+79161234567", phone)
            verification(token = "vt_2", callback = "+78002000000")
        }
        val vm = h.vm(event(VerificationStatus.EXPIRED))
        vm.watch()
        assertEquals(CallPhase.EXPIRED, vm.state.value.phase)

        h.auth.stream = { token -> opened += token; flowOf(event(VerificationStatus.PENDING)) }
        vm.requestAgain()

        assertEquals(1, keys.size)
        assertNotEquals("key-1", keys.single(), "a new action gets a new idempotency key")
        assertEquals(keys.single(), h.flow.phoneRequestKey)
        assertEquals("vt_2", h.flow.verification?.verificationToken)
        assertEquals(listOf("vt_1", "vt_2"), opened)
        assertEquals(CallPhase.WAITING, vm.state.value.phase)
        assertEquals("+78002000000", vm.state.value.callbackNumber)
        assertFalse(vm.state.value.busy)
    }

    @Test fun `calling again too often locks the screen`() {
        val h = harness()
        h.auth.startPhone = { _, _ -> throw AppError.RateLimited(null) }
        val vm = h.vm(event(VerificationStatus.EXPIRED)).apply { watch() }
        vm.requestAgain()
        assertEquals(CallPhase.LOCKED, vm.state.value.phase)
        assertEquals("Слишком много попыток. Позвонить можно будет позже", vm.state.value.lockText)
        assertFalse(vm.state.value.busy)
    }

    @Test fun `calling again offline or with an error keeps the screen out of waiting`() {
        val offline = harness().apply { auth.startPhone = { _, _ -> throw AppError.Offline() } }
        val vmOffline = offline.vm(event(VerificationStatus.EXPIRED)).apply { watch() }
        vmOffline.requestAgain()
        assertEquals(CallPhase.OFFLINE, vmOffline.state.value.phase)
        assertFalse(vmOffline.state.value.busy)

        val failed = harness().apply { auth.startPhone = { _, _ -> throw AppError.Api("SOMETHING", 400) } }
        val vmFailed = failed.vm(failure = AppError.Offline()).apply { watch() }
        assertEquals(CallPhase.OFFLINE, vmFailed.state.value.phase)
        vmFailed.requestAgain()
        assertEquals(CallPhase.EXPIRED, vmFailed.state.value.phase)
        assertFalse(vmFailed.state.value.busy)
    }

    @Test fun `opening the dialer is tracked`() {
        val h = harness()
        h.vm().onDialOpened()
        assertEquals(listOf("track:auth.call.started"), h.log.all().filter { it.startsWith("track:") })
    }
}
