package ru.finassist.pf.mock.contract

import ru.finassist.pf.core.api.model.StartPhoneVerificationRequest

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import ru.finassist.pf.core.api.model.AskRequest
import ru.finassist.pf.core.api.model.AskResponse
import ru.finassist.pf.core.api.model.ChangeCategoryRequest
import ru.finassist.pf.core.api.model.ErrorCodes
import ru.finassist.pf.core.api.model.IdempotencyKey
import ru.finassist.pf.core.api.model.OperationsFilter
import ru.finassist.pf.core.api.model.OperationsQuery
import ru.finassist.pf.core.api.model.PhoneVerification
import ru.finassist.pf.core.api.model.Profile
import ru.finassist.pf.core.api.model.RegisterRequest
import ru.finassist.pf.core.api.model.RegisterResponse
import ru.finassist.pf.core.api.model.RetryResponse
import ru.finassist.pf.core.api.model.TokenPair
import ru.finassist.pf.core.api.model.UploadAccepted
import ru.finassist.pf.mock.FAST
import ru.finassist.pf.mock.MockBackend
import ru.finassist.pf.mock.NOW
import java.time.OffsetDateTime

/**
 * Behaviour of the contract around errors (FIN-123, step 2): the order the server checks them in (api.md, «Ошибки»),
 * idempotency («Идемпотентность»: a repeat gets the stored response, another body 409) and 404 for an object the user
 * does not have, maintenance and the client version before everything else and never stored under a key (FIN-22).
 * Gaps the fake backend has are [Exclusions.behaviours], run through [ContractCheck.knownGap].
 */
class ContractBehaviourTest {

    private suspend fun Scene.answer(text: String, key: IdempotencyKey = IdempotencyKey.random()): String {
        val id = assistant.ask(key, AskRequest(text)).answerId
        drain(assistant.streamAnswer(id))
        return id
    }

    private suspend fun Scene.registrationToken(phone: String = NEW_PHONE): String {
        val v = auth.startPhoneVerification(IdempotencyKey.random(), StartPhoneVerificationRequest(phone, true, "2026-09-28", true))
        return drain(auth.streamPhoneVerification(v.verificationToken)).last().registrationToken!!
    }

    private suspend fun Scene.refreshToken(): String {
        val v = auth.startPhoneVerification(IdempotencyKey.random(), StartPhoneVerificationRequest(FAST.existingPhone, true, "2026-09-28", true))
        return drain(auth.streamPhoneVerification(v.verificationToken)).last().refreshToken!!
    }

    private fun expect(code: String, case: String, exchange: Wire.Exchange) {
        assertEquals("$case: $exchange", code, exchange.code)
        ContractCheck.assertContract(case, exchange)
    }

    // Order of errors: 503 MAINTENANCE → 400 CLIENT_VERSION_UNSUPPORTED → 401 → 400 → idempotency → 404 → 403 → 422.

    @Test
    fun `maintenance comes before the client version and the session`() = runBlocking {
        val s = Scene(FAST.copy(maintenance = true, minVersion = "0.3.0"), signedIn = false)
        expect(ErrorCodes.MAINTENANCE, "503 before 400 version", s.call("getProfile", null) { s.profile.getProfile() })
    }

    @Test
    fun `an unsupported client version comes before the session and validation`() = runBlocking {
        val s = Scene(FAST.copy(minVersion = "0.3.0"), signedIn = false)
        expect(ErrorCodes.CLIENT_VERSION_UNSUPPORTED, "400 version before 401", s.call("getProfile", null) { s.profile.getProfile() })
        expect(
            ErrorCodes.CLIENT_VERSION_UNSUPPORTED, "400 version before 400 validation",
            s.call("startPhoneVerification", null) { s.auth.startPhoneVerification(IdempotencyKey.random(), StartPhoneVerificationRequest("8916", true, "2026-09-28", true)) },
        )
    }

    @Test
    fun `a version equal to the minimum passes`() = runBlocking {
        val s = Scene(FAST.copy(minVersion = "0.2.8"))
        ContractCheck.assertContract("version equal to the minimum", s.call("getProfile", Profile.serializer()) { s.profile.getProfile() })
    }

