package ru.finassist.pf.feature.profile.impl.data

import java.time.Duration
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Test
import ru.finassist.pf.core.common.error.AppError
import ru.finassist.pf.core.common.error.ErrorResponseInfo
import ru.finassist.pf.core.common.time.ElapsedClock
import ru.finassist.pf.core.common.time.ReportZone
import ru.finassist.pf.core.common.time.ResponseTiming
import ru.finassist.pf.feature.auth.api.SessionState
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class TimeZoneDurableRetryTest {
    private val api = FakeProfileApi()
    private val session = FakeSession()
    private val pending = tempPendingStore(session)
    private val known = tempKnownStore(session)
    private val retry = tempZoneSyncStore(session)
    private val clock = FakeClock()
    private val device = FakeDeviceZone("Asia/Novosibirsk")
    private var elapsed = 100L
    private var boot: Int? = 7
    private val report = ReportZone { ZoneId.of("Europe/Moscow") }
    private fun checker() = TimeZoneChecker(api, session, pending, clock, device, report, known, retry, ElapsedClock { elapsed }, BootCounter { boot })
    private fun failure(flag: Boolean? = null, seconds: Long? = null) = AppError.Server(503, response =
        ErrorResponseInfo("FUTURE_ERROR", 503, requestRetryable = flag, retryAfterSeconds = seconds,
            timing = ResponseTiming(receivedElapsedMillis = elapsed)))

    @Test fun `GET false survives restart and target change but new login corrects its context`() = runBlocking<Unit> {
        api.getFailure = failure(false)
        checker().check()
        api.getFailure = null
        device.zone = "Asia/Yekaterinburg"
        checker().check()
        assertEquals(1, api.gets.get())
        session.state.value = SessionState.SignedIn("u1", "epoch-2")
        checker().check()
        assertEquals(2, api.gets.get())
    }

    @Test fun `GET wait uses original elapsed deadline across process restart`() = runBlocking<Unit> {
        api.getFailure = failure(true, 10)
        checker().check()
        api.getFailure = null
        elapsed = 10_099
        checker().check()
        assertEquals(1, api.gets.get())
        elapsed = 10_100
        checker().check()
        assertEquals(2, api.gets.get())
    }

    @Test fun `reboot and unknown boot never restart or expire an old wait`() = runBlocking<Unit> {
        api.getFailure = failure(true, 10)
        checker().check()
        api.getFailure = null
        elapsed = 1_000_000
        boot = 8
        checker().check()
        boot = null
        checker().check()
        assertEquals(1, api.gets.get())
    }

    @Test fun `unknown boot at receipt makes wait fail closed even when later boot is readable`() = runBlocking<Unit> {
        boot = null
        api.getFailure = failure(true, 10)
        checker().check()
        api.getFailure = null
        boot = 7
        elapsed = 1_000_000
        checker().check()
        assertEquals(1, api.gets.get())
    }

    @Test fun `PATCH false is not cleared by repeated successful GET or same observed zone`() = runBlocking<Unit> {
        val active = checker()
        active.check()
        clock.advance(Duration.ofHours(72))
        api.updateFailure = failure(false)
        active.check()
        api.updateFailure = null
        val origin = active.captureObservation()
        active.observed(api.getProfile(), origin)
        repeat(3) { active.check() }
        checker().check()
        assertEquals(1, api.updates.size)
        assertNotNull(pending.get("u1"))
    }

    @Test fun `PATCH wait expires independently and actual new target counts its own 72 hours`() = runBlocking<Unit> {
        var active = checker()
        active.check()
        clock.advance(Duration.ofHours(72))
        api.updateFailure = failure(true, 10)
        active.check()
        api.updateFailure = null
        elapsed = 10_099
        active = checker()
        active.check()
        assertEquals(1, api.updates.size)
        elapsed = 10_100
        active.check()
        assertEquals(2, api.updates.size)
        assertNull(pending.get("u1"))
    }

    @Test fun `changed PATCH target retires its false without granting early domain send`() = runBlocking<Unit> {
        val active = checker()
        active.check()
        clock.advance(Duration.ofHours(72))
        api.updateFailure = failure(false)
        active.check()
        api.updateFailure = null
        device.zone = "Asia/Yekaterinburg"
        active.check()
        assertEquals(1, api.updates.size)
        clock.advance(Duration.ofHours(72))
        active.check()
        assertEquals(2, api.updates.size)
        assertEquals("Asia/Yekaterinburg", api.updates.last().timezone)
    }

    @Test fun `ordinary offline failure permits later same context retry`() = runBlocking<Unit> {
        api.getFailure = AppError.Offline()
        checker().check()
        api.getFailure = null
        checker().check()
        assertEquals(2, api.gets.get())
    }

    @Test fun `cancelled in flight remains uncertain after new process`() = runBlocking<Unit> {
        api.onGet = { throw CancellationException("cancel") }
        assertFailsWith<CancellationException> { checker().check() }
        api.onGet = {}
        checker().check()
        assertEquals(1, api.gets.get())
    }

    @Test fun `late response of previous same user login cannot remember or clear new owner's state`() = runBlocking<Unit> {
        api.onGet = { session.state.value = SessionState.SignedIn("u1", "epoch-2") }
        checker().check()
        assertNull(known.get("u1"))
        assertNull(pending.get("u1"))
        api.onGet = {}
        checker().check()
        assertEquals(2, api.gets.get())
    }

    @Test fun `external old observation cannot overwrite a later background mutation`() = runBlocking<Unit> {
        val active = checker()
        active.check()
        val origin = active.captureObservation()
        val old = api.getProfile()
        clock.advance(Duration.ofHours(72))
        active.check()
        active.observed(old, origin)
        assertEquals("Asia/Novosibirsk", known.get("u1"))
    }

    @Test fun `unknown session epoch never dispatches background traffic`() = runBlocking<Unit> {
        session.state.value = SessionState.SignedIn("u1")
        checker().check()
        assertEquals(0, api.gets.get())
    }
}
