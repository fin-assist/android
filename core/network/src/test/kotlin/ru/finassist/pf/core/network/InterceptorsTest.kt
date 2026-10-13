package ru.finassist.pf.core.network

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import ru.finassist.pf.core.api.TokenStore
import ru.finassist.pf.core.api.model.TokenPair
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class InterceptorsTest {
    private val server = MockWebServer()

    @Before
    fun setUp() = server.start()

    @After
    fun tearDown() = server.shutdown()

    private fun get(client: OkHttpClient, build: Request.Builder.() -> Unit = {}) {
        client.newCall(Request.Builder().url(server.url("/v1/profile")).apply(build).build()).execute().close()
    }

    @Test
    fun `request id is a fresh uuid per call and replaces one set by the caller`() {
        repeat(2) { server.enqueue(MockResponse()) }
        val client = OkHttpClient.Builder().addNetworkInterceptor(RequestIdInterceptor()).build()

        get(client) { header(HEADER_REQUEST_ID, "stale") }
        get(client)

        val first = server.takeRequest().getHeader(HEADER_REQUEST_ID)!!
        val second = server.takeRequest().getHeader(HEADER_REQUEST_ID)!!
        assertEquals(first, UUID.fromString(first).toString())
        assertEquals(second, UUID.fromString(second).toString())
        assertNotEquals(first, second)
    }

    @Test
    fun `no session means no Authorization header`() {
        server.enqueue(MockResponse())
        val client = OkHttpClient.Builder().addInterceptor(AuthInterceptor(InMemoryTokenStore())).build()

        get(client)

        assertNull(server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `session access token goes as bearer`() {
        server.enqueue(MockResponse())
        val store = InMemoryTokenStore(TokenStore.Session("u1", TokenPair("acc", "ref")))
        val client = OkHttpClient.Builder().addInterceptor(AuthInterceptor(store)).build()

        get(client)

        assertEquals("Bearer acc", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `no-auth request gets no token and the marker never reaches the server`() {
        server.enqueue(MockResponse())
        val store = InMemoryTokenStore(TokenStore.Session("u1", TokenPair("acc", "ref")))
        val client = OkHttpClient.Builder().addInterceptor(AuthInterceptor(store)).build()

        get(client) { header(NoAuth.HEADER, NoAuth.VALUE) }

        val request = server.takeRequest()
        assertNull(request.getHeader("Authorization"))
        assertNull(request.getHeader(NoAuth.HEADER))
    }

    @Test
    fun `no-auth request keeps an explicit Authorization (logout of an ended session)`() {
        server.enqueue(MockResponse())
        val store = InMemoryTokenStore(TokenStore.Session("u1", TokenPair("new-acc", "new-ref")))
        val client = OkHttpClient.Builder().addInterceptor(AuthInterceptor(store)).build()

        get(client) {
            header(NoAuth.HEADER, NoAuth.VALUE)
            header("Authorization", "Bearer old-acc")
        }

        assertEquals("Bearer old-acc", server.takeRequest().getHeader("Authorization"))
    }
}
