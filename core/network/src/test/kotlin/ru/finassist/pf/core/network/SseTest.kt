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
import okhttp3.mockwebserver.SocketPolicy
import ru.finassist.pf.core.api.TokenStore
import ru.finassist.pf.core.api.model.AnswerErrorCode
import ru.finassist.pf.core.api.model.AnswerEvent
import ru.finassist.pf.core.api.model.BlockType
import ru.finassist.pf.core.api.model.ErrorCodes
import ru.finassist.pf.core.api.model.ImportStage
import ru.finassist.pf.core.api.model.TokenPair
import ru.finassist.pf.core.api.model.TransferMode
import ru.finassist.pf.core.api.model.VerificationStatus
import ru.finassist.pf.core.common.error.AppError
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
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

    private fun statementsApi() =
        HttpStatementsApi(Retrofit.Builder().baseUrl(server.url("/")).build().create(StatementsService::class.java), sse)

    private fun assistantApi() =
        HttpAssistantApi(Retrofit.Builder().baseUrl(server.url("/")).build().create(AssistantService::class.java), sse)

    @Test
    fun `phone verification stream goes with the verification token and without the session`() = runBlocking {
        server.enqueue(
            stream(
                "status" to """{"status":"pending"}""",
                "status" to """{"status":"verified","is_new_user":false,"access_token":"a","refresh_token":"r"}""",
            ),
        )
        // The production SSE client: AuthInterceptor would add the session if the marker were missing.
        val stack = NetworkStack(server, InMemoryTokenStore(TokenStore.Session("u0", TokenPair("stale", "stale-r"))))

        stack.api.auth.streamPhoneVerification("vt-1").test {
            assertEquals(VerificationStatus.PENDING, awaitItem().status)
            val verified = awaitItem()
            assertEquals(VerificationStatus.VERIFIED, verified.status)
            assertEquals("a", verified.accessToken)
            awaitComplete()
        }
        val request = server.takeRequest()
        assertEquals("/v1/auth/phone/events", request.path)
        assertEquals("vt-1", request.getHeader(HEADER_VERIFICATION_TOKEN))
        assertEquals("text/event-stream", request.getHeader("Accept"))
        assertNull(request.getHeader("Authorization"))
        assertNull(request.getHeader(NoAuth.HEADER))
    }

    @Test
    fun `phone verification ignores unknown events and fails on an error event with its code`() = runBlocking {
        server.enqueue(
            stream(
                "heartbeat" to "{}",
                "error" to """{"code":"RATE_LIMITED","message":"Слишком часто"}""",
            ),
        )
        val api = HttpAuthApi(Retrofit.Builder().baseUrl(server.url("/")).build().create(AuthService::class.java), sse)

        api.streamPhoneVerification("vt").test {
            val error = assertIs<AppError.Api>(awaitError())
            assertEquals("RATE_LIMITED", error.code)
            assertEquals(200, error.httpStatus)
            assertEquals("Слишком часто", error.message)
        }
    }

    @Test
    fun `phone verification error event without a readable payload is an internal error`() = runBlocking {
        server.enqueue(stream("error" to "not json"))
        val api = HttpAuthApi(Retrofit.Builder().baseUrl(server.url("/")).build().create(AuthService::class.java), sse)

        api.streamPhoneVerification("vt").test {
            assertEquals(ErrorCodes.INTERNAL_ERROR, assertIs<AppError.Api>(awaitError()).code)
        }
    }

    @Test
    fun `malformed status payload is a server error, not a crash`() = runBlocking {
        server.enqueue(stream("status" to """{"is_new_user":true}"""))
        val api = HttpAuthApi(Retrofit.Builder().baseUrl(server.url("/")).build().create(AuthService::class.java), sse)

        api.streamPhoneVerification("vt").test {
            assertEquals(200, assertIs<AppError.Server>(awaitError()).httpStatus)
        }
    }

    @Test
    fun `import error event is a typed event and the stream completes`() = runBlocking {
        server.enqueue(
            stream(
                "progress" to """{"stage":"dedup","percent":40}""",
                "error" to """{"code":"PROCESSING_FAILED","message":"Не удалось обработать"}""",
            ),
        )

        statementsApi().streamProgress("u1").test {
            assertIs<ImportEvent.Progress>(awaitItem())
            assertEquals(ImportEvent.Error("PROCESSING_FAILED", "Не удалось обработать"), awaitItem())
            awaitComplete()
        }
        assertEquals("/v1/statements/u1/progress", server.takeRequest().path)
    }

    @Test
    fun `import stream ignores unknown event types`() = runBlocking {
        server.enqueue(stream("future" to "{}", "progress" to """{"stage":"rules","percent":99}"""))

        statementsApi().streamProgress("u1").test {
            assertEquals(99, assertIs<ImportEvent.Progress>(awaitItem()).progress.percent)
            awaitComplete()
        }
    }

    @Test
    fun `connection dropped mid-stream is offline`() = runBlocking {
        server.enqueue(
            stream("progress" to """{"stage":"parsing","percent":10}""")
                .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY),
        )

        statementsApi().streamProgress("u1").test {
            assertIs<AppError.Offline>(awaitError())
        }
    }

    @Test
    fun `answer stream maps block, source and error events`() = runBlocking {
        server.enqueue(
            stream(
                "block" to """{"index":1,"block":{"type":"rows","alt_text":"Таблица","rows":[{"label":"Еда","amount":120000}]}}""",
                "source" to """{"calculated_at":"2026-10-04T12:00:00+03:00","transfer_mode":"without","changed_since":[]}""",
                "error" to """{"code":"CANNOT_ANSWER","message":"Не могу","remaining_limit":3}""",
            ),
        )

        assistantApi().streamAnswer("a1").test {
            val block = assertIs<AnswerEvent.BlockAdded>(awaitItem()).event
            assertEquals(1, block.index)
            assertEquals(BlockType.ROWS, block.block.type)
            assertEquals(TransferMode.WITHOUT, assertIs<AnswerEvent.Source>(awaitItem()).event.transferMode)
            val error = assertIs<AnswerEvent.Error>(awaitItem()).event
            assertEquals(AnswerErrorCode.CANNOT_ANSWER, error.code)
            assertEquals(3, error.remainingLimit)
            awaitComplete()
        }
        assertEquals("/v1/assistant/messages/a1/events", server.takeRequest().path)
    }

    @Test
    fun `malformed answer event is a server error`() = runBlocking {
        server.enqueue(stream("chunk" to """{"index":"x"}"""))

        assistantApi().streamAnswer("a1").test {
            assertEquals(200, assertIs<AppError.Server>(awaitError()).httpStatus)
        }
    }

    @Test
    fun `401 before the stream starts is unauthorized`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"code":"INVALID_TOKEN","message":"x"}}"""))

        assistantApi().streamAnswer("a1").test {
            assertEquals("INVALID_TOKEN", assertIs<AppError.Unauthorized>(awaitError()).code)
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
