package ru.finassist.pf.mock.contract

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import ru.finassist.pf.core.api.model.AskRequest
import ru.finassist.pf.core.api.model.AssistantConsentGrant
import ru.finassist.pf.core.api.model.ChangeCategoryRequest
import ru.finassist.pf.core.api.model.ErrorCodes
import ru.finassist.pf.core.api.model.IdempotencyKey
import ru.finassist.pf.core.api.model.OperationsFilter
import ru.finassist.pf.core.api.model.OperationsList
import ru.finassist.pf.core.api.model.OperationsQuery
import ru.finassist.pf.core.api.model.PhoneVerification
import ru.finassist.pf.core.api.model.Profile
import ru.finassist.pf.core.api.model.RegisterRequest
import ru.finassist.pf.core.api.model.RegisterResponse
import ru.finassist.pf.core.api.model.RetryResponse
import ru.finassist.pf.core.api.model.TokenPair
import ru.finassist.pf.core.api.model.UploadAccepted
import ru.finassist.pf.core.common.time.PeriodKey
import ru.finassist.pf.mock.FAST
import ru.finassist.pf.mock.MockBackend
import ru.finassist.pf.mock.fixtureText
import ru.finassist.pf.mock.load
import ru.finassist.pf.mock.statement
import ru.finassist.pf.mock.trn

/**
 * Error codes (FIN-123, step 2): every code of section 8 of `api.md` the fake backend can return comes from a case
 * here with the status of the registry and a body valid against the operation's error response. Codes it cannot
 * return are [Exclusions.codes]; [ContractCoverageTest] keeps the two lists equal to the registry.
 */
@RunWith(Parameterized::class)
class ContractErrorCodesTest(private val name: String) {

    @Test
    fun `error follows the registry and the schema`() = runBlocking {
        val case = cases.single { it.name == name }
        val exchange = case.run()
        assertEquals("$name: error.code of $exchange", case.code, exchange.code)
        ContractCheck.assertContract(name, exchange)
    }

    internal class ErrorCase(val name: String, val code: String, val run: suspend () -> Wire.Exchange)

