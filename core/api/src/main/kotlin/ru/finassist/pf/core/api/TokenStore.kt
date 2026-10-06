package ru.finassist.pf.core.api

import kotlinx.coroutines.flow.Flow
import ru.finassist.pf.core.api.model.TokenPair

/**
 * Where the session tokens live. The refresh token is a long-lived secret and is stored encrypted under an
 * Android Keystore key (`:core:storage`); the access token (JWT, 15 min) is kept alongside for convenience.
 * The network layer reads it for `Authorization` and rotates it on refresh; the auth feature writes it on
 * sign-in and clears it on sign-out. `userId` is kept so flags/analytics can be keyed before the profile loads.
 */
interface TokenStore {
    val session: Flow<Session?>
    suspend fun current(): Session?
    suspend fun save(session: Session)
    /**
     * Replaces the pair only if the stored session still holds [expectedRefreshToken] — atomically, so a
     * refresh finishing after a sign-out or a new sign-in can neither resurrect the old session nor overwrite
     * the new one. Returns false when the session has changed meanwhile.
     */
    suspend fun updateTokens(expectedRefreshToken: String, tokens: TokenPair): Boolean
    suspend fun clear()

    data class Session(val userId: String, val tokens: TokenPair)
}