    @Test
    fun `maintenance carries its end in the body and Retry-After in the header`() = runBlocking {
        val until = OffsetDateTime.parse(NOW).plusMinutes(30)
        val s = Scene(FAST.copy(maintenance = true, maintenanceUntil = until, maintenanceRetryAfterSeconds = 60))
        val exchange = s.call("getProfile", null) { s.profile.getProfile() }
        expect(ErrorCodes.MAINTENANCE, "503 with until", exchange)
        assertEquals("60", exchange.headers["Retry-After"])
        val error = Json.parseToJsonElement(exchange.body!!).jsonObject["error"]!!.jsonObject
        assertEquals(until, OffsetDateTime.parse(error["until"]!!.jsonPrimitive.content))
    }

    @Test
    fun `maintenance and an unsupported version are not stored under the key`() = runBlocking {
        val s = Scene(FAST.copy(maintenance = true), signedIn = false)
        val key = IdempotencyKey.random()
        expect(ErrorCodes.MAINTENANCE, "503 under a key", s.call("startPhoneVerification", null) { s.auth.startPhoneVerification(key, StartPhoneVerificationRequest(NEW_PHONE, true, "2026-09-28", true)) })
        s.backend.config = s.backend.config.copy(maintenance = false, minVersion = "0.3.0")
        expect(
            ErrorCodes.CLIENT_VERSION_UNSUPPORTED, "400 version under the same key",
            s.call("startPhoneVerification", null) { s.auth.startPhoneVerification(key, StartPhoneVerificationRequest(NEW_PHONE, true, "2026-09-28", true)) },
        )
        s.backend.config = s.backend.config.copy(minVersion = null)
        // api.md «Идемпотентность»: answers before the key check are not stored, the repeat runs as the first request.
        val after = s.call("startPhoneVerification", PhoneVerification.serializer()) { s.auth.startPhoneVerification(key, StartPhoneVerificationRequest(NEW_PHONE, true, "2026-09-28", true)) }
        assertEquals("$after", 200, after.status)
        ContractCheck.assertContract("after maintenance and update, same key", after)
    }

    @Test
    fun `a stream is not opened during maintenance or on an unsupported version`() = runBlocking {
        val s = Scene(signedIn = false)
        val token = s.auth.startPhoneVerification(IdempotencyKey.random(), StartPhoneVerificationRequest(NEW_PHONE, true, "2026-09-28", true)).verificationToken
        val signedIn = Scene()
        val upload = signedIn.backend.uploads.first().id
        val answer = signedIn.answer("Сколько я потратил?")
        val until = OffsetDateTime.parse(NOW).plusMinutes(30)
        listOf(s, signedIn).forEach { it.backend.config = it.backend.config.copy(maintenance = true, maintenanceUntil = until, minVersion = "0.3.0") }
        // 503 first, for each of the three streams (api.md «SSE»: an error before the stream is a plain HTTP answer).
        expect(ErrorCodes.MAINTENANCE, "stream 503: verification", s.phoneEvents(token))
        expect(ErrorCodes.MAINTENANCE, "stream 503: progress", signedIn.progressEvents(upload))
        val answerMaintenance = signedIn.answerEvents(answer)
        expect(ErrorCodes.MAINTENANCE, "stream 503: answer", answerMaintenance)
        assertEquals("15", answerMaintenance.headers["Retry-After"])
        listOf(s, signedIn).forEach { it.backend.config = it.backend.config.copy(maintenance = false) }
        expect(ErrorCodes.CLIENT_VERSION_UNSUPPORTED, "stream 400 version: verification", s.phoneEvents(token))
        expect(ErrorCodes.CLIENT_VERSION_UNSUPPORTED, "stream 400 version: progress", signedIn.progressEvents(upload))
        expect(ErrorCodes.CLIENT_VERSION_UNSUPPORTED, "stream 400 version: answer", signedIn.answerEvents(answer))
    }

    @Test
    fun `on a stream the client version comes before 401 and 404`() = runBlocking {
        val s = Scene(FAST.copy(minVersion = "0.3.0"))
        expect(ErrorCodes.CLIENT_VERSION_UNSUPPORTED, "stream: version before 404 progress", s.progressEvents(MISSING))
        expect(ErrorCodes.CLIENT_VERSION_UNSUPPORTED, "stream: version before 404 answer", s.answerEvents(MISSING))
        expect(ErrorCodes.CLIENT_VERSION_UNSUPPORTED, "stream: version before 401 verification", Scene(FAST.copy(minVersion = "0.3.0"), signedIn = false).phoneEvents("vt_unknown"))
    }

