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
import kotlin.test.assertTrue

class TimeZoneDurableRetryTest {
    private val api = FakeProfileApi()
    private val session = FakeSession()
    private val preferences = tempZoneSyncPreferences(session)
    private val pending = PendingZoneStore(preferences)
    private val known = KnownZoneStore(preferences)
    private val clock = FakeClock()
    private val device = FakeDeviceZone("Asia/Novosibirsk")
    private var elapsed = 100L
    private var boot: Int? = 7
    private val report = ReportZone { ZoneId.of("Europe/Moscow") }
    private fun checker() = TimeZoneChecker(api, session, PendingZoneStore(preferences), clock, device, report,
        KnownZoneStore(preferences), ZoneSyncStateStore(preferences), ElapsedClock { elapsed }, BootCounter { boot })
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

    @Test fun `applied PATCH interrupted before outcome converges through independent GET after restart`() = runBlocking<Unit> {
        val active = checker()
        active.check()
        clock.advance(Duration.ofHours(72))
        api.onUpdate = {
            api.timezone = device.zone // server committed; client never persisted the success
            throw CancellationException("process ended before durable PATCH outcome")
        }
        assertFailsWith<CancellationException> { active.check() }
        val saved = assertNotNull(pending.get("u1"))
        val owner = assertNotNull(active.captureOwner())
        assertEquals(ZoneSyncStateStore.Phase.IN_FLIGHT, ZoneSyncStateStore(preferences).current(owner)?.patch?.attempt?.phase)
        assertEquals(1, api.updates.size)
        val getsBeforeRestart = api.gets.get()
        api.onUpdate = {}

        checker().check() // new checker AND all store wrappers over the durable preferences

        assertTrue(api.gets.get() > getsBeforeRestart, "PATCH InFlight must not block independent GET")
        assertNull(pending.get("u1"), "fresh profile proves the recorded target already reached the server")
        assertEquals(saved.zone, known.get("u1"))
        assertEquals(1, api.updates.size, "do not send another PATCH after GET proved convergence")
    }

    @Test fun `unapplied interrupted PATCH remains blocked while independent GET runs after restart`() = runBlocking<Unit> {
        val active = checker()
        active.check()
        clock.advance(Duration.ofHours(72))
        api.onUpdate = { throw CancellationException("process ended before durable PATCH outcome") }
        assertFailsWith<CancellationException> { active.check() }
        val saved = assertNotNull(pending.get("u1"))
        val owner = assertNotNull(active.captureOwner())
        val actionId = assertNotNull(ZoneSyncStateStore(preferences).current(owner)?.patch?.attempt?.id)
        val getsBeforeRestart = api.gets.get()
        api.onUpdate = {}
        clock.advance(Duration.ofDays(8))
        elapsed += Duration.ofDays(8).toMillis()

        checker().check()
        checker().check()

        assertTrue(api.gets.get() > getsBeforeRestart, "GET remains independently available")
        assertEquals(saved, pending.get("u1"), "GET showing the old zone does not resolve the uncertain PATCH")
        assertEquals(1, api.updates.size, "elapsed time and fresh GET must not authorize another uncertain PATCH")
        val restored = assertNotNull(ZoneSyncStateStore(preferences).current(owner)?.patch?.attempt)
        assertEquals(ZoneSyncStateStore.Phase.IN_FLIGHT, restored.phase)
        assertEquals(actionId, restored.id)
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
