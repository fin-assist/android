package ru.finassist.pf.core.network

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import ru.finassist.pf.core.api.RequestOwner
import ru.finassist.pf.core.api.TokenStore
import ru.finassist.pf.core.api.model.IdempotencyKey
import ru.finassist.pf.core.api.model.ProfileUpdate
import ru.finassist.pf.core.api.model.TokenPair
import ru.finassist.pf.core.common.error.AppError
import ru.finassist.pf.core.common.time.ElapsedClock
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class RequestOwnerTest {
    private val server = MockWebServer()
    private val original = TokenStore.Session("u1", TokenPair("old-access", "old-refresh"), "login-1")
    private val owner = RequestOwner("u1", "login-1")
    @Before fun start() = server.start()
    @After fun stop() = server.shutdown()

    @Test fun `Retrofit binds both background profile operations before token selection`() = runBlocking<Unit> {
        val tokens = InMemoryTokenStore(original.copy(authSessionEpoch = "login-2"))
        val api = NetworkStack(server, tokens).api.profile
        withContext(owner) {
            assertFailsWith<AppError.Offline> { api.getProfile() }
            assertFailsWith<AppError.Offline> { api.updateProfile(ProfileUpdate(timezone = "Asia/Novosibirsk")) }
        }
        assertEquals(0, server.requestCount)
    }

    @Test fun `matching captured owner uses bearer but never sends identity as a header`() = runBlocking<Unit> {
        val api = NetworkStack(server, InMemoryTokenStore(original)).api.profile
        server.enqueue(MockResponse().setResponseCode(503).setBody("""{"error":{"code":"INTERNAL_ERROR","message":"failed"}}"""))
        withContext(owner) { assertFailsWith<AppError.Server> { api.getProfile() } }
        val request = assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        assertEquals("Bearer old-access", request.getHeader("Authorization"))
        assertFalse(request.headers.toString().contains("login-1"))
        assertEquals("RequestOwner", owner.toString())
    }

    @Test fun `new same user login during refresh prevents atomic save and final resend`() = runBlocking<Unit> {
        val base = InMemoryTokenStore(original)
        var guardedUpdates = 0
        val tokens = object : TokenStore by base {
            override suspend fun updateTokens(token: String, pair: TokenPair, epoch: String): Boolean {
                guardedUpdates++
                return base.updateTokens(token, pair, epoch)
            }
        }
        server.enqueue(MockResponse().setResponseCode(401))
        var refreshed = 0
        val replacement = original.copy(authSessionEpoch = "login-2")
        val client = OkHttpClient.Builder().addInterceptor(AuthInterceptor(tokens))
            .authenticator(TokenAuthenticator(tokens, ElapsedClock { 100L }) { _, _ ->
                refreshed++
                base.save(replacement) // same tokens/user, different login while refresh RPC is in flight
                TokenPair("stale-access", "stale-refresh")
            }).build()
        client.newCall(Request.Builder().url(server.url("/v1/profile")).tag(RequestOwner::class.java, owner).build())
            .execute().use { assertEquals(401, it.code) }
        assertEquals(1, refreshed)
        assertEquals(1, guardedUpdates)
        assertEquals(replacement, base.current())
        assertEquals(1, server.requestCount)
    }

    @Test fun `refresh retains captured login and resends with its new tokens`() = runBlocking<Unit> {
        val tokens = InMemoryTokenStore(original)
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setResponseCode(200))
        val client = OkHttpClient.Builder().addInterceptor(AuthInterceptor(tokens))
            .authenticator(TokenAuthenticator(tokens, ElapsedClock { 100L }) { _, _ -> TokenPair("fresh-access", "fresh-refresh") })
            .build()
        client.newCall(Request.Builder().url(server.url("/v1/profile")).tag(RequestOwner::class.java, owner).build())
            .execute().use { assertEquals(200, it.code) }
        assertEquals("login-1", tokens.current()?.authSessionEpoch)
        assertEquals("fresh-refresh", tokens.current()?.tokens?.refreshToken)
        assertEquals("Bearer old-access", server.takeRequest().getHeader("Authorization"))
        assertEquals("Bearer fresh-access", server.takeRequest().getHeader("Authorization"))
    }

    @Test fun `new login before 401 authentication prevents refresh entirely`() = runBlocking<Unit> {
        val base = InMemoryTokenStore(original)
        var reservations = 0
        val tokens = object : TokenStore by base {
            override suspend fun reserveRefreshAttempt(token: String, key: IdempotencyKey, epoch: String): Boolean {
                reservations++
                return base.reserveRefreshAttempt(token, key, epoch)
            }
        }
        val replacement = original.copy(authSessionEpoch = "login-2")
        var refreshed = 0
        val client = OkHttpClient.Builder().addInterceptor(AuthInterceptor(tokens))
            .addNetworkInterceptor { chain ->
                val result = chain.proceed(chain.request())
                // Network interceptors return before RetryAndFollowUpInterceptor invokes the authenticator.
                runBlocking { tokens.save(replacement) }
                result
            }
            .authenticator(TokenAuthenticator(tokens, ElapsedClock { 100L }) { _, _ -> refreshed++; TokenPair("a2", "r2") }).build()
        server.enqueue(MockResponse().setResponseCode(401))
        client.newCall(Request.Builder().url(server.url("/v1/profile")).tag(RequestOwner::class.java, owner).build())
            .execute().use { assertEquals(401, it.code) }
        assertEquals(0, reservations, "the captured-session guard must reject the old response before reserving refresh")
        assertEquals(0, refreshed)
        assertEquals(1, server.requestCount)
        assertEquals("Bearer old-access", server.takeRequest().getHeader("Authorization"))
        assertEquals(replacement, tokens.current())
    }
}
