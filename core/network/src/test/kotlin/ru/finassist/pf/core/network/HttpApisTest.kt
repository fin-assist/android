package ru.finassist.pf.core.network

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Before
import org.junit.Test
import ru.finassist.pf.core.api.UploadFile
import ru.finassist.pf.core.api.model.AskRequest
import ru.finassist.pf.core.api.model.AssistantConsentGrant
import ru.finassist.pf.core.api.model.ChangeCategoryRequest
import ru.finassist.pf.core.api.model.ConsentType
import ru.finassist.pf.core.api.model.IdempotencyKey
import ru.finassist.pf.core.api.model.OperationKindFilter
import ru.finassist.pf.core.api.model.OperationsFilter
import ru.finassist.pf.core.api.model.OperationsQuery
import ru.finassist.pf.core.api.model.PeriodTypeCode
import ru.finassist.pf.core.api.model.ProfileUpdate
import ru.finassist.pf.core.api.model.RegisterRequest
import ru.finassist.pf.core.api.model.Theme
import ru.finassist.pf.core.api.model.TransferMode
import ru.finassist.pf.core.common.error.AppError
import ru.finassist.pf.core.common.money.Money
import ru.finassist.pf.core.common.time.PeriodKey
import ru.finassist.pf.core.common.time.toApiDateTime
import java.io.ByteArrayInputStream
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Each `Http*Api` against [MockWebServer]: method, path, query, headers and body of the request; mapping of the reply. */
class HttpApisTest {
    private val server = MockWebServer()
    private lateinit var stack: NetworkStack
    private val api get() = stack.api
    private val key = IdempotencyKey("11111111-2222-3333-4444-555555555555")

    @Before
    fun setUp() {
        server.start()
        stack = NetworkStack(server)
    }

    @After
    fun tearDown() = server.shutdown()

    private fun json(body: String, code: Int = 200) = MockResponse().setResponseCode(code)
        .setHeader("Content-Type", "application/json").setBody(body)

    private fun reply(body: String, code: Int = 200) = server.enqueue(json(body, code))

    private fun noContent() = server.enqueue(MockResponse().setResponseCode(204))

    private fun taken(): RecordedRequest = server.takeRequest(5, TimeUnit.SECONDS)!!

    private fun RecordedRequest.query(name: String): String? = requestUrl!!.queryParameter(name)

    private fun RecordedRequest.queryNames(): Set<String> = requestUrl!!.queryParameterNames

    // ---- auth ----

    @Test
    fun `start phone verification posts the phone with the idempotency key and no session`() = runBlocking {
        stack.tokenStore.save(SESSION)
        reply("""{"verification_token":"vt","callback_number":"+78001234567","expires_at":"2026-10-04T22:27:00+03:00"}""")

        val verification = api.auth.startPhoneVerification(key, "+79991234567")

        assertEquals("vt", verification.verificationToken)
        assertEquals("+78001234567", verification.callbackNumber)
        val request = taken()
        assertEquals("POST", request.method)
        assertEquals("/v1/auth/phone", request.path)
        assertEquals(key.value, request.getHeader(HEADER_IDEMPOTENCY_KEY))
        assertEquals("""{"phone":"+79991234567"}""", request.body.readUtf8())
        assertNull(request.getHeader("Authorization"))
        assertNull(request.getHeader(NoAuth.HEADER))
    }

    @Test
    fun `start phone verification reads expires_in and keeps the server time of the Date header`() = runBlocking {
        server.enqueue(
            json("""{"verification_token":"vt","callback_number":"+78001234567","expires_at":"2026-10-04T22:17:00+03:00","expires_in":300}""")
                .addHeader("Date", "Sun, 04 Oct 2026 19:12:00 GMT"),
        )

        val verification = api.auth.startPhoneVerification(key, "+79991234567")

        assertEquals(300, verification.expiresIn)
        assertEquals(java.time.Instant.parse("2026-10-04T19:12:00Z"), verification.serverTime)
    }

