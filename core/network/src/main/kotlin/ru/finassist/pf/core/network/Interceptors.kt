package ru.finassist.pf.core.network

import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Response
import ru.finassist.pf.core.api.TokenStore
import ru.finassist.pf.core.api.model.ErrorCodes
import ru.finassist.pf.core.common.availability.ClientVersion
import ru.finassist.pf.core.common.availability.ServiceGate
import ru.finassist.pf.core.common.error.AppError
import ru.finassist.pf.core.common.time.Clock
import ru.finassist.pf.core.common.time.ElapsedClock
import ru.finassist.pf.core.common.time.ResponseTiming
import java.util.UUID

/** `X-Request-Id`: a fresh UUID per attempt (not per logical action — that is `Idempotency-Key`). */
internal class RequestIdInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request().newBuilder()
            .header(HEADER_REQUEST_ID, UUID.randomUUID().toString())
            .build()
        return chain.proceed(request)
    }
}

/**
 * Adds `Authorization: Bearer` from the [TokenStore] to every request that is not marked [NoAuth].
 * Endpoints under `/v1/auth/…` (except logout) and `GET /v1/consents/personal_data` are called without a
 * session; services tag them with the [NoAuth] header which is stripped here.
 */
internal class AuthInterceptor(private val tokenStore: TokenStore) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        if (original.header(NoAuth.HEADER) != null) {
            return chain.proceed(original.newBuilder().removeHeader(NoAuth.HEADER).build())
        }
        val session = runBlocking { tokenStore.current() }
        val request = if (session != null) {
            original.newBuilder().header("Authorization", "Bearer ${session.tokens.accessToken}")
                .tag(RequestSession::class.java, RequestSession(session.userId)).build()
        } else {
            original
        }
        return chain.proceed(request)
    }
}

/** Ephemeral identity of the original authenticated call; deliberately has no generated value-bearing toString. */
internal class RequestSession(private val userId: String) {
    fun matches(candidate: String): Boolean = userId == candidate
    override fun toString(): String = "RequestSession"
}

/** `X-Client-Version` on every request, auth and SSE included (api.md «Заголовки», FIN-20). */
internal class ClientVersionInterceptor(private val version: ClientVersion) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response =
        chain.proceed(chain.request().newBuilder().header(HEADER_CLIENT_VERSION, version.header).build())
}

/**
 * Reports `CLIENT_VERSION_UNSUPPORTED` and `MAINTENANCE` to the [ServiceGate] whatever request got them, so the app root
 * shows the update or maintenance screen; the caller still gets the error. Any other answer of the server after
 * maintenance reopens the gate.
 */
internal class ServiceGateInterceptor(
    private val gate: ServiceGate,
    private val clock: Clock,
    private val elapsedClock: ElapsedClock = AndroidElapsedClock,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        // Only the maintenance known before this call ends with its answer: a maintenance a nested call reported
        // meanwhile (the refresh of an expired token) stays. Hidden under an update requirement too (review of #81).
        val before = gate.maintenance
        val received = chain.proceed(chain.request())
        // Application boundary: BridgeInterceptor may replace Response.request after a network interceptor.
        // Capture once here; peeking/mapping the same response never restarts a relative duration.
        val timing = ResponseTiming(received.headers.getDate("Date")?.toInstant(), elapsedClock.nowMillis())
        val response = received.newBuilder().request(received.request.newBuilder().tag(ResponseTiming::class.java, timing).build()).build()
        if (response.code == 400 || response.code == 503) {
            when (val e = response.toAppError()) {
                is AppError.UpdateRequired -> gate.updateRequired(e.presentation)
                is AppError.Maintenance -> gate.maintenance(e.until, e.retryAfterSeconds, clock.now(), e.response)
                else -> if (response.code != 503 && before != null) gate.reopen(before)
            }
        } else if (response.code < 500 && before != null) {
            gate.reopen(before)
        }
        return response
    }
}

/** Marker header for requests that must not carry a session token. Never reaches the server. */
object NoAuth {
    const val HEADER = "X-Pf-No-Auth"
    const val VALUE = "1"
}

internal const val HEADER_REQUEST_ID = "X-Request-Id"
internal const val HEADER_CLIENT_VERSION = "X-Client-Version"
internal const val HEADER_IDEMPOTENCY_KEY = "Idempotency-Key"
internal const val HEADER_VERIFICATION_TOKEN = "X-Verification-Token"
