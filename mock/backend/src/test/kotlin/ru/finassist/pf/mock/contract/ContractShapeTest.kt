package ru.finassist.pf.mock.contract

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import ru.finassist.pf.core.api.model.Analytics
import ru.finassist.pf.core.api.model.AnalyticsPeriodsList
import ru.finassist.pf.core.api.model.AskRequest
import ru.finassist.pf.core.api.model.AskResponse
import ru.finassist.pf.core.api.model.AssistantConsentGrant
import ru.finassist.pf.core.api.model.AssistantLimit
import ru.finassist.pf.core.api.model.CategoriesList
import ru.finassist.pf.core.api.model.CategoryKind
import ru.finassist.pf.core.api.model.ChangeCategoryRequest
import ru.finassist.pf.core.api.model.ChangeCategoryResponse
import ru.finassist.pf.core.api.model.ConsentDocument
import ru.finassist.pf.core.api.model.ConsentType
import ru.finassist.pf.core.api.model.IdempotencyKey
import ru.finassist.pf.core.api.model.ImportConfig
import ru.finassist.pf.core.api.model.MessagesList
import ru.finassist.pf.core.api.model.OperationDetails
import ru.finassist.pf.core.api.model.OperationKind
import ru.finassist.pf.core.api.model.OperationsFilter
import ru.finassist.pf.core.api.model.OperationsList
import ru.finassist.pf.core.api.model.OperationsQuery
import ru.finassist.pf.core.api.model.PeriodTypeCode
import ru.finassist.pf.core.api.model.PhoneVerification
import ru.finassist.pf.core.api.model.Profile
import ru.finassist.pf.core.api.model.ProfileUpdate
import ru.finassist.pf.core.api.model.RegisterRequest
import ru.finassist.pf.core.api.model.RegisterResponse
import ru.finassist.pf.core.api.model.RetryResponse
import ru.finassist.pf.core.api.model.StatementsList
import ru.finassist.pf.core.api.model.Theme
import ru.finassist.pf.core.api.model.TokenPair
import ru.finassist.pf.core.api.model.TransferMode
import ru.finassist.pf.core.api.model.UnreadLinesList
import ru.finassist.pf.core.api.model.UploadAccepted
import ru.finassist.pf.core.common.time.PeriodKey
import ru.finassist.pf.mock.FAST
import ru.finassist.pf.mock.MockBackend
import java.time.OffsetDateTime

/**
 * Response shapes (FIN-123, step 1): every operation of `openapi.yaml`, in its main states, returns what its schema
 * says — the mock's result serialized as on the wire ([Wire.WireJson]) and validated against the operation's response
 * schema of the pinned contract; stream events against their `x-sse-events` schema; block `data` against its primitive.
 */
@RunWith(Parameterized::class)
class ContractShapeTest(private val name: String) {

    @Test
    fun `response follows the contract`() = runBlocking {
        val case = cases.single { it.name == name }
        val exchange = case.run(Scene())
        case.errorEvent?.let { code ->
            val (event, data) = exchange.events.last()
            assertEquals("$name: the terminal event", "error", event)
            assertEquals("$name: code of the error event", code, Json.parseToJsonElement(data).jsonObject["code"]?.jsonPrimitive?.content)
        }
        ContractCheck.assertContract(case.name, exchange)
    }

    internal class ShapeCase(
        val name: String,
        val operationId: String,
        /** The code of the stream's terminal `error` event the case ends with (a stream-only code of section 8). */
        val errorEvent: String? = null,
        val run: suspend (Scene) -> Wire.Exchange,
    ) {
        override fun toString() = name
    }

