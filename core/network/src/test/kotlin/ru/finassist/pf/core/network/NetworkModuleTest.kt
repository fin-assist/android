package ru.finassist.pf.core.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Authenticator
import okhttp3.logging.HttpLoggingInterceptor
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Before
import org.junit.Test
import ru.finassist.pf.core.api.TokenStore
import ru.finassist.pf.core.api.model.TokenPair
import ru.finassist.pf.core.network.di.NetworkModule
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The OkHttp setup of [NetworkModule]: what each client is built with and how the pieces work together. */
class NetworkModuleTest {
    private val server = MockWebServer()
    private val store = InMemoryTokenStore(TokenStore.Session("u1", TokenPair("old-access", "old-refresh")))
    private lateinit var stack: NetworkStack

    @Before
    fun setUp() {
        server.start()
        stack = NetworkStack(server, store)
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `plain client has timeouts, request id per attempt and no authenticator`() {
        val plain = stack.plain
        assertEquals(15_000, plain.connectTimeoutMillis)
        assertEquals(30_000, plain.readTimeoutMillis)
        assertEquals(0, plain.callTimeoutMillis)
        assertEquals(listOf(ClientVersionInterceptor::class, ServiceGateInterceptor::class), plain.interceptors.map { it::class })
        assertIs<RequestIdInterceptor>(plain.networkInterceptors.single())
        assertSame(Authenticator.NONE, plain.authenticator)
    }

    @Test
    fun `logging is added only when enabled`() {
        val logging = assertIs<HttpLoggingInterceptor>(
            NetworkModule.plainClient(
                logging = true,
                tlsFailures = TlsFailureReporter(RecordingCrashReporter()),
                version = stack.version,
                gate = stack.gate,
                clock = ru.finassist.pf.core.common.time.SystemClock(),
            ).interceptors.last(),
        )
        assertEquals(HttpLoggingInterceptor.Level.BASIC, logging.level)
    }

    @Test
    fun `optional application interceptor is inherited by SSE without reaching the network`() {
        var calls = 0
        val client = NetworkModule.plainClient(
            logging = false,
            tlsFailures = stack.tlsFailures,
            version = stack.version,
            gate = stack.gate,
            clock = ru.finassist.pf.core.common.time.SystemClock(),
            elapsedClock = ru.finassist.pf.core.common.time.ElapsedClock { 1_000L },
            clientInterceptors = setOf(okhttp3.Interceptor { chain ->
                calls++
                assertTrue(chain.request().header("X-Client-Version")!!.startsWith("android/"))
                okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1)
                    .code(204).message("Injected transport boundary").body(okhttp3.ResponseBody.create(null, "")).build()
            }),
        )
        client.newCall(okhttp3.Request.Builder().url(server.url("/v1/profile")).build()).execute().close()
        NetworkModule.sseClient(client).newCall(okhttp3.Request.Builder().url(server.url("/v1/auth/phone/events")).build()).execute().close()
        assertEquals(2, calls)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `main client adds the session and refreshes on 401, keeping the plain setup`() {
        val client = stack.client
        assertIs<AuthInterceptor>(client.interceptors.last())
        assertIs<RequestIdInterceptor>(client.networkInterceptors.single())
        assertIs<TokenAuthenticator>(client.authenticator)
        assertEquals(30_000, client.readTimeoutMillis)
        // No total-time limit: a large statement upload on a slow link legitimately takes minutes.
        assertEquals(0, client.callTimeoutMillis)
    }

    @Test
    fun `sse client has the same pipeline without a read timeout`() {
        val sse = stack.sseClient
        assertEquals(0, sse.readTimeoutMillis)
        assertEquals(15_000, sse.connectTimeoutMillis)
        assertIs<AuthInterceptor>(sse.interceptors.last())
        assertIs<TokenAuthenticator>(sse.authenticator)
    }

    @Test
    fun `401 refreshes through the refresh client and retries with a new request id`() = runBlocking {
        val requests = CopyOnWriteArrayList<RecordedRequest>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                return when {
                    request.path == "/v1/auth/token" -> json("""{"access_token":"new-access","refresh_token":"new-refresh"}""")
                    request.getHeader("Authorization") == "Bearer new-access" -> json(PROFILE_JSON)
                    else -> json("""{"error":{"code":"TOKEN_EXPIRED","message":"x"}}""", 401)
                }
            }
        }