    @Test
    fun `a re-open during maintenance gets 503 before its 429`() = runBlocking {
        // The call does not come for a minute: the verification stream starts with `pending`, which is not terminal.
        val s = Scene(FAST.copy(streamDrops = 1, streamRateLimits = 1, callVerifyDelayMs = 60_000), signedIn = false)
        val token = s.auth.startPhoneVerification(IdempotencyKey.random(), StartPhoneVerificationRequest(NEW_PHONE, true, "2026-09-28", true)).verificationToken
        // The first opening drops after its first event; the next one would be turned away with 429.
        assertEquals(1, s.phoneEvents(token).events.size)
        s.backend.config = s.backend.config.copy(maintenance = true)
        expect(ErrorCodes.MAINTENANCE, "re-open: 503 before 429", s.phoneEvents(token))
        assertEquals("the 429 is not spent on the turned-away opening", 0, s.backend.streamRateLimited.value)
        s.backend.config = s.backend.config.copy(maintenance = false)
        val limited = s.phoneEvents(token)
        expect(ErrorCodes.RATE_LIMITED, "re-open after maintenance: the 429", limited)
        assertEquals(s.backend.config.streamRetrySeconds.toString(), limited.headers["Retry-After"])
    }

    @Test
    fun `no session is 401 before a validation error`() = runBlocking {
        val s = Scene(signedIn = false)
        expect(ErrorCodes.INVALID_TOKEN, "401 before 400: question", s.call("askAssistant", null) { s.assistant.ask(IdempotencyKey.random(), AskRequest(" ")) })
        expect(
            ErrorCodes.INVALID_TOKEN, "401 before 400: feed",
            s.call("listOperations", null) { s.operations.listOperations(OperationsQuery(OperationsFilter(q = "а"), before = s.clock.at)) },
        )
        expect(ErrorCodes.INVALID_TOKEN, "401 before 400: payment id", s.call("dismissRegularPayment", null, "x") { s.analytics.dismissRegularPayment("x") })
    }

    @Test
    fun `no session is 401 before 404`() = runBlocking {
        val s = Scene(signedIn = false)
        expect(ErrorCodes.INVALID_TOKEN, "401 before 404: operation", s.call("getOperation", null, MISSING) { s.operations.getOperation(MISSING, "2026-09-25T12:00:00+03:00") })
        expect(ErrorCodes.INVALID_TOKEN, "401 before 404: unread lines", s.call("listUnreadLines", null) { s.statements.listUnreadLines(MISSING) })
        expect(ErrorCodes.INVALID_TOKEN, "401 before 404: retry", s.call("retryAnswer", null, MISSING) { s.assistant.retry(IdempotencyKey.random(), MISSING) })
    }

    @Test
    fun `a validation error comes before the consent check`() = runBlocking {
        val s = Scene(consent = false)
        expect(ErrorCodes.VALIDATION_ERROR, "400 before 403", s.call("askAssistant", null) { s.assistant.ask(IdempotencyKey.random(), AskRequest(" ")) })
    }

    @Test
    fun `a payment id that is not a UUID is 400, not 404`() = runBlocking {
        val s = Scene()
        expect(ErrorCodes.VALIDATION_ERROR, "400 before 404", s.call("restoreRegularPayment", null, "rp_x") { s.analytics.restoreRegularPayment("rp_x") })
    }

    @Test
    fun `an unknown operation is 404 before the category is checked`() = runBlocking {
        val s = Scene()
        expect(ErrorCodes.NOT_FOUND, "404 before 422", s.call("changeOperationCategory", null, MISSING) { s.operations.changeCategory(MISSING, ChangeCategoryRequest("cat_nope", "2026-09-25T12:00:00+03:00")) })
    }

    @Test
    fun `an unknown answer is 404 before the consent check`() = runBlocking {
        val s = Scene(consent = false)
        expect(ErrorCodes.NOT_FOUND, "404 before 403", s.call("retryAnswer", null, MISSING) { s.assistant.retry(IdempotencyKey.random(), MISSING) })
    }

    @Test
    fun `the consent is checked before the daily limit`() = runBlocking {
        val s = Scene()
        repeat(5) { s.answer("Сколько я потратил?") }
        s.profile.revokeAssistantConsent()
        expect(ErrorCodes.CONSENT_REQUIRED, "consent before limit", s.call("askAssistant", null) { s.assistant.ask(IdempotencyKey.random(), AskRequest("Сколько?")) })
    }

