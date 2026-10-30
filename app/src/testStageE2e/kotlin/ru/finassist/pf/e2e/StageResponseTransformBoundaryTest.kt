package ru.finassist.pf.e2e

import android.app.Application
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class StageResponseTransformBoundaryTest {
    @Test
    fun `unknown alias is observed before the real request while disabled fixture sends it unchanged`() {
        val context = RuntimeEnvironment.getApplication()
        context.filesDir.listFiles().orEmpty().filter { it.name.startsWith("stage-") }.forEach { it.delete() }
        File(context.filesDir, "stage-run.json").writeText(Json.encodeToString(StageRun("11111111-2222-4333-8444-555555555555", "+70000001234")))
        val faults = StageFaults(context, StageRunStore(context))
        val sent = mutableListOf<Request>()
        val client = OkHttpClient.Builder().addInterceptor(faults).addInterceptor { chain ->
            sent += chain.request()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("Actual transport boundary fixture")
                .body("{}".toResponseBody()).build()
        }.build()
        val original = Request.Builder().url("https://stage.plainfin.ru/v1/operations?kind=expense_e2e&selection=retained&transfer_mode=without").build()
        client.newCall(original).execute().close()
        assertEquals("expense_e2e", sent.last().url.queryParameter("kind"))
        assertEquals(0, faults.count("unknown_kind_requests"))
        faults.configure(mapOf("unknown_codes" to "true"))
        client.newCall(original).execute().close()
        assertEquals("expense", sent.last().url.queryParameter("kind"))
        assertEquals("retained", sent.last().url.queryParameter("selection"))
        assertEquals("without", sent.last().url.queryParameter("transfer_mode"))
        assertEquals(1, faults.count("unknown_kind_requests"))
        assertEquals(2, faults.count("real_requests"))
        assertTrue(File(context.filesDir, "stage-evidence.json").readText().contains("\"has_client_injections\":true"))
    }

    @Test
    fun `http failure cannot be transformed into a successful import completion`() {
        val context = RuntimeEnvironment.getApplication()
        context.filesDir.listFiles().orEmpty().filter { it.name.startsWith("stage-") }.forEach { it.delete() }
        File(context.filesDir, "stage-run.json").writeText(Json.encodeToString(StageRun("11111111-2222-4333-8444-555555555555", "+70000001234")))
        val faults = StageFaults(context, StageRunStore(context))
        faults.configure(mapOf("unknown_codes" to "true"))
        val raw = "event: complete\ndata: {}\n\n"
        val client = OkHttpClient.Builder().addInterceptor(faults).addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(500).message("Real refusal fixture")
                .body(raw.toResponseBody()).build()
        }.build()
        client.newCall(Request.Builder().url("https://stage.plainfin.ru/v1/statements/owned-id/progress").build()).execute().use {
            assertEquals(500, it.code)
            assertEquals(raw, it.body!!.string())
        }
        assertEquals(0, faults.count("import_response_transform"))
    }
}
