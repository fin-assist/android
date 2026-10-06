package ru.finassist.pf.feature.auth.impl.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import ru.finassist.pf.core.api.AuthApi
import ru.finassist.pf.core.api.ProfileApi
import ru.finassist.pf.core.api.TokenStore
import ru.finassist.pf.core.api.model.ProfileUpdate
import ru.finassist.pf.core.api.model.TokenPair
import ru.finassist.pf.core.toggles.FeatureFlags
import ru.finassist.pf.core.tracking.CrashReporter
import ru.finassist.pf.core.tracking.Events
import ru.finassist.pf.core.tracking.Tracker
import ru.finassist.pf.feature.auth.api.AuthRoutes
import ru.finassist.pf.feature.auth.api.SessionRepository
import ru.finassist.pf.feature.auth.api.SessionState
import java.util.TimeZone
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
    private val dataStore: DataStore<Preferences>,
) : SessionRepository {

    /** Outlives screens: best-effort logout and background sync after sign-in. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val signOutReason = AtomicReference<String?>(null)

    /** Set while [clearLocal] runs, so the observer below does not report our own sign-out as an expiry. */
    @Volatile private var signingOut = false

    override val state: Flow<SessionState> = tokenStore.session.map { session ->
        // A session without a user id exists only for the moment the profile is being fetched after sign-in.
        if (session == null || session.userId.isEmpty()) SessionState.SignedOut else SessionState.SignedIn(session.userId)
    }

    init {
        scope.launch {
            var wasSignedIn = false
            tokenStore.session.collect { s ->
                val signedIn = s != null && s.userId.isNotEmpty()
                if (wasSignedIn && s == null && !signingOut) {
                    // The network layer dropped the session (refresh rejected): same cleanup as a sign-out.
                    signOutReason.compareAndSet(null, AuthRoutes.Phone.REASON_EXPIRED)
                    resetIdentity()
                }
                if (!wasSignedIn && signedIn) syncTimeZone()
                wasSignedIn = signedIn
            }
        }
    }

    /**
     * Called by the sign-in flow after `verified` / `register`: persists tokens and tells the SDKs who the user
     * is. The `verified` event of an existing user carries no `user_id` (api.md 1.2), so it is read from the
     * profile right after the tokens are stored.
     */
    suspend fun start(userId: String?, tokens: TokenPair, registered: Boolean) {
        val id = userId ?: run {
            tokenStore.save(TokenStore.Session("", tokens))
            try {
                profileApi.getProfile().userId
            } catch (e: Throwable) {
                // Without a user id the placeholder session would look like «signed out» forever.
                tokenStore.clear()
                throw e
            }
        }
        // Persisted: the first-run upload step must survive process death between registration and passcode.
        if (registered) dataStore.edit { it[KEY_PENDING_FIRST_UPLOAD] = true }
        tokenStore.save(TokenStore.Session(id, tokens))
        flags.setUser(id)
        tracker.setUserId(id)
        crashReporter.setUserId(id)
        tracker.track(if (registered) Events.AUTH_REGISTERED else Events.AUTH_VERIFIED)
    }

    override suspend fun consumeJustRegistered(): Boolean {
        val pending = dataStore.data.first()[KEY_PENDING_FIRST_UPLOAD] == true
        if (pending) dataStore.edit { it.remove(KEY_PENDING_FIRST_UPLOAD) }
        return pending
    }

    /**
     * Local state is cleared first, so the UI leaves the main graph at once; `/logout` runs afterwards with the
     * saved refresh token, best effort (a slow network must not keep a locked-out user inside the app).
     */
    override suspend fun signOut(reason: String?) {
        val refresh = tokenStore.current()?.tokens?.refreshToken
        clearLocal(reason)
        tracker.track(Events.AUTH_LOGGED_OUT)
        if (refresh != null) scope.launch { runCatching { authApi.logout(refresh) } }
    }

    override suspend fun clearLocal(reason: String?) {
        signingOut = true
        try {
            signOutReason.set(reason)
            tokenStore.clear()
            dataStore.edit { it.remove(KEY_PENDING_FIRST_UPLOAD) }
            resetIdentity()
        } finally {
            signingOut = false
        }
    }

    private suspend fun resetIdentity() {
        flags.setUser(null)
        tracker.setUserId(null)
        crashReporter.setUserId(null)
    }

    /** api.md 2.2: the client sends the device time zone when it changed — checked once per signed-in start. */
    private fun syncTimeZone() {
        scope.launch {
            runCatching {
                val tz = TimeZone.getDefault().id
                if (profileApi.getProfile().timezone != tz) profileApi.updateProfile(ProfileUpdate(timezone = tz))
            }
        }
    }

    override fun consumeSignOutReason(): String? = signOutReason.getAndSet(null)

    private companion object {
        val KEY_PENDING_FIRST_UPLOAD = booleanPreferencesKey("auth.pending_first_upload")
    }
}
