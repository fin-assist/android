package ru.finassist.pf.core.mockbackend.http

import kotlinx.serialization.KSerializer
import okhttp3.Authenticator
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.Pipe
import okio.buffer
import ru.finassist.pf.core.mockbackend.engine.AnalyticsEngine
import ru.finassist.pf.core.mockbackend.engine.ApiFailure
import ru.finassist.pf.core.mockbackend.engine.Ledger
import ru.finassist.pf.core.mockbackend.engine.MockServer
import ru.finassist.pf.core.mockbackend.engine.OperationsQuery
import ru.finassist.pf.core.mockbackend.engine.StatementsService
import ru.finassist.pf.core.network.dto.*
import java.io.IOException
import kotlin.concurrent.thread

/**
 * In-process HTTP adapter over [MockServer]. Installed as the first application interceptor of the mock flavor:
 * every request is answered here and never reaches the network, while the rest of the client stack
 * (auth header, authenticator, SSE reader, error mapping) works exactly as against the real backend.
 */
class MockBackendInterceptor(
    private val server: MockServer,
    /** OkHttp's authenticator never runs for a response produced by an application interceptor — call it here. */
    private val authenticator: Authenticator? = null,
    /** Simulated latency per request. */
    private val latencyMillis: Long = 250,
    /** When true every request fails like a dead network — for offline states. */
    @Volatile var offline: Boolean = false,
) : Interceptor {

    private val jsonType = "application/json".toMediaType()

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (offline) throw IOException("mock: offline")
        if (latencyMillis > 0) Thread.sleep(latencyMillis)
        val response = serve(request)
        // 401 with a bearer → refresh and retry once, exactly like RetryAndFollowUpInterceptor would for a real server.
        if (response.code == 401 && request.header("Authorization") != null && authenticator != null) {
            val retried = authenticator.authenticate(null, response)
            if (retried != null) {
                response.close()
                return serve(retried).newBuilder().priorResponse(response.newBuilder().body(null).build()).build()
            }
        }
        return response
    }

    private fun serve(request: Request): Response = try { route(request) } catch (f: ApiFailure) { error(request, f) }

    // ---- routing -------------------------------------------------------------------------------------------------

    private fun route(request: Request): Response {
        val path = request.url.encodedPath
        val method = request.method
        val segments = path.trimStart('/').split('/')
        if (segments.firstOrNull() != "v1") throw ApiFailure(404, "NOT_FOUND", "unknown path")
        val rest = segments.drop(1)

        return synchronized(server.lock) {
            when {
                rest == listOf("auth", "phone") && method == "POST" -> idempotent(request, scope = "") {
                    val body = decode(request, StartPhoneVerificationRequestDto.serializer())
                    server.startVerification(body.phone).fold({ json(request, 200, PhoneVerificationDto.serializer(), it) }, { throw it })
                }
                rest == listOf("auth", "phone", "events") && method == "GET" -> {
                    val token = request.header("X-Verification-Token") ?: throw ApiFailure(401, "INVALID_TOKEN", "no verification token")
                    server.verificationStatus(token).getOrElse { throw it }
                    sse(request) { emit ->
                        while (true) {
                            val status = synchronized(server.lock) { server.verificationStatus(token) }.getOrElse { throw it }
                            emit("status", server.encode(PhoneVerificationStatusEventDto.serializer(), status))
                            if (status.status != "pending") return@sse
                            Thread.sleep(1000)
                        }
                    }
                }
                rest == listOf("auth", "register") && method == "POST" -> idempotent(request, scope = "") {
                    val body = decode(request, RegisterRequestDto.serializer())
                    server.register(body).fold({ json(request, 201, RegisterResponseDto.serializer(), it) }, { throw it })
                }
                rest == listOf("auth", "token") && method == "POST" -> idempotent(request, scope = "", ttlSeconds = 60) {
                    val body = decode(request, RefreshTokenRequestDto.serializer())
                    server.refresh(body.refreshToken).fold({ json(request, 200, TokenPairDto.serializer(), it) }, { throw it })
                }
                rest == listOf("auth", "logout") && method == "POST" -> {
                    val body = decode(request, RefreshTokenRequestDto.serializer())
                    server.logout(body.refreshToken)
                    empty(request, 204)
                }
                rest.size == 2 && rest[0] == "consents" && method == "GET" -> {
                    if (rest[1] == "assistant") user(request)
                    val doc = server.consentDocument(rest[1]) ?: throw ApiFailure(404, "NOT_FOUND", "unknown consent type")
                    json(request, 200, ConsentDocumentDto.serializer(), doc)
                }
                else -> authed(request, rest, method)
            }
        }
    }

    private fun authed(request: Request, rest: List<String>, method: String): Response {
        val u = user(request)
        val wire = server.wire(u)
        val now = server.clock()
        val statements = StatementsService(u.ledger, u.timezone, wire)
        val ops = OperationsQuery(u.ledger, u.timezone, wire)
        val analytics = AnalyticsEngine(u.ledger, u.timezone, wire)
        val q = request.url
        return when {
            rest == listOf("profile") && method == "GET" -> json(request, 200, ProfileDto.serializer(), server.profile(u))
            rest == listOf("profile") && method == "PATCH" -> {
                val body = decode(request, ProfileUpdateDto.serializer())
                body.theme?.let { u.theme = it }
                body.timezone?.let { tz -> u.timezone = runCatching { java.time.ZoneId.of(tz) }.getOrElse { throw ApiFailure(400, "VALIDATION_ERROR", "bad timezone", listOf(ErrorDetailDto("timezone", "INVALID_FORMAT", "IANA zone"))) } }
                json(request, 200, ProfileDto.serializer(), server.profile(u))
            }
            rest == listOf("profile") && method == "DELETE" -> { server.deleteAccount(u); empty(request, 204) }
            rest == listOf("profile", "assistant-consent") && method == "PUT" -> {
                val body = decode(request, AssistantConsentGrantDto.serializer())
                if (body.version != server.config.assistantConsentVersion) throw ApiFailure(422, "CONSENT_OUTDATED", "outdated")
                u.assistantConsentVersion = body.version
                json(request, 200, ProfileDto.serializer(), server.profile(u))
            }
            rest == listOf("profile", "assistant-consent") && method == "DELETE" -> { u.assistantConsentVersion = null; empty(request, 204) }

            rest == listOf("statements", "config") && method == "GET" -> json(request, 200, ImportConfigDto.serializer(), statements.config(now))
            rest == listOf("statements") && method == "GET" -> json(request, 200, StatementsListDto.serializer(), statements.list(now))
            rest == listOf("statements") && method == "POST" -> {
                // The body hash must cover the file, not the multipart envelope (its boundary differs per attempt).
                val (name, bytes) = multipartFile(request)
                idempotent(request, scope = u.id, hash = bytes.contentHashCode()) {
                    server.startUpload(u, name, bytes).fold({ json(request, 202, UploadAcceptedDto.serializer(), it) }, { throw it })
                }
            }
            rest == listOf("statements", "unread-lines") && method == "GET" -> {
                val uploadId = q.queryParameter("upload_id"); val from = q.queryParameter("from"); val to = q.queryParameter("to")
                if ((uploadId == null) == (from == null && to == null)) throw ApiFailure(400, "VALIDATION_ERROR", "upload_id xor from/to")
                val res = statements.unreadLines(uploadId, from?.let(wire::parse), to?.let(wire::parse)) ?: throw ApiFailure(404, "NOT_FOUND", "no such upload")
                json(request, 200, UnreadLinesListDto.serializer(), res)
            }
            rest.size == 2 && rest[0] == "statements" && method == "DELETE" -> { if (!server.deleteUpload(u, rest[1])) throw ApiFailure(404, "NOT_FOUND", "no such upload"); empty(request, 204) }
            rest.size == 3 && rest[0] == "statements" && rest[2] == "progress" && method == "GET" -> {
                val process = server.uploads[rest[1]] ?: throw ApiFailure(404, "NOT_FOUND", "no such upload")
                sse(request) { emit ->
                    var sent = 0
                    while (true) {
                        val events = process.events
                        // First event is the current state: the latest progress, or the terminal event straight away.
                        if (sent == 0 && events.isNotEmpty()) {
                            val last = events.last(); emit(last.first, last.second); sent = events.size
                        }
                        while (sent < events.size) { val e = events[sent]; emit(e.first, e.second); sent++ }
                        if (process.done && sent >= process.events.size) return@sse
                        Thread.sleep(150)
                    }
                }
            }

            rest == listOf("operations") && method == "GET" -> {
                val params = OperationsQuery.Params(
                    from = q.queryParameter("from")?.let(wire::parse), to = q.queryParameter("to")?.let(wire::parse),
                    allTime = q.queryParameter("all_time") == "true", q = q.queryParameter("q"), categoryId = q.queryParameter("category_id"),
                    kind = q.queryParameter("kind"), amountFrom = q.queryParameter("amount_from")?.toLongOrNull(), amountTo = q.queryParameter("amount_to")?.toLongOrNull(),
                    transferMode = q.queryParameter("transfer_mode"), selection = q.queryParameter("selection"), before = q.queryParameter("before")?.let(wire::parse),
                )
                when (val r = ops.query(params, now)) {
                    is OperationsQuery.Result.Ok -> json(request, 200, OperationsListDto.serializer(), r.body)
                    is OperationsQuery.Result.Error -> throw ApiFailure(r.status, r.code, r.message)
                }
            }
            rest.size == 2 && rest[0] == "operations" && method == "GET" -> json(request, 200, OperationDto.serializer(), ops.details(rest[1]) ?: throw ApiFailure(404, "NOT_FOUND", "no such operation"))
            rest.size == 2 && rest[0] == "operations" && method == "PATCH" -> {
                val body = decode(request, ChangeCategoryRequestDto.serializer())
                when (val r = u.ledger.changeCategory(rest[1], body.categoryId, now)) {
                    Ledger.ChangeResult.NotFound -> throw ApiFailure(404, "NOT_FOUND", "no such operation")
                    Ledger.ChangeResult.NotAssignable -> throw ApiFailure(422, "CATEGORY_NOT_ASSIGNABLE", "category does not fit")
                    is Ledger.ChangeResult.Changed -> {
                        val items = if (r.pair != null) listOf(wire.pair(u.ledger, r.pair, details = true)) else r.items.map { wire.operation(u.ledger, it, details = true) }
                        json(request, 200, ChangeCategoryResponseDto.serializer(), ChangeCategoryResponseDto(items, r.replacedId))
                    }
                }
            }
            rest == listOf("categories") && method == "GET" -> json(request, 200, CategoriesListDto.serializer(), statements.categories())

            rest == listOf("analytics") && method == "GET" -> {
                val period = q.queryParameter("period") ?: "month"
                val mode = q.queryParameter("transfer_mode") ?: "with"
                if (mode != "with" && mode != "without") throw ApiFailure(400, "VALIDATION_ERROR", "transfer_mode", listOf(ErrorDetailDto("transfer_mode", "INVALID_FORMAT", "with|without")))
                if (period !in setOf("month", "quarter", "year")) throw ApiFailure(400, "VALIDATION_ERROR", "period", listOf(ErrorDetailDto("period", "INVALID_FORMAT", "month|quarter|year")))
                json(request, 200, AnalyticsDto.serializer(), analytics.analytics(period, q.queryParameter("date"), mode, now))
            }
            rest == listOf("analytics", "periods") && method == "GET" -> json(request, 200, AnalyticsPeriodsListDto.serializer(), AnalyticsPeriodsListDto(analytics.periodsWithData(q.queryParameter("period") ?: "month", now)))
            rest.size == 4 && rest[0] == "analytics" && rest[1] == "regular-payments" && rest[3] == "dismissal" -> {
                if (method == "PUT") u.ledger.dismissedRegularPayments += rest[2] else u.ledger.dismissedRegularPayments -= rest[2]
                empty(request, 204)
            }

            rest == listOf("assistant", "limit") && method == "GET" -> json(request, 200, AssistantLimitDto.serializer(), server.limit(u))
            rest == listOf("assistant", "messages") && method == "GET" -> json(request, 200, MessagesListDto.serializer(), server.messages(u, (q.queryParameter("limit")?.toIntOrNull() ?: 20).coerceIn(1, 50), q.queryParameter("before_id")))
            rest == listOf("assistant", "messages") && method == "POST" -> idempotent(request, scope = u.id) {
                val body = decode(request, AskRequestDto.serializer())
                server.ask(u, body).fold({ json(request, 201, AskResponseDto.serializer(), it) }, { throw it })
            }
            rest.size == 4 && rest[0] == "assistant" && rest[1] == "messages" && rest[3] == "retry" && method == "POST" -> idempotent(request, scope = u.id) {
                server.retry(u, rest[2]).fold({ json(request, 201, RetryResponseDto.serializer(), it) }, { throw it })
            }
            rest.size == 4 && rest[0] == "assistant" && rest[1] == "messages" && rest[3] == "events" && method == "GET" -> {
                val answerId = rest[2]
                server.snapshot(u, answerId).getOrElse { throw it }
                sse(request) { emit ->
                    val process = u.answers.getValue(answerId)
                    val snapshot = synchronized(server.lock) { server.snapshot(u, answerId) }.getOrElse { throw it }
                    emit("snapshot", server.encode(AnswerSnapshotEventDto.serializer(), snapshot))
                    // Everything emitted before the snapshot is already inside it; stream only what comes after.
                    var sent = process.events.size
                    if (process.status != "generating") {
                        val last = process.events.lastOrNull(); if (last != null) emit(last.first, last.second); return@sse
                    }
                    while (true) {
                        while (sent < process.events.size) { val e = process.events[sent]; emit(e.first, e.second); sent++ }
                        if (process.status != "generating" && sent >= process.events.size) return@sse
                        Thread.sleep(100)
                    }
                }
            }
            else -> throw ApiFailure(404, "NOT_FOUND", "unknown path")
        }
    }

    // ---- helpers -------------------------------------------------------------------------------------------------

    private fun user(request: Request): MockServer.User = server.authenticate(request.header("Authorization")).getOrElse { throw it }

    private fun <T> decode(request: Request, serializer: KSerializer<T>): T {
        val text = bodyText(request)
        return runCatching { server.json.decodeFromString(serializer, text) }
            .getOrElse { throw ApiFailure(400, "VALIDATION_ERROR", "malformed body", listOf(ErrorDetailDto(null, "INVALID_FORMAT", it.message ?: "json"))) }
    }

    private fun bodyText(request: Request): String {
        val body = request.body ?: return ""
        val buffer = Buffer(); body.writeTo(buffer); return buffer.readUtf8()
    }

    private fun bodyBytes(request: Request): ByteArray {
        val body = request.body ?: return ByteArray(0)
        val buffer = Buffer(); body.writeTo(buffer); return buffer.readByteArray()
    }

    /** Minimal multipart reader: returns the file part's name and bytes. */
    private fun multipartFile(request: Request): Pair<String, ByteArray> {
        val contentType = request.body?.contentType()?.toString() ?: throw ApiFailure(400, "VALIDATION_ERROR", "no multipart body")
        val boundary = Regex("boundary=\"?([^\";]+)\"?").find(contentType)?.groupValues?.get(1) ?: throw ApiFailure(400, "VALIDATION_ERROR", "no boundary")
        val bytes = bodyBytes(request)
        val delimiter = "--$boundary".toByteArray()
        var start = indexOf(bytes, delimiter, 0)
        while (start >= 0) {
            val headerStart = start + delimiter.size + 2
            val headerEnd = indexOf(bytes, "\r\n\r\n".toByteArray(), headerStart)
            if (headerEnd < 0) break
            val headers = String(bytes, headerStart, headerEnd - headerStart, Charsets.UTF_8)
            val next = indexOf(bytes, delimiter, headerEnd)
            val dataStart = headerEnd + 4
            val dataEnd = if (next >= 0) next - 2 else bytes.size
            if (headers.contains("name=\"file\"")) {
                val name = Regex("filename=\"([^\"]*)\"").find(headers)?.groupValues?.get(1) ?: "statement.ofx"
                return name to bytes.copyOfRange(dataStart, maxOf(dataStart, dataEnd))
            }
            start = next
        }
        throw ApiFailure(400, "VALIDATION_ERROR", "file part missing", listOf(ErrorDetailDto("file", "REQUIRED", "file is required")))
    }

    private fun indexOf(hay: ByteArray, needle: ByteArray, from: Int): Int {
        outer@ for (i in maxOf(0, from)..hay.size - needle.size) {
            for (j in needle.indices) if (hay[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }

    /** Idempotency per api.md: same key+body → stored response; same key, other body → 409; missing key → 400. */
    private fun idempotent(request: Request, scope: String, ttlSeconds: Long = 24 * 3600, hash: Int? = null, handler: () -> Response): Response {
        val key = request.header("Idempotency-Key") ?: throw ApiFailure(400, "VALIDATION_ERROR", "Idempotency-Key required", listOf(ErrorDetailDto("Idempotency-Key", "REQUIRED", "header is required")))
        val endpoint = request.method + " " + request.url.encodedPath
        val hash = hash ?: bodyBytes(request).contentHashCode()
        server.idempotencyLookup(scope, endpoint, key, hash, ttlSeconds)?.let { stored ->
            return response(request, stored.status, stored.body.toResponseBody(jsonType))
        }
        val response = try { handler() } catch (f: ApiFailure) { error(request, f) }
        val body = response.peekBody(Long.MAX_VALUE).string()
        server.idempotencyStore(scope, endpoint, key, hash, response.code, body)
        return response
    }

    private fun <T> json(request: Request, status: Int, serializer: KSerializer<T>, value: T): Response =
        response(request, status, server.encode(serializer, value).toResponseBody(jsonType))

    private fun empty(request: Request, status: Int): Response = response(request, status, ByteArray(0).toResponseBody(null))

    private fun error(request: Request, f: ApiFailure): Response {
        val builder = response(request, f.status, server.errorBody(f).toResponseBody(jsonType)).newBuilder()
        f.retryAfterSeconds?.let { builder.header("Retry-After", it.toString()) }
        if (f.code == "RATE_LIMITED") builder.header("Retry-After", "600")
        return builder.build()
    }

    private fun response(request: Request, status: Int, body: ResponseBody): Response = Response.Builder()
        .request(request).protocol(Protocol.HTTP_1_1).code(status).message(reason(status))
        .header("X-Request-Id", request.header("X-Request-Id") ?: "")
        .body(body)
        .build()

    private fun reason(status: Int) = when (status) { 200 -> "OK"; 201 -> "Created"; 202 -> "Accepted"; 204 -> "No Content"; 400 -> "Bad Request"; 401 -> "Unauthorized"; 403 -> "Forbidden"; 404 -> "Not Found"; 409 -> "Conflict"; 413 -> "Payload Too Large"; 422 -> "Unprocessable Entity"; 429 -> "Too Many Requests"; else -> "Error" }

    /**
     * Streams `text/event-stream` through an okio Pipe fed from a producer thread. A heartbeat comment goes out
     * every 15 s like the real server. The producer's exceptions close the pipe → the reader sees a failure.
     */
    private fun sse(request: Request, producer: (emit: (String, String) -> Unit) -> Unit): Response {
        val pipe = Pipe(64 * 1024)
        val sink = pipe.sink.buffer()
        thread(name = "mock-sse") {
            try {
                val lockObj = Any()
                var lastWrite = System.currentTimeMillis()
                val emit: (String, String) -> Unit = { name, data ->
                    synchronized(lockObj) { sink.writeUtf8("event: $name\ndata: $data\n\n").flush(); lastWrite = System.currentTimeMillis() }
                }
                val heartbeat = thread(isDaemon = true, name = "mock-sse-hb") {
                    try { while (true) { Thread.sleep(1000); if (System.currentTimeMillis() - lastWrite >= 15_000) synchronized(lockObj) { sink.writeUtf8(": ping\n\n").flush(); lastWrite = System.currentTimeMillis() } } } catch (_: Exception) {}
                }
                try { producer(emit) } finally { heartbeat.interrupt() }
            } catch (_: Exception) {
                // closing the sink below ends the stream; the client treats a cut stream as a transport failure
            } finally {
                runCatching { sink.close() }
            }
        }
        val source: BufferedSource = pipe.source.buffer()
        val body = source.asResponseBody("text/event-stream".toMediaType())
        return response(request, 200, body)
    }

    private fun BufferedSource.asResponseBody(type: okhttp3.MediaType): ResponseBody = object : ResponseBody() {
        override fun contentType() = type
        override fun contentLength() = -1L
        override fun source() = this@asResponseBody
    }
}
