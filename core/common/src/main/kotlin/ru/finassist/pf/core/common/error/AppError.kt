package ru.finassist.pf.core.common.error

import java.time.OffsetDateTime
import kotlinx.serialization.json.JsonElement
import ru.finassist.pf.core.common.time.ResponseTiming

/**
 * Error as the UI sees it. API error codes are mapped here by the network layer (or thrown directly by the
 * mock); screens branch on the subtype, never on HTTP status or raw code strings.
 */
enum class ErrorContext { REQUEST, GENERIC_EVENT, RESULT_EVENT }

/** Immutable parsed response data survives specialized exception mapping; never render metadata/message directly. */
data class ErrorResponseInfo(
    val code: String,
    val httpStatus: Int,
    val details: List<AppError.FieldError> = emptyList(),
    val retryAt: OffsetDateTime? = null,
    val resetsAt: OffsetDateTime? = null,
    val until: OffsetDateTime? = null,
    val presentation: ErrorPresentation = ErrorPresentation(),
    val requestRetryable: Boolean? = null,
    val timing: ResponseTiming = ResponseTiming(),
    val context: ErrorContext = ErrorContext.REQUEST,
    val metadata: Map<String, JsonElement> = emptyMap(),
    val retryAfterSeconds: Long? = null,
)

sealed class AppError(
    message: String? = null,
    cause: Throwable? = null,
    val response: ErrorResponseInfo? = null,
) : Exception(message, cause) {
    val presentation: ErrorPresentation get() = response?.presentation ?: ErrorPresentation()
    val requestRetryable: Boolean? get() = response?.requestRetryable
    val rawCode: String? get() = response?.code ?: when (this) {
        is Api -> code
        is Unauthorized -> code
        else -> null
    }

    /** No network / timeout / connection reset — "Нет сети" states. */
    class Offline(cause: Throwable? = null) : AppError("offline", cause)

    /** Session is gone (`INVALID_TOKEN`, `TOKEN_REVOKED` after a failed refresh): go to the sign-in screen. */
    class Unauthorized(val code: String, response: ErrorResponseInfo? = null) : AppError(code, response = response)

    /**
     * `RATE_LIMITED` with the moment to retry at (may be null if the server did not say). [retryAfterSeconds] — the
     * `Retry-After` header of the same answer: relative, so it does not depend on the device clock being right.
     */
    class RateLimited(val retryAt: OffsetDateTime?, val retryAfterSeconds: Long? = null, response: ErrorResponseInfo? = null) : AppError("rate_limited", response = response)

    /** 409 `REQUEST_IN_PROGRESS`: the same idempotent request is still running — retry after [retryAfterSeconds]. */
    class InProgress(val retryAfterSeconds: Int?, response: ErrorResponseInfo? = null) : AppError("request_in_progress", response = response)

    /**
     * Any other API error with a machine-readable [code] (`WRONG_BANK`, `LIMIT_EXCEEDED`, …). Screens that know
     * the code handle it; others show a generic message by [httpStatus].
     */
    class Api(
        val code: String,
        val httpStatus: Int,
        val details: List<FieldError> = emptyList(),
        val retryAt: OffsetDateTime? = null,
        val resetsAt: OffsetDateTime? = null,
        message: String? = null,
        response: ErrorResponseInfo? = null,
    ) : AppError(code, response = response)

    /** 400 `CLIENT_VERSION_UNSUPPORTED` (FIN-20): this version is no longer supported; a retry is pointless. */
    class UpdateRequired(response: ErrorResponseInfo? = null) : AppError("client_version_unsupported", response = response)

    /** 503 `MAINTENANCE` (FIN-20): [until] — expected end, if known; [retryAfterSeconds] — the server's `Retry-After`. */
    class Maintenance(val until: OffsetDateTime?, val retryAfterSeconds: Int?, response: ErrorResponseInfo? = null) : AppError("maintenance", response = response)

    /** 5xx or a malformed response. */
    class Server(val httpStatus: Int, cause: Throwable? = null, response: ErrorResponseInfo? = null) : AppError("server_$httpStatus", cause, response)

    /** Anything unexpected on the client side. */
    class Unknown(cause: Throwable) : AppError("unknown", cause)

    data class FieldError(val field: String?, val code: String, val message: String)
}

/** Converts any throwable into an [AppError]; already-typed errors pass through. */
fun Throwable.toAppError(): AppError = when (this) {
    is AppError -> this
    is java.io.IOException -> AppError.Offline(this)
    else -> AppError.Unknown(this)
}
