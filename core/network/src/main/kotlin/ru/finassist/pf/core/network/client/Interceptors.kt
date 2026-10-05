package ru.finassist.pf.core.network.client

import okhttp3.Interceptor
import okhttp3.Response
import java.util.UUID

/** Adds `Authorization: Bearer` to every request that did not opt out via the [NO_AUTH] header. */
class AuthHeaderInterceptor(private val tokens: SessionTokens) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        if (original.header(NO_AUTH) != null) {
            return chain.proceed(original.newBuilder().removeHeader(NO_AUTH).build())
        }
        val token = tokens.accessToken() ?: return chain.proceed(original)
        return chain.proceed(original.newBuilder().header("Authorization", "Bearer $token").build())
    }

    companion object {
        /** Marker header for the auth endpoints and `/v1/consents/personal_data`; stripped before sending. */
        const val NO_AUTH = "X-Pf-No-Auth"
        /** Retrofit `@Headers` needs a literal: same marker as [NO_AUTH] with its value. */
        const val NO_AUTH_HEADER = "X-Pf-No-Auth: 1"
    }
}

/**
 * `X-Request-Id` — new on every attempt, so it is a *network* interceptor: retries made by the authenticator
 * and follow-ups re-enter network interceptors but not application ones.
 */
class RequestIdInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response =
        chain.proceed(chain.request().newBuilder().header("X-Request-Id", UUID.randomUUID().toString()).build())
}
