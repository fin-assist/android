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
import ru.finassist.pf.core.common.time.ElapsedClock
import ru.finassist.pf.core.common.time.RequestReplayWindow
import java.util.UUID

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
    private val elapsedClock: ElapsedClock,
    private val refresh: suspend (key: IdempotencyKey, refreshToken: String) -> TokenPair,
) : Authenticator {

    private val mutex = Mutex()

    /**
     * Key of the refresh attempt for the current refresh token. A retry after a lost response must reuse it,
     * otherwise the server sees the old refresh token again and revokes the whole session (api.md 1.4).
     * Guarded by [mutex].
     */
    private class PendingRefresh(val token: String, val key: IdempotencyKey, val window: RequestReplayWindow)
    private var pendingKey: PendingRefresh? = null

    override fun authenticate(route: Route?, response: Response): Request? {
        // Auth endpoints are never retried here: most are session-less, and `/logout` carries the tokens of a
        // session already cleared locally (the store may hold a newer one by now) — the session repository
        // handles its 401 itself.
        if (response.request.url.encodedPath.startsWith("/v1/auth/")) return null
        if (responseCount(response) >= 2) return null
        // Successful auth correction does not override the original operation's replay window.
        if (!mayResume(response.request)) return null

        val failedAccess = response.request.header("Authorization")?.removePrefix("Bearer ")
        val newAccess = runBlocking {
            mutex.withLock {
                val session = tokenStore.current() ?: return@withLock null
                if (session.tokens.accessToken != failedAccess) {
                    // Someone else already refreshed while we waited.
                    return@withLock session.tokens.accessToken
                }
                val refreshToken = session.tokens.refreshToken
                val pending = pendingKey?.takeIf { it.token == refreshToken }
                if (pending != null && !pending.window.isOpen(elapsedClock)) {
                    // Reusing the old token outside its 60s replay window can revoke the whole session chain.
                    // Never silently issue a new key for the same uncertain refresh; recover by sign-in.
                    pendingKey = null
                    tokenStore.clear()
                    return@withLock null
                }
                val key = pending?.key ?: IdempotencyKey.random(elapsedClock).also {
                    pendingKey = PendingRefresh(refreshToken, it, RequestReplayWindow(elapsedClock.nowMillis(), 60_000))
                }
                try {
                    val pair = refresh(key, refreshToken)
                    // The key is retired only once the new pair is stored: if saving fails, the next attempt
                    // repeats the same refresh with the same key and gets the same pair back.
                    if (!tokenStore.updateTokens(refreshToken, pair)) return@withLock null
                    pendingKey = null
                    pair.accessToken
                } catch (e: AppError) {
                    when {
                        // The session is gone: clear it, the app shows the phone screen.
                        e is AppError.Unauthorized ||
                            (e is AppError.Api && e.httpStatus in 400..499 && e.httpStatus != 409 && e.httpStatus != 429) -> {
                            pendingKey = null
                            tokenStore.clear()
                        }
                        // Offline / 5xx / in progress: keep the session and the key for the next attempt.
                        else -> Unit
                    }
                    null
                }
            }
        } ?: return null

        if (!mayResume(response.request)) return null
        return response.request.newBuilder()
            .header(HEADER_REQUEST_ID, UUID.randomUUID().toString())
            .header("Authorization", "Bearer $newAccess")
            .build()
    }

    private fun mayResume(request: Request): Boolean = when (request.method) {
        "GET", "HEAD", "PUT", "PATCH", "DELETE" -> true // idempotent methods in this API's existing contract
        "POST" -> request.header(HEADER_IDEMPOTENCY_KEY) != null &&
            request.tag(RequestReplayWindow::class.java)?.isOpen(elapsedClock) == true &&
            request.body?.isOneShot() != true && request.body?.isDuplex() != true
        else -> false
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