    companion object {
        private fun case(code: String, what: String, run: suspend () -> Wire.Exchange) = ErrorCase("$code: $what", code, run)

        private suspend fun Scene.answer(text: String): String {
            val id = assistant.ask(IdempotencyKey.random(), AskRequest(text)).answerId
            drain(assistant.streamAnswer(id))
            return id
        }

        private suspend fun Scene.registrationToken(): String {
            val v = auth.startPhoneVerification(IdempotencyKey.random(), NEW_PHONE)
            return drain(auth.streamPhoneVerification(v.verificationToken)).last().registrationToken!!
        }

        private fun Scene.upload(name: String, text: String, size: Long? = null) =
            statementFile(name, text).let { f -> if (size == null) f else ru.finassist.pf.core.api.UploadFile(f.name, size, f.open) }

        internal val cases: List<ErrorCase> = listOf(
            case(ErrorCodes.VALIDATION_ERROR, "phone outside +7 and 10 digits") {
                val s = Scene(signedIn = false)
                s.call("startPhoneVerification", PhoneVerification.serializer()) { s.auth.startPhoneVerification(IdempotencyKey.random(), "8916") }
            },
            case(ErrorCodes.VALIDATION_ERROR, "feed page with search filters") {
                val s = Scene()
                val before = s.operations.listOperations(OperationsQuery()).nextBefore!!
                s.call("listOperations", OperationsList.serializer()) { s.operations.listOperations(OperationsQuery(OperationsFilter(q = "а"), before = before)) }
            },
            case(ErrorCodes.VALIDATION_ERROR, "analytics date in no format") {
                val s = Scene()
                s.call("getAnalytics", null) { s.analytics.getAnalytics(date = PeriodKey("2026-13")) }
            },
            case(ErrorCodes.VALIDATION_ERROR, "empty question") {
                val s = Scene()
                s.call("askAssistant", null) { s.assistant.ask(IdempotencyKey.random(), AskRequest(" ")) }
            },
            case(ErrorCodes.VALIDATION_ERROR, "regular payment id that is not a UUID") {
                val s = Scene()
                s.call("dismissRegularPayment", null, "Netflix") { s.analytics.dismissRegularPayment("Netflix") }
            },
            case(ErrorCodes.INVALID_TOKEN, "a call without a session") {
                val s = Scene(signedIn = false)
                s.call("getProfile", Profile.serializer()) { s.profile.getProfile() }
            },
            case(ErrorCodes.INVALID_TOKEN, "an unknown verification token, before the stream") {
                Scene(signedIn = false).phoneEvents("vt_unknown")
            },
            case(ErrorCodes.INVALID_TOKEN, "an unknown registration token") {
                val s = Scene(signedIn = false)
                s.call("register", RegisterResponse.serializer()) {
                    s.auth.register(IdempotencyKey.random(), RegisterRequest("reg_unknown", true, MockBackend.CONSENT_VERSION_PD))
                }
            },
            case(ErrorCodes.TOKEN_REVOKED, "an unknown refresh token") {
                val s = Scene(signedIn = false)
                s.call("refreshTokens", TokenPair.serializer()) { s.auth.refreshTokens(IdempotencyKey.random(), "rt_unknown") }
            },
            case(ErrorCodes.CONSENT_REQUIRED, "registration without the personal data consent") {
                val s = Scene(signedIn = false)
                val token = s.registrationToken()
                s.call("register", RegisterResponse.serializer()) {
                    s.auth.register(IdempotencyKey.random(), RegisterRequest(token, false, MockBackend.CONSENT_VERSION_PD))
                }
            },
            case(ErrorCodes.CONSENT_REQUIRED, "a question without the assistant consent") {
                val s = Scene(consent = false)
                s.call("askAssistant", null) { s.assistant.ask(IdempotencyKey.random(), AskRequest("Сколько я потратил?")) }
            },
            case(ErrorCodes.CONSENT_REQUIRED, "a retry after the consent was revoked") {
                val s = Scene(FAST.copy(assistantModelErrors = 1))
                val failed = s.answer("Сколько я потратил?")
                s.profile.revokeAssistantConsent()
                s.call("retryAnswer", RetryResponse.serializer(), failed) { s.assistant.retry(IdempotencyKey.random(), failed) }
            },
            case(ErrorCodes.LIMIT_EXCEEDED, "the sixth question of the day") {
                val s = Scene()
                repeat(5) { s.answer("Сколько я потратил?") }
                s.call("askAssistant", null) { s.assistant.ask(IdempotencyKey.random(), AskRequest("Сколько я потратил?")) }
            },
            case(ErrorCodes.NOT_FOUND, "an operation that does not exist") {
                val s = Scene()
                s.call("getOperation", null, MISSING) { s.operations.getOperation(MISSING) }
            },
            case(ErrorCodes.NOT_FOUND, "unread lines of an unknown upload") {
                val s = Scene()
                s.call("listUnreadLines", null) { s.statements.listUnreadLines(MISSING) }
            },
            case(ErrorCodes.NOT_FOUND, "progress of an unknown upload, before the stream") { Scene().progressEvents(MISSING) },
            case(ErrorCodes.NOT_FOUND, "an unknown answer, before the stream") { Scene().answerEvents(MISSING) },
            case(ErrorCodes.NOT_FOUND, "a retry of an unknown answer") {
                val s = Scene()
                s.call("retryAnswer", null, MISSING) { s.assistant.retry(IdempotencyKey.random(), MISSING) }
            },
            case(ErrorCodes.NOT_FOUND, "an unknown regular payment") {
                val s = Scene()
                s.call("restoreRegularPayment", null, UNKNOWN_UUID) { s.analytics.restoreRegularPayment(UNKNOWN_UUID) }
            },
            case(ErrorCodes.IDEMPOTENCY_CONFLICT, "the same key with another phone") {
                val s = Scene(signedIn = false)
                val key = IdempotencyKey.random()
                s.auth.startPhoneVerification(key, NEW_PHONE)
                s.call("startPhoneVerification", null) { s.auth.startPhoneVerification(key, "+79990000002") }
            },
            case(ErrorCodes.REQUEST_IN_PROGRESS, "a second retry while the first is on its way") {
                val s = Scene(FAST.copy(assistantModelErrors = 1))
                val failed = s.answer("Сколько я потратил?")
                s.backend.config = s.backend.config.copy(networkDelayMs = 500)
                val key = IdempotencyKey.random()
                coroutineScope {
                    val first = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) { s.assistant.retry(key, failed) }
                    val second = s.call("retryAnswer", null, failed) { s.assistant.retry(key, failed) }
                    first.await()
                    second
                }
            },
            case(ErrorCodes.FILE_TOO_LARGE, "a file over the limit") {
                val s = Scene()
                s.call("uploadStatement", UploadAccepted.serializer()) {
                    s.statements.uploadStatement(IdempotencyKey.random(), s.upload("big.ofx", fixtureText, size = FAST.maxFileSizeBytes + 1))
                }
            },
            case(ErrorCodes.WRONG_FORMAT, "a PDF") {
                val s = Scene()
                s.call("uploadStatement", null) { s.statements.uploadStatement(IdempotencyKey.random(), s.upload("s.pdf", "%PDF-1.4")) }
            },
            case(ErrorCodes.CSV_NOT_ACCEPTED, "a CSV export") {
                val s = Scene()
                s.call("uploadStatement", null) {
                    s.statements.uploadStatement(IdempotencyKey.random(), s.upload("s.csv", "Дата операции;Дата платежа;Сумма\n01.09.2026;01.09.2026;-100"))
                }
            },
            case(ErrorCodes.WRONG_BANK, "an OFX of another bank") {
                val s = Scene()
                s.call("uploadStatement", null) { s.statements.uploadStatement(IdempotencyKey.random(), s.upload("s.ofx", fixtureText.replace("T-BANK", "SBER"))) }
            },
            case(ErrorCodes.CONSENT_OUTDATED, "registration with an old document") {
                val s = Scene(signedIn = false)
                val token = s.registrationToken()
                s.call("register", null) { s.auth.register(IdempotencyKey.random(), RegisterRequest(token, true, "2020-01-01")) }
            },
            case(ErrorCodes.CONSENT_OUTDATED, "the assistant consent to an old document") {
                val s = Scene(consent = false)
                s.call("grantAssistantConsent", null) { s.profile.grantAssistantConsent(AssistantConsentGrant("2020-01-01")) }
            },
            case(ErrorCodes.TOO_MANY_RESULTS, "a search over 10 000 operations") {
                val s = Scene(loaded = false)
                s.backend.load(
                    statement(
                        "2026-09-01", "2026-09-30",
                        *(0..10_000).map { i -> trn("t$i", "2026-09-%02dT12:00".format(1 + i % 30), 100, "Кофейня", "Рестораны") }.toTypedArray(),
                    ),
                )
                s.call("listOperations", null) { s.operations.listOperations(OperationsQuery(OperationsFilter(q = "Кофейня"))) }
            },
            case(ErrorCodes.SELECTION_NOT_FOUND, "a selection the server does not know") {
                val s = Scene()
                s.call("listOperations", null) { s.operations.listOperations(OperationsQuery(OperationsFilter(selection = "s_regular_$UNKNOWN_UUID"))) }
            },
            case(ErrorCodes.CATEGORY_NOT_ASSIGNABLE, "an unknown category") {
                val s = Scene()
                val id = s.operations.listOperations(OperationsQuery()).items.first().id
                s.call("changeOperationCategory", null, id) { s.operations.changeCategory(id, ChangeCategoryRequest("cat_nope")) }
            },
            case(ErrorCodes.RETRY_NOT_ALLOWED, "a retry of a complete answer") {
                val s = Scene()
                val done = s.answer("Сколько я потратил?")
                s.call("retryAnswer", null, done) { s.assistant.retry(IdempotencyKey.random(), done) }
            },
        )

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun parameters(): List<String> = cases.map { it.name }

        private const val NEW_PHONE = "+79990000001"
        private const val MISSING = "00000000-0000-0000-0000-000000000000"
        private const val UNKNOWN_UUID = "0b9d5c2e-6f1a-4c3b-9a7e-2d4f6b8c0e1a"
    }
}
