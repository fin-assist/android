package ru.finassist.pf.feature.auth.impl.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import ru.finassist.pf.core.api.AuthApi
import ru.finassist.pf.core.api.ProfileApi
import ru.finassist.pf.core.api.TokenStore
import ru.finassist.pf.core.api.model.TokenPair
import ru.finassist.pf.core.toggles.FeatureFlags
import ru.finassist.pf.core.tracking.CrashReporter
import ru.finassist.pf.core.tracking.Events
import ru.finassist.pf.core.tracking.Tracker
import ru.finassist.pf.feature.auth.api.SessionRepository
import ru.finassist.pf.feature.auth.api.SessionState
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SessionRepositoryImpl @Inject constructor(
    private val tokenStore: TokenStore,
    private val authApi: AuthApi,
    private val profileApi: ProfileApi,
    private val flags: FeatureFlags,
    private val tracker: Tracker,
    private val crashReporter: CrashReporter,
) : SessionRepository {

    private val justRegistered = AtomicBoolean(false)
    private val signOutReason = AtomicReference<String?>(null)

    override val state: Flow<SessionState> = tokenStore.session.map { session ->
        // A session without a user id exists only for the moment the profile is being fetched after sign-in.
        if (session == null || session.userId.isEmpty()) SessionState.SignedOut else SessionState.SignedIn(session.userId)
    }

    /**
     * Called by the sign-in flow after `verified` / `register`: persists tokens and tells the SDKs who the user
     * is. The `verified` event of an existing user carries no `user_id` (api.md 1.2), so it is read from the
     * profile right after the tokens are stored.
     */
    suspend fun start(userId: String?, tokens: TokenPair, registered: Boolean) {
        val id = userId ?: run {
            tokenStore.save(TokenStore.Session("", tokens))
            profileApi.getProfile().userId
        }
        tokenStore.save(TokenStore.Session(id, tokens))
        if (registered) justRegistered.set(true)
        flags.setUser(id)
        tracker.setUserId(id)
        crashReporter.setUserId(id)
        tracker.track(if (registered) Events.AUTH_REGISTERED else Events.AUTH_VERIFIED)
    }

    override fun consumeJustRegistered(): Boolean = justRegistered.getAndSet(false)

    override suspend fun signOut(reason: String?) {
        val session = tokenStore.current()
        if (session != null) {
            runCatching { authApi.logout(session.tokens.refreshToken) }
        }
        clearLocal(reason)
        tracker.track(Events.AUTH_LOGGED_OUT)
    }

    override suspend fun clearLocal(reason: String?) {
        signOutReason.set(reason)
        tokenStore.clear()
        flags.setUser(null)
        tracker.setUserId(null)
        crashReporter.setUserId(null)
    }

    override fun consumeSignOutReason(): String? = signOutReason.getAndSet(null)
}