    @Test
    fun `start phone verification without expires_in and Date leaves both empty`() = runBlocking {
        server.enqueue(
            json("""{"verification_token":"vt","callback_number":"+78001234567","expires_at":"2026-10-04T22:17:00+03:00"}""")
                .removeHeader("Date"),
        )

        val verification = api.auth.startPhoneVerification(key, "+79991234567")

        assertNull(verification.expiresIn)
        assertNull(verification.serverTime)
    }

    @Test
    fun `invalid phone comes back as a validation error on the phone field`() = runBlocking {
        reply(
            """{"error":{"code":"VALIDATION_ERROR","message":"bad","details":[{"field":"phone","code":"INVALID_FORMAT","message":"x"}]}}""",
            code = 400,
        )

        val error = assertFailsWith<AppError.Api> { api.auth.startPhoneVerification(key, "123") }

        assertEquals("VALIDATION_ERROR", error.code)
        assertEquals(400, error.httpStatus)
        assertEquals(AppError.FieldError("phone", "INVALID_FORMAT", "x"), error.details.single())
    }

    @Test
    fun `register sends snake_case body without null fields`() = runBlocking {
        reply("""{"access_token":"a","refresh_token":"r","user_id":"u1"}""")

        val response = api.auth.register(key, RegisterRequest("rt", pdConsentAccepted = true, pdConsentVersion = "1.0"))

        assertEquals("u1", response.userId)
        val request = taken()
        assertEquals("POST", request.method)
        assertEquals("/v1/auth/register", request.path)
        assertEquals(key.value, request.getHeader(HEADER_IDEMPOTENCY_KEY))
        assertEquals(
            """{"registration_token":"rt","pd_consent_accepted":true,"pd_consent_version":"1.0"}""",
            request.body.readUtf8(),
        )
        assertNull(request.getHeader("Authorization"))
    }

    @Test
    fun `refresh tokens posts the refresh token with the key and returns the new pair`() = runBlocking {
        stack.tokenStore.save(SESSION)
        reply("""{"access_token":"a2","refresh_token":"r2"}""")

        val pair = api.auth.refreshTokens(key, "r1")

        assertEquals("a2", pair.accessToken)
        assertEquals("r2", pair.refreshToken)
        val request = taken()
        assertEquals("/v1/auth/token", request.path)
        assertEquals(key.value, request.getHeader(HEADER_IDEMPOTENCY_KEY))
        assertEquals("""{"refresh_token":"r1"}""", request.body.readUtf8())
        assertNull(request.getHeader("Authorization"))
    }

    @Test
    fun `logout carries the ended session's tokens, not the stored ones`() = runBlocking {
        stack.tokenStore.save(SESSION) // a newer session signed in meanwhile
        noContent()

        api.auth.logout(accessToken = "old-access", refreshToken = "old-refresh")

        val request = taken()
        assertEquals("POST", request.method)
        assertEquals("/v1/auth/logout", request.path)
        assertEquals("Bearer old-access", request.getHeader("Authorization"))
        assertEquals("""{"refresh_token":"old-refresh"}""", request.body.readUtf8())
        assertNull(request.getHeader(NoAuth.HEADER))
    }

    @Test
    fun `logout 401 is not refreshed and surfaces as unauthorized`() = runBlocking {
        stack.tokenStore.save(SESSION)
        reply("""{"error":{"code":"TOKEN_REVOKED","message":"x"}}""", code = 401)

        val error = assertFailsWith<AppError.Unauthorized> { api.auth.logout("old-access", "old-refresh") }

        assertEquals("TOKEN_REVOKED", error.code)
        assertEquals(1, server.requestCount)
        assertEquals(SESSION, stack.tokenStore.current())
    }

    @Test
    fun `consent document is requested by type code`() = runBlocking {
        reply("""{"type":"personal_data","version":"1.2","title":"Согласие","url":"https://x/pd"}""")

        val doc = api.auth.getConsentDocument(ConsentType.PERSONAL_DATA)

        assertEquals(ConsentType.PERSONAL_DATA, doc.type)
        assertEquals("1.2", doc.version)
        val request = taken()
        assertEquals("GET", request.method)
        assertEquals("/v1/consents/personal_data", request.path)
    }

    // ---- profile ----

