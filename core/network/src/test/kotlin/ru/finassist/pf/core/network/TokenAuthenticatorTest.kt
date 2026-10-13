package ru.finassist.pf.core.network

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Before
import org.junit.Test
import ru.finassist.pf.core.api.TokenStore
import ru.finassist.pf.core.api.model.TokenPair
import ru.finassist.pf.core.common.error.AppError
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TokenAuthenticatorTest {
    private val server = MockWebServer()
    private lateinit var store: InMemoryTokenStore
    private val refreshCalls = AtomicInteger()

    @Before
    fun setUp() {
        store = InMemoryTokenStore(TokenStore.Session("u1", TokenPair("old-access", "old-refresh")))
        server.start()
    }

    @After
    fun tearDown() = server.shutdown()

    private fun client(refreshResult: suspend () -> TokenPair): OkHttpClient {
        val refreshMutex = Mutex()
        return OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(store))
            .authenticator(
                TokenAuthenticator(store) { _, _ ->
                    refreshMutex.withLock {
                        refreshCalls.incrementAndGet()
                        refreshResult()
                    }
                },
            )
            .build()
    }

    @Test
    fun `401 triggers one refresh and retries with the new access token`() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.getHeader("Authorization") == "Bearer new-access") {
                    MockResponse().setBody("ok")
                } else {
                    MockResponse().setResponseCode(401).setBody("""{"error":{"code":"INVALID_TOKEN","message":"x"}}""")
                }
        }
        val client = client { TokenPair("new-access", "new-refresh") }

        val response = client.newCall(Request.Builder().url(server.url("/v1/profile")).build()).execute()

        assertEquals(200, response.code)
        assertEquals(1, refreshCalls.get())
        assertEquals("new-refresh", runBlocking { store.current()?.tokens?.refreshToken })
    }

    @Test
    fun `parallel 401s share a single refresh`() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.getHeader("Authorization") == "Bearer new-access") {
                    MockResponse().setBody("ok")
                } else {
                    MockResponse().setResponseCode(401).setBody("""{"error":{"code":"INVALID_TOKEN","message":"x"}}""")
                }
        }
        val client = client { TokenPair("new-access", "new-refresh") }

        val codes = runBlocking {
            (1..4).map {
                async(kotlinx.coroutines.Dispatchers.IO) {
                    client.newCall(Request.Builder().url(server.url("/v1/operations")).build()).execute().code
                }
            }.awaitAll()
        }

        assertEquals(listOf(200, 200, 200, 200), codes)
        assertEquals(1, refreshCalls.get())
    }

    @Test
    fun `revoked refresh clears the session`() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"code":"INVALID_TOKEN","message":"x"}}"""))
        val client = client { throw ru.finassist.pf.core.common.error.AppError.Unauthorized("TOKEN_REVOKED") }

        val response = client.newCall(Request.Builder().url(server.url("/v1/profile")).build()).execute()

        assertEquals(401, response.code)
        assertNull(runBlocking { store.current() })
    }

    @Test
    fun `auth endpoints never refresh`() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"code":"INVALID_TOKEN","message":"x"}}"""))
        val client = client { TokenPair("new-access", "new-refresh") }

        val response = client.newCall(Request.Builder().url(server.url("/v1/auth/phone/events")).build()).execute()

        assertEquals(401, response.code)
        assertEquals(0, refreshCalls.get())
    }

    @Test
    fun `refresh retried after a lost response reuses the same idempotency key`() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.getHeader("Authorization") == "Bearer new-access") {
                    MockResponse().setBody("ok")
                } else {
                    MockResponse().setResponseCode(401).setBody("""{"error":{"code":"INVALID_TOKEN","message":"x"}}""")
                }
        }
        val keys = mutableListOf<String>()
        var attempt = 0
        val client = OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(store))
            .authenticator(
                TokenAuthenticator(store) { key, _ ->
                    keys += key.value
                    // First attempt: the server rotated the pair but the response was lost.
                    if (attempt++ == 0) throw AppError.Offline() else TokenPair("new-access", "new-refresh")
                },
            )
            .build()

        val first = client.newCall(Request.Builder().url(server.url("/v1/profile")).build()).execute()
        assertEquals(401, first.code)
        val second = client.newCall(Request.Builder().url(server.url("/v1/profile")).build()).execute()
        assertEquals(200, second.code)
        assertEquals(2, keys.size)
        assertEquals(keys[0], keys[1])
    }

    private fun alwaysUnauthorized() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) =
                MockResponse().setResponseCode(401).setBody("""{"error":{"code":"INVALID_TOKEN","message":"x"}}""")
        }
    }

    @Test
    fun `a retry that is still 401 is not refreshed again`() {
        alwaysUnauthorized()
        var pair = 0
        val client = client { TokenPair("new-access-${++pair}", "new-refresh-$pair") }

        val response = client.newCall(Request.Builder().url(server.url("/v1/profile")).build()).execute()

        assertEquals(401, response.code)
        assertEquals(1, refreshCalls.get())
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `sign-out while refreshing is not undone by the refresh result`() {
        alwaysUnauthorized()
        val client = client {
            store.clear() // the user signed out while the refresh was in flight
            TokenPair("new-access", "new-refresh")
        }

        val response = client.newCall(Request.Builder().url(server.url("/v1/profile")).build()).execute()

        assertEquals(401, response.code)
        assertEquals(1, server.requestCount, "the request is not retried with the discarded pair")
        assertNull(runBlocking { store.current() })
    }

    @Test
    fun `no session means no refresh`() {
        alwaysUnauthorized()
        runBlocking { store.clear() }
        val client = client { TokenPair("new-access", "new-refresh") }

        val response = client.newCall(Request.Builder().url(server.url("/v1/profile")).build()).execute()

        assertEquals(401, response.code)
        assertEquals(0, refreshCalls.get())
    }

    @Test
    fun `refresh rejected with a client error clears the session`() {
        alwaysUnauthorized()
        val client = client { throw AppError.Api(code = "VALIDATION_ERROR", httpStatus = 400) }

        client.newCall(Request.Builder().url(server.url("/v1/profile")).build()).execute()

        assertNull(runBlocking { store.current() })
    }

    @Test
    fun `transient refresh failures keep the session`() {
        val transient = listOf(
            AppError.InProgress(1),
            AppError.Api(code = "IDEMPOTENCY_CONFLICT", httpStatus = 409),
            AppError.Api(code = "RATE_LIMITED", httpStatus = 429),
            AppError.RateLimited(null),
            AppError.Server(503),
            AppError.Offline(),
        )
        for (error in transient) {
            alwaysUnauthorized()
            val client = client { throw error }

            val response = client.newCall(Request.Builder().url(server.url("/v1/profile")).build()).execute()

            assertEquals(401, response.code)
            assertEquals("old-refresh", runBlocking { store.current()?.tokens?.refreshToken }, "after $error")
        }
    }
}
