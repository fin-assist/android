package ru.finassist.pf.core.network

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import ru.finassist.pf.core.api.TokenStore
import ru.finassist.pf.core.api.model.TokenPair
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Exercises the production client graph with debug diagnostics enabled, without rendering UI. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class HttpDiagnosticsPrivacyTest {
    private val server = MockWebServer()

    @Before
    fun setUp() {
        ShadowLog.clear()
        server.start()
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `debug HTTP diagnostics keep only method and status while private data reaches the API`() {
        val store = InMemoryTokenStore(TokenStore.Session("synthetic-user", TokenPair(ACCESS, REFRESH)))
        val stack = NetworkStack(server, tokenStore = store, logging = true)
        val url = server.url("/v1/operations/$PRIVATE_PATH").newBuilder()
            .addQueryParameter("q", QUERY)
            .addQueryParameter("amount_from", AMOUNT)
            .addQueryParameter("amount_to", AMOUNT_TO)
            .build()
        server.enqueue(MockResponse().setResponseCode(201).setHeader("X-Private", RESPONSE_SECRET).setBody(RESPONSE_SECRET))

        val request = Request.Builder().url(url)
            .header("X-Private", HEADER_SECRET)
            .post(BODY_SECRET.toRequestBody("application/json".toMediaType()))
            .build()
        stack.client.newCall(request).execute().use { response ->
            assertEquals(201, response.code)
            assertEquals(RESPONSE_SECRET, response.body?.string())
        }

        val received = assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        assertEquals(QUERY, received.requestUrl?.queryParameter("q"))
        assertEquals(AMOUNT, received.requestUrl?.queryParameter("amount_from"))
        assertEquals(AMOUNT_TO, received.requestUrl?.queryParameter("amount_to"))
        assertEquals("Bearer $ACCESS", received.getHeader("Authorization"))
        assertEquals(HEADER_SECRET, received.getHeader("X-Private"))
        assertEquals(BODY_SECRET, received.body.readUtf8())
        assertEquals(listOf("POST 201"), diagnostics())
        assertPrivateValuesAbsent(url.encodedQuery.orEmpty())
    }

    @Test
    fun `plain and SSE clients inherit the same bounded debug diagnostics`() {
        val stack = NetworkStack(server, logging = true)
        for (client in listOf(stack.plain, stack.sseClient)) {
            server.enqueue(MockResponse().setResponseCode(204))
            val request = Request.Builder().url(server.url("/v1/assistant/messages/$PRIVATE_PATH/events?q=$AMOUNT")).build()
            client.newCall(request).execute().close()
        }
        assertEquals(listOf("GET 204", "GET 204"), diagnostics())
        assertPrivateValuesAbsent()
    }

    @Test
    fun `debug failures never log the exception or request data and preserve the original failure`() {
        val stack = NetworkStack(server, logging = true)
        val failure = IOException("$EXCEPTION_SECRET $PRIVATE_PATH $QUERY $ACCESS $AMOUNT")
        val client = stack.client.newBuilder().addInterceptor(Interceptor { throw failure }).build()

        val thrown = assertFailsWith<IOException> {
            client.newCall(Request.Builder().url(server.url("/v1/operations/$PRIVATE_PATH?q=$AMOUNT")).build()).execute()
        }

        assertSame(failure, thrown)
        assertEquals(listOf("GET failed"), diagnostics())
        assertTrue(ShadowLog.getLogsForTag("PfHttp").all { it.throwable == null })
        assertPrivateValuesAbsent()
    }

    @Test
    fun `an arbitrary HTTP method cannot become a free form diagnostic`() {
        val stack = NetworkStack(server, logging = true)
        server.enqueue(MockResponse().setResponseCode(202))
        stack.plain.newCall(Request.Builder().url(server.url("/"))
            .method("PRIVATE_SEARCH_TOKEN", null).build()).execute().close()

        assertEquals(listOf("OTHER 202"), diagnostics())
        assertFalse(ShadowLog.getLogs().any { "PRIVATE_SEARCH_TOKEN" in it.msg })
    }

    @Test
    fun `disabled HTTP diagnostics emit no request response or failure log`() {
        val stack = NetworkStack(server, logging = false)
        server.enqueue(MockResponse().setBody(RESPONSE_SECRET))
        stack.client.newCall(Request.Builder().url(server.url("/v1/operations?q=$AMOUNT")).build()).execute().close()
        val client = stack.client.newBuilder().addInterceptor(Interceptor { throw IOException(EXCEPTION_SECRET) }).build()
        assertFailsWith<IOException> { client.newCall(Request.Builder().url(server.url("/")).build()).execute() }

        assertTrue(diagnostics().isEmpty())
        assertPrivateValuesAbsent()
    }

    private fun diagnostics(): List<String> = ShadowLog.getLogsForTag("PfHttp").map { it.msg }

    private fun assertPrivateValuesAbsent(encodedQuery: String = "") {
        val messages = ShadowLog.getLogs().joinToString("\n") { it.msg }
        val encodedValues = encodedQuery.split('&').map { it.substringAfter('=', "") }.filter { it.isNotEmpty() }
        for (value in listOf(QUERY, AMOUNT, AMOUNT_TO, ACCESS, REFRESH, PRIVATE_PATH, HEADER_SECRET,
            BODY_SECRET, RESPONSE_SECRET, EXCEPTION_SECRET) + encodedValues) {
            if (value.isNotEmpty()) assertFalse(value in messages, "private request/response values must not enter diagnostics")
        }
    }

    private companion object {
        const val QUERY = "Зарплата синтетического пользователя"
        const val AMOUNT = "123456789"
        const val AMOUNT_TO = "987654321"
        const val ACCESS = "synthetic-secret-access-token"
        const val REFRESH = "synthetic-secret-refresh-token"
        const val PRIVATE_PATH = "synthetic-private-object-id"
        const val HEADER_SECRET = "synthetic-private-header"
        const val BODY_SECRET = "synthetic-private-request-body"
        const val RESPONSE_SECRET = "synthetic-private-response"
        const val EXCEPTION_SECRET = "synthetic-private-exception-message"
    }
}