        stack.api.profile.getProfile()

        assertEquals(listOf("/v1/profile", "/v1/auth/token", "/v1/profile"), requests.map { it.path })
        val (failed, refresh, retried) = requests
        assertEquals("Bearer old-access", failed.getHeader("Authorization"))
        assertNull(refresh.getHeader("Authorization"))
        assertTrue(refresh.getHeader(HEADER_IDEMPOTENCY_KEY)!!.isNotBlank())
        assertNull(refresh.getHeader(NoAuth.HEADER))
        assertEquals("""{"refresh_token":"old-refresh"}""", refresh.body.readUtf8())
        assertEquals("Bearer new-access", retried.getHeader("Authorization"))
        assertNotEquals(failed.getHeader(HEADER_REQUEST_ID), retried.getHeader(HEADER_REQUEST_ID))
        assertEquals(TokenPair("new-access", "new-refresh"), store.current()?.tokens)
    }

    @Test
    fun `refresh is not starved when every dispatcher slot waits in the authenticator`() = runBlocking {
        // OkHttp runs at most 5 requests per host. All 5 get their 401 only once all 5 are in flight, so every slot
        // of the main dispatcher is held by a request blocked in TokenAuthenticator while the refresh is enqueued.
        val parallel = 5
        val allInFlight = CountDownLatch(parallel)
        // Without it a request that never joined the others would let the latch time out and the test pass vacuously.
        val allSlotsHeld = AtomicBoolean(true)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path == "/v1/auth/token" -> json("""{"access_token":"new-access","refresh_token":"new-refresh"}""")
                request.getHeader("Authorization") == "Bearer new-access" -> json(PROFILE_JSON)
                else -> {
                    allInFlight.countDown()
                    if (!allInFlight.await(5, TimeUnit.SECONDS)) allSlotsHeld.set(false)
                    json("""{"error":{"code":"TOKEN_EXPIRED","message":"x"}}""", 401)
                }
            }
        }

        val profiles = withTimeout(15_000) {
            List(parallel) { async(Dispatchers.IO) { stack.api.profile.getProfile() } }.awaitAll()
        }

        assertTrue(allSlotsHeld.get(), "all $parallel requests were in flight at once")
        assertEquals(List(parallel) { "u1" }, profiles.map { it.userId })
        assertEquals(1, server.requestCount - 2 * parallel, "exactly one refresh call")
    }

    private fun json(body: String, code: Int = 200) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    private companion object {
        val PROFILE_JSON = """
            {"user_id":"u1","phone":"+79991234567","theme":"dark","timezone":"Europe/Moscow",
             "assistant_consent":{"granted":false,"current_version":"2.0"},"created_at":"2026-04-01T09:00:00+03:00"}
        """.trimIndent()
    }
    @Test
    fun `a new user signing in before a 401 is handled never receives the old mutation`() = runBlocking {
        val replacement = TokenStore.Session("different-user", TokenPair("different-access", "different-refresh"))
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                runBlocking { store.save(replacement) }
                return MockResponse().setResponseCode(401).setHeader("Content-Type", "application/json")
                    .setBody("""{"error":{"code":"INVALID_TOKEN","message":"private","retryable":false}}""")
            }
        }
        val key = ru.finassist.pf.core.api.model.IdempotencyKey.random(ru.finassist.pf.core.common.time.ElapsedClock { System.nanoTime() / 1_000_000 })
        kotlin.test.assertFailsWith<ru.finassist.pf.core.common.error.AppError.Unauthorized> {
            stack.api.assistant.ask(key, ru.finassist.pf.core.api.model.AskRequest("question"))
        }
        assertEquals(1, server.requestCount, "neither refresh nor the old POST may run for the replacement user")
        assertEquals(replacement, store.current())
    }

}
