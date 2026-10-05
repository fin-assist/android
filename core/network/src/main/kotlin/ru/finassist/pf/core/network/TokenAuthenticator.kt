package ru.finassist.pf.core.network

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import ru.finassist.pf.core.api.TokenStore
import ru.finassist.pf.core.api.model.IdempotencyKey
import ru.finassist.pf.core.api.model.TokenPair
import ru.finassist.pf.core.common.error.AppError

/**
 * On 401: refresh the token pair once and retry the request with the new access token.
 *
 * - One refresh at a time: parallel 401s wait on the mutex and then reuse the pair the first one obtained
 *   (the access token in the store differs from the one the failed request carried).
 * - The refresh call itself is idempotent with a key that lives 60 s on the server: a retry of the same refresh
 *   after a dropped connection gets the same new pair instead of revoking the session chain.
 * - Refresh failure clears the session; the original request fails with [AppError.Unauthorized] and the app
 *   shows the sign-in screen.
 */
internal class TokenAuthenticator(
    private val tokenStore: TokenStore,
    private val refresh: suspend (key: IdempotencyKey, refreshToken: String) -> TokenPair,
) : Authenticator {

    private val mutex = Mutex()

    override fun authenticate(route: Route?, response: Response): Request? {
        // Session-less endpoints (the NoAuth marker is stripped by the interceptor, so check the path).
        val path = response.request.url.encodedPath
        if (path.startsWith("/v1/auth/") && !path.endsWith("/logout")) return null
        if (responseCount(response) >= 2) return null

        val failedAccess = response.request.header("Authorization")?.removePrefix("Bearer ")
        val newAccess = runBlocking {
            mutex.withLock {
                val session = tokenStore.current() ?: return@withLock null
                if (session.tokens.accessToken != failedAccess) {
                    // Someone else already refreshed while we waited.
                    return@withLock session.tokens.accessToken
                }
                try {
                    val pair = refresh(IdempotencyKey.random(), session.tokens.refreshToken)
                    tokenStore.updateTokens(pair)
                    pair.accessToken
                } catch (e: AppError.Unauthorized) {
                    tokenStore.clear()
                    null
                } catch (e: AppError.Api) {
                    if (e.httpStatus == 401) tokenStore.clear()
                    null
                } catch (e: AppError) {
                    null // offline / server error: give up on this request, keep the session
                }
            }
        } ?: return null

        return response.request.newBuilder()
            .header("Authorization", "Bearer $newAccess")
            .build()
    }

    private fun responseCount(response: Response): Int {
        var count = 1
        var prior = response.priorResponse
        while (prior != null) {
            count++
            prior = prior.priorResponse
        }
        return count
    }
}
