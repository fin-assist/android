package ru.finassist.pf.feature.applock.impl.domain

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import ru.finassist.pf.core.tracking.Tracker
import ru.finassist.pf.feature.applock.api.AppLock
import ru.finassist.pf.feature.applock.impl.data.PasscodeStore
import ru.finassist.pf.feature.auth.api.SessionRepository
import ru.finassist.pf.feature.auth.api.SessionState
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppLockImpl @Inject constructor(
    private val store: PasscodeStore,
    private val session: SessionRepository,
    private val tracker: Tracker,
) : AppLock {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _locked = MutableStateFlow(true)
    private val _confirmations = MutableSharedFlow<String>(extraBufferCapacity = 1)
    private var backgroundedAt: Long? = null

    override val hasPasscode: Flow<Boolean> = store.hasPasscode
    override val biometricEnabled: Flow<Boolean> = store.biometricEnabled
    override val locked: StateFlow<Boolean> = _locked
    override val confirmations: SharedFlow<String> = _confirmations

    init {
        // Logging out (or losing the session) drops the passcode: the next login sets a new one.
        scope.launch { session.state.collect { if (it is SessionState.LoggedOut) { store.clear(); _locked.value = true } } }
    }

    override fun onAppForegrounded(nowMillis: Long) {
        val away = backgroundedAt?.let { nowMillis - it } ?: Long.MAX_VALUE
        if (away >= LOCK_AFTER_MS) _locked.value = true
        backgroundedAt = null
    }

    override fun onAppBackgrounded(nowMillis: Long) { backgroundedAt = nowMillis }

    suspend fun setPasscode(code: String) { store.setPasscode(code); _locked.value = false }

    /** Returns remaining attempts on failure, or null on success (unlocks). After the 5th failure → logout. */
    suspend fun tryUnlock(code: String): Int? {
        if (store.verify(code)) { _locked.value = false; return null }
        val attempts = store.attempts.first()
        if (attempts >= MAX_ATTEMPTS) {
            tracker.track("applock.lockout")
            session.logout()
            return 0
        }
        return MAX_ATTEMPTS - attempts
    }

    suspend fun verify(code: String): Int? {
        if (store.verify(code)) return null
        val attempts = store.attempts.first()
        if (attempts >= MAX_ATTEMPTS) { session.logout(); return 0 }
        return MAX_ATTEMPTS - attempts
    }

    fun unlockedByBiometric() { _locked.value = false }
    suspend fun setBiometric(enabled: Boolean) = store.setBiometric(enabled)
    fun confirmed(reason: String) { _confirmations.tryEmit(reason) }
    suspend fun forgot() = session.logout()

    companion object {
        const val MAX_ATTEMPTS = 5
        const val LOCK_AFTER_MS = 5 * 60 * 1000L
    }
}
