package ru.finassist.pf.feature.applock.impl.data

import android.content.Context
import android.content.pm.PackageManager
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import android.os.SystemClock
import ru.finassist.pf.core.tracking.Events
import ru.finassist.pf.core.tracking.Tracker
import ru.finassist.pf.feature.applock.api.AppLock
import ru.finassist.pf.feature.applock.api.BiometricAvailability
import ru.finassist.pf.feature.applock.api.LockState
import ru.finassist.pf.feature.applock.api.MAX_WRONG_ATTEMPTS
import ru.finassist.pf.feature.applock.api.VerifyResult
import ru.finassist.pf.feature.auth.api.AuthRoutes
import ru.finassist.pf.feature.auth.api.SessionRepository
import ru.finassist.pf.feature.auth.api.SessionState
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Lock state = stored passcode × in-memory «unlocked» flag. The flag starts false, so every cold start is
 * locked; it is dropped again after [BACKGROUND_LOCK_MS] in the background (ProcessLifecycleOwner). Sign-out
 * (any reason) clears the passcode, so the next sign-in sets a new one — new device or new session → new code.
 */
@Singleton
internal class AppLockImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: PasscodeStore,
    private val session: SessionRepository,
    private val tracker: Tracker,
) : AppLock, DefaultLifecycleObserver {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val unlocked = MutableStateFlow(false)
    private var backgroundedAt: Long? = null

    /**
     * Serializes writes of the passcode. The sign-out reset (started on every cold start that begins signed out)
     * deletes Keystore keys before the record — slow on some devices. A new code saved in between would lose its
     * record or MAC key and end up «configured but locked» with a code that never matches.
     */
    private val writes = Mutex()

    /**
     * What the store holds, as last read by [refresh]; null until the first read. DataStore collectors cannot be
     * trusted with it: one that starts while a write is in flight can miss that write for good (FIN-87), e.g. the
     * sign-out reset racing the lock's own start. So nothing here publishes a collected value: every value is a
     * fresh read of the store, taken after each own write and whenever the store reports a change.
     */
    private val stored = MutableStateFlow<Stored?>(null)

    /** Serializes [refresh]: a read is published only after every read started before it. */
    private val reads = Mutex()

    override val state: StateFlow<LockState> = combine(stored, unlocked) { s, open ->
        when {
            s == null -> LockState.Locked
            !s.configured -> LockState.NotConfigured
            open -> LockState.Unlocked
            else -> LockState.Locked
        }
    }.distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, LockState.Locked)

    override val biometricEnabled: StateFlow<Boolean> = stored.map { s ->
        s != null && s.biometric && s.configured && biometricAvailability() != BiometricAvailability.NONE
    }.stateIn(scope, SharingStarted.Eagerly, false)

    init {
        scope.launch(Dispatchers.Main.immediate) { ProcessLifecycleOwner.get().lifecycle.addObserver(this@AppLockImpl) }
        // A trigger only (its values may be stale, see [stored]); it also takes the first read. A failed read
        // (I/O, Keystore) would end the collection for good, so it starts over: a store that can be read again is
        // picked up without waiting for the next write.
        scope.launch {
            merge(store.isConfigured, store.biometricEnabled)
                .retryWhen { e, _ -> (e !is CancellationException).also { if (it) delay(READ_RETRY_MS) } }
                .collect { refresh() }
        }
        scope.launch {
            session.state.collect { s -> if (s is SessionState.SignedOut) reset() }
        }
    }

    /**
     * NonCancellable: a write that has landed is published even if its caller has gone away meanwhile. A failed
     * read keeps the last value (none yet reads as Locked) and does not throw: it runs in `finally` of the writes,
     * where it would replace their own exception, and in the trigger, which would stop.
     */
    private suspend fun refresh() = withContext(NonCancellable) {
        reads.withLock {
            stored.value = try {
                Stored(store.isConfigured.first(), store.biometricEnabled.first())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return@withLock
            }
        }
    }

    override fun onStart(owner: LifecycleOwner) {
        val since = backgroundedAt ?: return
        backgroundedAt = null
        if (SystemClock.elapsedRealtime() - since >= BACKGROUND_LOCK_MS) lock()
    }

    override fun onStop(owner: LifecycleOwner) {
        backgroundedAt = SystemClock.elapsedRealtime()
    }

    /**
     * Weak biometrics (camera-only face unlock) are accepted: the passcode is the actual secret and the
     * biometric is only a convenience over it, same as the system lock screen.
     */
    override fun biometricAvailability(): BiometricAvailability {
        val can = BiometricManager.from(context).canAuthenticate(BIOMETRIC_STRONG or BIOMETRIC_WEAK)
        if (can != BiometricManager.BIOMETRIC_SUCCESS) return BiometricAvailability.NONE
        val pm = context.packageManager
        return when {
            pm.hasSystemFeature(PackageManager.FEATURE_FINGERPRINT) -> BiometricAvailability.FINGERPRINT
            pm.hasSystemFeature(PackageManager.FEATURE_FACE) -> BiometricAvailability.FACE
            else -> BiometricAvailability.FINGERPRINT
        }
    }

    /**
     * The session is opened before the record lands. Otherwise the state passes through Locked (record saved,
     * Keystore marker still being generated), `PfApp` ends setup on Locked and drops the setup screen, and its
     * cancelled view model never reaches `unlocked = true`: the user sees the unlock overlay right after choosing
     * the code (FIN-38). NonCancellable for the same reason: the caller's scope may go away mid-write.
     */
    override suspend fun setPasscode(code: String) = withContext(NonCancellable) {
        writes.withLock {
            unlocked.value = true
            try {
                store.save(code)
            } catch (e: Throwable) {
                unlocked.value = false
                throw e
            } finally {
                refresh()
            }
        }
        tracker.track(Events.APPLOCK_PASSCODE_SET)
    }

    override suspend fun verify(code: String): VerifyResult {
        if (matches(code)) {
            store.setWrongAttempts(0)
            unlocked.value = true
            return VerifyResult.Ok
        }
        val attempts = store.wrongAttempts.first() + 1
        tracker.track(Events.APPLOCK_UNLOCK_FAILED, mapOf("attempt" to attempts.toString()))
        if (attempts >= MAX_WRONG_ATTEMPTS) {
            forgetAndSignOut()
            return VerifyResult.LockedOut
        }
        store.setWrongAttempts(attempts)
        return VerifyResult.Wrong(MAX_WRONG_ATTEMPTS - attempts)
    }

    /** A right code refreshes: one of a legacy install is re-saved as a sealed record, which turns biometrics on. */
    override suspend fun matches(code: String): Boolean = store.matches(code).also { if (it) refresh() }

    override suspend fun setBiometricEnabled(enabled: Boolean) {
        try {
            store.setBiometricEnabled(enabled)
        } finally {
            refresh()
        }
        if (enabled) tracker.track(Events.APPLOCK_BIOMETRIC_ENABLED)
    }

    override fun unlockByBiometric() {
        unlocked.value = true
        scope.launch { store.setWrongAttempts(0) }
    }

    override fun lock() {
        unlocked.value = false
    }

    /** Session first (cleared locally at once), then the passcode — never a «set a new code» window while signed in. */
    override suspend fun forgetAndSignOut() {
        session.signOut(reason = AuthRoutes.Phone.REASON_LOGGED_OUT)
        reset()
    }

    override suspend fun reset() = writes.withLock {
        try {
            store.clear()
            unlocked.value = false
        } finally {
            refresh()
        }
    }

    private data class Stored(val configured: Boolean, val biometric: Boolean)

    private companion object {
        const val BACKGROUND_LOCK_MS = 5 * 60 * 1000L
        const val READ_RETRY_MS = 1_000L
    }
}
