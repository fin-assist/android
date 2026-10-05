package ru.finassist.pf.feature.auth.impl.domain

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.finassist.pf.core.network.api.PfApi
import ru.finassist.pf.core.network.dto.RefreshTokenRequestDto
import ru.finassist.pf.core.network.dto.TokenPairDto
import ru.finassist.pf.core.toggles.FeatureFlags
import ru.finassist.pf.core.tracking.CrashReporter
import ru.finassist.pf.core.tracking.Tracker
import ru.finassist.pf.feature.auth.api.PhoneScreenNotice
import ru.finassist.pf.feature.auth.api.SessionRepository
import ru.finassist.pf.feature.auth.api.SessionState
import ru.finassist.pf.feature.auth.impl.data.SecureTokenStore
import ru.finassist.pf.feature.auth.impl.data.SessionStore
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SessionRepositoryImpl @Inject constructor(
    private val tokens: SecureTokenStore,
    private val store: SessionStore,
    private val api: PfApi,
    private val flags: FeatureFlags,
    private val tracker: Tracker,
    private val crashes: CrashReporter,
) : SessionRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<SessionState>(SessionState.Unknown)
    override val state: StateFlow<SessionState> = _state

    private val _notice = MutableStateFlow(PhoneScreenNotice.None)
    override val lastSignOutNotice: StateFlow<PhoneScreenNotice> = _notice

    init {
        tokens.onCleared = { scope.launch { dropSession(PhoneScreenNotice.SessionExpired) } }
        scope.launch {
            val userId = store.userId.first()
            _state.value = if (userId != null && tokens.hasTokens()) SessionState.LoggedIn(userId) else SessionState.LoggedOut
            applyIdentity(userId)
        }
    }

    /** Tokens are usable before the user id is known (api.md 1.2 gives tokens without `user_id`). */
    fun storeTokens(pair: TokenPairDto) = tokens.update(pair)

    suspend fun onLoggedIn(userId: String, phone: String, pair: TokenPairDto) {
        tokens.update(pair)
        store.set(userId, phone)
        _notice.value = PhoneScreenNotice.None
        _state.value = SessionState.LoggedIn(userId)
        applyIdentity(userId)
    }

    override suspend fun logout() {
        val refresh = tokens.refreshToken()
        if (refresh != null) runCatching { withContext(Dispatchers.IO) { api.logout(RefreshTokenRequestDto(refresh)) } }
            .onFailure { crashes.reportNonFatal(it, "logout") }
        dropSession(PhoneScreenNotice.LoggedOut)
    }

    override suspend fun onAccountDeleted() = dropSession(PhoneScreenNotice.AccountDeleted)

    private suspend fun dropSession(notice: PhoneScreenNotice) {
        tokens.onCleared = null
        tokens.clear()
        tokens.onCleared = { scope.launch { dropSession(PhoneScreenNotice.SessionExpired) } }
        store.clear()
        _notice.value = notice
        _state.value = SessionState.LoggedOut
        applyIdentity(null)
    }

    private suspend fun applyIdentity(userId: String?) {
        tracker.setUser(userId)
        crashes.setUser(userId)
        runCatching { flags.setUser(userId) }
    }
}
