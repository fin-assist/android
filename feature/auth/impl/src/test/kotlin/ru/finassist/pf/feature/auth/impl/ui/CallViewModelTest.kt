package ru.finassist.pf.feature.auth.impl.ui

import ru.finassist.pf.core.common.time.ElapsedClock
import ru.finassist.pf.core.common.time.ResponseTiming
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import ru.finassist.pf.core.api.TokenStore
import ru.finassist.pf.core.api.model.PhoneVerification
import ru.finassist.pf.core.api.model.PhoneVerificationStatusEvent
import ru.finassist.pf.core.api.model.TokenPair
import ru.finassist.pf.core.api.model.VerificationStatus
import ru.finassist.pf.core.common.error.AppError
import ru.finassist.pf.core.common.error.ErrorResponseInfo
import ru.finassist.pf.core.common.time.Clock
import ru.finassist.pf.core.toggles.Flag
import ru.finassist.pf.feature.auth.impl.SessionHarness
import ru.finassist.pf.feature.auth.impl.domain.CallDeadline
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
    /** Device time; tests move it to model a device that slept while the timer coroutine did not run. */
    private var deviceNow = now
    private val clock = object : Clock {
        override fun now(): OffsetDateTime = deviceNow
        override val zone: ZoneId = Clock.MOSCOW
    }
    private val harnesses = mutableListOf<SessionHarness>()

    /** Streams opened, by verification token. */
    private val opened = mutableListOf<String>()

    /** Virtual time of the deadline timer (`delay` in the view model). */
    private val scheduler = TestCoroutineScheduler()
    private var elapsedOffset = 0L

    @BeforeTest fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))

    @AfterTest fun tearDown() {
        Dispatchers.resetMain()
        harnesses.forEach { it.close() }
    }

    private fun verification(token: String = "vt_1", callback: String = "+78001000000", expiresIn: Int? = 300) =
        PhoneVerification(token, callback, now.plusMinutes(5), expiresIn = expiresIn, responseTiming = ResponseTiming(receivedElapsedMillis = scheduler.currentTime))

    /** «ЧЧ:ММ» the screen shows for a deadline [seconds] after [now] (device zone of the test JVM). */
    private fun clockText(seconds: Long) = CallDeadline.clockText(now.plusSeconds(seconds).toInstant(), ZoneId.systemDefault())

    private fun advanceSeconds(seconds: Long) {
        scheduler.advanceTimeBy(seconds * 1000)
        scheduler.runCurrent()
    }

    private fun harness(registration: Boolean = true) =
        SessionHarness(flagValues = mapOf(Flag.AUTH_REGISTRATION to registration)).also { h ->
            harnesses += h
            h.flow.phone = "+79161234567"
            h.flow.phoneRequestKey = "key-1"
            h.flow.verificationReceived(verification(), now.toInstant())
        }

    private fun SessionHarness.vm(vararg events: PhoneVerificationStatusEvent, failure: AppError? = null): CallViewModel {
        auth.stream = { token ->
            opened += token
            flow {
                events.forEach { emit(it) }
                failure?.let { throw it }
            }
        }
        return CallViewModel(auth, flow, repo, flags, tracker, clock, ElapsedClock { scheduler.currentTime + elapsedOffset })
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
        h.flow.clearVerification()
        val vm = h.vm()
        vm.watch()
        assertTrue(opened.isEmpty())
        assertEquals("", vm.state.value.callbackNumber)
    }

    @Test fun `resume does not open a second stream while one is live`() {
        val h = harness()
        h.auth.stream = { token -> opened += token; MutableSharedFlow() }
        val vm = CallViewModel(h.auth, h.flow, h.repo, h.flags, h.tracker, clock, ElapsedClock { scheduler.currentTime + elapsedOffset })
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
        assertEquals(CallPhase.ERROR, server.state.value.phase)

        val retryAt = now.plusMinutes(10)
        val locked = harness().vm(failure = AppError.RateLimited(retryAt)).apply { watch() }
        assertEquals(CallPhase.LOCKED, locked.state.value.phase)
        assertEquals(CallLockText.withRemaining(retryAt, null, Clock.MOSCOW), locked.state.value.lockText)
        assertEquals("Слишком много попыток. Позвонить можно будет позже, в 14:32", locked.state.value.lockText)
    }

    /** Each opening of the stream takes the next flow of [openings]; none left — a stream that never ends. */
    private fun SessionHarness.scripted(vararg openings: kotlinx.coroutines.flow.Flow<PhoneVerificationStatusEvent>): CallViewModel {
        val queue = ArrayDeque(openings.toList())
        auth.stream = { token ->
            opened += token
            queue.removeFirstOrNull() ?: flow { awaitCancellation() }
        }
        return CallViewModel(auth, flow, repo, flags, tracker, clock, ElapsedClock { scheduler.currentTime + elapsedOffset })
    }

    private val newUser = PhoneVerificationStatusEvent(VerificationStatus.VERIFIED, isNewUser = true, registrationToken = "reg_1")

    @Test fun `maintenance refusal allows one guarded read recovery without replacing verification`() {
        val h = harness()
        val refused = AppError.Maintenance(null, 15, ErrorResponseInfo(
            code = "MAINTENANCE", httpStatus = 503, requestRetryable = false,
            timing = ResponseTiming(receivedElapsedMillis = scheduler.currentTime), retryAfterSeconds = 15,
        ))
        val vm = h.scripted(flow { throw refused }, flowOf(newUser))
        vm.watch()
        assertEquals(listOf("vt_1"), opened)
        vm.stopWatching()
        vm.watch()
        scheduler.advanceTimeBy(14_999); scheduler.runCurrent()
        assertEquals(listOf("vt_1"), opened, "the corrected stream cannot bypass Retry-After")
        scheduler.advanceTimeBy(1); scheduler.runCurrent()
        assertEquals(listOf("vt_1", "vt_1"), opened)
        assertEquals("vt_1", h.flow.verification?.verificationToken, "the original verification is retained")
        assertEquals(CallEvent.NewUser, vm.events.value)
    }

    @Test fun `a stream closed after pending is re-opened after a pause and the wait goes on without an error`() {
        val h = harness()
        val vm = h.scripted(flowOf(event(VerificationStatus.PENDING)), flowOf(event(VerificationStatus.PENDING), newUser))
        vm.watch()
        assertEquals(listOf("vt_1"), opened)
        assertEquals(CallPhase.WAITING, vm.state.value.phase)
        // The first pause is random in [0.5 s; 1 s] (api.md «SSE»).
        scheduler.advanceTimeBy(499)
        scheduler.runCurrent()
        assertEquals(1, opened.size, "not re-opened before the pause")
        scheduler.advanceTimeBy(501)
        scheduler.runCurrent()
        assertEquals(listOf("vt_1", "vt_1"), opened)
        assertEquals(CallEvent.NewUser, vm.events.value)
    }

    @Test fun `a network drop of an open stream is re-opened instead of showing offline`() {
        val h = harness()
        val dropped = flow {
            emit(event(VerificationStatus.PENDING))
            throw AppError.Offline()
        }
        // The re-open fails too (still no network) and is retried; then the stream comes back.
        val vm = h.scripted(dropped, flow { throw AppError.Offline() }, flowOf(event(VerificationStatus.PENDING), newUser))
        vm.watch()
        assertEquals(CallPhase.WAITING, vm.state.value.phase)
        advanceSeconds(1)
        assertEquals(CallPhase.WAITING, vm.state.value.phase, "a failed re-open is not «нет сети»")
        advanceSeconds(2)
        assertEquals(3, opened.size)
        assertEquals(CallEvent.NewUser, vm.events.value)
    }

    @Test fun `a 429 on a re-open waits for retry_at and does not lock the screen`() {
        val h = harness()
        val vm = h.scripted(
            flowOf(event(VerificationStatus.PENDING)),
            flow { throw AppError.RateLimited(now.plusSeconds(20), retryAfterSeconds = 20) },
            flowOf(event(VerificationStatus.PENDING), newUser),
        )
        vm.watch()
        advanceSeconds(1)
        assertEquals(2, opened.size)
        assertEquals(CallPhase.WAITING, vm.state.value.phase)
        advanceSeconds(19)
        assertEquals(2, opened.size, "not before Retry-After")
        // The re-open came after at most 1 s, then Retry-After plus up to d/2 = 1 s.
        advanceSeconds(2)
        assertEquals(3, opened.size)
        assertEquals(CallEvent.NewUser, vm.events.value)
    }

    @Test fun `coming back to the screen during a 429 pause waits again instead of locking, with one stream at a time`() {
        val h = harness()
        var active = 0
        var maxActive = 0
        fun tracked(f: kotlinx.coroutines.flow.Flow<PhoneVerificationStatusEvent>) = f
            .onStart { active++; maxActive = maxOf(maxActive, active) }
            .onCompletion { active-- }
        val limitedAt = mutableListOf<Long>()
        val limited = { tracked(flow { limitedAt += scheduler.currentTime; throw AppError.RateLimited(now.plusSeconds(60), retryAfterSeconds = 60) }) }
        val vm = h.scripted(
            tracked(flowOf(event(VerificationStatus.PENDING))),
            limited(),
            // The next opening after the retained wait gets a fresh 429 with its own wait.
            limited(),
            tracked(flowOf(event(VerificationStatus.PENDING), newUser)),
        )
        vm.watch()
        advanceSeconds(1)
        assertEquals(2, opened.size, "the drop was re-opened and got 429")
        advanceSeconds(4)
        vm.stopWatching()
        vm.watch()
        assertEquals(2, opened.size, "resume must not bypass the retained Retry-After")
        assertEquals(CallPhase.WAITING, vm.state.value.phase, "a 429 on opening the stream again does not lock the screen")
        assertNull(vm.state.value.lockText)
        fun advanceTo(target: Long) {
            scheduler.advanceTimeBy(target - scheduler.currentTime)
            scheduler.runCurrent()
        }
        val firstRefusal = limitedAt.single()
        advanceTo(firstRefusal + 59_999)
        assertEquals(2, opened.size, "not before the original Retry-After")
        advanceTo(firstRefusal + 62_001) // retained wait + original jitter <= 1 s, not a new wait on resume
        assertEquals(3, opened.size)
        val secondRefusal = limitedAt.last()
        advanceTo(secondRefusal + 59_999)
        assertEquals(3, opened.size, "a fresh 429 gets its own Retry-After")
        advanceTo(secondRefusal + 62_001)
        assertEquals(4, opened.size)
        assertEquals(CallEvent.NewUser, vm.events.value)
        assertEquals(1, maxActive, "never two streams at once")
    }

    @Test fun `a 429 on the first opening of the request still locks the screen`() {
        val h = harness()
        val vm = h.scripted(flow { throw AppError.RateLimited(now.plusMinutes(10)) })
        vm.watch()
        assertEquals(CallPhase.LOCKED, vm.state.value.phase)
    }

    @Test fun `leaving the screen during a pause does not re-open the stream`() {
        val h = harness()
        val vm = h.scripted(flowOf(event(VerificationStatus.PENDING)))
        vm.watch()
        vm.stopWatching()
        advanceSeconds(60)
        assertEquals(listOf("vt_1"), opened)
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
            verification(token = "vt_2", callback = "+78002000000", expiresIn = 120)
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
        assertEquals(clockText(120), vm.state.value.waitUntil, "the deadline of the new request")
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
        assertEquals(CallPhase.ERROR, vmFailed.state.value.phase)
        assertFalse(vmFailed.state.value.busy)
    }

    @Test fun `a lost phone request response retains its key and never resends automatically on resume`() {
        val h = harness()
        val keys = mutableListOf<String>()
        h.auth.startPhone = { key, _ -> keys += key.value; throw AppError.Offline() }
        val vm = h.vm(event(VerificationStatus.EXPIRED)).apply { watch() }
        vm.requestAgain()
        vm.watch() // lifecycle resume does not POST or read the previous token
        assertEquals(1, keys.size)
        vm.retry()
        assertEquals(2, keys.size)
        assertEquals(keys.first(), keys.last())
        elapsedOffset = 24L * 60 * 60 * 1000
        vm.retry()
        assertEquals(2, keys.size)
        assertTrue(vm.state.value.replayUnavailable)
    }

    @Test fun `opening the dialer is tracked`() {
        val h = harness()
        h.vm().onDialOpened()
        assertEquals(listOf("track:auth.call.started"), h.log.all().filter { it.startsWith("track:") })
    }

    // ---- device deadline (api.md 1.1, «Срок на устройстве») ----

    /** Streams by opening order: [first] for the first one, [rest] for every one after it. */
    private fun SessionHarness.streams(first: () -> kotlinx.coroutines.flow.Flow<PhoneVerificationStatusEvent>, rest: () -> kotlinx.coroutines.flow.Flow<PhoneVerificationStatusEvent>) {
        auth.stream = { token -> opened += token; if (opened.size == 1) first() else rest() }
    }

    private fun pendingForever() = flow { emit(event(VerificationStatus.PENDING)); awaitCancellation() }

    @Test fun `the wait shows the device deadline`() {
        val vm = harness().vm()
        assertEquals(clockText(300), vm.state.value.waitUntil)
    }

    @Test fun `without a deadline no time is shown and no timer hides the number`() {
        val h = harness()
        h.flow.verificationReceived(verification(expiresIn = null), now.toInstant())
        h.auth.stream = { token -> opened += token; pendingForever() }
        val vm = CallViewModel(h.auth, h.flow, h.repo, h.flags, h.tracker, clock, ElapsedClock { scheduler.currentTime + elapsedOffset })
        vm.watch()
        assertNull(vm.state.value.waitUntil)
        advanceSeconds(3600)
        assertEquals(CallPhase.WAITING, vm.state.value.phase)
        assertEquals(listOf("vt_1"), opened)
        vm.stopWatching()
    }

    @Test fun `at the deadline the number leaves the screen and the stream confirms expiry`() {
        val h = harness()
        h.streams(first = ::pendingForever, rest = { flowOf(event(VerificationStatus.EXPIRED)) })
        val vm = CallViewModel(h.auth, h.flow, h.repo, h.flags, h.tracker, clock, ElapsedClock { scheduler.currentTime + elapsedOffset })
        vm.watch()
        advanceSeconds(300)
        assertEquals(CallPhase.WAITING, vm.state.value.phase, "at the deadline itself the number is still there (grace)")
        advanceSeconds(1)
        assertEquals(listOf("vt_1", "vt_1"), opened, "the stream is re-opened to confirm the status")
        assertEquals(CallPhase.EXPIRED, vm.state.value.phase)
        assertNull(vm.state.value.waitUntil)
    }

    @Test fun `the number stays hidden until the re-opened stream answers`() {
        val h = harness()
        h.streams(first = ::pendingForever, rest = { flow { awaitCancellation() } })
        val vm = CallViewModel(h.auth, h.flow, h.repo, h.flags, h.tracker, clock, ElapsedClock { scheduler.currentTime + elapsedOffset })
        vm.watch()
        advanceSeconds(301)
        assertEquals(CallPhase.CHECKING, vm.state.value.phase)
        vm.stopWatching()
    }

    @Test fun `a 429 on the deadline re-check keeps the number hidden and waits for Retry-After instead of locking`() {
        val h = harness()
        val vm = h.scripted(
            pendingForever(),
            flow { throw AppError.RateLimited(now.plusSeconds(361), retryAfterSeconds = 60) },
            flowOf(event(VerificationStatus.PENDING)),
        )
        vm.watch()
        advanceSeconds(301)
        assertEquals(CallPhase.CHECKING, vm.state.value.phase, "not LOCKED: the stream had been open on this screen")
        assertNull(vm.state.value.lockText)
        assertEquals(2, opened.size)
        advanceSeconds(59)
        assertEquals(2, opened.size, "not before Retry-After")
        // Retry-After plus up to d/2 = 0.5 s.
        advanceSeconds(2)
        assertEquals(3, opened.size)
        assertEquals(CallPhase.WAITING, vm.state.value.phase)
        assertNull(vm.state.value.waitUntil, "the deadline has passed: no time is promised")
        vm.stopWatching()
    }

    @Test fun `the deadline confirmed by a closed request is a fresh start`() {
        val h = harness()
        h.streams(first = ::pendingForever, rest = { flow { throw AppError.Unauthorized("INVALID_TOKEN") } })
        val vm = CallViewModel(h.auth, h.flow, h.repo, h.flags, h.tracker, clock, ElapsedClock { scheduler.currentTime + elapsedOffset })
        vm.watch()
        advanceSeconds(301)
        assertEquals(CallPhase.EXPIRED, vm.state.value.phase)
    }

    @Test fun `pending after the deadline (device clock ahead) brings the number back without a time`() {
        val h = harness()
        h.streams(first = ::pendingForever, rest = ::pendingForever)
        val vm = CallViewModel(h.auth, h.flow, h.repo, h.flags, h.tracker, clock, ElapsedClock { scheduler.currentTime + elapsedOffset })
        vm.watch()
        advanceSeconds(301)
        assertEquals(CallPhase.WAITING, vm.state.value.phase)
        assertEquals("+78001000000", vm.state.value.callbackNumber)
        assertNull(vm.state.value.waitUntil)
        advanceSeconds(600)
        assertEquals(listOf("vt_1", "vt_1"), opened, "the timer fires once per request; then the stream decides")
        vm.stopWatching()
    }

    @Test fun `a deadline passed in the background hides the number and the stream opens on resume`() {
        val h = harness()
        h.streams(first = ::pendingForever, rest = { flowOf(event(VerificationStatus.EXPIRED)) })
        val vm = CallViewModel(h.auth, h.flow, h.repo, h.flags, h.tracker, clock, ElapsedClock { scheduler.currentTime + elapsedOffset })
        vm.watch()
        vm.stopWatching()
        advanceSeconds(301)
        assertEquals(CallPhase.CHECKING, vm.state.value.phase)
        assertEquals(listOf("vt_1"), opened, "no stream while the screen is in the background")
        vm.watch()
        assertEquals(CallPhase.EXPIRED, vm.state.value.phase)
    }

    @Test fun `a deadline already passed when the screen opens hides the number at once`() {
        val h = harness()
        h.flow.verificationReceived(verification(expiresIn = 0), now.toInstant())
        h.auth.stream = { token -> opened += token; flowOf(event(VerificationStatus.EXPIRED)) }
        val vm = CallViewModel(h.auth, h.flow, h.repo, h.flags, h.tracker, clock, ElapsedClock { scheduler.currentTime + elapsedOffset })
        assertEquals(CallPhase.CHECKING, vm.state.value.phase)
        advanceSeconds(1)
        assertEquals(CallPhase.CHECKING, vm.state.value.phase)
        vm.watch()
        assertEquals(CallPhase.EXPIRED, vm.state.value.phase)
    }

    @Test fun `the timer does not override a locked or offline screen`() {
        val h = harness()
        h.auth.stream = { token -> opened += token; flow { throw AppError.Offline() } }
        val vm = CallViewModel(h.auth, h.flow, h.repo, h.flags, h.tracker, clock, ElapsedClock { scheduler.currentTime + elapsedOffset })
        vm.watch()
        assertEquals(CallPhase.OFFLINE, vm.state.value.phase)
        advanceSeconds(301)
        assertEquals(CallPhase.OFFLINE, vm.state.value.phase)
        assertEquals(listOf("vt_1"), opened)
    }

    @Test fun `calling again after the deadline arms a new timer`() {
        val h = harness()
        h.streams(first = ::pendingForever, rest = { flowOf(event(VerificationStatus.EXPIRED)) })
        val vm = CallViewModel(h.auth, h.flow, h.repo, h.flags, h.tracker, clock, ElapsedClock { scheduler.currentTime + elapsedOffset })
        vm.watch()
        advanceSeconds(301)
        assertEquals(CallPhase.EXPIRED, vm.state.value.phase)

        h.auth.startPhone = { _, _ -> verification(token = "vt_2", expiresIn = 60) }
        h.auth.stream = { token -> opened += token; pendingForever() }
        vm.requestAgain()
        assertEquals(CallPhase.WAITING, vm.state.value.phase)
        assertEquals(clockText(60), vm.state.value.waitUntil)
        advanceSeconds(61)
        assertEquals(CallPhase.WAITING, vm.state.value.phase, "re-opened stream says pending: number back")
        assertNull(vm.state.value.waitUntil)
        assertEquals(listOf("vt_1", "vt_1", "vt_2", "vt_2"), opened)
        vm.stopWatching()
    }

    @Test fun `once verified the deadline no longer re-opens the stream`() {
        val h = harness(registration = true)
        h.auth.stream = { token ->
            opened += token
            flowOf(PhoneVerificationStatusEvent(VerificationStatus.VERIFIED, isNewUser = true, registrationToken = "reg_1"))
        }
        val vm = CallViewModel(h.auth, h.flow, h.repo, h.flags, h.tracker, clock, ElapsedClock { scheduler.currentTime + elapsedOffset })
        vm.watch()
        assertEquals(CallEvent.NewUser, vm.events.value)
        advanceSeconds(301)
        assertEquals(listOf("vt_1"), opened)
        assertEquals(CallPhase.WAITING, vm.state.value.phase)
    }

    @Test fun `the wait text names the deadline only when there is one`() {
        assertEquals("Как только дозвонитесь, вход продолжится сам. Ждём звонок до 12:34", waitText("12:34"))
        assertEquals("Как только дозвонитесь, вход продолжится сам.", waitText(null))
    }

    @Test fun `the timer fires a second after the deadline, the shown time stays the deadline`() {
        val h = harness()
        h.streams(first = ::pendingForever, rest = { flow { awaitCancellation() } })
        val vm = CallViewModel(h.auth, h.flow, h.repo, h.flags, h.tracker, clock, ElapsedClock { scheduler.currentTime + elapsedOffset })
        vm.watch()
        assertEquals(clockText(300), vm.state.value.waitUntil, "«до ЧЧ:ММ» is the deadline itself, without the grace")
        advanceSeconds(300)
        assertEquals(CallPhase.WAITING, vm.state.value.phase)
        assertEquals(listOf("vt_1"), opened)
        advanceSeconds(1)
        assertEquals(CallPhase.CHECKING, vm.state.value.phase)
        assertEquals(listOf("vt_1", "vt_1"), opened)
        vm.stopWatching()
    }

    @Test fun `back on the screen before the deadline only the time left is waited`() {
        val h = harness()
        h.streams(first = ::pendingForever, rest = ::pendingForever)
        val vm = CallViewModel(h.auth, h.flow, h.repo, h.flags, h.tracker, clock, ElapsedClock { scheduler.currentTime + elapsedOffset })
        vm.watch()
        vm.stopWatching()
        // The device slept for 200 s: the clock moved, the timer coroutine did not.
        deviceNow = now.plusSeconds(200)
        elapsedOffset = 200_000L
        vm.watch()
        advanceSeconds(100)
        assertEquals(CallPhase.WAITING, vm.state.value.phase)
        assertEquals(clockText(300), vm.state.value.waitUntil)
        advanceSeconds(1)
        assertNull(vm.state.value.waitUntil, "fired 101 s after the return, not 301 s")
        assertEquals(listOf("vt_1", "vt_1", "vt_1"), opened, "re-opened at the deadline")
        vm.stopWatching()
    }

    @Test fun `back on the screen after the deadline the number is hidden and one stream confirms`() {
        val h = harness()
        h.streams(first = ::pendingForever, rest = { flow { awaitCancellation() } })
        val vm = CallViewModel(h.auth, h.flow, h.repo, h.flags, h.tracker, clock, ElapsedClock { scheduler.currentTime + elapsedOffset })
        vm.watch()
        vm.stopWatching()
        deviceNow = now.plusSeconds(400)
        elapsedOffset = 400_000L
        vm.watch()
        assertEquals(CallPhase.CHECKING, vm.state.value.phase)
        assertNull(vm.state.value.waitUntil)
        assertEquals(listOf("vt_1", "vt_1"), opened, "exactly one stream on the return")
        vm.stopWatching()
    }
    @Test fun `changing phone wall clock does not expire a fresh verification countdown`() {
        val h = harness()
        h.auth.stream = { pendingForever() }
        val vm = CallViewModel(h.auth, h.flow, h.repo, h.flags, h.tracker, clock, ElapsedClock { scheduler.currentTime })
        vm.watch()
        vm.stopWatching()
        deviceNow = now.plusDays(3)
        vm.watch()
        advanceSeconds(299)
        assertEquals(CallPhase.WAITING, vm.state.value.phase)
        advanceSeconds(2)
        assertNull(vm.state.value.waitUntil)
        vm.stopWatching()
    }

}
