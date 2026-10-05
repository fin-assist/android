package ru.finassist.pf.core.network

import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Response
import ru.finassist.pf.core.api.TokenStore
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
 * Endpoints under `/v1/auth/*` (except logout) and `GET /v1/consents/personal_data` are called without a
 * session; services tag them with the [NoAuth] header which is stripped here.
 */
internal class AuthInterceptor(private val tokenStore: TokenStore) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        if (original.header(NoAuth.HEADER) != null) {
            return chain.proceed(original.newBuilder().removeHeader(NoAuth.HEADER).build())
        }
        val access = runBlocking { tokenStore.current()?.tokens?.accessToken }
        val request = if (access != null) {
            original.newBuilder().header("Authorization", "Bearer $access").build()
        } else {
            original
        }
        return chain.proceed(request)
    }
}

/** Marker header for requests that must not carry a session token. Never reaches the server. */
object NoAuth {
    const val HEADER = "X-Pf-No-Auth"
    const val VALUE = "1"
}

internal const val HEADER_REQUEST_ID = "X-Request-Id"
internal const val HEADER_IDEMPOTENCY_KEY = "Idempotency-Key"
internal const val HEADER_VERIFICATION_TOKEN = "X-Verification-Token"
