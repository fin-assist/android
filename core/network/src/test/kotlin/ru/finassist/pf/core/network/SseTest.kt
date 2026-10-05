package ru.finassist.pf.core.network

import app.cash.turbine.test
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import ru.finassist.pf.core.api.ImportEvent
import ru.finassist.pf.core.api.model.AnswerEvent
import ru.finassist.pf.core.api.model.ImportStage
import ru.finassist.pf.core.common.error.AppError
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SseTest {
    private val server = MockWebServer()
    private lateinit var sse: SseRequests

    @Before
    fun setUp() {
        server.start()
        sse = SseRequests(server.url("/"), OkHttpClient())
    }

    @After
    fun tearDown() = server.shutdown()

    private fun stream(vararg events: Pair<String, String>): MockResponse = MockResponse()
        .setHeader("Content-Type", "text/event-stream")
        .setBody(events.joinToString("") { (type, data) -> "event: $type\ndata: $data\n\n" })

    @Test
    fun `import progress events are typed and the flow completes after the terminal event`() = runBlocking {
        server.enqueue(
            stream(
                "progress" to """{"stage":"parsing","percent":20}""",
                "progress" to """{"stage":"rules","percent":85}""",
                "complete" to COMPLETE_JSON,
            ),
        )
        val api = HttpStatementsApi(Retrofit.Builder().baseUrl(server.url("/")).build().create(StatementsService::class.java), sse)

        api.streamProgress("u1").test {
            val first = assertIs<ImportEvent.Progress>(awaitItem())
            assertEquals(ImportStage.PARSING, first.progress.stage)
            assertEquals(85, assertIs<ImportEvent.Progress>(awaitItem()).progress.percent)
            val done = assertIs<ImportEvent.Complete>(awaitItem())
            assertEquals(1086, done.result.operationCount)
            assertTrue(done.result.isFirstImport)
            awaitComplete()
        }
    }

    @Test
    fun `unknown stage code does not break parsing`() = runBlocking {
        server.enqueue(stream("progress" to """{"stage":"something_new","percent":5}"""))
        val api = HttpStatementsApi(Retrofit.Builder().baseUrl(server.url("/")).build().create(StatementsService::class.java), sse)

        api.streamProgress("u1").test {
            assertEquals(ImportStage.UNKNOWN, assertIs<ImportEvent.Progress>(awaitItem()).progress.stage)
            awaitComplete()
        }
    }

    @Test
    fun `answer stream maps every event type and ignores unknown ones`() = runBlocking {
        server.enqueue(
            stream(
                "snapshot" to """{"status":"generating","blocks":[{"type":"text","text":"За"}],"chips":[],"source":null}""",
                "chunk" to """{"index":0,"text":" апрель"}""",
                "future_event" to """{"x":1}""",
                "chip" to """{"label":"Аналитика","screen":"analytics","params":{"period":"month","date":"2026-09","transfer_mode":"with"}}""",
                "done" to """{"charged":true,"remaining_limit":4}""",
            ),
        )
        val api = HttpAssistantApi(Retrofit.Builder().baseUrl(server.url("/")).build().create(AssistantService::class.java), sse)

        api.streamAnswer("a1").test {
            assertIs<AnswerEvent.Snapshot>(awaitItem())
            assertEquals(" апрель", assertIs<AnswerEvent.Chunk>(awaitItem()).event.text)
            assertIs<AnswerEvent.Unknown>(awaitItem())
            assertEquals("2026-09", assertIs<AnswerEvent.ChipAdded>(awaitItem()).event.params?.date?.value)
            assertEquals(4, assertIs<AnswerEvent.Done>(awaitItem()).event.remainingLimit)
            awaitComplete()
        }
    }

    @Test
    fun `http error before the stream starts is mapped`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":{"code":"NOT_FOUND","message":"x"}}"""))
        val api = HttpAssistantApi(Retrofit.Builder().baseUrl(server.url("/")).build().create(AssistantService::class.java), sse)

        api.streamAnswer("missing").test {
            val error = assertIs<AppError.Api>(awaitError())
            assertEquals("NOT_FOUND", error.code)
        }
    }

    private companion object {
        val COMPLETE_JSON = """
            {"operation_count":1086,"new_count":1086,"duplicate_count":0,"unread_count":0,
             "first_operation_at":"2026-04-01T09:12:00+03:00","last_operation_at":"2026-09-25T21:40:00+03:00",
             "is_first_import":true,"totals":{"scope":"all","expense":52882000,"income":92140000},
             "accounts":[{"type_name":"Текущий","mask":"··4821","is_other_bank":false}],
             "categorized_count":1052,"own_transfer_count":24,"uncategorized_count":34,
             "uncategorized_filters":{"selection":"s_uncategorized","selection_name":"Без категории"},
             "coverage":{"full_months":5,"full_months_before":0,"newly_full_months":["2026-04"],
               "incomplete_months":[],"features":[{"feature":"comparison","open":true,"opened_now":true}]},
             "notices":[]}
        """.trimIndent().replace("\n", "")
    }
}
