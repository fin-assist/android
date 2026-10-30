package ru.finassist.pf.e2e

import android.content.Context
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import android.app.Application
import java.io.File
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class StageFaultsTest {
    private lateinit var context: Context
    private lateinit var store: StageRunStore
    private lateinit var faults: StageFaults
    private var realCalls = 0
    private var responseText = "{}"
    private var responseStatus = 200
    private lateinit var client: OkHttpClient

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.filesDir.listFiles().orEmpty().filter { it.name.startsWith("stage-") }.forEach { it.delete() }
        File(context.filesDir, "stage-run.json").writeText(Json.encodeToString(StageRun("11111111-2222-4333-8444-555555555555", "+70000001234")))
        store = StageRunStore(context)
        faults = StageFaults(context, store)
        client = OkHttpClient.Builder().addInterceptor(faults).addInterceptor { chain ->
            realCalls++
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(responseStatus).message("Real transport fixture").body(responseText.toResponseBody()).build()
        }.build()
    }

    private fun request(path: String = "/v1/profile", method: String = "GET") =
        client.newCall(Request.Builder().url("https://stage.plainfin.ru$path").method(method, null).build())

    @Test
    fun `normal requests reach real boundary and evidence contains only safe counters`() {
        request().execute().use { assertEquals(200, it.code) }
        assertEquals(1, realCalls)
        assertEquals(1, faults.count("real_requests"))
        val report = File(context.filesDir, "stage-evidence.json").readText()
        assertFalse(report.contains("+70000001234"))
        assertFalse(report.contains("profile"))
        assertTrue(report.contains("\"has_client_injections\":false"))
        assertEquals(1, StageFaults(context, store).count("real_requests"))
    }

    @Test
    fun `offline fault blocks requests then recovery returns to real transport`() {
        faults.configure(mapOf("offline" to "true"))
        assertFailsWith<IOException> { request().execute() }
        assertEquals(0, realCalls)
        faults.configure(mapOf("offline" to "false"))
        request().execute().close()
        assertEquals(1, realCalls)
        assertEquals(1, faults.count("offline"))
    }

    @Test
    fun `stream drop is followed by bounded 429 then real stream`() {
        faults.configure(mapOf("stream_drops" to "1", "stream_rate_limits" to "1", "stream_retry_s" to "3"))
        request("/v1/statements/test/progress").execute().use { assertEquals("", it.body!!.string()) }
        request("/v1/statements/test/progress").execute().use {
            assertEquals(429, it.code)
            assertEquals("3", it.header("Retry-After"))
        }
        request("/v1/statements/test/progress").execute().use { assertEquals("{}", it.body!!.string()) }
        assertEquals(2, realCalls)
        assertEquals(1, faults.streamRateLimited.value)
        assertEquals(1, faults.count("stream_drop"))
    }

    @Test
    fun `missing receipt and other origin are refused before transport`() {
        assertFailsWith<IOException> {
            client.newCall(Request.Builder().url("https://other.example/v1/profile").build()).execute()
        }
        File(context.filesDir, "stage-run.json").delete()
        assertFailsWith<IOException> { request().execute() }
        assertEquals(0, realCalls)
    }

    @Test
    fun `invalid compound control applies no valid prefix`() {
        assertFailsWith<IllegalArgumentException> { faults.configure(mapOf("offline" to "true", "arbitrary" to "body")) }
        request().execute().close()
        assertEquals(1, realCalls)
    }
    @Test
    fun `only real successful registration with correlated verification gets an ownership receipt`() {
        fun post(path: String, body: String) = client.newCall(Request.Builder()
            .url("https://stage.plainfin.ru$path").post(body.toRequestBody()).build()).execute()
        responseText = """{"verification_token":"verification-secret"}"""
        post("/v1/auth/phone", """{"phone":"+70000001234"}""").close()
        responseText = "event: status\ndata: {\"status\":\"verified\",\"is_new_user\":true,\"registration_token\":\"registration-secret\"}\n\n"
        client.newCall(Request.Builder().url("https://stage.plainfin.ru/v1/auth/phone/events")
            .header("X-Verification-Token", "verification-secret").build()).execute().use { it.body!!.string() }
        responseStatus = 500
        responseText = "{}"
        post("/v1/auth/register", """{"registration_token":"registration-secret"}""").close()
        assertFalse(File(context.filesDir, "stage-evidence.json").readText().contains("\"phone\""))
        responseStatus = 200
        responseText = """{"user_id":"aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"}"""
        post("/v1/auth/register", """{"registration_token":"registration-secret"}""").close()
        val evidence = File(context.filesDir, "stage-evidence.json").readText()
        assertTrue(faults.canCleanup("+70000001234", "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"))
        assertFalse(faults.canCleanup("+70000001234", "foreign-user"))
        assertFalse(faults.canCleanup("+70000009999", "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"))
        assertTrue(evidence.contains("+70000001234"))
        assertTrue(evidence.contains("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"))
        assertFalse(evidence.contains("verification-secret"))
        assertFalse(evidence.contains("registration-secret"))
        assertTrue(evidence.contains("\"has_client_injections\":false"))
        val before = realCalls
        assertFailsWith<IOException> { post("/v1/auth/register", """{"registration_token":"foreign"}""") }
        assertEquals(before, realCalls)
    }

    @Test
    fun `invalid receipt fails the asynchronous request without crashing the dispatcher`() {
        File(context.filesDir, "stage-run.json").writeText(Json.encodeToString(
            StageRun("11111111-2222-4333-8444-555555555555", "+70000001234", "+70000001234"),
        ))
        val finished = java.util.concurrent.CountDownLatch(1)
        var failure: IOException? = null
        request().enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, error: IOException) {
                failure = error
                finished.countDown()
            }
            override fun onResponse(call: okhttp3.Call, response: Response) {
                response.close()
                finished.countDown()
            }
        })
        assertTrue(finished.await(10, java.util.concurrent.TimeUnit.SECONDS))
        assertEquals("Stage harness rejected an invalid request or run receipt", failure?.message)
        assertEquals(0, realCalls)
    }

    private fun authenticatedRequest(path: String, method: String = "GET", body: String? = null, authorization: String = "Bearer synthetic-owned-token") =
        client.newCall(Request.Builder().url("https://stage.plainfin.ru$path")
            .header("Authorization", authorization).method(method, body?.toRequestBody()).build())

    private fun bindOwnedProfile() {
        responseText = """{"phone":"+70000001234"}"""
        authenticatedRequest("/v1/profile").execute().close()
    }

    private val acceptedAsk = """{"question":{"id":"question-1","role":"user","text":"Synthetic question","created_at":"2026-10-30T12:00:00Z"},"answer_id":"answer-1","remaining_limit":9}"""

    @Test
    fun `accepted Ask is durably recorded before delivery and survives harness reload`() {
        bindOwnedProfile()
        faults.configure(mapOf("hold_ask_response_ms" to "60000"))
        responseText = acceptedAsk
        val finished = java.util.concurrent.CountDownLatch(1)
        val failure = java.util.concurrent.atomic.AtomicReference<Exception?>()
        val returned = java.util.concurrent.atomic.AtomicBoolean(false)
        val worker = Thread {
            try {
                authenticatedRequest("/v1/assistant/messages", "POST", """{"text":"Synthetic question"}""").execute().use {
                    returned.set(true)
                }
            } catch (error: Exception) {
                failure.set(error)
            } finally {
                finished.countDown()
            }
        }
        worker.start()
        try {
            val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10)
            while (faults.count("ask_response_held") == 0 && System.nanoTime() < deadline) Thread.sleep(5)
            assertEquals(1, faults.count("ask_response_held"))
            assertEquals(1, faults.count("real_ask_accepted"))
            assertEquals(0, faults.count("ask_response_released"))
            assertFalse(returned.get())
            val restarted = StageFaults(context, store)
            assertEquals(1, restarted.count("real_ask_accepted"))
            assertEquals(1, restarted.count("ask_response_held"))
            val evidence = File(context.filesDir, "stage-evidence.json").readText()
            assertTrue(evidence.contains("\"has_client_injections\":true"))
            assertFalse(evidence.contains("synthetic-owned-token"))
            assertFalse(evidence.contains("Synthetic question"))
        } finally {
            worker.interrupt()
            assertTrue(finished.await(10, java.util.concurrent.TimeUnit.SECONDS))
        }
        assertTrue(failure.get() is IOException)
        assertEquals("Stage UI Ask response hold interrupted", failure.get()?.message)
        assertFalse(returned.get())
    }

    @Test
    fun `unheld successful Ask is a passive real metric and refused Ask is never accepted`() {
        bindOwnedProfile()
        responseText = acceptedAsk
        authenticatedRequest("/v1/assistant/messages", "POST", "{}").execute().close()
        assertEquals(1, faults.count("real_ask_accepted"))
        assertEquals(0, faults.count("ask_response_held"))
        assertTrue(File(context.filesDir, "stage-evidence.json").readText().contains("\"has_client_injections\":false"))
        faults.configure(mapOf("hold_ask_response_ms" to "60000"))
        responseStatus = 429
        responseText = "{}"
        authenticatedRequest("/v1/assistant/messages", "POST", "{}").execute().close()
        assertEquals(1, faults.count("real_ask_accepted"))
        assertEquals(0, faults.count("ask_response_held"))
    }

    @Test
    fun `Ask cannot get a false held marker for an unverified authenticated profile`() {
        faults.configure(mapOf("hold_ask_response_ms" to "60000"))
        responseText = acceptedAsk
        assertFailsWith<IOException> { authenticatedRequest("/v1/assistant/messages", "POST", "{}").execute() }
        assertEquals(1, faults.count("real_ask_accepted"))
        assertEquals(0, faults.count("ask_response_held"))
    }

    @Test
    fun `only successful DELETE with the same actual owned authentication records deletion`() {
        bindOwnedProfile()
        responseStatus = 500
        responseText = "{}"
        authenticatedRequest("/v1/profile", "DELETE").execute().close()
        assertFalse(File(context.filesDir, "stage-evidence.json").readText().contains("\"deleted_phones\":[\""))
        responseStatus = 204
        authenticatedRequest("/v1/profile", "DELETE", authorization = "Bearer foreign-token").execute().close()
        assertFalse(File(context.filesDir, "stage-evidence.json").readText().contains("\"deleted_phones\":[\""))
        authenticatedRequest("/v1/profile", "DELETE").execute().close()
        val evidence = File(context.filesDir, "stage-evidence.json").readText()
        assertTrue(evidence.contains("\"deleted_phones\":[\"+70000001234\"]"))
        assertTrue(evidence.contains("\"has_client_injections\":false"))
        assertFalse(evidence.contains("synthetic-owned-token"))
        StageFaults(context, store)
    }

}
