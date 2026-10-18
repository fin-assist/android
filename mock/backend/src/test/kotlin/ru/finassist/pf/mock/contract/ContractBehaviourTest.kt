package ru.finassist.pf.mock.contract

import kotlinx.coroutines.runBlocking
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
import ru.finassist.pf.core.api.model.RegisterRequest
import ru.finassist.pf.core.api.model.RegisterResponse
import ru.finassist.pf.core.api.model.RetryResponse
import ru.finassist.pf.core.api.model.TokenPair
import ru.finassist.pf.core.api.model.UploadAccepted
import ru.finassist.pf.mock.FAST
import ru.finassist.pf.mock.MockBackend

/**
 * Behaviour of the contract around errors (FIN-123, step 2): the order the server checks them in (api.md, «Ошибки»),
 * idempotency («Идемпотентность»: a repeat gets the stored response, another body 409) and 404 for an object the user
 * does not have. Gaps the fake backend has are [Exclusions.behaviours], run through [ContractCheck.knownGap].
 */
class ContractBehaviourTest {

    private suspend fun Scene.answer(text: String, key: IdempotencyKey = IdempotencyKey.random()): String {
        val id = assistant.ask(key, AskRequest(text)).answerId
        drain(assistant.streamAnswer(id))
        return id
    }

    private suspend fun Scene.registrationToken(phone: String = NEW_PHONE): String {
        val v = auth.startPhoneVerification(IdempotencyKey.random(), phone)
        return drain(auth.streamPhoneVerification(v.verificationToken)).last().registrationToken!!
    }

    private suspend fun Scene.refreshToken(): String {
        val v = auth.startPhoneVerification(IdempotencyKey.random(), FAST.existingPhone)
        return drain(auth.streamPhoneVerification(v.verificationToken)).last().refreshToken!!
    }

    private fun expect(code: String, case: String, exchange: Wire.Exchange) {
        assertEquals("$case: $exchange", code, exchange.code)
        ContractCheck.assertContract(case, exchange)
    }

