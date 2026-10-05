package ru.finassist.pf.core.network.client

import ru.finassist.pf.core.network.dto.TokenPairDto

/**
 * Token storage seen by the network layer. Implemented by the auth feature (encrypted storage) and bound in `:app`.
 * All methods are safe to call from any thread; [update] and [clear] must be atomic with respect to each other.
 */
interface SessionTokens {
    fun accessToken(): String?
    fun refreshToken(): String?
    fun update(pair: TokenPairDto)
    /** Session is over (refresh rejected). The implementation must switch the app to the logged-out state. */
    fun clear()
}
