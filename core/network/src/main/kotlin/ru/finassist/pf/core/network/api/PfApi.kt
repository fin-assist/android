package ru.finassist.pf.core.network.api

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
import ru.finassist.pf.core.network.client.AuthHeaderInterceptor
import ru.finassist.pf.core.network.dto.*

/**
 * REST part of the client API (api.md). SSE endpoints are not here — see [ru.finassist.pf.core.network.sse.SseClient]
 * and the `*Streams` helpers; generated-style clients would buffer the whole stream.
 * Endpoints with side effects take `Idempotency-Key` explicitly: the caller owns the key's lifetime.
 */
interface PfApi {

    // ---- 1. auth
    @Headers("${AuthHeaderInterceptor.NO_AUTH}: 1")
    @POST("v1/auth/phone")
    suspend fun startPhoneVerification(
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body body: StartPhoneVerificationRequestDto,
    ): PhoneVerificationDto

    @Headers("${AuthHeaderInterceptor.NO_AUTH}: 1")
    @POST("v1/auth/register")
    suspend fun register(@Header("Idempotency-Key") idempotencyKey: String, @Body body: RegisterRequestDto): RegisterResponseDto

    @POST("v1/auth/logout")
    suspend fun logout(@Body body: RefreshTokenRequestDto): Response<Unit>

    /** `personal_data` is fetched before login; the auth header is simply absent then. */
    @GET("v1/consents/{type}")
    suspend fun getConsentDocument(@Path("type") type: String): ConsentDocumentDto

    // ---- 2. profile
    @GET("v1/profile")
    suspend fun getProfile(): ProfileDto

    @PATCH("v1/profile")
    suspend fun updateProfile(@Body body: ProfileUpdateDto): ProfileDto

    @DELETE("v1/profile")
    suspend fun deleteAccount(): Response<Unit>

    @PUT("v1/profile/assistant-consent")
    suspend fun grantAssistantConsent(@Body body: AssistantConsentGrantDto): ProfileDto

    @DELETE("v1/profile/assistant-consent")
    suspend fun revokeAssistantConsent(): Response<Unit>

    // ---- 3. statements
    @GET("v1/statements/config")
    suspend fun getImportConfig(): ImportConfigDto

    @Multipart
    @POST("v1/statements")
    suspend fun uploadStatement(@Header("Idempotency-Key") idempotencyKey: String, @Part file: MultipartBody.Part): UploadAcceptedDto

    @GET("v1/statements")
    suspend fun listStatements(): StatementsListDto

    @DELETE("v1/statements/{upload_id}")
    suspend fun deleteStatement(@Path("upload_id") uploadId: String): Response<Unit>

    @GET("v1/statements/unread-lines")
    suspend fun listUnreadLines(
        @Query("upload_id") uploadId: String? = null,
        @Query("from") from: String? = null,
        @Query("to") to: String? = null,
    ): UnreadLinesListDto

    // ---- 4. operations
    @GET("v1/operations")
    suspend fun listOperations(
        @Query("from") from: String? = null,
        @Query("to") to: String? = null,
        @Query("all_time") allTime: Boolean? = null,
        @Query("q") q: String? = null,
        @Query("category_id") categoryId: String? = null,
        @Query("kind") kind: String? = null,
        @Query("amount_from") amountFrom: Long? = null,
        @Query("amount_to") amountTo: Long? = null,
        @Query("transfer_mode") transferMode: String? = null,
        @Query("selection") selection: String? = null,
        @Query("before") before: String? = null,
    ): OperationsListDto

    @GET("v1/operations/{id}")
    suspend fun getOperation(@Path("id") id: String): OperationDto

    @PATCH("v1/operations/{id}")
    suspend fun changeCategory(@Path("id") id: String, @Body body: ChangeCategoryRequestDto): ChangeCategoryResponseDto

    // ---- 5. categories
    @GET("v1/categories")
    suspend fun listCategories(): CategoriesListDto

    // ---- 6. analytics
    @GET("v1/analytics")
    suspend fun getAnalytics(
        @Query("period") period: String? = null,
        @Query("date") date: String? = null,
        @Query("transfer_mode") transferMode: String? = null,
    ): AnalyticsDto

    @GET("v1/analytics/periods")
    suspend fun listAnalyticsPeriods(@Query("period") period: String? = null): AnalyticsPeriodsListDto

    @PUT("v1/analytics/regular-payments/{id}/dismissal")
    suspend fun dismissRegularPayment(@Path("id") id: String): Response<Unit>

    @DELETE("v1/analytics/regular-payments/{id}/dismissal")
    suspend fun restoreRegularPayment(@Path("id") id: String): Response<Unit>

    // ---- 7. assistant
    @GET("v1/assistant/messages")
    suspend fun listMessages(@Query("limit") limit: Int? = null, @Query("before_id") beforeId: String? = null): MessagesListDto

    @POST("v1/assistant/messages")
    suspend fun ask(@Header("Idempotency-Key") idempotencyKey: String, @Body body: AskRequestDto): AskResponseDto

    @POST("v1/assistant/messages/{answer_id}/retry")
    suspend fun retryAnswer(@Path("answer_id") answerId: String, @Header("Idempotency-Key") idempotencyKey: String): RetryResponseDto

    @GET("v1/assistant/limit")
    suspend fun getAssistantLimit(): AssistantLimitDto
}
