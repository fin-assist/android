package ru.finassist.pf.core.network

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transform
import kotlinx.serialization.DeserializationStrategy
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import okio.source
import retrofit2.Response
import ru.finassist.pf.core.api.AnalyticsApi
import ru.finassist.pf.core.api.ApiJson
import ru.finassist.pf.core.api.AssistantApi
import ru.finassist.pf.core.api.AuthApi
import ru.finassist.pf.core.api.CategoriesApi
import ru.finassist.pf.core.api.ImportEvent
import ru.finassist.pf.core.api.OperationsApi
import ru.finassist.pf.core.api.PfApi
import ru.finassist.pf.core.api.ProfileApi
import ru.finassist.pf.core.api.StatementsApi
import ru.finassist.pf.core.api.UploadFile
import ru.finassist.pf.core.api.model.Analytics
import ru.finassist.pf.core.api.model.AnalyticsPeriodsList
import ru.finassist.pf.core.api.model.AnswerBlockEvent
import ru.finassist.pf.core.api.model.AnswerChunkEvent
import ru.finassist.pf.core.api.model.AnswerDoneEvent
import ru.finassist.pf.core.api.model.AnswerErrorEvent
import ru.finassist.pf.core.api.model.AnswerEvent
import ru.finassist.pf.core.api.model.AnswerSnapshotEvent
import ru.finassist.pf.core.api.model.AnswerSource
import ru.finassist.pf.core.api.model.AskRequest
import ru.finassist.pf.core.api.model.AskResponse
import ru.finassist.pf.core.api.model.AssistantConsentGrant
import ru.finassist.pf.core.api.model.AssistantLimit
import ru.finassist.pf.core.api.model.CategoriesList
import ru.finassist.pf.core.api.model.ChangeCategoryRequest
import ru.finassist.pf.core.api.model.ChangeCategoryResponse
import ru.finassist.pf.core.api.model.Chip
import ru.finassist.pf.core.api.model.ConsentDocument
import ru.finassist.pf.core.api.model.ConsentType
import ru.finassist.pf.core.api.model.ErrorCodes
import ru.finassist.pf.core.api.model.IdempotencyKey
import ru.finassist.pf.core.api.model.ImportConfig
import ru.finassist.pf.core.api.model.ImportProgress
import ru.finassist.pf.core.api.model.ImportResult
import ru.finassist.pf.core.api.model.MessagesList
import ru.finassist.pf.core.api.model.OperationDetails
import ru.finassist.pf.core.api.model.OperationKindFilter
import ru.finassist.pf.core.api.model.OperationsList
import ru.finassist.pf.core.api.model.OperationsQuery
import ru.finassist.pf.core.api.model.PeriodTypeCode
import ru.finassist.pf.core.api.model.PhoneVerification
import ru.finassist.pf.core.api.model.PhoneVerificationStatusEvent
import ru.finassist.pf.core.api.model.Profile
import ru.finassist.pf.core.api.model.ProfileUpdate
import ru.finassist.pf.core.api.model.RefreshTokenRequest
import ru.finassist.pf.core.api.model.RegisterRequest
import ru.finassist.pf.core.api.model.RegisterResponse
import ru.finassist.pf.core.api.model.RetryResponse
import ru.finassist.pf.core.api.model.StartPhoneVerificationRequest
import ru.finassist.pf.core.api.model.StatementsList
import ru.finassist.pf.core.api.model.TokenPair
import ru.finassist.pf.core.api.model.TransferMode
import ru.finassist.pf.core.api.model.UnreadLinesList
import ru.finassist.pf.core.api.model.UploadAccepted
import ru.finassist.pf.core.common.error.AppError
import ru.finassist.pf.core.common.time.PeriodKey
import ru.finassist.pf.core.common.time.toApiString
import java.time.OffsetDateTime

/** Executes a Retrofit call: 2xx → body, anything else → [AppError]. */
internal suspend fun <T> call(block: suspend () -> Response<T>): T {
    val response = try {
        block()
    } catch (e: Throwable) {
        throw e.toNetworkError()
    }
    if (response.isSuccessful) {
        @Suppress("UNCHECKED_CAST")
        return response.body() ?: (Unit as T)
    }
    throw mapHttpError(response.code(), response.errorBody()?.string(), response.headers()["Retry-After"])
}