    @Test
    fun `profile calls carry the bearer token and hit the documented endpoints`() = runBlocking {
        stack.tokenStore.save(SESSION)
        reply(PROFILE_JSON)
        reply(PROFILE_JSON)
        reply(PROFILE_JSON)
        noContent()
        noContent()

        assertEquals(Theme.DARK, api.profile.getProfile().theme)
        api.profile.updateProfile(ProfileUpdate(theme = Theme.LIGHT))
        api.profile.grantAssistantConsent(AssistantConsentGrant("2.0"))
        api.profile.revokeAssistantConsent()
        api.profile.deleteAccount()

        val requests = List(5) { taken() }
        assertEquals(
            listOf(
                "GET /v1/profile",
                "PATCH /v1/profile",
                "PUT /v1/profile/assistant-consent",
                "DELETE /v1/profile/assistant-consent",
                "DELETE /v1/profile",
            ),
            requests.map { "${it.method} ${it.path}" },
        )
        assertTrue(requests.all { it.getHeader("Authorization") == "Bearer access-1" })
        assertEquals("""{"theme":"light"}""", requests[1].body.readUtf8())
        assertEquals("""{"version":"2.0"}""", requests[2].body.readUtf8())
    }

    @Test
    fun `profile request without a session goes without Authorization`() = runBlocking {
        reply("""{"error":{"code":"INVALID_TOKEN","message":"x"}}""", code = 401)

        assertFailsWith<AppError.Unauthorized> { api.profile.getProfile() }

        assertNull(taken().getHeader("Authorization"))
    }

    // ---- statements ----

    @Test
    fun `import config and statements list`() = runBlocking {
        reply(
            """{"download_url":"https://d","login_url":"https://l","max_file_size_bytes":10485760,"accepted_formats":["ofx"],
               "suggested_period":{"from":"2026-04-01T00:00:00+03:00","to":"2026-10-01T00:00:00+03:00"}}""",
        )
        reply("""{"uploads":[]}""")
        noContent()

        assertEquals(10_485_760L, api.statements.getImportConfig().maxFileSizeBytes)
        assertEquals(emptyList(), api.statements.listStatements().uploads)
        api.statements.deleteStatement("up-1")

        assertEquals("GET /v1/statements/config", taken().let { "${it.method} ${it.path}" })
        assertEquals("GET /v1/statements", taken().let { "${it.method} ${it.path}" })
        assertEquals("DELETE /v1/statements/up-1", taken().let { "${it.method} ${it.path}" })
    }

