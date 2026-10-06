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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
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

    override val state: StateFlow<LockState> = combine(store.isConfigured, unlocked) { configured, open ->
        when {
            !configured -> LockState.NotConfigured
            open -> LockState.Unlocked
            else -> LockState.Locked
        }
    }.distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, LockState.Locked)

    override val biometricEnabled: StateFlow<Boolean> = combine(store.biometricEnabled, store.isConfigured) { on, configured ->
        on && configured && biometricAvailability() != BiometricAvailability.NONE
    }.stateIn(scope, SharingStarted.Eagerly, false)

    init {
        scope.launch(Dispatchers.Main.immediate) { ProcessLifecycleOwner.get().lifecycle.addObserver(this@AppLockImpl) }
        scope.launch {
            session.state.collect { s -> if (s is SessionState.SignedOut) reset() }
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

    override suspend fun setPasscode(code: String) {
        store.save(code)
        unlocked.value = true
        tracker.track(Events.APPLOCK_PASSCODE_SET)
    }

    override suspend fun verify(code: String): VerifyResult {
        if (store.matches(code)) {
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

    override suspend fun matches(code: String): Boolean = store.matches(code)

    override suspend fun setBiometricEnabled(enabled: Boolean) {
        store.setBiometricEnabled(enabled)
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

    override suspend fun reset() {
        store.clear()
        unlocked.value = false
    }

    private companion object {
        const val BACKGROUND_LOCK_MS = 5 * 60 * 1000L
    }
}