    companion object {
        private fun case(name: String, operationId: String, run: suspend (Scene) -> Wire.Exchange) = ShapeCase(name, operationId, run = run)

        private fun streamError(code: String, operationId: String, run: suspend (Scene) -> Wire.Exchange) =
            ShapeCase("$operationId: $code", operationId, errorEvent = code, run = run)

        private suspend fun Scene.feed() = operations.listOperations(OperationsQuery())
        private suspend fun Scene.itemOf(kind: OperationKind) = feed().items.first { it.kind == kind }.id

        private suspend fun Scene.newUserRegistrationToken(): String {
            val v = auth.startPhoneVerification(IdempotencyKey.random(), NEW_PHONE)
            return drain(auth.streamPhoneVerification(v.verificationToken)).last().registrationToken!!
        }

        private suspend fun Scene.existingUserRefreshToken(): String {
            val v = auth.startPhoneVerification(IdempotencyKey.random(), FAST.existingPhone)
            return drain(auth.streamPhoneVerification(v.verificationToken)).last().refreshToken!!
        }

        private suspend fun Scene.finishedAnswer(text: String): String {
            val id = assistant.ask(IdempotencyKey.random(), AskRequest(text)).answerId
            drain(assistant.streamAnswer(id))
            return id
        }

        internal val cases: List<ShapeCase> = listOf(
            // 1. Auth
            case("startPhoneVerification", "startPhoneVerification") { s ->
                s.call("startPhoneVerification", PhoneVerification.serializer()) { s.auth.startPhoneVerification(IdempotencyKey.random(), NEW_PHONE) }
            },
            case("streamPhoneVerification: new user", "streamPhoneVerification") { s ->
                s.phoneEvents(s.auth.startPhoneVerification(IdempotencyKey.random(), NEW_PHONE).verificationToken)
            },
            case("streamPhoneVerification: existing user", "streamPhoneVerification") { s ->
                s.phoneEvents(s.auth.startPhoneVerification(IdempotencyKey.random(), FAST.existingPhone).verificationToken)
            },
            case("streamPhoneVerification: pending, then verified", "streamPhoneVerification") { s ->
                s.backend.config = s.backend.config.copy(callVerifyDelayMs = 50)
                s.phoneEvents(s.auth.startPhoneVerification(IdempotencyKey.random(), NEW_PHONE).verificationToken)
            },
            case("streamPhoneVerification: expired", "streamPhoneVerification") { s ->
                val token = s.auth.startPhoneVerification(IdempotencyKey.random(), NEW_PHONE).verificationToken
                s.clock.at = s.clock.at.plusMinutes(10)
                s.phoneEvents(token)
            },
            case("register", "register") { s ->
                val token = s.newUserRegistrationToken()
                s.call("register", RegisterResponse.serializer()) {
                    s.auth.register(IdempotencyKey.random(), RegisterRequest(token, true, MockBackend.CONSENT_VERSION_PD, "Europe/Moscow"))
                }
            },
            case("refreshTokens", "refreshTokens") { s ->
                val refresh = s.existingUserRefreshToken()
                s.call("refreshTokens", TokenPair.serializer()) { s.auth.refreshTokens(IdempotencyKey.random(), refresh) }
            },
            case("logout", "logout") { s ->
                val refresh = s.existingUserRefreshToken()
                s.call("logout", null) { s.auth.logout("mock.access", refresh) }
            },
            case("getConsentDocument: personal_data", "getConsentDocument") { s ->
                s.call("getConsentDocument", ConsentDocument.serializer(), "personal_data") { s.auth.getConsentDocument(ConsentType.PERSONAL_DATA) }
            },
            case("getConsentDocument: assistant", "getConsentDocument") { s ->
                s.call("getConsentDocument", ConsentDocument.serializer(), "assistant") { s.auth.getConsentDocument(ConsentType.ASSISTANT) }
            },

            // 2. Profile
            case("getProfile", "getProfile") { s -> s.call("getProfile", Profile.serializer()) { s.profile.getProfile() } },
            case("getProfile: without the assistant consent", "getProfile") { s ->
                s.backend.profile = s.backend.emptyProfile("+79160000000", "u1")
                s.call("getProfile", Profile.serializer()) { s.profile.getProfile() }
            },
            case("updateProfile", "updateProfile") { s ->
                s.call("updateProfile", Profile.serializer()) { s.profile.updateProfile(ProfileUpdate(theme = Theme.DARK, timezone = "Asia/Yekaterinburg")) }
            },
            case("deleteAccount", "deleteAccount") { s -> s.call("deleteAccount", null) { s.profile.deleteAccount() } },
            case("grantAssistantConsent", "grantAssistantConsent") { s ->
                s.call("grantAssistantConsent", Profile.serializer()) { s.profile.grantAssistantConsent(AssistantConsentGrant(MockBackend.CONSENT_VERSION_ASSISTANT)) }
            },
            case("revokeAssistantConsent", "revokeAssistantConsent") { s -> s.call("revokeAssistantConsent", null) { s.profile.revokeAssistantConsent() } },

            // 3. Statements
            case("getImportConfig", "getImportConfig") { s -> s.call("getImportConfig", ImportConfig.serializer()) { s.statements.getImportConfig() } },
            case("getImportConfig: nothing loaded", "getImportConfig") { _ ->
                Scene(loaded = false).let { e -> e.call("getImportConfig", ImportConfig.serializer()) { e.statements.getImportConfig() } }
            },
            case("listStatements", "listStatements") { s -> s.call("listStatements", StatementsList.serializer()) { s.statements.listStatements() } },
            case("listStatements: nothing loaded", "listStatements") { _ ->
                Scene(loaded = false).let { e -> e.call("listStatements", StatementsList.serializer()) { e.statements.listStatements() } }
            },
            case("uploadStatement", "uploadStatement") { s ->
                s.call("uploadStatement", UploadAccepted.serializer()) { s.statements.uploadStatement(IdempotencyKey.random(), s.statementFile()) }
            },
            case("streamStatementProgress: complete, first upload", "streamStatementProgress") { _ ->
                val e = Scene(loaded = false)
                e.progressEvents(e.statements.uploadStatement(IdempotencyKey.random(), e.statementFile()).uploadId)
            },
            case("streamStatementProgress: complete, repeated statement", "streamStatementProgress") { s ->
                s.progressEvents(s.statements.uploadStatement(IdempotencyKey.random(), s.statementFile()).uploadId)
            },
            streamError("CANCELLED", "streamStatementProgress") { _ ->
                val e = Scene(FAST.copy(importStepDelayMs = 5_000), loaded = false)
                val id = e.statements.uploadStatement(IdempotencyKey.random(), e.statementFile()).uploadId
                e.statements.deleteStatement(id)
                e.progressEvents(id)
            },
            case("deleteStatement", "deleteStatement") { s ->
                val id = s.backend.uploads.first().id
                s.call("deleteStatement", null, id) { s.statements.deleteStatement(id) }
            },
            case("listUnreadLines: by upload", "listUnreadLines") { s ->
                val id = s.backend.uploads.first().id
                s.call("listUnreadLines", UnreadLinesList.serializer()) { s.statements.listUnreadLines(id) }
            },
            case("listUnreadLines: by period", "listUnreadLines") { s ->
                s.call("listUnreadLines", UnreadLinesList.serializer()) {
                    s.statements.listUnreadLines(OffsetDateTime.parse("2025-01-01T00:00:00+03:00"), OffsetDateTime.parse("2026-10-07T00:00:00+03:00"))
                }
            },

            // 4. Operations, 5. Categories
            case("listOperations: feed", "listOperations") { s -> s.call("listOperations", OperationsList.serializer()) { s.feed() } },
            case("listOperations: feed, older page", "listOperations") { s ->
                val before = s.feed().nextBefore!!
                s.call("listOperations", OperationsList.serializer()) { s.operations.listOperations(OperationsQuery(before = before)) }
            },
            case("listOperations: feed, nothing loaded", "listOperations") { _ ->
                Scene(loaded = false).let { e -> e.call("listOperations", OperationsList.serializer()) { e.feed() } }
            },
            case("listOperations: search by text", "listOperations") { s ->
                s.call("listOperations", OperationsList.serializer()) { s.operations.listOperations(OperationsQuery(OperationsFilter(q = "а"))) }
            },
            case("listOperations: search, all time", "listOperations") { s ->
                s.call("listOperations", OperationsList.serializer()) { s.operations.listOperations(OperationsQuery(OperationsFilter(q = "а"), allTime = true)) }
            },
            case("getOperation: expense", "getOperation") { s ->
                val id = s.itemOf(OperationKind.EXPENSE)
                s.call("getOperation", OperationDetails.serializer(), id) { s.operations.getOperation(id) }
            },
            case("getOperation: income", "getOperation") { s ->
                val id = s.itemOf(OperationKind.INCOME)
                s.call("getOperation", OperationDetails.serializer(), id) { s.operations.getOperation(id) }
            },
            case("getOperation: own transfer", "getOperation") { s ->
                val id = s.itemOf(OperationKind.OWN_TRANSFER)
                s.call("getOperation", OperationDetails.serializer(), id) { s.operations.getOperation(id) }
            },
            case("changeOperationCategory", "changeOperationCategory") { s ->
                val id = s.itemOf(OperationKind.EXPENSE)
                val category = s.operations.listCategories().categories.first { it.assignable && it.id != s.operations.getOperation(id).categoryId && CategoryKind.EXPENSE in it.kinds }
                s.call("changeOperationCategory", ChangeCategoryResponse.serializer(), id) { s.operations.changeCategory(id, ChangeCategoryRequest(category.id)) }
            },
            case("listCategories", "listCategories") { s -> s.call("listCategories", CategoriesList.serializer()) { s.operations.listCategories() } },

            // 6. Analytics
            case("getAnalytics: default", "getAnalytics") { s -> s.call("getAnalytics", Analytics.serializer()) { s.analytics.getAnalytics() } },
            case("getAnalytics: quarter without transfers", "getAnalytics") { s ->
                s.call("getAnalytics", Analytics.serializer()) { s.analytics.getAnalytics(PeriodTypeCode.QUARTER, transferMode = TransferMode.WITHOUT) }
            },
            case("getAnalytics: year", "getAnalytics") { s ->
                s.call("getAnalytics", Analytics.serializer()) { s.analytics.getAnalytics(PeriodTypeCode.YEAR) }
            },
            case("getAnalytics: an incomplete month", "getAnalytics") { s ->
                s.call("getAnalytics", Analytics.serializer()) { s.analytics.getAnalytics(PeriodTypeCode.MONTH, PeriodKey("2026-10")) }
            },
            case("getAnalytics: nothing loaded", "getAnalytics") { _ ->
                Scene(loaded = false).let { e -> e.call("getAnalytics", Analytics.serializer()) { e.analytics.getAnalytics() } }
            },
            case("getAnalytics: a block failed to build", "getAnalytics") { _ ->
                Scene(FAST.copy(failingBlocks = setOf("tiles"))).let { e -> e.call("getAnalytics", Analytics.serializer()) { e.analytics.getAnalytics() } }
            },
            case("listAnalyticsPeriods", "listAnalyticsPeriods") { s ->
                s.call("listAnalyticsPeriods", AnalyticsPeriodsList.serializer()) { s.analytics.listPeriods(PeriodTypeCode.MONTH) }
            },
            case("dismissRegularPayment", "dismissRegularPayment") { s ->
                val id = s.regularPaymentIds().first()
                s.call("dismissRegularPayment", null, id) { s.analytics.dismissRegularPayment(id) }
            },
            case("restoreRegularPayment", "restoreRegularPayment") { s ->
                val id = s.regularPaymentIds().first()
                s.analytics.dismissRegularPayment(id)
                s.call("restoreRegularPayment", null, id) { s.analytics.restoreRegularPayment(id) }
            },

            // 7. Assistant
            case("listMessages", "listMessages") { s ->
                s.finishedAnswer("Сколько я потратил?")
                s.call("listMessages", MessagesList.serializer()) { s.assistant.listMessages() }
            },
            case("listMessages: empty", "listMessages") { s -> s.call("listMessages", MessagesList.serializer()) { s.assistant.listMessages() } },
            case("askAssistant", "askAssistant") { s ->
                s.call("askAssistant", AskResponse.serializer()) { s.assistant.ask(IdempotencyKey.random(), AskRequest("Сколько я потратил?")) }
            },
            case("streamAnswer: complete", "streamAnswer") { s -> s.answerEvents(s.finishedAnswer("Сколько я потратил?")) },
            case("streamAnswer: category with blocks", "streamAnswer") { s -> s.answerEvents(s.finishedAnswer("Сколько уходит на рестораны")) },
            streamError("CANNOT_ANSWER", "streamAnswer") { s -> s.answerEvents(s.finishedAnswer("Сколько я потрачу в следующем году?")) },
            streamError("OFF_TOPIC", "streamAnswer") { s -> s.answerEvents(s.finishedAnswer("Дай рецепт борща")) },
            streamError("MODEL_ERROR", "streamAnswer") { s ->
                s.backend.config = s.backend.config.copy(assistantModelErrors = 1)
                s.answerEvents(s.finishedAnswer("Сколько я потратил?"))
            },
            case("retryAnswer", "retryAnswer") { s ->
                s.backend.config = s.backend.config.copy(assistantModelErrors = 1)
                val failed = s.finishedAnswer("Сколько я потратил?")
                s.call("retryAnswer", RetryResponse.serializer(), failed) { s.assistant.retry(IdempotencyKey.random(), failed) }
            },
            case("listMessages: a failed and a retried answer", "listMessages") { s ->
                s.backend.config = s.backend.config.copy(assistantModelErrors = 1)
                val failed = s.finishedAnswer("Сколько я потратил?")
                s.drain(s.assistant.streamAnswer(s.assistant.retry(IdempotencyKey.random(), failed).answerId))
                s.finishedAnswer("Дай рецепт борща")
                s.call("listMessages", MessagesList.serializer()) { s.assistant.listMessages() }
            },
            case("getAssistantLimit", "getAssistantLimit") { s -> s.call("getAssistantLimit", AssistantLimit.serializer()) { s.assistant.getLimit() } },
        )

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun parameters(): List<String> = cases.map { it.name }

        private const val NEW_PHONE = "+79990000001"
    }
}
