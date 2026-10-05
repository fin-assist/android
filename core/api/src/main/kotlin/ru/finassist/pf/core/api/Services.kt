package ru.finassist.pf.core.api

import kotlinx.coroutines.flow.Flow
import ru.finassist.pf.core.api.model.Analytics
import ru.finassist.pf.core.api.model.AnalyticsPeriodsList
import ru.finassist.pf.core.api.model.AnswerEvent
import ru.finassist.pf.core.api.model.AskRequest
import ru.finassist.pf.core.api.model.AskResponse
import ru.finassist.pf.core.api.model.AssistantConsentGrant
import ru.finassist.pf.core.api.model.AssistantLimit
import ru.finassist.pf.core.api.model.CategoriesList
import ru.finassist.pf.core.api.model.ChangeCategoryRequest
import ru.finassist.pf.core.api.model.ChangeCategoryResponse
import ru.finassist.pf.core.api.model.ConsentDocument
import ru.finassist.pf.core.api.model.ConsentType
import ru.finassist.pf.core.api.model.IdempotencyKey
import ru.finassist.pf.core.api.model.ImportConfig
import ru.finassist.pf.core.api.model.ImportProgress
import ru.finassist.pf.core.api.model.ImportResult
import ru.finassist.pf.core.api.model.MessagesList
import ru.finassist.pf.core.api.model.OperationDetails
import ru.finassist.pf.core.api.model.OperationsList
import ru.finassist.pf.core.api.model.OperationsQuery
import ru.finassist.pf.core.api.model.PeriodTypeCode
import ru.finassist.pf.core.api.model.PhoneVerification
import ru.finassist.pf.core.api.model.PhoneVerificationStatusEvent
import ru.finassist.pf.core.api.model.Profile
import ru.finassist.pf.core.api.model.ProfileUpdate
import ru.finassist.pf.core.api.model.RegisterRequest
import ru.finassist.pf.core.api.model.RegisterResponse
import ru.finassist.pf.core.api.model.RetryResponse
import ru.finassist.pf.core.api.model.StatementsList
import ru.finassist.pf.core.api.model.TokenPair
import ru.finassist.pf.core.api.model.TransferMode
import ru.finassist.pf.core.api.model.UnreadLinesList
import ru.finassist.pf.core.api.model.UploadAccepted
import ru.finassist.pf.core.common.time.PeriodKey
import java.io.InputStream
import java.time.OffsetDateTime

/*
 * Service interfaces of the client API (api.md / openapi.yaml). Implemented by :core:network over HTTP and by
 * :mock:backend in-process. Every call either returns or throws an `AppError` (core/common) — features never
 * see HTTP. SSE endpoints are exposed as cold Flows: collecting opens the stream, cancelling closes it; the
 * first element is always the current state, so re-collecting after a drop loses nothing.
 */

interface AuthApi {
    suspend fun startPhoneVerification(key: IdempotencyKey, phone: String): PhoneVerification

    /** `GET /v1/auth/phone/events`. Completes after a terminal status (`verified` / `expired`). */
    fun streamPhoneVerification(verificationToken: String): Flow<PhoneVerificationStatusEvent>

    suspend fun register(key: IdempotencyKey, request: RegisterRequest): RegisterResponse

    suspend fun refreshTokens(key: IdempotencyKey, refreshToken: String): TokenPair

    suspend fun logout(refreshToken: String)

    suspend fun getConsentDocument(type: ConsentType): ConsentDocument
}

interface ProfileApi {
    suspend fun getProfile(): Profile
    suspend fun updateProfile(update: ProfileUpdate): Profile
    suspend fun deleteAccount()
    suspend fun grantAssistantConsent(grant: AssistantConsentGrant): Profile
    suspend fun revokeAssistantConsent()
}

/** File handed to [StatementsApi.uploadStatement]; opened lazily so a retry can re-read it. */
class UploadFile(
    val name: String,
    val size: Long,
    val open: () -> InputStream,
)

/** Typed events of the import progress stream. */
sealed interface ImportEvent {
    data class Progress(val progress: ImportProgress) : ImportEvent
    data class Complete(val result: ImportResult) : ImportEvent
    /** `PROCESSING_FAILED`, `CANCELLED` or unknown (handled as `PROCESSING_FAILED`). */
    data class Error(val code: String, val message: String) : ImportEvent
}

interface StatementsApi {
    suspend fun getImportConfig(): ImportConfig
    suspend fun uploadStatement(key: IdempotencyKey, file: UploadFile): UploadAccepted
    /** `GET /v1/statements/{upload_id}/progress`. Completes after `complete` or `error`. */
    fun streamProgress(uploadId: String): Flow<ImportEvent>
    suspend fun listStatements(): StatementsList
    suspend fun deleteStatement(uploadId: String)
    suspend fun listUnreadLines(uploadId: String): UnreadLinesList
    suspend fun listUnreadLines(from: OffsetDateTime, to: OffsetDateTime): UnreadLinesList
}

interface OperationsApi {
    suspend fun listOperations(query: OperationsQuery): OperationsList
    suspend fun getOperation(id: String): OperationDetails
    suspend fun changeCategory(id: String, request: ChangeCategoryRequest): ChangeCategoryResponse
}

interface CategoriesApi {
    suspend fun listCategories(): CategoriesList
}

interface AnalyticsApi {
    suspend fun getAnalytics(
        period: PeriodTypeCode? = null,
        date: PeriodKey? = null,
        transferMode: TransferMode? = null,
    ): Analytics

    suspend fun listPeriods(period: PeriodTypeCode? = null): AnalyticsPeriodsList
    suspend fun dismissRegularPayment(id: String)
    suspend fun restoreRegularPayment(id: String)
}

interface AssistantApi {
    suspend fun listMessages(limit: Int? = null, beforeId: String? = null): MessagesList
    suspend fun ask(key: IdempotencyKey, request: AskRequest): AskResponse
    /** `GET /v1/assistant/messages/{answer_id}/events`. Completes after `done` or `error`. */
    fun streamAnswer(answerId: String): Flow<AnswerEvent>
    suspend fun retry(key: IdempotencyKey, answerId: String): RetryResponse
    suspend fun getLimit(): AssistantLimit
}

/** Convenience holder so a feature can inject one object instead of six. */
interface PfApi {
    val auth: AuthApi
    val profile: ProfileApi
    val statements: StatementsApi
    val operations: OperationsApi
    val categories: CategoriesApi
    val analytics: AnalyticsApi
    val assistant: AssistantApi
}