private inline fun <reified T> decode(strategy: DeserializationStrategy<T>, data: String): T =
    ApiJson.decodeFromString(strategy, data)

/** Builds an SSE request to [path] against the base URL with the standard headers. */
internal class SseRequests(private val baseUrl: HttpUrl, private val client: OkHttpClient) {
    fun open(path: String, headers: Map<String, String> = emptyMap()): Flow<SseEvent> {
        val builder = Request.Builder()
            .url(baseUrl.newBuilder().addPathSegments(path).build())
            .header("Accept", "text/event-stream")
        headers.forEach { (k, v) -> builder.header(k, v) }
        return client.sse(builder.build())
    }
}

internal class HttpAuthApi(private val service: AuthService, private val sse: SseRequests) : AuthApi {
    override suspend fun startPhoneVerification(key: IdempotencyKey, phone: String): PhoneVerification =
        call { service.startPhoneVerification(key.value, StartPhoneVerificationRequest(phone)) }

    override fun streamPhoneVerification(verificationToken: String): Flow<PhoneVerificationStatusEvent> =
        sse.open(
            "v1/auth/phone/events",
            mapOf(HEADER_VERIFICATION_TOKEN to verificationToken, NoAuth.HEADER to NoAuth.VALUE),
        ).transform { event ->
            when (event.type) {
                "status" -> emit(decode(PhoneVerificationStatusEvent.serializer(), event.data))
                "error" -> throw streamError(event.data)
                // unknown event types are ignored (api.md, SSE rules)
            }
        }

    override suspend fun register(key: IdempotencyKey, request: RegisterRequest): RegisterResponse =
        call { service.register(key.value, request) }

    override suspend fun refreshTokens(key: IdempotencyKey, refreshToken: String): TokenPair =
        call { service.refreshTokens(key.value, RefreshTokenRequest(refreshToken)) }

    override suspend fun logout(refreshToken: String) {
        call { service.logout(RefreshTokenRequest(refreshToken)) }
    }

    override suspend fun getConsentDocument(type: ConsentType): ConsentDocument =
        call { service.getConsentDocument(type.code) }
}

internal class HttpProfileApi(private val service: ProfileService) : ProfileApi {
    override suspend fun getProfile(): Profile = call { service.getProfile() }
    override suspend fun updateProfile(update: ProfileUpdate): Profile = call { service.updateProfile(update) }
    override suspend fun deleteAccount() { call { service.deleteAccount() } }
    override suspend fun grantAssistantConsent(grant: AssistantConsentGrant): Profile = call { service.grantAssistantConsent(grant) }
    override suspend fun revokeAssistantConsent() { call { service.revokeAssistantConsent() } }
}

internal class HttpStatementsApi(private val service: StatementsService, private val sse: SseRequests) : StatementsApi {
    override suspend fun getImportConfig(): ImportConfig = call { service.getImportConfig() }

    override suspend fun uploadStatement(key: IdempotencyKey, file: UploadFile): UploadAccepted {
        val body = object : RequestBody() {
            override fun contentType() = OFX_MEDIA_TYPE
            override fun contentLength() = file.size
            override fun writeTo(sink: BufferedSink) {
                file.open().source().use { sink.writeAll(it) }
            }
        }
        val part = MultipartBody.Part.createFormData("file", file.name, body)
        return call { service.uploadStatement(key.value, part) }
    }

    override fun streamProgress(uploadId: String): Flow<ImportEvent> =
        sse.open("v1/statements/$uploadId/progress").transform { event ->
            when (event.type) {
                "progress" -> emit(ImportEvent.Progress(decode(ImportProgress.serializer(), event.data)))
                "complete" -> emit(ImportEvent.Complete(decode(ImportResult.serializer(), event.data)))
                "error" -> {
                    val err = decode(StreamErrorPayload.serializer(), event.data)
                    emit(ImportEvent.Error(err.code, err.message))
                }
            }
        }

    override suspend fun listStatements(): StatementsList = call { service.listStatements() }

    override suspend fun deleteStatement(uploadId: String) { call { service.deleteStatement(uploadId) } }

    override suspend fun listUnreadLines(uploadId: String): UnreadLinesList =
        call { service.listUnreadLines(uploadId = uploadId) }

    override suspend fun listUnreadLines(from: OffsetDateTime, to: OffsetDateTime): UnreadLinesList =
        call { service.listUnreadLines(from = from.toApiString(), to = to.toApiString()) }