    @Test
    fun `upload sends the file as an ofx multipart part with the idempotency key`() = runBlocking {
        val content = "OFXHEADER:100\n<OFX>тест</OFX>".toByteArray()
        var closed = 0
        val file = UploadFile("statement.ofx", content.size.toLong()) {
            object : ByteArrayInputStream(content) {
                override fun close() {
                    closed++
                    super.close()
                }
            }
        }
        reply("""{"upload_id":"up-1","file_name":"statement.ofx"}""")

        val accepted = api.statements.uploadStatement(key, file)

        assertEquals("up-1", accepted.uploadId)
        val request = taken()
        assertEquals("POST", request.method)
        assertEquals("/v1/statements", request.path)
        assertEquals(key.value, request.getHeader(HEADER_IDEMPOTENCY_KEY))
        assertTrue(request.getHeader("Content-Type")!!.startsWith("multipart/form-data; boundary="))
        val body = request.body.readUtf8()
        assertTrue("""Content-Disposition: form-data; name="file"; filename="statement.ofx"""" in body, body)
        assertTrue("Content-Type: application/x-ofx" in body, body)
        assertTrue("Content-Length: ${content.size}" in body, body)
        assertTrue(String(content) in body, body)
        assertEquals(1, closed, "the file stream is closed after writing")
    }

    @Test
    fun `upload rejected by the server keeps the error code`() = runBlocking {
        reply("""{"error":{"code":"WRONG_BANK","message":"Не Т-Банк"}}""", code = 422)

        val error = assertFailsWith<AppError.Api> {
            api.statements.uploadStatement(key, UploadFile("x.ofx", 1) { ByteArrayInputStream(byteArrayOf(1)) })
        }

        assertEquals("WRONG_BANK", error.code)
        assertEquals("Не Т-Банк", error.message)
    }

    @Test
    fun `unread lines by upload or by period`() = runBlocking {
        reply("""{"lines":[]}""")
        reply("""{"lines":[]}""")

        api.statements.listUnreadLines("up-1")
        api.statements.listUnreadLines(
            "2026-04-01T00:00:00+03:00".toApiDateTime(),
            "2026-05-01T00:00:00+03:00".toApiDateTime(),
        )

        val byUpload = taken()
        assertEquals("/v1/statements/unread-lines", byUpload.requestUrl!!.encodedPath)
        assertEquals(setOf("upload_id"), byUpload.queryNames())
        assertEquals("up-1", byUpload.query("upload_id"))
        val byPeriod = taken()
        assertEquals(setOf("from", "to"), byPeriod.queryNames())
        assertEquals("2026-04-01T00:00:00+03:00", byPeriod.query("from"))
        assertEquals("2026-05-01T00:00:00+03:00", byPeriod.query("to"))
        // `+` of the offset must be percent-encoded, or the server reads it as a space.
        assertTrue("%2B03" in byPeriod.path!!, byPeriod.path)
    }

    // ---- operations ----

    @Test
    fun `feed query sends nothing but before`() = runBlocking {
        reply("""{"items":[]}""")

        api.operations.listOperations(OperationsQuery(before = "2026-09-01T10:00:00+03:00".toApiDateTime()))

        val request = taken()
        assertEquals("/v1/operations", request.requestUrl!!.encodedPath)
        assertEquals(setOf("before"), request.queryNames())
        assertEquals("2026-09-01T10:00:00+03:00", request.query("before"))
    }

    @Test
    fun `search query maps every filter to its parameter`() = runBlocking {
        reply("""{"items":[],"total_count":0}""")

        api.operations.listOperations(
            OperationsQuery(
                filter = OperationsFilter(
                    from = "2026-04-01T00:00:00+03:00".toApiDateTime(),
                    to = "2026-05-01T00:00:00+03:00".toApiDateTime(),
                    q = "кофе",
                    categoryId = "cat_food",
                    kind = OperationKindFilter.EXPENSE,
                    amountFrom = Money(10_000),
                    amountTo = Money(500_000),
                    transferMode = TransferMode.WITHOUT,
                    selection = "s_uncategorized",
                    selectionName = "Без категории",
                ),
                allTime = true,
            ),
        )

        val request = taken()
        assertEquals(
            mapOf(
                "from" to "2026-04-01T00:00:00+03:00",
                "to" to "2026-05-01T00:00:00+03:00",
                "all_time" to "true",
                "q" to "кофе",
                "category_id" to "cat_food",
                "kind" to "expense",
                "amount_from" to "10000",
                "amount_to" to "500000",
                "transfer_mode" to "without",
                "selection" to "s_uncategorized",
            ),
            request.queryNames().associateWith { request.query(it) },
        )
    }

    @Test
    fun `search query drops a blank text, false all_time and maps income and unknown transfer mode`() = runBlocking {
        reply("""{"items":[]}""")

        api.operations.listOperations(
            OperationsQuery(
                filter = OperationsFilter(q = "   ", kind = OperationKindFilter.INCOME, transferMode = TransferMode.UNKNOWN),
                allTime = false,
            ),
        )

        val request = taken()
        assertEquals(setOf("kind", "transfer_mode"), request.queryNames())
        assertEquals("income", request.query("kind"))
        // api.md: an unknown transfer_mode (as it came in a response) is sent back as `with`.
        assertEquals("with", request.query("transfer_mode"))
    }

    @Test
    fun `operation details, category change and categories`() = runBlocking {
        reply(OPERATION_JSON)
        reply("""{"items":[$OPERATION_JSON],"replaced_id":"op-0"}""")
        reply("""{"categories":[]}""")

        assertEquals("op-1", api.operations.getOperation("op-1", "2026-09-25T12:00:00+03:00").id)
        val changed = api.operations.changeCategory("op-1", ChangeCategoryRequest("cat_food", "2026-09-25T12:00:00+03:00"))
        assertEquals("op-0", changed.replacedId)
        assertEquals(emptyList(), api.categories.listCategories().categories)

        val get = taken()
        assertEquals("2026-09-25T12:00:00+03:00", get.query("occurred_at"))
        assertEquals("GET /v1/operations/op-1?occurred_at=2026-09-25T12%3A00%3A00%2B03%3A00", "${get.method} ${get.path}")
        val patch = taken()
        assertEquals("PATCH /v1/operations/op-1", "${patch.method} ${patch.path}")
        assertEquals("""{"category_id":"cat_food","occurred_at":"2026-09-25T12:00:00+03:00"}""", patch.body.readUtf8())
        assertEquals("GET /v1/categories", taken().let { "${it.method} ${it.path}" })
    }

    @Test
    fun `too many results is an api error the search screen can branch on`() = runBlocking {
        reply("""{"error":{"code":"TOO_MANY_RESULTS","message":"x"}}""", code = 422)

        val error = assertFailsWith<AppError.Api> { api.operations.listOperations(OperationsQuery()) }

        assertEquals("TOO_MANY_RESULTS", error.code)
    }

    // ---- analytics ----

    @Test
    fun `analytics sends only the parameters that are set`() = runBlocking {
        reply("""{"has_data":false}""")
        reply("""{"has_data":true}""")

        assertEquals(false, api.analytics.getAnalytics().hasData)
        api.analytics.getAnalytics(PeriodTypeCode.QUARTER, PeriodKey("2026-Q3"), TransferMode.WITHOUT)

        val bare = taken()
        assertEquals("/v1/analytics", bare.path)
        val full = taken()
        assertEquals(
            mapOf("period" to "quarter", "date" to "2026-Q3", "transfer_mode" to "without"),
            full.queryNames().associateWith { full.query(it) },
        )
    }

    @Test
    fun `analytics periods and regular payment dismissal`() = runBlocking {
        reply("""{"periods":[]}""")
        reply("""{"periods":[]}""")
        noContent()
        noContent()

        api.analytics.listPeriods()
        api.analytics.listPeriods(PeriodTypeCode.YEAR)
        api.analytics.dismissRegularPayment("rp-1")
        api.analytics.restoreRegularPayment("rp-1")

        assertEquals("/v1/analytics/periods", taken().path)
        assertEquals("/v1/analytics/periods?period=year", taken().path)
        assertEquals("PUT /v1/analytics/regular-payments/rp-1/dismissal", taken().let { "${it.method} ${it.path}" })
        assertEquals("DELETE /v1/analytics/regular-payments/rp-1/dismissal", taken().let { "${it.method} ${it.path}" })
    }

    // ---- assistant ----

    @Test
    fun `assistant history paging parameters`() = runBlocking {
        reply("""{"messages":[],"has_older":false}""")
        reply("""{"messages":[],"has_older":true}""")

        api.assistant.listMessages()
        assertTrue(api.assistant.listMessages(limit = 20, beforeId = "m-9").hasOlder)

        assertEquals("/v1/assistant/messages", taken().path)
        val page = taken()
        assertEquals("20", page.query("limit"))
        assertEquals("m-9", page.query("before_id"))
    }

    @Test
    fun `ask and retry are idempotent posts`() = runBlocking {
        reply("""{"question":{"id":"m1","role":"user","text":"Сколько?","created_at":"2026-10-04T12:00:00+03:00"},"answer_id":"a1","remaining_limit":4}""")
        reply("""{"answer_id":"a2","remaining_limit":3}""")
        reply("""{"remaining":3,"daily_max":5,"resets_at":"2026-10-05T00:00:00+03:00"}""")

        assertEquals("a1", api.assistant.ask(key, AskRequest("Сколько?", TransferMode.WITH)).answerId)
        assertEquals("a2", api.assistant.retry(IdempotencyKey("k2"), "a1").answerId)
        assertEquals(5, api.assistant.getLimit().dailyMax)

        val ask = taken()
        assertEquals("POST /v1/assistant/messages", "${ask.method} ${ask.path}")
        assertEquals(key.value, ask.getHeader(HEADER_IDEMPOTENCY_KEY))
        assertEquals("""{"text":"Сколько?","transfer_mode":"with"}""", ask.body.readUtf8())
        val retry = taken()
        assertEquals("POST /v1/assistant/messages/a1/retry", "${retry.method} ${retry.path}")
        assertEquals("k2", retry.getHeader(HEADER_IDEMPOTENCY_KEY))
        assertEquals("GET /v1/assistant/limit", taken().let { "${it.method} ${it.path}" })
    }

    @Test
    fun `limit exceeded keeps resets_at`() = runBlocking {
        reply("""{"error":{"code":"LIMIT_EXCEEDED","message":"x","resets_at":"2026-10-05T00:00:00+03:00"}}""", code = 403)

        val error = assertFailsWith<AppError.Api> { api.assistant.ask(key, AskRequest("?")) }

        assertEquals("LIMIT_EXCEEDED", error.code)
        assertEquals("2026-10-05T00:00:00+03:00".toApiDateTime(), error.resetsAt)
    }

    // ---- call(): transport failures ----

    @Test
    fun `dropped connection is offline`() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))

        assertFailsWith<AppError.Offline> { api.profile.getProfile() }
        Unit
    }

    @Test
    fun `malformed success body is a server error, not a crash`() = runBlocking {
        reply("""{"user_id":"u1"}""")

        val error = assertFailsWith<AppError.Server> { api.profile.getProfile() }

        assertEquals(200, error.httpStatus)
    }

    @Test
    fun `html 502 from the ingress is a server error`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(502).setBody("<html>Bad Gateway</html>"))

        assertEquals(502, assertFailsWith<AppError.Server> { api.categories.listCategories() }.httpStatus)
    }

    @Test
    fun `request in progress reads Retry-After of the response`() = runBlocking {
        server.enqueue(json("""{"error":{"code":"REQUEST_IN_PROGRESS","message":"x"}}""", code = 409).setHeader("Retry-After", "2"))

        val error = assertFailsWith<AppError.InProgress> { api.assistant.ask(key, AskRequest("?")) }

        assertEquals(2, error.retryAfterSeconds)
    }

    @Test
    fun `cancellation is propagated, not reported as offline`() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))

        assertFailsWith<TimeoutCancellationException> {
            withTimeout(300) { api.profile.getProfile() }
        }
        Unit
    }

    @Test
    fun `operations and categories are served by one singleton`() {
        assertIs<HttpOperationsApi>(api.operations)
        assertTrue(api.operations === api.categories)
    }

    private companion object {
        val SESSION = ru.finassist.pf.core.api.TokenStore.Session(
            "u1",
            ru.finassist.pf.core.api.model.TokenPair("access-1", "refresh-1"),
        )

        val PROFILE_JSON = """
            {"user_id":"u1","phone":"+79991234567","theme":"dark","timezone":"Europe/Moscow",
             "assistant_consent":{"granted":true,"version":"2.0","current_version":"2.0"},
             "created_at":"2026-04-01T09:00:00+03:00"}
        """.trimIndent()

        val OPERATION_JSON = """
            {"id":"op-1","kind":"expense","occurred_at":"2026-09-15T14:30:00+03:00","amount":45000,"currency":"RUB",
             "title":"Кофейня","category_id":"cat_food","category_name":"Еда","category_icon":"food",
             "is_category_manual":true,"status":"posted","source_name":"Т-Банк"}
        """.trimIndent().replace("\n", "")
    }
    @Test fun `operation timestamp preserves fractional seconds and positive offset on both requests`() = runBlocking {
        val timestamp = "2026-10-01T00:00:00.123456789+03:00"
        reply(OPERATION_JSON)
        reply("""{"items":[$OPERATION_JSON]}""")
        api.operations.getOperation("op-1", timestamp)
        api.operations.changeCategory("op-1", ChangeCategoryRequest("cat_food", timestamp))
        val get = taken()
        assertEquals(timestamp, get.query("occurred_at"))
        assertTrue(get.path!!.contains(".123456789%2B03%3A00"))
        assertEquals("""{"category_id":"cat_food","occurred_at":"$timestamp"}""", taken().body.readUtf8())
    }

}