    @Test
    fun `a wrong registration token is 401 before the consent (api md example)`() = runBlocking {
        val s = Scene(signedIn = false)
        expect(
            ErrorCodes.INVALID_TOKEN, "401 of the service before 403",
            s.call("register", null) { s.auth.register(IdempotencyKey.random(), RegisterRequest("reg_unknown", false, "2020-01-01")) },
        )
    }

    @Test
    fun `missing consent is 403 before an outdated document`() = runBlocking {
        val s = Scene(signedIn = false)
        val token = s.registrationToken()
        expect(ErrorCodes.CONSENT_REQUIRED, "403 before 422", s.call("register", null) { s.auth.register(IdempotencyKey.random(), RegisterRequest(token, false, "2020-01-01")) })
    }

    @Test
    fun `retry of a complete answer without the consent is 403 before 422`() = runBlocking {
        val s = Scene()
        val done = s.answer("Сколько я потратил?")
        s.profile.revokeAssistantConsent()
        val retry = s.call("retryAnswer", null, done) { s.assistant.retry(IdempotencyKey.random(), done) }
        ContractCheck.knownGap(GAP_RETRY_ORDER, retry, contract = ErrorCodes.CONSENT_REQUIRED, now = ErrorCodes.RETRY_NOT_ALLOWED)
    }

    // Idempotency: the stored response for a repeat, before the state and limit checks.

    @Test
    fun `a repeat with the same key and body gets the stored response`() = runBlocking {
        val s = Scene(signedIn = false)
        val key = IdempotencyKey.random()
        val first = s.call("startPhoneVerification", PhoneVerification.serializer()) { s.auth.startPhoneVerification(key, StartPhoneVerificationRequest(NEW_PHONE, true, "2026-09-28", true)) }
        val again = s.call("startPhoneVerification", PhoneVerification.serializer()) { s.auth.startPhoneVerification(key, StartPhoneVerificationRequest(NEW_PHONE, true, "2026-09-28", true)) }
        ContractCheck.assertContract("replay: phone", again)
        assertEquals(first.status to first.body, again.status to again.body)

        val token = s.registrationToken("+79990000003")
        val registerKey = IdempotencyKey.random()
        val request = RegisterRequest(token, true, MockBackend.CONSENT_VERSION_PD, "Europe/Moscow")
        val registered = s.call("register", RegisterResponse.serializer()) { s.auth.register(registerKey, request) }
        // The registration token is spent now: only the stored response can answer the repeat (api.md 1.3).
        val repeated = s.call("register", RegisterResponse.serializer()) { s.auth.register(registerKey, request) }
        assertEquals(registered.status to registered.body, repeated.status to repeated.body)
    }

    @Test
    fun `a repeated token refresh gets the stored pair, not TOKEN_REVOKED`() = runBlocking {
        val s = Scene()
        val refresh = s.refreshToken()
        val key = IdempotencyKey.random()
        val first = s.call("refreshTokens", TokenPair.serializer()) { s.auth.refreshTokens(key, refresh) }
        val again = s.call("refreshTokens", TokenPair.serializer()) { s.auth.refreshTokens(key, refresh) }
        assertEquals(first.status to first.body, again.status to again.body)
        expect(ErrorCodes.TOKEN_REVOKED, "spent refresh token, new key", s.call("refreshTokens", null) { s.auth.refreshTokens(IdempotencyKey.random(), refresh) })
    }

    @Test
    fun `a repeated question past the limit and a repeated upload get the stored response`() = runBlocking {
        val s = Scene()
        val key = IdempotencyKey.random()
        val first = s.call("askAssistant", AskResponse.serializer()) { s.assistant.ask(key, AskRequest("Сколько я потратил?")) }
        repeat(4) { s.answer("Сколько я потратил?") }
        val again = s.call("askAssistant", AskResponse.serializer()) { s.assistant.ask(key, AskRequest("Сколько я потратил?")) }
        assertEquals(first.status to first.body, again.status to again.body)
        val upload = s.call("uploadStatement", UploadAccepted.serializer()) { s.statements.uploadStatement(KEY, s.statementFile()) }
        assertEquals(upload.body, s.call("uploadStatement", UploadAccepted.serializer()) { s.statements.uploadStatement(KEY, s.statementFile()) }.body)
    }