    private companion object {
        val OFX_MEDIA_TYPE = "application/x-ofx".toMediaType()
    }
}

internal class HttpOperationsApi(private val service: OperationsService) : OperationsApi, CategoriesApi {
    override suspend fun listOperations(query: OperationsQuery): OperationsList = call {
        val f = query.filter
        service.listOperations(
            from = f.from?.toApiString(),
            to = f.to?.toApiString(),
            allTime = query.allTime.takeIf { it },
            q = f.q?.takeIf { it.isNotBlank() },
            categoryId = f.categoryId,
            kind = f.kind?.let { if (it == OperationKindFilter.EXPENSE) "expense" else "income" },
            amountFrom = f.amountFrom?.minor,
            amountTo = f.amountTo?.minor,
            transferMode = f.transferMode?.takeIf { it != TransferMode.UNKNOWN }?.code,
            selection = f.selection,
            before = query.before?.toApiString(),
        )
    }

    override suspend fun getOperation(id: String): OperationDetails = call { service.getOperation(id) }

    override suspend fun changeCategory(id: String, request: ChangeCategoryRequest): ChangeCategoryResponse =
        call { service.changeCategory(id, request) }

    override suspend fun listCategories(): CategoriesList = call { service.listCategories() }
}

internal class HttpAnalyticsApi(private val service: AnalyticsService) : AnalyticsApi {
    override suspend fun getAnalytics(period: PeriodTypeCode?, date: PeriodKey?, transferMode: TransferMode?): Analytics =
        call { service.getAnalytics(period?.code, date?.value, transferMode?.code) }

    override suspend fun listPeriods(period: PeriodTypeCode?): AnalyticsPeriodsList =
        call { service.listPeriods(period?.code) }

    override suspend fun dismissRegularPayment(id: String) { call { service.dismissRegularPayment(id) } }
    override suspend fun restoreRegularPayment(id: String) { call { service.restoreRegularPayment(id) } }
}

internal class HttpAssistantApi(private val service: AssistantService, private val sse: SseRequests) : AssistantApi {
    override suspend fun listMessages(limit: Int?, beforeId: String?): MessagesList =
        call { service.listMessages(limit, beforeId) }

    override suspend fun ask(key: IdempotencyKey, request: AskRequest): AskResponse = call { service.ask(key.value, request) }

    override fun streamAnswer(answerId: String): Flow<AnswerEvent> =
        sse.open("v1/assistant/messages/$answerId/events").map { event ->
            when (event.type) {
                "snapshot" -> AnswerEvent.Snapshot(decode(AnswerSnapshotEvent.serializer(), event.data))
                "chunk" -> AnswerEvent.Chunk(decode(AnswerChunkEvent.serializer(), event.data))
                "block" -> AnswerEvent.BlockAdded(decode(AnswerBlockEvent.serializer(), event.data))
                "source" -> AnswerEvent.Source(decode(AnswerSource.serializer(), event.data))
                "chip" -> AnswerEvent.ChipAdded(decode(Chip.serializer(), event.data))
                "done" -> AnswerEvent.Done(decode(AnswerDoneEvent.serializer(), event.data))
                "error" -> AnswerEvent.Error(decode(AnswerErrorEvent.serializer(), event.data))
                else -> AnswerEvent.Unknown(event.type)
            }
        }

    override suspend fun retry(key: IdempotencyKey, answerId: String): RetryResponse =
        call { service.retry(key.value, answerId) }

    override suspend fun getLimit(): AssistantLimit = call { service.getLimit() }
}

/** `error` event payload shared by the verification and import streams. */
@kotlinx.serialization.Serializable
internal data class StreamErrorPayload(val code: String, val message: String)

private fun streamError(data: String): AppError {
    val payload = runCatching { decode(StreamErrorPayload.serializer(), data) }.getOrNull()
    return AppError.Api(code = payload?.code ?: ErrorCodes.INTERNAL_ERROR, httpStatus = 200, message = payload?.message)
}

internal class HttpPfApi(
    override val auth: AuthApi,
    override val profile: ProfileApi,
    override val statements: StatementsApi,
    override val operations: OperationsApi,
    override val categories: CategoriesApi,
    override val analytics: AnalyticsApi,
    override val assistant: AssistantApi,
) : PfApi
