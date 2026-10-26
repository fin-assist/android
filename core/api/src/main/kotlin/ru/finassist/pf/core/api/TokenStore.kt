package ru.finassist.pf.core.api

import kotlinx.coroutines.flow.Flow
import ru.finassist.pf.core.api.model.TokenPair
import ru.finassist.pf.core.api.model.IdempotencyKey

/**
 * Where the session tokens live. The refresh token is a long-lived secret and is stored encrypted under an
 * Android Keystore key (`:core:storage`); the access token (JWT, 15 min) is kept alongside for convenience.
 * The network layer reads it for `Authorization` and rotates it on refresh; the auth feature writes it on
 * sign-in and clears it on sign-out. `userId` is kept so flags/analytics can be keyed before the profile loads.
 */
interface TokenStore {
    /**
     * The stored session, null when signed out. A subscriber gets the current value at once and every later change,
     * whenever it subscribes, also while a [save] or [clear] is in flight (`PfApp` switches graphs on it).
     */
    val session: Flow<Session?>
    suspend fun current(): Session?
    suspend fun save(session: Session)
    /**
     * Replaces the pair only if the stored session still holds [expectedRefreshToken] — atomically, so a
     * refresh finishing after a sign-out or a new sign-in can neither resurrect the old session nor overwrite
     * the new one. Returns false when the session has changed meanwhile.
     */
    suspend fun updateTokens(expectedRefreshToken: String, tokens: TokenPair): Boolean
    /** Durable CAS before the refresh RPC. Unknown/unsupported stores never grant a refresh attempt. */
    suspend fun reserveRefreshAttempt(expectedRefreshToken: String, key: IdempotencyKey): Boolean = false
    /** CAS-retire only this marker after caller-validated proof that no refresh RPC consumed the token. */
    suspend fun retireRefreshAttempt(expectedRefreshToken: String, key: IdempotencyKey): Boolean = false
    /** Clear only the failed session; never erase a new sign-in racing with an old response. */
    suspend fun clearIfRefreshToken(expectedRefreshToken: String): Boolean = false
    suspend fun clear()

    data class Session(val userId: String, val tokens: TokenPair)
}
