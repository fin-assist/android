package ru.finassist.pf.feature.applock.impl.data

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.biometrics.BiometricManager
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowBiometricManager
import org.robolectric.shadows.ShadowSystemClock
import ru.finassist.pf.core.tracking.Event
import ru.finassist.pf.core.tracking.Tracker
import ru.finassist.pf.feature.applock.api.BiometricAvailability
import ru.finassist.pf.feature.applock.api.LockState
import ru.finassist.pf.feature.applock.api.VerifyResult
import ru.finassist.pf.feature.auth.api.AuthRoutes
import ru.finassist.pf.feature.auth.api.SessionRepository
import ru.finassist.pf.feature.auth.api.SessionState
import java.io.File
import java.time.Duration
import java.util.Collections
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Robolectric: the lock reads `BiometricManager` and `PackageManager` and observes `ProcessLifecycleOwner`. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppLockImplTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dir: File = createTempDirectory("applock").toFile()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dataStore = PreferenceDataStoreFactory.create(scope = scope) { File(dir, "test.preferences_pb") }
    private val crypto = FakePasscodeCrypto()
    private val store = PasscodeStore(dataStore, crypto)

    private inner class FakeSession : SessionRepository {
        override val state = MutableStateFlow<SessionState>(SessionState.SignedIn("u1"))
        val signOuts = Collections.synchronizedList(mutableListOf<String?>())
        /** Whether a passcode was still stored when the session was ended. */
        var configuredAtSignOut: Boolean? = null
        override suspend fun consumeJustRegistered() = false
        override suspend fun signOut(reason: String?) {
            configuredAtSignOut = store.isConfigured.first()
            signOuts += reason
        }
        override suspend fun clearLocal(reason: String?) = Unit
        override fun consumeSignOutReason(): String? = null
    }

    private val session = FakeSession()
    private val tracked = Collections.synchronizedList(mutableListOf<Pair<String, Map<String, String>>>())
    private val tracker = object : Tracker {
        override fun track(event: Event, params: Map<String, String>) { tracked += event.name to params }
        override fun setUserId(userId: String?) = Unit
    }

    @After
    fun tearDown() {
        scope.cancel()
        dir.deleteRecursively()
    }

    /**
     * The lock observes the store through eager state flows. A DataStore collector that starts while a write is in
     * flight can miss that write for good (FIN-86), so a test lets the collectors take their first value before it
     * writes: an awaited first state where it differs from the initial `Locked`, and a pause for the rest.
     */
    private fun appLock() = AppLockImpl(context, store, session, tracker).also { settle() }

    private fun <T> StateFlow<T>.await(value: T) = runBlocking { withTimeout(5_000) { first { it == value } } }

    /** For «stays the same» checks: gives the state pipeline time to emit a change if there were one. */
    private fun settle() = Thread.sleep(150)

    private fun events() = tracked.map { it.first }

    private fun biometrics(available: Boolean, fingerprint: Boolean = false, face: Boolean = false) {
        Shadow.extract<ShadowBiometricManager>(context.getSystemService(BiometricManager::class.java)).apply {
            setCanAuthenticate(available)
            setAuthenticatorType(BiometricManager.Authenticators.BIOMETRIC_STRONG)
        }
        shadowOf(context.packageManager).apply {
            setSystemFeature(PackageManager.FEATURE_FINGERPRINT, fingerprint)
            setSystemFeature(PackageManager.FEATURE_FACE, face)
        }
    }

    @Test
    fun `without a passcode the gate asks for setup`() {
        val lock = appLock()
        lock.state.await(LockState.NotConfigured)
        assertFalse(lock.biometricEnabled.value)
    }

    @Test
    fun `a new passcode opens the app and is tracked`() = runBlocking<Unit> {
        val lock = appLock()
        lock.state.await(LockState.NotConfigured)
        lock.setPasscode("1234")
        lock.state.await(LockState.Unlocked)
        assertTrue(store.matches("1234"))
        assertEquals(listOf("applock.passcode.set"), events())
    }

    @Test
    fun `a passcode that failed to save leaves the setup open`() = runBlocking<Unit> {
        val lock = appLock()
        crypto.failSeal = true
        assertFailsWith<java.security.GeneralSecurityException> { lock.setPasscode("1234") }
        lock.state.await(LockState.NotConfigured)
        settle()
        assertEquals(LockState.NotConfigured, lock.state.value)
        assertTrue(events().isEmpty())
    }

    @Test
    fun `every cold start with a passcode is locked until the right code`() = runBlocking<Unit> {
        store.save("1234")
        val lock = appLock()
        settle()
        assertEquals(LockState.Locked, lock.state.value)
        assertEquals(VerifyResult.Ok, lock.verify("1234"))
        lock.state.await(LockState.Unlocked)
    }

    @Test
    fun `wrong codes count down and the fifth ends the session before the passcode is forgotten`() = runBlocking<Unit> {
        store.save("1234")
        val lock = appLock()
        val results = (1..4).map { lock.verify("0000") }
        assertEquals((4 downTo 1).map { VerifyResult.Wrong(it) }, results)
        assertEquals(4, store.wrongAttempts.first())
        assertEquals(LockState.Locked, lock.state.value)

        assertEquals(VerifyResult.LockedOut, lock.verify("0000"))

        assertEquals(listOf<String?>(AuthRoutes.Phone.REASON_LOGGED_OUT), session.signOuts)
        assertEquals(true, session.configuredAtSignOut, "session first, then the passcode")
        assertFalse(store.isConfigured.first())
        lock.state.await(LockState.NotConfigured)
        assertEquals(List(5) { "applock.unlock.failed" }, events())
        assertEquals((1..5).map { mapOf("attempt" to "$it") }, tracked.map { it.second })
    }

    @Test
    fun `the right code resets the attempt counter`() = runBlocking<Unit> {
        store.save("1234")
        val lock = appLock()
        lock.verify("0000")
        lock.verify("0000")
        assertEquals(VerifyResult.Ok, lock.verify("1234"))
        assertEquals(0, store.wrongAttempts.first())
        lock.lock()
        assertEquals(VerifyResult.Wrong(4), lock.verify("0000"))
    }

    @Test
    fun `checking a code neither counts attempts nor unlocks`() = runBlocking<Unit> {
        store.save("1234")
        val lock = appLock()
        assertFalse(lock.matches("0000"))
        assertTrue(lock.matches("1234"))
        assertEquals(0, store.wrongAttempts.first())
        settle()
        assertEquals(LockState.Locked, lock.state.value)
        assertTrue(events().isEmpty())
    }

    @Test
    fun `biometric unlock opens the app and resets the attempt counter`() = runBlocking<Unit> {
        store.save("1234")
        val lock = appLock()
        lock.verify("0000")
        lock.unlockByBiometric()
        lock.state.await(LockState.Unlocked)
        // Fresh reads, not a new collector racing the write (see appLock()).
        runBlocking { withTimeout(5_000) { while (store.wrongAttempts.first() != 0) delay(10) } }
        lock.lock()
        lock.state.await(LockState.Locked)
    }

    @Test
    fun `five minutes in the background lock the app, less does not`() = runBlocking<Unit> {
        val lock = appLock()
        val owner = ProcessLifecycleOwner.get()
        lock.state.await(LockState.NotConfigured)
        lock.setPasscode("1234")
        lock.state.await(LockState.Unlocked)

        lock.onStart(owner) // a start without a stop before it (cold start) changes nothing
        lock.onStop(owner)
        ShadowSystemClock.advanceBy(Duration.ofMinutes(5).minusSeconds(1))
        lock.onStart(owner)
        settle()
        assertEquals(LockState.Unlocked, lock.state.value)

        lock.onStop(owner)
        ShadowSystemClock.advanceBy(Duration.ofMinutes(5))
        lock.onStart(owner)
        lock.state.await(LockState.Locked)

        // The background mark is spent: a later start does not lock again after an unlock.
        lock.verify("1234")
        lock.onStart(owner)
        settle()
        assertEquals(LockState.Unlocked, lock.state.value)
    }

    @Test
    fun `a sign-out from anywhere forgets the passcode`() = runBlocking<Unit> {
        val lock = appLock()
        lock.state.await(LockState.NotConfigured)
        lock.setPasscode("1234")
        lock.state.await(LockState.Unlocked)
        session.state.value = SessionState.SignedOut
        lock.state.await(LockState.NotConfigured)
        assertFalse(store.matches("1234"))
        assertEquals(1, crypto.resets)
    }

    @Test
    fun `forgot code ends the session and forgets the passcode`() = runBlocking<Unit> {
        store.save("1234")
        val lock = appLock()
        lock.forgetAndSignOut()
        assertEquals(listOf<String?>(AuthRoutes.Phone.REASON_LOGGED_OUT), session.signOuts)
        assertEquals(true, session.configuredAtSignOut)
        lock.state.await(LockState.NotConfigured)
    }

    @Test
    fun `biometric availability follows the hardware`() {
        val lock = appLock()
        biometrics(available = false, fingerprint = true)
        assertEquals(BiometricAvailability.NONE, lock.biometricAvailability())
        biometrics(available = true, fingerprint = true, face = true)
        assertEquals(BiometricAvailability.FINGERPRINT, lock.biometricAvailability())
        biometrics(available = true, face = true)
        assertEquals(BiometricAvailability.FACE, lock.biometricAvailability())
        biometrics(available = true)
        assertEquals(BiometricAvailability.FINGERPRINT, lock.biometricAvailability(), "enrolled but unknown kind")
    }

    @Test
    fun `biometric unlock is on only with a passcode and a capable device`() = runBlocking<Unit> {
        biometrics(available = true, fingerprint = true)
        val lock = appLock()
        lock.setBiometricEnabled(true)
        settle()
        assertFalse(lock.biometricEnabled.value, "no passcode yet")

        lock.setPasscode("1234")
        lock.setBiometricEnabled(true)
        lock.biometricEnabled.await(true)
        assertEquals(listOf("applock.biometric.enabled", "applock.passcode.set", "applock.biometric.enabled"), events())

        lock.setBiometricEnabled(false)
        lock.biometricEnabled.await(false)
        assertEquals(3, events().size, "switching off is not tracked")
    }

    @Test
    fun `biometric unlock is off on a device that lost its biometrics`() = runBlocking<Unit> {
        biometrics(available = false)
        val lock = appLock()
        lock.setPasscode("1234")
        lock.setBiometricEnabled(true)
        settle()
        assertFalse(lock.biometricEnabled.value)
    }
}
