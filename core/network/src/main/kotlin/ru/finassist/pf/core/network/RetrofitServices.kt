package ru.finassist.pf.core.network

import okhttp3.MultipartBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Headers
import retrofit2.http.Multipart
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query
import ru.finassist.pf.core.api.model.Analytics
import ru.finassist.pf.core.api.model.AnalyticsPeriodsList
import ru.finassist.pf.core.api.model.AskRequest
import ru.finassist.pf.core.api.model.AskResponse
import ru.finassist.pf.core.api.model.AssistantConsentGrant
import ru.finassist.pf.core.api.model.AssistantLimit
import ru.finassist.pf.core.api.model.CategoriesList
import ru.finassist.pf.core.api.model.ChangeCategoryRequest
import ru.finassist.pf.core.api.model.ChangeCategoryResponse
import ru.finassist.pf.core.api.model.ConsentDocument
import ru.finassist.pf.core.api.model.ImportConfig
import ru.finassist.pf.core.api.model.MessagesList
import ru.finassist.pf.core.api.model.OperationDetails
import ru.finassist.pf.core.api.model.OperationsList
import ru.finassist.pf.core.api.model.PhoneVerification
import ru.finassist.pf.core.api.model.Profile
import ru.finassist.pf.core.api.model.ProfileUpdate
import ru.finassist.pf.core.api.model.RefreshTokenRequest
import ru.finassist.pf.core.api.model.RegisterRequest
import ru.finassist.pf.core.api.model.RegisterResponse
import ru.finassist.pf.core.api.model.RetryResponse
import ru.finassist.pf.core.api.model.StartPhoneVerificationRequest
import ru.finassist.pf.core.api.model.StatementsList
import ru.finassist.pf.core.api.model.TokenPair
import ru.finassist.pf.core.api.model.UnreadLinesList
import ru.finassist.pf.core.api.model.UploadAccepted

/*
 * Retrofit declarations for the JSON endpoints (SSE endpoints are read with OkHttp directly, see Sse.kt).
 * Every call returns Response<T>; HttpServices.call() maps non-2xx bodies to AppError.
 */

private const val NO_AUTH = "${NoAuth.HEADER}: ${NoAuth.VALUE}"

internal interface AuthService {
    @Headers(NO_AUTH)
    @POST("v1/auth/phone")
    suspend fun startPhoneVerification(
        @Header(HEADER_IDEMPOTENCY_KEY) key: String,
        @Body body: StartPhoneVerificationRequest,
    ): Response<PhoneVerification>

    @Headers(NO_AUTH)
    @POST("v1/auth/register")
    suspend fun register(@Header(HEADER_IDEMPOTENCY_KEY) key: String, @Body body: RegisterRequest): Response<RegisterResponse>

    @Headers(NO_AUTH)
    @POST("v1/auth/token")
    suspend fun refreshTokens(@Header(HEADER_IDEMPOTENCY_KEY) key: String, @Body body: RefreshTokenRequest): Response<TokenPair>

    /** Authorization is passed explicitly (the local session is already gone); NoAuth keeps the interceptor out. */
    @Headers(NO_AUTH)
    @POST("v1/auth/logout")
    suspend fun logout(@Header("Authorization") authorization: String, @Body body: RefreshTokenRequest): Response<Unit>

    /** `personal_data` is public; `assistant` needs the session — the server ignores an extra bearer. */
    @GET("v1/consents/{type}")
    suspend fun getConsentDocument(@Path("type") type: String): Response<ConsentDocument>
}

internal interface ProfileService {
    @GET("v1/profile")
    suspend fun getProfile(): Response<Profile>

    @PATCH("v1/profile")
    suspend fun updateProfile(@Body body: ProfileUpdate): Response<Profile>

    @DELETE("v1/profile")
    suspend fun deleteAccount(): Response<Unit>

    @PUT("v1/profile/assistant-consent")
    suspend fun grantAssistantConsent(@Body body: AssistantConsentGrant): Response<Profile>

    @DELETE("v1/profile/assistant-consent")
    suspend fun revokeAssistantConsent(): Response<Unit>
}

internal interface StatementsService {
    @GET("v1/statements/config")
    suspend fun getImportConfig(): Response<ImportConfig>

    @Multipart
    @POST("v1/statements")
    suspend fun uploadStatement(
        @Header(HEADER_IDEMPOTENCY_KEY) key: String,
        @Part file: MultipartBody.Part,
    ): Response<UploadAccepted>

    @GET("v1/statements")
    suspend fun listStatements(): Response<StatementsList>

    @DELETE("v1/statements/{upload_id}")
    suspend fun deleteStatement(@Path("upload_id") uploadId: String): Response<Unit>

    @GET("v1/statements/unread-lines")
    suspend fun listUnreadLines(
        @Query("upload_id") uploadId: String? = null,
        @Query("from") from: String? = null,
        @Query("to") to: String? = null,
    ): Response<UnreadLinesList>
}

internal interface OperationsService {
    @GET("v1/operations")
    suspend fun listOperations(
        @Query("from") from: String?,
        @Query("to") to: String?,
        @Query("all_time") allTime: Boolean?,
        @Query("q") q: String?,
        @Query("category_id") categoryId: String?,
        @Query("kind") kind: String?,
        @Query("amount_from") amountFrom: Long?,
        @Query("amount_to") amountTo: Long?,
        @Query("transfer_mode") transferMode: String?,
        @Query("selection") selection: String?,
        @Query("before") before: String?,
    ): Response<OperationsList>

    @GET("v1/operations/{id}")
    suspend fun getOperation(@Path("id") id: String): Response<OperationDetails>

    @PATCH("v1/operations/{id}")
    suspend fun changeCategory(@Path("id") id: String, @Body body: ChangeCategoryRequest): Response<ChangeCategoryResponse>

    @GET("v1/categories")
    suspend fun listCategories(): Response<CategoriesList>
}

internal interface AnalyticsService {
    @GET("v1/analytics")
    suspend fun getAnalytics(
        @Query("period") period: String?,
        @Query("date") date: String?,
        @Query("transfer_mode") transferMode: String?,
    ): Response<Analytics>

    @GET("v1/analytics/periods")
    suspend fun listPeriods(@Query("period") period: String?): Response<AnalyticsPeriodsList>

    @PUT("v1/analytics/regular-payments/{id}/dismissal")
    suspend fun dismissRegularPayment(@Path("id") id: String): Response<Unit>

    @DELETE("v1/analytics/regular-payments/{id}/dismissal")
    suspend fun restoreRegularPayment(@Path("id") id: String): Response<Unit>
}

internal interface AssistantService {
    @GET("v1/assistant/messages")
    suspend fun listMessages(@Query("limit") limit: Int?, @Query("before_id") beforeId: String?): Response<MessagesList>

    @POST("v1/assistant/messages")
    suspend fun ask(@Header(HEADER_IDEMPOTENCY_KEY) key: String, @Body body: AskRequest): Response<AskResponse>

    @POST("v1/assistant/messages/{answer_id}/retry")
    suspend fun retry(@Header(HEADER_IDEMPOTENCY_KEY) key: String, @Path("answer_id") answerId: String): Response<RetryResponse>

    @GET("v1/assistant/limit")
    suspend fun getLimit(): Response<AssistantLimit>
}