    @Test
    fun `the same key with another body is IDEMPOTENCY_CONFLICT`() = runBlocking {
        val s = Scene(signedIn = false)
        val token = s.registrationToken()
        val key = IdempotencyKey.random()
        val request = RegisterRequest(token, true, MockBackend.CONSENT_VERSION_PD, "Europe/Moscow")
        s.auth.register(key, request)
        expect(ErrorCodes.IDEMPOTENCY_CONFLICT, "conflict: register", s.call("register", null) { s.auth.register(key, request.copy(timezone = "Asia/Omsk")) })

        val signedIn = Scene()
        val refresh = signedIn.refreshToken()
        val other = signedIn.refreshToken()
        val refreshKey = IdempotencyKey.random()
        signedIn.auth.refreshTokens(refreshKey, refresh)
        expect(ErrorCodes.IDEMPOTENCY_CONFLICT, "conflict: token", signedIn.call("refreshTokens", null) { signedIn.auth.refreshTokens(refreshKey, other) })
    }

    @Test
    fun `the same key with another file or question is IDEMPOTENCY_CONFLICT`() = runBlocking {
        val s = Scene()
        s.statements.uploadStatement(KEY, s.statementFile())
        val otherContent = s.call("uploadStatement", null) {
            s.statements.uploadStatement(KEY, s.statementFile(text = s.statementFile().open().readBytes().decodeToString() + "\n"))
        }
        expect(ErrorCodes.IDEMPOTENCY_CONFLICT, "conflict: upload, other content under the same name", otherContent)
        // 3.2 compares the content and the name (FIN-297): the same file under another name is another body.
        val renamed = s.call("uploadStatement", null) {
            s.statements.uploadStatement(KEY, s.statementFile("renamed.ofx", s.statementFile().open().readBytes().decodeToString()))
        }
        expect(ErrorCodes.IDEMPOTENCY_CONFLICT, "conflict: upload, other name", renamed)

        val q = Scene()
        q.assistant.ask(KEY, AskRequest("Сколько я потратил?"))
        val question = q.call("askAssistant", AskResponse.serializer()) { q.assistant.ask(KEY, AskRequest("Сколько на такси?")) }
        expect(ErrorCodes.IDEMPOTENCY_CONFLICT, "conflict: another assistant question", question)
    }

    @Test
    fun `upload validation errors replay the stored status and body and reserve the key`() = runBlocking {
        val s = Scene(loaded = false)
        val cases = listOf(
            s.statementFile("s.pdf", "%PDF-1.4") to ErrorCodes.WRONG_FORMAT,
            s.statementFile("s.csv", "Дата операции;Сумма\n01.09.2026;100") to ErrorCodes.CSV_NOT_ACCEPTED,
            s.statementFile("s.ofx", "<OFX><BANKMSGSRSV1><BANKID>SBER</BANKID></BANKMSGSRSV1></OFX>") to ErrorCodes.WRONG_BANK,
        )
        for ((file, code) in cases) {
            val key = IdempotencyKey.random()
            val first = s.call("uploadStatement", null) { s.statements.uploadStatement(key, file) }
            expect(code, "422 first: $code", first)
            val again = s.call("uploadStatement", null) { s.statements.uploadStatement(key, file) }
            expect(code, "422 replay: $code", again)
            assertEquals(first.status to first.body, again.status to again.body)
            expect(ErrorCodes.IDEMPOTENCY_CONFLICT, "changed content: $code", s.call("uploadStatement", null) {
                s.statements.uploadStatement(key, s.statementFile(file.name, "%PDF-2.0"))
            })
            expect(ErrorCodes.IDEMPOTENCY_CONFLICT, "changed name: $code", s.call("uploadStatement", null) {
                s.statements.uploadStatement(key, s.statementFile("renamed-" + file.name, file.open().use { it.readBytes().decodeToString() }))
            })
        }
        assertEquals(0, s.backend.uploads.size)
        val corrected = s.call("uploadStatement", UploadAccepted.serializer()) {
            s.statements.uploadStatement(IdempotencyKey.random(), s.statementFile())
        }
        assertEquals(202, corrected.status)
        ContractCheck.assertContract("corrected file uses a new key", corrected)
        assertEquals(1, s.backend.uploads.size)
    }

    // 404: an object the user never had, or another user's.

