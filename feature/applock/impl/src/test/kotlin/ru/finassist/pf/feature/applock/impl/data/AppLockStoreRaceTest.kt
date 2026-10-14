package ru.finassist.pf.feature.applock.impl.data

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.biometrics.BiometricManager
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowBiometricManager
import ru.finassist.pf.core.tracking.Event
import ru.finassist.pf.core.tracking.Tracker
import ru.finassist.pf.feature.applock.api.LockState
import ru.finassist.pf.feature.auth.api.SessionRepository
import ru.finassist.pf.feature.auth.api.SessionState
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals

/**
 * FIN-87: a DataStore collector that starts while a write is in flight can miss that write for good. The lock
 * starts observing the store and, at once, clears a passcode left behind by a signed-out session (process death
 * between sign-out and clear). Its state must still end up at «no passcode», every time.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppLockStoreRaceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val root: File = createTempDirectory("applock-race").toFile()
    private val jobs = mutableListOf<Job>()

    private val tracker = object : Tracker {
        override fun track(event: Event, params: Map<String, String>) = Unit
        override fun setUserId(userId: String?) = Unit
    }

    private class SignedOutSession : SessionRepository {
        override val state = MutableStateFlow<SessionState>(SessionState.SignedOut)
        override suspend fun consumeJustRegistered() = false
        override suspend fun signOut(reason: String?) = Unit
        override suspend fun clearLocal(reason: String?) = Unit
        override fun consumeSignOutReason(): String? = null
    }

    @Before
    fun biometrics() {
        Shadow.extract<ShadowBiometricManager>(context.getSystemService(BiometricManager::class.java)).apply {
            setCanAuthenticate(true)
            setAuthenticatorType(BiometricManager.Authenticators.BIOMETRIC_STRONG)
        }
        shadowOf(context.packageManager).setSystemFeature(PackageManager.FEATURE_FINGERPRINT, true)
    }

    @After
    fun tearDown() {
        jobs.forEach { it.cancel() }
        root.deleteRecursively()
    }

    /** A passcode with biometrics on, written by the previous process; the store of this one has not been read yet. */
    private fun leftoverPasscode(file: File, crypto: FakePasscodeCrypto) = runBlocking {
        val job = Job()
        val previous = PasscodeStore(PreferenceDataStoreFactory.create(scope = CoroutineScope(job + Dispatchers.IO)) { file }, crypto)
        previous.save("1234")
        previous.setBiometricEnabled(true)
        job.cancel()
        job.join()
    }

    private fun <T> StateFlow<T>.reaches(value: T) = runBlocking { withTimeoutOrNull(WAIT_MS) { first { it == value } } != null }

    @Test
    fun `a passcode left by a signed-out session is forgotten however the start races the clear`() {
        val stale = mutableListOf<String>()
        repeat(RUNS) { run ->
            val file = File(root, "run$run.preferences_pb")
            val crypto = FakePasscodeCrypto()
            leftoverPasscode(file, crypto)
            val job = Job().also { jobs += it }
            val store = PasscodeStore(PreferenceDataStoreFactory.create(scope = CoroutineScope(job + Dispatchers.IO)) { file }, crypto)

            val lock = AppLockImpl(context, store, SignedOutSession(), tracker)

            val forgotten = lock.state.reaches(LockState.NotConfigured)
            val biometricOff = lock.biometricEnabled.reaches(false)
            val cleared = runBlocking { !store.isConfigured.first() }
            if (cleared && !(forgotten && biometricOff)) stale += "run $run: state=${lock.state.value}, biometric=${lock.biometricEnabled.value}"
            assertEquals(true, cleared, "run $run: the sign-out reset did not clear the store")
            job.cancel()
        }
        assertEquals(emptyList(), stale, "${stale.size} of $RUNS starts kept a stale lock state")
    }

    private companion object {
        const val RUNS = 200
        const val WAIT_MS = 500L
    }
}
