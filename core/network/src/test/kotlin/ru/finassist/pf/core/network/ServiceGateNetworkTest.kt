package ru.finassist.pf.core.network

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import ru.finassist.pf.core.common.availability.ServiceGate
import ru.finassist.pf.core.common.error.AppError
import java.time.OffsetDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** FIN-22: the client version on every request and the server's update / maintenance answers in the [ServiceGate]. */
class ServiceGateNetworkTest {
    private val server = MockWebServer()
    private lateinit var stack: NetworkStack

    @Before
    fun setUp() {
        server.start()
        stack = NetworkStack(server)
    }

    @After
    fun tearDown() = server.shutdown()

    private val profile = """{"user_id":"u1","phone":"+79161234567","theme":"system","timezone":"Europe/Moscow","consents":[],"created_at":"2026-10-04T20:00:00+03:00"}"""

    @Test
    fun `every request carries the client version, auth calls included`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setBody(profile))
        server.enqueue(MockResponse().setBody("""{"type":"personal_data","version":"1","url":"https://plainfin.ru/pd","title":"Согласие"}"""))
        stack.api.profile.getProfile()
        runCatching { stack.api.auth.getConsentDocument(ru.finassist.pf.core.api.model.ConsentType.PERSONAL_DATA) }
        repeat(2) { assertEquals("android/0.2.7+41", server.takeRequest().getHeader("X-Client-Version")) }
    }

    @Test
    fun `CLIENT_VERSION_UNSUPPORTED blocks the app until an update`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":{"code":"CLIENT_VERSION_UNSUPPORTED","message":"update"}}"""))
        assertFailsWith<AppError.UpdateRequired> { stack.api.profile.getProfile() }
        assertEquals(ServiceGate.State.UpdateRequired, stack.gate.state.value)

        // A later success does not unblock: only a new version does.
        server.enqueue(MockResponse().setBody(profile))
        stack.api.profile.getProfile()
        assertEquals(ServiceGate.State.UpdateRequired, stack.gate.state.value)
    }

    @Test
    fun `MAINTENANCE carries until and Retry-After, and the next success reopens the app`() = runBlocking<Unit> {
        server.enqueue(
            MockResponse().setResponseCode(503).setHeader("Retry-After", "600")
                .setBody("""{"error":{"code":"MAINTENANCE","message":"m","until":"2026-10-15T14:30:00+03:00"}}"""),
        )
        val e = assertFailsWith<AppError.Maintenance> { stack.api.profile.getProfile() }
        assertEquals(OffsetDateTime.parse("2026-10-15T14:30:00+03:00"), e.until)
        assertEquals(600, e.retryAfterSeconds)
        val m = assertIs<ServiceGate.State.Maintenance>(stack.gate.state.value)
        assertEquals(e.until, m.until)
        assertTrue(m.retryAt.isAfter(OffsetDateTime.now().plusSeconds(590)))

        server.enqueue(MockResponse().setBody(profile))
        stack.api.profile.getProfile()
        assertEquals(ServiceGate.State.Open, stack.gate.state.value)
        assertEquals(1, stack.gate.reopened.value)
    }

    @Test
    fun `another 503 keeps the maintenance, another 400 is an ordinary error`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setResponseCode(503).setBody("""{"error":{"code":"MAINTENANCE","message":"m"}}"""))
        runCatching { stack.api.profile.getProfile() }
        server.enqueue(MockResponse().setResponseCode(503))
        assertIs<AppError.Server>(runCatching { stack.api.profile.getProfile() }.exceptionOrNull())
        assertIs<ServiceGate.State.Maintenance>(stack.gate.state.value)
    }
}