    @Test
    fun `objects of a deleted account are 404 to the next user`() = runBlocking {
        val s = Scene(FAST.copy(assistantModelErrors = 1))
        val operation = s.operations.listOperations(OperationsQuery()).items.first().id
        val upload = s.backend.uploads.first().id
        val answer = s.answer("Сколько я потратил?")
        s.profile.deleteAccount()
        s.backend.profile = s.backend.emptyProfile("+79990000004", "u2")

        expect(ErrorCodes.NOT_FOUND, "foreign: operation", s.call("getOperation", null, operation) { s.operations.getOperation(operation, "2026-09-25T12:00:00+03:00") })
        expect(
            ErrorCodes.NOT_FOUND, "foreign: category change",
            s.call("changeOperationCategory", null, operation) { s.operations.changeCategory(operation, ChangeCategoryRequest("cat_clothes", "2026-09-25T12:00:00+03:00")) },
        )
        expect(ErrorCodes.NOT_FOUND, "foreign: unread lines", s.call("listUnreadLines", null) { s.statements.listUnreadLines(upload) })
        expect(ErrorCodes.NOT_FOUND, "foreign: progress", s.progressEvents(upload))
        expect(ErrorCodes.NOT_FOUND, "foreign: answer", s.answerEvents(answer))
        expect(ErrorCodes.NOT_FOUND, "foreign: retry", s.call("retryAnswer", null, answer) { s.assistant.retry(IdempotencyKey.random(), answer) })
        val delete = s.call("deleteStatement", null, upload) { s.statements.deleteStatement(upload) }
        ContractCheck.knownGap(GAP_DELETE_UNKNOWN, delete, contract = ErrorCodes.NOT_FOUND, now = null)
    }

    @Test
    fun `deleting an upload twice is 204, an upload never had is 404`() = runBlocking {
        val s = Scene()
        val upload = s.backend.uploads.first().id
        assertEquals(204, s.call("deleteStatement", null, upload) { s.statements.deleteStatement(upload) }.status)
        assertEquals(204, s.call("deleteStatement", null, upload) { s.statements.deleteStatement(upload) }.status)
        val never = s.call("deleteStatement", null, MISSING) { s.statements.deleteStatement(MISSING) }
        ContractCheck.knownGap(GAP_DELETE_UNKNOWN, never, contract = ErrorCodes.NOT_FOUND, now = null)
    }

    @Test
    fun `streams need a session`() = runBlocking {
        val s = Scene()
        val upload = s.backend.uploads.first().id
        val answer = s.answer("Сколько я потратил?")
        s.backend.profile = s.backend.emptyProfile("", "")
        ContractCheck.knownGap(GAP_STREAM_SESSION, s.progressEvents(upload), contract = ErrorCodes.INVALID_TOKEN, now = null)
        ContractCheck.knownGap(GAP_STREAM_SESSION, s.answerEvents(answer), contract = ErrorCodes.INVALID_TOKEN, now = null)
    }

    @Test
    fun `logout and account deletion need a session`() = runBlocking {
        val s = Scene(signedIn = false)
        val logout = s.call("logout", null) { s.auth.logout("mock.unknown", "rt_unknown") }
        ContractCheck.knownGap(GAP_SESSIONLESS, logout, contract = ErrorCodes.INVALID_TOKEN, now = null)
        ContractCheck.knownGap(GAP_SESSIONLESS, s.call("deleteAccount", null) { s.profile.deleteAccount() }, contract = ErrorCodes.INVALID_TOKEN, now = null)
    }

    @Test
    fun `a retry while the first one runs is REQUEST_IN_PROGRESS with Retry-After`() = runBlocking {
        val exchange = ContractErrorCodesTest.cases.single { it.code == ErrorCodes.REQUEST_IN_PROGRESS }.run()
        assertEquals("1", exchange.headers["Retry-After"])
    }

    internal companion object {
        const val NEW_PHONE = "+79990000001"
        const val MISSING = "00000000-0000-0000-0000-000000000000"
        val KEY = IdempotencyKey("6f1e1b8a-3c2d-4e5f-8a9b-0c1d2e3f4a5b")

        const val GAP_RETRY_ORDER = "retry-error-order"
        const val GAP_DELETE_UNKNOWN = "delete-unknown-upload"
        const val GAP_STREAM_SESSION = "stream-session"
        const val GAP_SESSIONLESS = "logout-delete-session"

        /** Every gap a test here runs; [ContractCoverageTest] keeps it equal to [Exclusions.behaviours]. */
        val GAPS = listOf(GAP_RETRY_ORDER, GAP_DELETE_UNKNOWN, GAP_STREAM_SESSION, GAP_SESSIONLESS)
    }
}