    // Order of errors: 401 → 400 → idempotency → 404 → 403 → 422.

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
        expect(ErrorCodes.INVALID_TOKEN, "401 before 404: operation", s.call("getOperation", null, MISSING) { s.operations.getOperation(MISSING) })
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
        expect(ErrorCodes.NOT_FOUND, "404 before 422", s.call("changeOperationCategory", null, MISSING) { s.operations.changeCategory(MISSING, ChangeCategoryRequest("cat_nope")) })
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
        ContractCheck.knownGap(GAP_RETRY_ORDER) {
            val s = Scene()
            val done = s.answer("Сколько я потратил?")
            s.profile.revokeAssistantConsent()
            expect(ErrorCodes.CONSENT_REQUIRED, "403 before 422: retry", s.call("retryAnswer", null, done) { s.assistant.retry(IdempotencyKey.random(), done) })
        }
    }

    // Idempotency: the stored response for a repeat, before the state and limit checks.

    @Test
    fun `a repeat with the same key and body gets the stored response`() = runBlocking {
        val s = Scene(signedIn = false)
        val key = IdempotencyKey.random()
        val first = s.call("startPhoneVerification", PhoneVerification.serializer()) { s.auth.startPhoneVerification(key, NEW_PHONE) }
        val again = s.call("startPhoneVerification", PhoneVerification.serializer()) { s.auth.startPhoneVerification(key, NEW_PHONE) }
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
        ContractCheck.knownGap(GAP_CONFLICT) {
            val s = Scene()
            s.statements.uploadStatement(KEY, s.statementFile())
            expect(
                ErrorCodes.IDEMPOTENCY_CONFLICT, "conflict: upload",
                s.call("uploadStatement", null) { s.statements.uploadStatement(KEY, s.statementFile("other.ofx", s.statementFile().open().readBytes().decodeToString() + "\n")) },
            )
        }
        ContractCheck.knownGap(GAP_CONFLICT) {
            val s = Scene()
            s.assistant.ask(KEY, AskRequest("Сколько я потратил?"))
            expect(ErrorCodes.IDEMPOTENCY_CONFLICT, "conflict: question", s.call("askAssistant", null) { s.assistant.ask(KEY, AskRequest("Сколько на такси?")) })
        }
    }

    @Test
    fun `a 422 is stored under the key`() = runBlocking {
        ContractCheck.knownGap(GAP_STORED_ERRORS) {
            val s = Scene()
            val pdf = s.statementFile("s.pdf", "%PDF-1.4")
            expect(ErrorCodes.WRONG_FORMAT, "422 first", s.call("uploadStatement", null) { s.statements.uploadStatement(KEY, pdf) })
            // Same key, same body: the stored 422, not a new run — here the limit was raised meanwhile.
            s.backend.config = s.backend.config.copy(maxFileSizeBytes = 1)
            expect(ErrorCodes.WRONG_FORMAT, "422 stored", s.call("uploadStatement", null) { s.statements.uploadStatement(KEY, pdf) })
        }
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

        expect(ErrorCodes.NOT_FOUND, "foreign: operation", s.call("getOperation", null, operation) { s.operations.getOperation(operation) })
        expect(
            ErrorCodes.NOT_FOUND, "foreign: category change",
            s.call("changeOperationCategory", null, operation) { s.operations.changeCategory(operation, ChangeCategoryRequest("cat_clothes")) },
        )
        expect(ErrorCodes.NOT_FOUND, "foreign: unread lines", s.call("listUnreadLines", null) { s.statements.listUnreadLines(upload) })
        expect(ErrorCodes.NOT_FOUND, "foreign: progress", s.progressEvents(upload))
        expect(ErrorCodes.NOT_FOUND, "foreign: answer", s.answerEvents(answer))
        expect(ErrorCodes.NOT_FOUND, "foreign: retry", s.call("retryAnswer", null, answer) { s.assistant.retry(IdempotencyKey.random(), answer) })
        ContractCheck.knownGap(GAP_DELETE_UNKNOWN) {
            expect(ErrorCodes.NOT_FOUND, "foreign: delete upload", s.call("deleteStatement", null, upload) { s.statements.deleteStatement(upload) })
        }
    }

    @Test
    fun `deleting an upload twice is 204, an upload never had is 404`() = runBlocking {
        val s = Scene()
        val upload = s.backend.uploads.first().id
        assertEquals(204, s.call("deleteStatement", null, upload) { s.statements.deleteStatement(upload) }.status)
        assertEquals(204, s.call("deleteStatement", null, upload) { s.statements.deleteStatement(upload) }.status)
        ContractCheck.knownGap(GAP_DELETE_UNKNOWN) {
            expect(ErrorCodes.NOT_FOUND, "never had: delete upload", s.call("deleteStatement", null, MISSING) { s.statements.deleteStatement(MISSING) })
        }
    }

    @Test
    fun `streams need a session`() = runBlocking {
        val s = Scene()
        val upload = s.backend.uploads.first().id
        val answer = s.answer("Сколько я потратил?")
        s.backend.profile = s.backend.emptyProfile("", "")
        ContractCheck.knownGap(GAP_STREAM_SESSION) { expect(ErrorCodes.INVALID_TOKEN, "stream without a session: progress", s.progressEvents(upload)) }
        ContractCheck.knownGap(GAP_STREAM_SESSION) { expect(ErrorCodes.INVALID_TOKEN, "stream without a session: answer", s.answerEvents(answer)) }
    }

    @Test
    fun `logout and account deletion need a session`() = runBlocking {
        val s = Scene(signedIn = false)
        ContractCheck.knownGap(GAP_SESSIONLESS) { expect(ErrorCodes.INVALID_TOKEN, "logout without a session", s.call("logout", null) { s.auth.logout("mock.unknown", "rt_unknown") }) }
        ContractCheck.knownGap(GAP_SESSIONLESS) { expect(ErrorCodes.INVALID_TOKEN, "delete without a session", s.call("deleteAccount", null) { s.profile.deleteAccount() }) }
    }

    @Test
    fun `a retry while the first one runs is REQUEST_IN_PROGRESS with Retry-After`() = runBlocking {
        val exchange = ContractErrorCodesTest.cases.single { it.code == ErrorCodes.REQUEST_IN_PROGRESS }.run()
        assertEquals("1", exchange.headers["Retry-After"])
    }

    private companion object {
        const val NEW_PHONE = "+79990000001"
        const val MISSING = "00000000-0000-0000-0000-000000000000"
        val KEY = IdempotencyKey("6f1e1b8a-3c2d-4e5f-8a9b-0c1d2e3f4a5b")

        const val GAP_RETRY_ORDER = "retry-error-order"
        const val GAP_CONFLICT = "idempotency-conflict-statements-assistant"
        const val GAP_STORED_ERRORS = "idempotency-stored-errors"
        const val GAP_DELETE_UNKNOWN = "delete-unknown-upload"
        const val GAP_STREAM_SESSION = "stream-session"
        const val GAP_SESSIONLESS = "logout-delete-session"
    }
}
