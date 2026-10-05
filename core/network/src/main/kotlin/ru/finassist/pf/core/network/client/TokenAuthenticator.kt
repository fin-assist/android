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
 * an `Idempotency-Key` that is reused for the same refresh token within 60 s, so a repeat after a dropped
 * connection returns the stored pair and is not treated as token reuse.
 */
class TokenAuthenticator(
    private val tokens: SessionTokens,
    private val baseUrl: String,
    private val refreshClient: OkHttpClient,
) : Authenticator {
    private val lock = Any()

    /** Idempotency key of the refresh in flight, bound to the refresh token it was issued for (api.md 1.4: 60 s). */
    private var refreshKey: Triple<String, String, Long>? = null

    override fun authenticate(route: Route?, response: Response): Request? {
        val request = response.request
        // No bearer was sent (auth endpoints, public consent document) — refreshing cannot help.
        val failedToken = request.header("Authorization")?.removePrefix("Bearer ") ?: return null
        if (responseCount(response) >= 2) return null

        val fresh = synchronized(lock) {
            val current = tokens.accessToken()
            if (current != null && current != failedToken) current else refresh()
        } ?: return null
        return request.newBuilder().header("Authorization", "Bearer $fresh").build()
    }

    /** Returns the new access token or null when the session is lost (tokens are cleared then). */
    private fun refresh(): String? {
        val refreshToken = tokens.refreshToken() ?: run { tokens.clear(); return null }
        // Same token within 60 s → same key: a repeat after a dropped connection returns the stored pair instead of
        // counting as token reuse (which would revoke the whole session).
        val now = System.currentTimeMillis()
        val key = refreshKey?.takeIf { it.first == refreshToken && now - it.third < KEY_TTL_MS }?.second
            ?: UUID.randomUUID().toString().also { refreshKey = Triple(refreshToken, it, now) }
        val body = PfJson.encodeToString(RefreshTokenRequestDto(refreshToken))
        val call = Request.Builder()
            .url(baseUrl.trimEnd('/') + "/v1/auth/token")
            .header("Idempotency-Key", key)
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        var attempt = 0
        while (attempt < MAX_ATTEMPTS) {
            attempt++
            try {
                refreshClient.newCall(call).execute().use { r ->
                    when {
                        r.isSuccessful -> {
                            val pair = PfJson.decodeFromString(TokenPairDto.serializer(), r.body.string())
                            tokens.update(pair)
                            refreshKey = null
                            return pair.accessToken
                        }
                        r.code == 401 -> { tokens.clear(); refreshKey = null; return null }
                        // The first attempt with this key is still running on the server: wait and ask again, same key.
                        r.code == 409 -> Thread.sleep(((r.header("Retry-After")?.toLongOrNull() ?: 1L).coerceIn(1L, 5L)) * 1000)
                        // 5xx: do not drop the session; the caller gets the original 401 and the key stays for the next try.
                        else -> return null
                    }
                }
            } catch (e: IOException) {
                if (attempt >= MAX_ATTEMPTS) return null
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

    private companion object {
        const val KEY_TTL_MS = 60_000L
        const val MAX_ATTEMPTS = 3
    }
}
