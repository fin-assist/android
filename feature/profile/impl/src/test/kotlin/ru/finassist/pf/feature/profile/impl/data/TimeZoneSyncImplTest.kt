package ru.finassist.pf.feature.profile.impl.data

import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import ru.finassist.pf.core.api.model.ProfileUpdate
import ru.finassist.pf.feature.auth.api.SessionState
import ru.finassist.pf.feature.profile.impl.domain.PendingZone
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Robolectric: the triggers are a broadcast receiver and `ProcessLifecycleOwner`. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TimeZoneSyncImplTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val api = FakeProfileApi(timezone = "Europe/Moscow")
    private val session = FakeSession(SessionState.SignedOut)
    private val store = tempPendingStore()
    private val clock = FakeClock()
    private val device = FakeDeviceZone("Europe/Moscow")
    private val sync = TimeZoneSyncImpl(context, TimeZoneChecker(api, session, store, clock, device), session, device)

    private fun eventually(condition: suspend () -> Boolean) = runBlocking {
        withTimeout(5_000) {
            while (!condition()) {
                shadowOf(Looper.getMainLooper()).idle()
                delay(10)
            }
        }
    }

    @Test fun `sign-in checks the zone, sign-out drops the record`() {
        sync.start()
        device.zone = "Asia/Novosibirsk"
        session.state.value = SessionState.SignedIn("u1")
        eventually { store.get("u1") != null }

        session.state.value = SessionState.SignedOut
        eventually { store.get("u1") == null }
        assertTrue(api.updates.isEmpty())
    }

    @Test fun `a return to the foreground sends a zone held for 72 hours`() {
        session.state.value = SessionState.SignedIn("u1")
        device.zone = "Asia/Novosibirsk"
        sync.start()
        eventually { store.get("u1") == PendingZone("Asia/Novosibirsk", clock.now().toInstant()) }

        clock.advance(Duration.ofHours(72))
        sync.onStart(ProcessLifecycleOwner.get())
        eventually { api.updates.isNotEmpty() }
        assertEquals(listOf(ProfileUpdate(timezone = "Asia/Novosibirsk")), api.updates)
    }

    @Test fun `a device time zone change is checked at once`() {
        session.state.value = SessionState.SignedIn("u1")
        sync.start()
        sync.start() // a second start registers nothing twice
        eventually { api.gets.get() > 0 }
        assertNull(runBlocking { store.get("u1") })

        // The cached default zone is still the old one when the broadcast arrives: the receiver must re-read it.
        device.pending = "Asia/Yekaterinburg"
        context.sendBroadcast(Intent(Intent.ACTION_TIMEZONE_CHANGED))
        eventually { store.get("u1") != null }
        assertEquals("Asia/Yekaterinburg", runBlocking { store.get("u1") }?.zone)
    }
}
