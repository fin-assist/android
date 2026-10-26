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
import ru.finassist.pf.core.api.model.ErrorCodes
import ru.finassist.pf.core.api.model.TokenPair
import ru.finassist.pf.core.common.error.AppError
import ru.finassist.pf.core.common.error.ErrorContext
import ru.finassist.pf.core.common.error.RequestRetryGate
import ru.finassist.pf.core.common.availability.ServiceGate
import ru.finassist.pf.core.common.time.ServerDeadline
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
 * - Temporary failures retain the session and original key while waiting/replay remains possible. A first
 *   no-effect refusal never automatically rotates a new key or logs the user out.
 * - On a later 401, an explicit refresh prohibition without a usable corrective flow recovers through sign-in.
 *   Known maintenance keeps its one guarded correction; an update requirement waits for the actual update.
 *   Authentication rejection or an unusable uncertain replay window also ends only the matching session.
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
    private class PendingRefresh(val token: String, val key: IdempotencyKey, val retryGate: RequestRetryGate) {
        // This is the first possible token-use window, not the UUID's immutable creation/origin window.
        var window: RequestReplayWindow? = null
        var mayHaveUsedToken: Boolean = false
        var requiresAppUpdate: Boolean = false
        var maintenanceRecovery: ServerDeadline? = null
        var maintenanceRecoveryConsumed: Boolean = false
    }
    private var pendingKey: PendingRefresh? = null

    override fun authenticate(route: Route?, response: Response): Request? {
        // Auth endpoints are never retried here: most are session-less, and `/logout` carries the tokens of a
        // session already cleared locally (the store may hold a newer one by now) — the session repository
        // handles its 401 itself.
        if (response.request.url.encodedPath.startsWith("/v1/auth/")) return null
        if (responseCount(response) >= 2) return null
        // Successful auth correction does not override the original operation's replay window.
        if (!mayResume(response.request)) return null

        val originalSession = response.request.tag(RequestSession::class.java) ?: return null
        val failedAccess = response.request.header("Authorization")?.removePrefix("Bearer ")
        val newAccess = runBlocking {
            mutex.withLock {
                val session = tokenStore.current() ?: return@withLock null
                if (!originalSession.matches(session.userId)) return@withLock null
                if (session.tokens.accessToken != failedAccess) {
                    // Someone else already refreshed while we waited.
                    return@withLock session.tokens.accessToken
                }
                val refreshToken = session.tokens.refreshToken
                val pending = pendingKey?.takeIf { it.token == refreshToken }
                if (pending?.window?.isOpen(elapsedClock) == false) {
                    // Reusing the old token outside its 60s replay window can revoke the whole session chain.
                    // Never silently issue a new key for the same uncertain refresh; recover by sign-in.
                    pendingKey = null
                    tokenStore.clearIfRefreshToken(refreshToken)
                    return@withLock null
                }
                val attempt = pending ?: PendingRefresh(
                    refreshToken, IdempotencyKey.random(elapsedClock), RequestRetryGate(elapsedClock),
                ).also { pendingKey = it }
                if (attempt.requiresAppUpdate) return@withLock null
                val maintenanceRemaining = attempt.maintenanceRecovery?.remainingMillis(elapsedClock)
                val hasMaintenanceCorrection = !attempt.maintenanceRecoveryConsumed && maintenanceRemaining != null
                val useMaintenanceCorrection = !attempt.retryGate.isReady() && hasMaintenanceCorrection && maintenanceRemaining == 0L
                if (!attempt.retryGate.isReady() && !useMaintenanceCorrection) {
                    if (attempt.retryGate.isExplicitlyForbidden() && !hasMaintenanceCorrection) {
                        // This later 401 cannot obtain usable credentials. End the old session rather than leave
                        // every protected screen permanently signed in but unable to proceed. A concurrent login
                        // wins the CAS; no new refresh key or RPC is created to escape the prohibition.
                        pendingKey = null
                        tokenStore.clearIfRefreshToken(refreshToken)
                    }
                    return@withLock null
                }
                val key = attempt.key
                val priorUncertainty = attempt.mayHaveUsedToken
                try {
                    // A fresh process has no trusted key/window for an encrypted pending marker.
                    // Reserving a different candidate fails closed before any RPC with the old token.
                    if (!tokenStore.reserveRefreshAttempt(refreshToken, key)) {
                        pendingKey = null
                        tokenStore.clearIfRefreshToken(refreshToken)
                        return@withLock null
                    }
                    // Recheck after durable I/O, immediately before sending. A prior uncertain window never renews.
                    if (attempt.window?.isOpen(elapsedClock) == false) {
                        pendingKey = null
                        tokenStore.clearIfRefreshToken(refreshToken)
                        return@withLock null
                    }
                    if (useMaintenanceCorrection) {
                        if (attempt.maintenanceRecovery?.remainingMillis(elapsedClock) != 0L) return@withLock null
                    } else if (!attempt.retryGate.isReady()) return@withLock null
                    if (attempt.window == null) attempt.window = RequestReplayWindow(elapsedClock.nowMillis(), 60_000)
                    attempt.mayHaveUsedToken = true
                    if (useMaintenanceCorrection) attempt.maintenanceRecoveryConsumed = true
                    val pair = refresh(key, refreshToken)
                    // The key is retired only once the new pair is stored: if saving fails, the next attempt
                    // repeats the same refresh with the same key and gets the same pair back.
                    if (!tokenStore.updateTokens(refreshToken, pair)) return@withLock null
                    pendingKey = null
                    pair.accessToken
                } catch (e: AppError) {
                    // A definitive no-effect response resolves ONLY a never-uncertain attempt. Later pre-key
                    // responses cannot disprove an earlier lost/committed call or restart its 60-second window.
                    if (!priorUncertainty && isDefinitiveNoEffect(e) && tokenStore.retireRefreshAttempt(refreshToken, key)) {
                        attempt.mayHaveUsedToken = false
                        attempt.window = null
                    }
                    attempt.retryGate.record(e)
                    if (isDefinitiveNoEffect(e)) {
                        if (e is AppError.UpdateRequired) attempt.requiresAppUpdate = true
                        if (e is AppError.Maintenance && e.requestRetryable == false && !attempt.maintenanceRecoveryConsumed && attempt.maintenanceRecovery == null) {
                            attempt.maintenanceRecovery = ServiceGate.recoveryDeadline(e, elapsedClock)
                        }
                    }
                    when {
                        // The session is gone: clear it, the app shows the phone screen.
                        e is AppError.Unauthorized ||
                            (e is AppError.Api && e.httpStatus in 400..499 && e.httpStatus != 409 && e.httpStatus != 429) -> {
                            pendingKey = null
                            tokenStore.clearIfRefreshToken(refreshToken)
                        }
                        // Offline / 5xx / in progress: keep the session and the key for the next attempt.
                        else -> Unit
                    }
                    null
                }
            }
        } ?: return null

        if (!mayResume(response.request)) return null
        val currentSession = runBlocking { tokenStore.current() } ?: return null
        if (!originalSession.matches(currentSession.userId)) return null
        return response.request.newBuilder()
            .header(HEADER_REQUEST_ID, UUID.randomUUID().toString())
            .header("Authorization", "Bearer $newAccess")
            .build()
    }

    private fun isDefinitiveNoEffect(error: AppError): Boolean {
        val response = error.response ?: return false
        if (response.context != ErrorContext.REQUEST) return false
        return when (error) {
            is AppError.Maintenance -> response.code == ErrorCodes.MAINTENANCE && response.httpStatus == 503
            is AppError.UpdateRequired -> response.code == ErrorCodes.CLIENT_VERSION_UNSUPPORTED && response.httpStatus == 400
            is AppError.RateLimited -> response.code == ErrorCodes.RATE_LIMITED && response.httpStatus == 429
            else -> false
        }
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
