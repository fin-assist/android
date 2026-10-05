package ru.finassist.pf.core.network.client

import kotlinx.serialization.encodeToString
import okhttp3.Authenticator
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.Route
import ru.finassist.pf.core.network.dto.RefreshTokenRequestDto
import ru.finassist.pf.core.network.dto.TokenPairDto
import java.io.IOException
import java.util.UUID

/**
 * On 401: refresh once, retry the request with the new access token. Concurrent 401s share one refresh:
 * if another thread already rotated the token since this request was built, reuse it instead of refreshing again
 * (a second refresh with the old refresh token would revoke the whole session — api.md 1.4).
 *
 * The refresh call itself bypasses this authenticator (it is sent with [refreshClient], which has none) and carries
 * an `Idempotency-Key`; one transport retry with the same key is allowed because the server keeps the response
 * for 60 s and does not treat the repeat as token reuse.
 */
class TokenAuthenticator(
    private val tokens: SessionTokens,
    private val baseUrl: String,
    private val refreshClient: OkHttpClient,
) : Authenticator {
    private val lock = Any()

    override fun authenticate(route: Route?, response: Response): Request? {
        val request = response.request
        if (request.header(AuthHeaderInterceptor.NO_AUTH) != null || request.url.encodedPath.startsWith("/v1/auth/")) return null
        if (responseCount(response) >= 2) return null
        val failedToken = request.header("Authorization")?.removePrefix("Bearer ")

        val fresh = synchronized(lock) {
            val current = tokens.accessToken()
            if (current != null && current != failedToken) current else refresh()
        } ?: return null
        return request.newBuilder().header("Authorization", "Bearer $fresh").build()
    }

    /** Returns the new access token or null when the session is lost (tokens are cleared then). */
    private fun refresh(): String? {
        val refreshToken = tokens.refreshToken() ?: run { tokens.clear(); return null }
        val key = UUID.randomUUID().toString()
        val body = PfJson.encodeToString(RefreshTokenRequestDto(refreshToken))
        val call = Request.Builder()
            .url(baseUrl.trimEnd('/') + "/v1/auth/token")
            .header("Idempotency-Key", key)
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        repeat(2) { attempt ->
            try {
                refreshClient.newCall(call).execute().use { r ->
                    when {
                        r.isSuccessful -> {
                            val pair = PfJson.decodeFromString(TokenPairDto.serializer(), r.body.string())
                            tokens.update(pair)
                            return pair.accessToken
                        }
                        r.code == 401 -> { tokens.clear(); return null }
                        // 409 REQUEST_IN_PROGRESS / 5xx: do not drop the session; the caller gets the original 401
                        else -> return null
                    }
                }
            } catch (e: IOException) {
                if (attempt == 1) return null
            }
        }
        return null
    }

    private fun responseCount(response: Response): Int {
        var r: Response? = response
        var n = 0
        while (r != null) { n++; r = r.priorResponse }
        return n
    }
}
