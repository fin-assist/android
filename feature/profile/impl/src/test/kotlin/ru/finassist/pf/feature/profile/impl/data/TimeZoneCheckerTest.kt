package ru.finassist.pf.feature.profile.impl.data

import kotlinx.coroutines.runBlocking
import ru.finassist.pf.core.api.model.ProfileUpdate
import ru.finassist.pf.core.common.error.AppError
import ru.finassist.pf.core.common.time.ReportZone
import ru.finassist.pf.feature.auth.api.SessionState
import ru.finassist.pf.feature.profile.impl.domain.PendingZone
import java.time.Duration
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TimeZoneCheckerTest {
    private val api = FakeProfileApi(timezone = MOSCOW)
    private val session = FakeSession()
    private val store = tempPendingStore()
    private val clock = FakeClock()
    private val device = FakeDeviceZone(MOSCOW)
    private val reportZone = ReportZone { ZoneId.of(device.zone) }
    private val known = tempKnownStore()
    private val checker = TimeZoneChecker(api, session, store, clock, device, reportZone, known)

    private fun check() = runBlocking { checker.check() }
    private fun pending(user: String = "u1") = runBlocking { store.get(user) }
    private val now get() = clock.now().toInstant()

    @Test fun `the device in the profile zone sends nothing and records nothing`() {
        check()
        check()
        assertTrue(api.updates.isEmpty())
        assertNull(pending())
        assertEquals(1, api.gets.get(), "the profile zone is read once, then remembered")
    }

    @Test fun `a new zone is recorded, not sent, until it has held for 72 hours`() {
        device.zone = NOVOSIBIRSK
        val firstSeen = now
        check()
        assertEquals(PendingZone(NOVOSIBIRSK, firstSeen), pending())
        assertTrue(api.updates.isEmpty())

        clock.advance(Duration.ofHours(72).minusMinutes(1))
        check()
        assertEquals(PendingZone(NOVOSIBIRSK, firstSeen), pending(), "the count is not restarted by later checks")
        assertTrue(api.updates.isEmpty())
    }

    @Test fun `after 72 hours the zone is sent once and the record cleared`() {
        device.zone = NOVOSIBIRSK
        check()
        clock.advance(Duration.ofHours(72))
        check()
        assertEquals(listOf(ProfileUpdate(timezone = NOVOSIBIRSK)), api.updates)
        assertNull(pending())

        clock.advance(Duration.ofDays(10))
        check()
        assertEquals(1, api.updates.size, "the profile is in the device zone now")
        assertNull(pending())
    }

    @Test fun `going back to the profile zone earlier drops the record`() {
        device.zone = NOVOSIBIRSK
        check()
        clock.advance(Duration.ofHours(48))
        device.zone = MOSCOW
        check()
        assertNull(pending())

        // The next trip counts from its own start, not from the first one.
        device.zone = NOVOSIBIRSK
        clock.advance(Duration.ofHours(30))
        check()
        assertEquals(PendingZone(NOVOSIBIRSK, now), pending())
        assertTrue(api.updates.isEmpty())
    }

    @Test fun `another zone restarts the record`() {
        device.zone = NOVOSIBIRSK
        check()
        clock.advance(Duration.ofHours(60))
        device.zone = YEKATERINBURG
        check()
        assertEquals(PendingZone(YEKATERINBURG, now), pending())

        clock.advance(Duration.ofHours(71))
        check()
        assertTrue(api.updates.isEmpty())
        clock.advance(Duration.ofHours(1))
        check()
        assertEquals(listOf(ProfileUpdate(timezone = YEKATERINBURG)), api.updates)
    }

    @Test fun `a failed PATCH keeps the record and the next check sends again`() {
        device.zone = NOVOSIBIRSK
        check()
        val since = pending()!!.since
        clock.advance(Duration.ofHours(80))
        api.updateFailure = AppError.Offline()
        check()
        assertEquals(PendingZone(NOVOSIBIRSK, since), pending())

        api.updateFailure = null
        check()
        assertEquals(2, api.updates.size)
        assertEquals(NOVOSIBIRSK, api.timezone)
        assertNull(pending())
    }

    @Test fun `a profile that cannot be read sends nothing and keeps the record`() {
        device.zone = NOVOSIBIRSK
        api.getFailure = AppError.Offline()
        check()
        assertNull(pending(), "the profile zone is unknown, so nothing is counted yet")

        api.getFailure = null
        check()
        val since = pending()!!.since
        clock.advance(Duration.ofHours(72))
        api.getFailure = AppError.Offline()
        check()
        assertTrue(api.updates.isEmpty(), "the profile is re-read before sending")
        assertEquals(PendingZone(NOVOSIBIRSK, since), pending())
    }

    @Test fun `a zone already in the profile by the time of sending is not sent again`() {
        device.zone = NOVOSIBIRSK
        check()
        api.timezone = NOVOSIBIRSK // e.g. registration on another device
        clock.advance(Duration.ofHours(72))
        check()
        assertTrue(api.updates.isEmpty())
        assertNull(pending())
    }

    @Test fun `signed out, nothing is read, recorded or sent`() {
        session.state.value = SessionState.SignedOut
        device.zone = NOVOSIBIRSK
        check()
        assertEquals(0, api.gets.get())
        assertNull(pending())
    }

    @Test fun `sign-out drops the record and the remembered zone`() {
        device.zone = NOVOSIBIRSK
        check()
        runBlocking { checker.signedOut() }
        assertNull(pending())

        session.state.value = SessionState.SignedIn("u1")
        check()
        assertEquals(2, api.gets.get(), "the profile zone is read again after a sign-in")
    }

    @Test fun `a sign-in as another user during the check does not move their zone`() {
        device.zone = NOVOSIBIRSK
        check()
        clock.advance(Duration.ofHours(72))
        // The profile read before the PATCH already goes out with the token of u2.
        api.onGet = {
            api.userId = "u2"
            session.state.value = SessionState.SignedIn("u2")
        }
        check()
        assertTrue(api.updates.isEmpty())
        assertEquals(NOVOSIBIRSK, pending("u1")?.zone, "the record of u1 waits for u1")
    }

    @Test fun `a sign-out between the profile read and the PATCH sends nothing`() {
        device.zone = NOVOSIBIRSK
        check()
        clock.advance(Duration.ofHours(72))
        api.onGet = { session.state.value = SessionState.SignedOut }
        check()
        assertTrue(api.updates.isEmpty())
    }

    @Test fun `a PATCH answered with another user's profile keeps the record`() {
        device.zone = NOVOSIBIRSK
        check()
        clock.advance(Duration.ofHours(72))
        // Another user signed in after the session check: the PATCH went out with their token.
        api.onUpdate = { api.userId = "u2" }
        check()
        assertEquals(1, api.updates.size)
        assertEquals(NOVOSIBIRSK, pending("u1")?.zone, "the record of u1 waits for u1")
    }

    @Test fun `a record of another user is not counted`() {
        runBlocking { store.set("u0", PendingZone(NOVOSIBIRSK, now.minus(Duration.ofDays(10)))) }
        device.zone = NOVOSIBIRSK
        check()
        assertTrue(api.updates.isEmpty())
        assertEquals(PendingZone(NOVOSIBIRSK, now), pending())
        assertNull(pending("u0"))
    }

    @Test fun `the profile zone read by the check becomes the report zone and is stored`() {
        api.timezone = VLADIVOSTOK
        check()
        assertEquals(ZoneId.of(VLADIVOSTOK), reportZone.current)
        assertEquals(VLADIVOSTOK, runBlocking { known.get("u1") })
    }

    @Test fun `offline at start, the stored profile zone is the report zone`() {
        runBlocking { known.set("u1", VLADIVOSTOK) }
        api.getFailure = AppError.Offline()
        check()
        assertEquals(ZoneId.of(VLADIVOSTOK), reportZone.current)
    }

    @Test fun `a stored zone of another user is not restored`() {
        runBlocking { known.set("u0", VLADIVOSTOK) }
        api.getFailure = AppError.Offline()
        check()
        assertNull(reportZone.profile.value)
        assertEquals(ZoneId.of(MOSCOW), reportZone.current, "the device zone")
    }

    @Test fun `a sent zone becomes the report zone`() {
        device.zone = NOVOSIBIRSK
        check()
        assertEquals(ZoneId.of(MOSCOW), reportZone.current, "the profile is still in Moscow")
        clock.advance(Duration.ofHours(72))
        check()
        assertEquals(ZoneId.of(NOVOSIBIRSK), reportZone.current)
        assertEquals(NOVOSIBIRSK, runBlocking { known.get("u1") })
    }

    @Test fun `a profile read elsewhere moves the report zone, one of another user does not`() {
        check()
        runBlocking { checker.observed(api.getProfile().copy(timezone = VLADIVOSTOK)) }
        assertEquals(ZoneId.of(VLADIVOSTOK), reportZone.current)

        runBlocking { checker.observed(api.getProfile().copy(userId = "u2", timezone = NOVOSIBIRSK)) }
        assertEquals(ZoneId.of(VLADIVOSTOK), reportZone.current)
    }

    @Test fun `sign-out returns to the device zone and forgets the stored one`() {
        api.timezone = VLADIVOSTOK
        check()
        runBlocking { checker.signedOut() }
        assertNull(reportZone.profile.value)
        assertNull(runBlocking { known.get("u1") })
    }

    @Test fun `a zone id Java does not know is neither applied nor stored`() {
        api.timezone = VLADIVOSTOK
        check()
        runBlocking { checker.observed(api.getProfile().copy(timezone = "Mars/Olympus_Mons")) }
        assertEquals(ZoneId.of(VLADIVOSTOK), reportZone.current)
        assertEquals(VLADIVOSTOK, runBlocking { known.get("u1") }, "an offline start still restores the last usable zone")
    }

    @Test fun `a failing zone store does not fail the check, the profile screen or the sign-out`() {
        val failing = KnownZoneStore(FailingDataStore())
        val checker = TimeZoneChecker(api, session, store, clock, device, reportZone, failing)
        api.timezone = VLADIVOSTOK
        runBlocking { checker.check() }
        assertEquals(ZoneId.of(VLADIVOSTOK), reportZone.current)

        runBlocking { checker.observed(api.getProfile().copy(timezone = NOVOSIBIRSK)) }
        assertEquals(ZoneId.of(NOVOSIBIRSK), reportZone.current)

        runBlocking { checker.signedOut() }
        assertNull(reportZone.profile.value)
    }

    private companion object {
        const val MOSCOW = "Europe/Moscow"
        const val NOVOSIBIRSK = "Asia/Novosibirsk"
        const val YEKATERINBURG = "Asia/Yekaterinburg"
        const val VLADIVOSTOK = "Asia/Vladivostok"
    }
}
